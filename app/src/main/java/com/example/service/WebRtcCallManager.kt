package com.example.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

enum class WebRtcCallState {
    IDLE, INITIALIZING, RINGING, CONNECTING, CONNECTED, ERROR, ENDED
}

class WebRtcCallManager(private val context: Context) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private fun ensureFactory() {
        if (factory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    fun startPeerConnection(
        turnUrl: String = "",
        turnUsername: String = "",
        turnCredential: String = "",
        onLocalIceCandidate: (IceCandidate) -> Unit
    ): Boolean {
        return try {
            closeSession()
            _callState.value = WebRtcCallState.INITIALIZING
            _statusMessage.value = "Initializing audio engine..."
            ensureFactory()

            val iceServers = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            if (turnUrl.isNotBlank()) {
                val builder = PeerConnection.IceServer.builder(turnUrl.trim())
                if (turnUsername.isNotBlank()) builder.setUsername(turnUsername.trim())
                if (turnCredential.isNotBlank()) builder.setPassword(turnCredential.trim())
                iceServers.add(builder.createIceServer())
            }

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            _callState.value = WebRtcCallState.CONNECTED
                            _statusMessage.value = "Voice connected"
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _callState.value = WebRtcCallState.ERROR
                            _statusMessage.value = "Connection blocked by network NAT/firewall. Configure a TURN server in Settings or try Wi-Fi."
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _statusMessage.value = "Weak connection, reconnecting..."
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            _callState.value = WebRtcCallState.ENDED
                        }
                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) onLocalIceCandidate(candidate)
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }

            val pc = factory?.createPeerConnection(rtcConfig, observer)
                ?: throw IllegalStateException("Unable to create PeerConnection")
            peerConnection = pc

            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val source = factory?.createAudioSource(audioConstraints)
            val track = factory?.createAudioTrack("ARDAMSa0", source)
            track?.setEnabled(true)
            localAudioSource = source
            localAudioTrack = track
            if (track != null) {
                pc.addTrack(track, listOf("ARDAMS"))
            }

            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerRouting(_isSpeakerOn.value)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC start failed", e)
            _callState.value = WebRtcCallState.ERROR
            _statusMessage.value = e.localizedMessage ?: "Audio hardware unavailable"
            false
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: run { onError("PeerConnection missing"); return }
        _callState.value = WebRtcCallState.RINGING
        _statusMessage.value = "Ringing..."
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) { onError("Null offer"); return }
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { onOfferCreated(desc.description) }
                    override fun onSetFailure(err: String?) { onError(err ?: "Set local offer failed") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onCreateFailure(err: String?) { onError(err ?: "Create offer failed") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun acceptOfferAndCreateAnswer(
        offerSdp: String,
        onAnswerCreated: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val pc = peerConnection ?: run { onError("PeerConnection missing"); return }
        _callState.value = WebRtcCallState.CONNECTING
        _statusMessage.value = "Connecting audio..."
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) { onError("Null answer"); return }
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { onAnswerCreated(desc.description) }
                            override fun onSetFailure(err: String?) { onError(err ?: "Set local answer failed") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onCreateFailure(err: String?) { onError(err ?: "Create answer failed") }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onSetFailure(err: String?) { onError(err ?: "Invalid offer SDP") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteDesc)
    }

    fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        _callState.value = WebRtcCallState.CONNECTING
        _statusMessage.value = "Negotiating ICE..."
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {
                _callState.value = WebRtcCallState.ERROR
                _statusMessage.value = err ?: "Failed to apply remote answer"
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        runCatching {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        localAudioTrack?.setEnabled(!next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        setSpeakerRouting(next)
    }

    private fun setSpeakerRouting(enableSpeaker: Boolean) {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enableSpeaker) {
                    val speaker = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    }
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = enableSpeaker
            }
        }
    }

    fun closeSession() {
        runCatching { localAudioTrack?.dispose() }
        runCatching { localAudioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        localAudioTrack = null
        localAudioSource = null
        peerConnection = null
        _isMuted.value = false
        _isSpeakerOn.value = false
        setSpeakerRouting(false)
        runCatching { audioManager?.mode = AudioManager.MODE_NORMAL }
        _callState.value = WebRtcCallState.IDLE
        _statusMessage.value = ""
    }

    companion object {
        private const val TAG = "WebRtcCallManager"
    }
}
