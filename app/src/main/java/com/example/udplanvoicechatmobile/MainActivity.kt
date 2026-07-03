package com.example.udplanvoicechatmobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class MainActivity : Activity() {

    private val bgColor = Color.rgb(0x3F, 0x3F, 0x3F)
    private val mainTextColor = Color.rgb(0xCE, 0xDF, 0x99)
    private val valueColor = Color.rgb(0xDC, 0xDC, 0xCC)
    private val punctuationColor = Color.rgb(0x9F, 0x9D, 0x6D)

    private lateinit var remoteIpEdit: EditText
    private lateinit var remotePortEdit: EditText
    private lateinit var localPortEdit: EditText
    private lateinit var startButton: Button
    private lateinit var gainSeekBar: SeekBar
    private lateinit var gainText: TextView
    private lateinit var meterView: ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var muteMicButton: Button

    private var socket: DatagramSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    @Volatile
    private var running = false


    @Volatile
    private var micMuted = false


    private var playbackGain = 10.0f

    private val sampleRate = 44100
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val configFileName = "udp_voice_config.json"

    private var audioManager: AudioManager? = null
    private var oldAudioMode: Int = AudioManager.MODE_NORMAL
    private var oldSpeakerphoneOn: Boolean = false
    private var meterBoost = 5.0f

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadConfig()
        logInfo("App opened")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(28, 42, 28, 28)
        }

        val title = TextView(this).apply {
            text = "[ UDP LAN Voice Chat ]"
            textSize = 24f
            setTextColor(mainTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        remoteIpEdit = makeEditText("Remote IP Address", "10.0.0.244")
        remotePortEdit = makeEditText("Remote Port", "64555", true)
        localPortEdit = makeEditText("Local Port", "64555", true)

        root.addView(makeLabel("{ Remote IP }"))
        root.addView(remoteIpEdit)

        root.addView(makeLabel("{ Remote Port }"))
        root.addView(remotePortEdit)

        root.addView(makeLabel("{ Local Port }"))
        root.addView(localPortEdit)

        gainText = TextView(this).apply {
            text = "Speaker Amplification: x10.0"
            textSize = 16f
            setTextColor(valueColor)
            setPadding(0, 20, 0, 4)
        }
        root.addView(gainText)

        gainSeekBar = SeekBar(this).apply {
            max = 200
            progress = 100       // progress / 10 = x10.0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    playbackGain = progress / 10.0f
                    gainText.text = "Speaker Amplification: x${"%.1f".format(playbackGain)}"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(gainSeekBar)

        val meterLabel = TextView(this).apply {
            text = "[ Received Audio Level ]"
            textSize = 16f
            setTextColor(mainTextColor)
            setPadding(0, 18, 0, 4)
        }
        root.addView(meterLabel)

        meterView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(meterView)

        startButton = Button(this).apply {
            text = "START VOICE CHAT"
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(mainTextColor)
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                if (running) {
                    stopVoiceChat()
                } else {
                    ensurePermissionThenStart()
                }
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 26, 0, 18)
        }

        root.addView(startButton, buttonParams)

        muteMicButton = Button(this).apply {
            text = "MUTE MIC: OFF"
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(punctuationColor)
            setPadding(0, 16, 0, 16)

            setOnClickListener {
                micMuted = !micMuted

                if (micMuted) {
                    text = "MUTE MIC: ON"
                    logInfo("Microphone muted")
                } else {
                    text = "MUTE MIC: OFF"
                    logInfo("Microphone unmuted")
                }
            }
        }

        val muteButtonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 18)
        }

        root.addView(muteMicButton, muteButtonParams)

        val logLabel = TextView(this).apply {
            text = "{ Log Output }"
            textSize = 16f
            setTextColor(punctuationColor)
            setPadding(0, 0, 0, 4)
        }
        root.addView(logLabel)

        logText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(valueColor)
            setPadding(12, 12, 12, 12)
        }

        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x2B, 0x2B, 0x2B))
            addView(logText)
        }

        val logParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            weight = 1f

            // Keeps the log box from touching the bottom of the screen.
            setMargins(0, 0, 0, 200)
        }

        root.addView(logScroll, logParams)

        setContentView(root)
    }

    private fun makeLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(punctuationColor)
            setPadding(0, 14, 0, 4)
        }
    }

    private fun makeEditText(hintValue: String, defaultValue: String, numeric: Boolean = false): EditText {
        return EditText(this).apply {
            hint = hintValue
            setText(defaultValue)
            textSize = 17f
            setTextColor(valueColor)
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
            setSingleLine(true)
            setBackgroundColor(Color.rgb(0x2B, 0x2B, 0x2B))
            setPadding(14, 10, 14, 10)

            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        }
    }

    private fun ensurePermissionThenStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }

        startVoiceChat()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logInfo("Microphone permission granted")
                startVoiceChat()
            } else {
                logInfo("Microphone permission denied")
            }
        }
    }

    private fun startVoiceChat() {
        if (running) return

        val remoteIp = remoteIpEdit.text.toString().trim()
        val remotePort = remotePortEdit.text.toString().toIntOrNull()
        val localPort = localPortEdit.text.toString().toIntOrNull()

        if (remoteIp.isEmpty()) {
            logInfo("Remote IP is empty")
            return
        }

        if (remotePort == null || remotePort <= 0 || remotePort > 65535) {
            logInfo("Remote port is invalid")
            return
        }

        if (localPort == null || localPort <= 0 || localPort > 65535) {
            logInfo("Local port is invalid")
            return
        }

        saveConfig(remoteIp, remotePort, localPort)

        try {
            running = true
            startButton.text = "STOP VOICE CHAT"

            socket = DatagramSocket(localPort)
            socket?.broadcast = true

            logInfo("UDP started")
            logInfo("Local port: $localPort")
            logInfo("Remote: $remoteIp:$remotePort")

            enableSpeakerphoneMode()

            setupAudio()

            startReceiveThread()
            startSendThread(remoteIp, remotePort)

        } catch (ex: Exception) {
            logInfo("Start error: ${ex.message}")
            stopVoiceChat()
        }
    }

    private fun setupAudio() {
        val recordMinBuffer = AudioRecord.getMinBufferSize(sampleRate, channelIn, audioFormat)
        val playMinBuffer = AudioTrack.getMinBufferSize(sampleRate, channelOut, audioFormat)

        val recordBufferSize = maxOf(recordMinBuffer, 4096)
        val playBufferSize = maxOf(playMinBuffer * 4, 8192)

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

        logInfo("Audio capture started")
        logInfo("Audio playback started")
        logInfo("Format: 44100 Hz, 16-bit, mono PCM")
    }

    private fun startSendThread(remoteIp: String, remotePort: Int) {
        thread(start = true, name = "UdpVoiceSender") {
            try {
                val address = InetAddress.getByName(remoteIp)

                // Around 20ms of 44.1kHz 16-bit mono audio = 1764 bytes.
                val micBuffer = ByteArray(1764)

                logInfo("Sender thread started")

                while (running) {
                    val read = recorder?.read(micBuffer, 0, micBuffer.size) ?: 0

                    if (read > 0 && !micMuted) {
                        val packet = DatagramPacket(micBuffer, read, address, remotePort)
                        socket?.send(packet)
                    }
                }
            } catch (ex: Exception) {
                if (running) {
                    logInfo("Send error: ${ex.message}")
                }
            }

            logInfo("Sender thread stopped")
        }
    }

    private fun startReceiveThread() {
        thread(start = true, name = "UdpVoiceReceiver") {
            try {
                val receiveBuffer = ByteArray(4096)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                logInfo("Receiver thread started")

                while (running) {
                    socket?.receive(packet)

                    if (packet.length > 0) {
                        val audioBytes = packet.data.copyOfRange(0, packet.length)

                        val level = getPcm16Level(audioBytes)
                        updateMeter(level)

                        applyGainPcm16(audioBytes, playbackGain)

                        player?.write(audioBytes, 0, audioBytes.size)
                    }
                }
            } catch (ex: Exception) {
                if (running) {
                    logInfo("Receive error: ${ex.message}")
                }
            }

            updateMeter(0)
            logInfo("Receiver thread stopped")
        }
    }

    private fun stopVoiceChat() {
        if (!running) return

        running = false

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            player?.stop()
        } catch (_: Exception) {
        }

        recorder?.release()
        player?.release()

        disableSpeakerphoneMode()

        socket = null
        recorder = null
        player = null

        updateMeter(0)

        runOnUiThread {
            startButton.text = "START VOICE CHAT"
        }

        logInfo("UDP stopped")
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

    private fun getPcm16Level(buffer: ByteArray): Int {
        var peak = 0
        var i = 0

        while (i < buffer.size - 1) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()

            var sample = (high shl 8) or low

            if (sample > 32767) {
                sample -= 65536
            }

            peak = maxOf(peak, abs(sample))

            i += 2
        }

        val normalized = peak / 32767.0f
        val boosted = normalized * meterBoost
        val percent = (boosted * 100.0f).toInt()

        return percent.coerceIn(0, 100)
    }

    private fun updateMeter(level: Int) {
        runOnUiThread {
            meterView.progress = level
        }
    }

    private fun saveConfig(remoteIp: String, remotePort: Int, localPort: Int) {
        try {
            val json = JSONObject()
            json.put("remoteIp", remoteIp)
            json.put("remotePort", remotePort)
            json.put("localPort", localPort)
            json.put("playbackGain", playbackGain)

            val file = File(filesDir, configFileName)
            file.writeText(json.toString(4))

            logInfo("Config saved")
        } catch (ex: Exception) {
            logInfo("Config save error: ${ex.message}")
        }
    }

    private fun loadConfig() {
        try {
            val file = File(filesDir, configFileName)

            if (!file.exists()) {
                logInfo("No saved config found")
                return
            }

            val json = JSONObject(file.readText())

            remoteIpEdit.setText(json.optString("remoteIp", "10.0.0.244"))
            remotePortEdit.setText(json.optInt("remotePort", 64555).toString())
            localPortEdit.setText(json.optInt("localPort", 64555).toString())

            playbackGain = json.optDouble("playbackGain", 10.0).toFloat()
            playbackGain = playbackGain.coerceIn(0.0f, 20.0f)

            gainSeekBar.progress = (playbackGain * 10).toInt()
            gainText.text = "Speaker Amplification: x${"%.1f".format(playbackGain)}"

            logInfo("Config loaded")
        } catch (ex: Exception) {
            logInfo("Config load error: ${ex.message}")
        }
    }

    private fun logInfo(message: String) {
        runOnUiThread {
            val line = "[ ${currentTimeText()} ] { $message }\n"
            logText.append(line)

            logScroll.post {
                logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun currentTimeText(): String {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return now.format(java.util.Date())
    }

    override fun onDestroy() {
        stopVoiceChat()
        super.onDestroy()
    }

    private fun enableSpeakerphoneMode() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val am = audioManager ?: return

        oldAudioMode = am.mode
        oldSpeakerphoneOn = am.isSpeakerphoneOn

        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            if (speaker != null) {
                val success = am.setCommunicationDevice(speaker)
                logInfo("Speaker route requested: $success")
            } else {
                logInfo("Built-in speaker route not found")
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true

            logInfo("Legacy speakerphone enabled")
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

            logInfo("Audio route restored")
        } catch (ex: Exception) {
            logInfo("Audio route restore error: ${ex.message}")
        }
    }
}