package com.example.udplanvoicechatmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

class VoiceChatService : Service() {

    companion object {
        const val ACTION_START = "com.example.udplanvoicechatmobile.action.START"
        const val ACTION_STOP = "com.example.udplanvoicechatmobile.action.STOP"
        const val ACTION_UPDATE_GAIN = "com.example.udplanvoicechatmobile.action.UPDATE_GAIN"
        const val ACTION_UPDATE_MUTE = "com.example.udplanvoicechatmobile.action.UPDATE_MUTE"

        const val EXTRA_REMOTE_IP = "remoteIp"
        const val EXTRA_REMOTE_PORT = "remotePort"
        const val EXTRA_LOCAL_PORT = "localPort"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_MIC_MUTED = "micMuted"

        private const val NOTIFICATION_CHANNEL_ID = "udp_voice_chat_channel"
        private const val NOTIFICATION_ID = 9001
    }

    private var socket: DatagramSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    private var audioManager: AudioManager? = null
    private var oldAudioMode: Int = AudioManager.MODE_NORMAL
    private var oldSpeakerphoneOn: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val hpFilter = SimpleHighPassFilter(44100f, 100f)
    private val lpFilter = SimpleLowPassFilter(44100f, 3500f)

    @Volatile
    private var running = false

    @Volatile
    private var micMuted = false

    @Volatile
    private var playbackGain = 10.0f

    private val sampleRate = 44100
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val receiveQueueLock = Object()
    private val receiveQueue = ArrayDeque<ByteArray>()

    private val maxReceivePackets = 6
    private val trimToPackets = 3

