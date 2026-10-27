package com.bharath.cameramonitor

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.MediaStream
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.UUID

/**
 * One-to-one WebRTC video with Firestore signaling. Video itself is peer-to-peer;
 * Firebase stores only SDP/ICE signaling and remote commands.
 */
object RtcEngine {
    private val db get() = FirebaseFirestore.getInstance()
    private val sessions get() = db.collection("cameraMonitor").document("live").collection("sessions")
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var activeSession: DocumentReference? = null
    private var sessionQueryListener: ListenerRegistration? = null
    private var answerListener: ListenerRegistration? = null
    private var candidateListener: ListenerRegistration? = null
    private var processedSessionId: String? = null
    private var activeViewerRenderer: SurfaceViewRenderer? = null

    @Synchronized
    private fun ensureFactory(context: Context) {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun startViewer(context: Context, renderer: SurfaceViewRenderer, onStatus: (String) -> Unit) {
        stopViewer()
        try {
            ensureFactory(context)
            renderer.init(eglBase!!.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            activeViewerRenderer = renderer
            val ref = sessions.document(UUID.randomUUID().toString())
            activeSession = ref
            val pc = createPeer(context, ref, true, renderer, onStatus) ?: run {
                onStatus("Could not create WebRTC connection")
                return
            }
            peer = pc
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            candidateListener = ref.collection("cameraCandidates").addSnapshotListener { snap, error ->
                if (error != null) { onStatus("ICE signaling error: " + error.message); return@addSnapshotListener }
                snap?.documentChanges?.forEach { change ->
                    if (change.type.name == "ADDED") addCandidate(pc, change.document.data)
                }
            }
            answerListener = ref.addSnapshotListener { snap, error ->
                if (error != null) { onStatus("Signaling error: " + error.message); return@addSnapshotListener }
                val answer = snap?.get("answer") as? Map<*, *> ?: return@addSnapshotListener
                if (pc.remoteDescription != null) return@addSnapshotListener
                val sdp = answer["sdp"] as? String ?: return@addSnapshotListener
                setRemote(pc, SessionDescription(SessionDescription.Type.ANSWER, sdp), onStatus)
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) { onStatus("WebRTC did not create an offer"); return }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            ref.set(mapOf(
                                "offer" to mapOf("type" to "offer", "sdp" to desc.description),
                                "createdAt" to System.currentTimeMillis(),
                                "state" to "WAITING"
                            )).addOnSuccessListener {
                                onStatus("Offer sent. Requesting remote camera…")
                                Thread {
                                    runCatching { kotlinx.coroutines.runBlocking { CommandRepository.sendCommand("START_LIVE") } }
                                        .onFailure { onStatus("Could not send start command: " + it.message) }
                                }.start()
                            }.addOnFailureListener { onStatus("Could not create signaling session: " + it.message) }
                        }
                        override fun onCreateFailure(error: String?) { onStatus("Set local offer failed: " + error) }
                        override fun onSetFailure(error: String?) { onStatus("Set local offer failed: " + error) }
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { onStatus("Create offer failed: " + error) }
                override fun onSetFailure(error: String?) { onStatus("Create offer failed: " + error) }
            }, MediaConstraints())
        } catch (e: Exception) {
            onStatus("Viewer error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun startCamera(context: Context, onStatus: (String) -> Unit = {}) {
        stopCamera()
        try {
            ensureFactory(context)
            prepareCamera(context)
            processedSessionId = null
            onStatus("Camera ready — waiting for viewer")
            sessionQueryListener = sessions.orderBy("createdAt", Query.Direction.DESCENDING).limit(5)
                .addSnapshotListener { snap, error ->
                    if (error != null) { onStatus("Waiting for viewer: " + error.message); return@addSnapshotListener }
                    val doc = snap?.documents?.firstOrNull { it.get("offer") is Map<*, *> && it.id != processedSessionId }
                        ?: return@addSnapshotListener
                    processedSessionId = doc.id
                    answerOffer(context, doc.reference, doc.get("offer") as Map<*, *>, onStatus)
                }
        } catch (e: Exception) {
            onStatus("Camera start failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun prepareCamera(context: Context) {
        val f = factory ?: error("WebRTC factory unavailable")
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: error("No camera found")
        capturer = enumerator.createCapturer(name, null) ?: error("Could not open camera")
        videoSource = f.createVideoSource(false)
        textureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase!!.eglBaseContext)
        capturer!!.initialize(textureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(640, 480, 15)
        videoTrack = f.createVideoTrack("camera-video", videoSource)
    }

    private fun answerOffer(context: Context, ref: DocumentReference, offer: Map<*, *>, onStatus: (String) -> Unit) {
        val sdp = offer["sdp"] as? String ?: return
        val pc = createPeer(context, ref, false, null, onStatus) ?: return
        peer?.close()
        peer = pc
        videoTrack?.let { pc.addTrack(it, listOf("camera-stream")) }
        candidateListener?.remove()
        candidateListener = ref.collection("viewerCandidates").addSnapshotListener { snap, error ->
            if (error != null) { onStatus("ICE signaling error: " + error.message); return@addSnapshotListener }
            snap?.documentChanges?.forEach { change ->
                if (change.type.name == "ADDED") addCandidate(pc, change.document.data)
            }
        }
        setRemote(pc, SessionDescription(SessionDescription.Type.OFFER, sdp)) {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) { onStatus("WebRTC did not create an answer"); return }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            ref.update(
                                mapOf("answer" to mapOf("type" to "answer", "sdp" to desc.description), "state" to "LIVE")
                            ).addOnSuccessListener { onStatus("Live video connected") }
                                .addOnFailureListener { onStatus("Could not publish answer: " + it.message) }
                        }
                        override fun onCreateFailure(error: String?) { onStatus("Create answer failed: " + error) }
                        override fun onSetFailure(error: String?) { onStatus("Set answer failed: " + error) }
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { onStatus("Create answer failed: " + error) }
                override fun onSetFailure(error: String?) { onStatus("Create answer failed: " + error) }
            }, MediaConstraints())
        }
    }

    private fun createPeer(
        context: Context,
        ref: DocumentReference,
        isViewer: Boolean,
        renderer: SurfaceViewRenderer?,
        onStatus: (String) -> Unit
    ): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onStatus("Live video connected")
                    PeerConnection.IceConnectionState.DISCONNECTED -> onStatus("Video disconnected — trying to reconnect")
                    PeerConnection.IceConnectionState.FAILED -> onStatus("WebRTC connection failed. Network may require a TURN relay.")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val collection = if (isViewer) "viewerCandidates" else "cameraCandidates"
                ref.collection(collection).add(mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "sdp" to candidate.sdp
                ))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null && renderer != null) track.addSink(renderer)
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                if (track != null && renderer != null) track.addSink(renderer)
            }
        }
        return factory?.createPeerConnection(iceServers, observer)
    }

    private fun setRemote(pc: PeerConnection, description: SessionDescription, onStatus: (String) -> Unit) {
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { onStatus("Remote description set") }
            override fun onCreateFailure(error: String?) { onStatus("Remote description failed: " + error) }
            override fun onSetFailure(error: String?) { onStatus("Remote description failed: " + error) }
        }, description)
    }

    private fun addCandidate(pc: PeerConnection, data: Map<String, Any>) {
        val mid = data["sdpMid"] as? String
        val line = (data["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        val sdp = data["sdp"] as? String ?: return
        pc.addIceCandidate(IceCandidate(mid, line, sdp))
    }

    fun stopCamera(onStatus: (String) -> Unit = {}) {
        sessionQueryListener?.remove()
        sessionQueryListener = null
        candidateListener?.remove()
        candidateListener = null
        processedSessionId = null
        closePeer()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        onStatus("Remote camera stopped")
    }

    fun stopViewer(onStatus: (String) -> Unit = {}) {
        answerListener?.remove()
        answerListener = null
        candidateListener?.remove()
        candidateListener = null
        closePeer()
        activeViewerRenderer = null
        onStatus("Viewer stopped")
    }

    private fun closePeer() {
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
    }
}