    private var droppedPacketLogCounter = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val remoteIp = intent.getStringExtra(EXTRA_REMOTE_IP) ?: "10.0.0.244"
                val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, 64555)
                val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 64555)

                playbackGain = intent.getFloatExtra(EXTRA_GAIN, 10.0f)
                micMuted = intent.getBooleanExtra(EXTRA_MIC_MUTED, false)

                startVoiceChat(remoteIp, remotePort, localPort)
            }

            ACTION_STOP -> {
                stopVoiceChat()
                stopSelf()
            }

            ACTION_UPDATE_GAIN -> {
                playbackGain = intent.getFloatExtra(EXTRA_GAIN, playbackGain)
            }

            ACTION_UPDATE_MUTE -> {
                micMuted = intent.getBooleanExtra(EXTRA_MIC_MUTED, micMuted)
            }
        }

        return START_STICKY
    }

    private fun startVoiceChat(remoteIp: String, remotePort: Int, localPort: Int) {
        if (running) return

        running = true

        startForeground(NOTIFICATION_ID, buildNotification("UDP voice chat running"))

        acquireAwakeLocks()
        enableSpeakerphoneMode()

        synchronized(receiveQueueLock) {
            receiveQueue.clear()
            receiveQueueLock.notifyAll()
        }

        try {
            socket = DatagramSocket(localPort)
            socket?.broadcast = true

            setupAudio()

            startReceiveThread()
            startPlaybackThread()
            startSendThread(remoteIp, remotePort)

        } catch (ex: Exception) {
            stopVoiceChat()
            stopSelf()
        }
    }

    private fun setupAudio() {
        val recordMinBuffer = AudioRecord.getMinBufferSize(sampleRate, channelIn, audioFormat)
        val playMinBuffer = AudioTrack.getMinBufferSize(sampleRate, channelOut, audioFormat)

        val recordBufferSize = maxOf(recordMinBuffer, 4096)
        val playBufferSize = maxOf(playMinBuffer * 2, 4096)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelIn,
            audioFormat,
            recordBufferSize
        )

        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(playBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        recorder?.startRecording()
        player?.play()
    }

    private fun startSendThread(remoteIp: String, remotePort: Int) {
        thread(start = true, name = "UdpVoiceSender") {
            try {
                val address = InetAddress.getByName(remoteIp)

                // About 20ms of 44.1kHz 16-bit mono PCM.
                val micBuffer = ByteArray(1764)

                while (running) {
                    val read = recorder?.read(micBuffer, 0, micBuffer.size) ?: 0

                    if (read > 0 && !micMuted) {
                        val packet = DatagramPacket(micBuffer, read, address, remotePort)
                        socket?.send(packet)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startReceiveThread() {
        thread(start = true, name = "UdpVoiceReceiver") {
            try {
                val receiveBuffer = ByteArray(4096)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (running) {
                    socket?.receive(packet)

                    if (packet.length > 0) {
                        val audioBytes = packet.data.copyOfRange(0, packet.length)

                        synchronized(receiveQueueLock) {
                            receiveQueue.addLast(audioBytes)

                            if (receiveQueue.size > maxReceivePackets) {
                                var dropped = 0

                                while (receiveQueue.size > trimToPackets) {
                                    receiveQueue.removeFirst()
                                    dropped++
                                }

                                droppedPacketLogCounter += dropped
                            }

                            receiveQueueLock.notifyAll()
                        }
                    }
                }
            } catch (_: Exception) {
            }

            synchronized(receiveQueueLock) {
                receiveQueue.clear()
                receiveQueueLock.notifyAll()
            }
        }
    }

    private fun startPlaybackThread() {
        thread(start = true, name = "UdpVoicePlayback") {
            while (running) {
                var audioBytes: ByteArray? = null

                synchronized(receiveQueueLock) {
                    while (running && receiveQueue.isEmpty()) {
                        try {
                            receiveQueueLock.wait(50)
                        } catch (_: InterruptedException) {
                        }
                    }

                    if (receiveQueue.isNotEmpty()) {
                        audioBytes = receiveQueue.removeFirst()
                    }
                }

                val bytes = audioBytes

                if (bytes != null) {

                    applyVoiceBandPass(bytes)
                    applyGainPcm16(bytes, playbackGain)

                    try {
                        player?.write(bytes, 0, bytes.size)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun stopVoiceChat() {
        if (!running) return

        running = false

        synchronized(receiveQueueLock) {
            receiveQueue.clear()
            receiveQueueLock.notifyAll()
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            player?.pause()
            player?.flush()
            player?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        try {
            player?.release()
        } catch (_: Exception) {
        }

        socket = null
        recorder = null
        player = null

        disableSpeakerphoneMode()
        releaseAwakeLocks()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun applyGainPcm16(buffer: ByteArray, gain: Float) {
        var i = 0

        while (i < buffer.size - 1) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()

            var sample = (high shl 8) or low

            if (sample > 32767) {
                sample -= 65536
            }

            var amplified = (sample * gain).toInt()

            if (amplified > 32767) amplified = 32767
            if (amplified < -32768) amplified = -32768

            buffer[i] = amplified.toByte()
            buffer[i + 1] = (amplified shr 8).toByte()

            i += 2
        }
    }

    private fun acquireAwakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UdpLanVoiceChatMobile::VoiceChatWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "UdpLanVoiceChatMobile::VoiceChatWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseAwakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }

            wakeLock = null
            wifiLock = null
        } catch (_: Exception) {
        }
    }

    private fun enableSpeakerphoneMode() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val am = audioManager ?: return

        oldAudioMode = am.mode

        @Suppress("DEPRECATION")
        oldSpeakerphoneOn = am.isSpeakerphoneOn

        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            if (speaker != null) {
                am.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun disableSpeakerphoneMode() {
        val am = audioManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = oldSpeakerphoneOn
            }

            am.mode = oldAudioMode
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UDP Voice Chat",
                NotificationManager.IMPORTANCE_LOW
            )

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(textValue: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VoiceChatService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("UDP LAN Voice Chat")
                .setContentText(textValue)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPendingIntent
                )
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("UDP LAN Voice Chat")
                .setContentText(textValue)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPendingIntent
                )
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopVoiceChat()
        super.onDestroy()
    }

    private fun applyVoiceBandPass(buffer: ByteArray) {

        var i = 0

        while (i < buffer.size - 1) {

            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()

            var sample = (high shl 8) or low

            if (sample > 32767)
                sample -= 65536

            var s = sample / 32768.0f

            s = hpFilter.process(s)
            s = lpFilter.process(s)

            s = s.coerceIn(-1.0f, 1.0f)

            val output = (s * 32767f).toInt()

            buffer[i] = output.toByte()
            buffer[i + 1] = (output shr 8).toByte()

            i += 2
        }
    }
}