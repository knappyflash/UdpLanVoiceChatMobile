package com.example.udplanvoicechatmobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private val bgColor = Color.rgb(0x3F, 0x3F, 0x3F)
    private val mainTextColor = Color.rgb(0xCE, 0xDF, 0x99)
    private val valueColor = Color.rgb(0xDC, 0xDC, 0xCC)
    private val punctuationColor = Color.rgb(0x9F, 0x9D, 0x6D)

    private lateinit var remoteIpEdit: EditText
    private lateinit var remotePortEdit: EditText
    private lateinit var localPortEdit: EditText
    private lateinit var startButton: Button
    private lateinit var muteMicButton: Button
    private lateinit var gainSeekBar: SeekBar
    private lateinit var gainText: TextView
    private lateinit var meterView: ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var playbackGain = 10.0f
    private var micMuted = false
    private var serviceRunning = false

    private val configFileName = "udp_voice_config.json"

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
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
            progress = 100

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    playbackGain = progress / 10.0f
                    gainText.text = "Speaker Amplification: x${"%.1f".format(playbackGain)}"

                    if (serviceRunning) {
                        sendServiceCommand(VoiceChatService.ACTION_UPDATE_GAIN)
                    }
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
                if (serviceRunning) {
                    stopVoiceChatService()
                } else {
                    ensurePermissionsThenStart()
                }
            }
        }

        val startParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 26, 0, 18)
        }

        root.addView(startButton, startParams)

        muteMicButton = Button(this).apply {
            text = "MUTE MIC: OFF"
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(punctuationColor)
            setPadding(0, 16, 0, 16)

            setOnClickListener {
                micMuted = !micMuted
                updateMuteButton()

                if (serviceRunning) {
                    sendServiceCommand(VoiceChatService.ACTION_UPDATE_MUTE)
                }

                if (micMuted) {
                    logInfo("Microphone muted")
                } else {
                    logInfo("Microphone unmuted")
                }
            }
        }

        val muteParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 18)
        }

        root.addView(muteMicButton, muteParams)

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

    private fun makeEditText(
        hintValue: String,
        defaultValue: String,
        numeric: Boolean = false
    ): EditText {
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

    private fun ensurePermissionsThenStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
                return
            }
        }

        startVoiceChatService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQ_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    logInfo("Microphone permission granted")
                    ensurePermissionsThenStart()
                } else {
                    logInfo("Microphone permission denied")
                }
            }

            REQ_POST_NOTIFICATIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    logInfo("Notification permission granted")
                    startVoiceChatService()
                } else {
                    logInfo("Notification permission denied")
                }
            }
        }
    }

    private fun startVoiceChatService() {
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

        val intent = Intent(this, VoiceChatService::class.java).apply {
            action = VoiceChatService.ACTION_START
            putExtra(VoiceChatService.EXTRA_REMOTE_IP, remoteIp)
            putExtra(VoiceChatService.EXTRA_REMOTE_PORT, remotePort)
            putExtra(VoiceChatService.EXTRA_LOCAL_PORT, localPort)
            putExtra(VoiceChatService.EXTRA_GAIN, playbackGain)
            putExtra(VoiceChatService.EXTRA_MIC_MUTED, micMuted)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        serviceRunning = true
        startButton.text = "STOP VOICE CHAT"

        logInfo("Foreground voice chat service starting")
        logInfo("Remote: $remoteIp:$remotePort")
        logInfo("Local port: $localPort")
    }

    private fun stopVoiceChatService() {
        val intent = Intent(this, VoiceChatService::class.java).apply {
            action = VoiceChatService.ACTION_STOP
        }

        startService(intent)

        serviceRunning = false
        startButton.text = "START VOICE CHAT"
        meterView.progress = 0

        logInfo("Foreground voice chat service stopping")
    }

    private fun sendServiceCommand(actionName: String) {
        val intent = Intent(this, VoiceChatService::class.java).apply {
            action = actionName
            putExtra(VoiceChatService.EXTRA_GAIN, playbackGain)
            putExtra(VoiceChatService.EXTRA_MIC_MUTED, micMuted)
        }

        startService(intent)
    }

    private fun updateMuteButton() {
        if (micMuted) {
            muteMicButton.text = "MUTE MIC: ON"
            muteMicButton.setBackgroundColor(Color.rgb(0xCC, 0x66, 0x66))
        } else {
            muteMicButton.text = "MUTE MIC: OFF"
            muteMicButton.setBackgroundColor(punctuationColor)
        }
    }

    private fun saveConfig(remoteIp: String, remotePort: Int, localPort: Int) {
        try {
            val json = JSONObject()
            json.put("remoteIp", remoteIp)
            json.put("remotePort", remotePort)
            json.put("localPort", localPort)
            json.put("playbackGain", playbackGain)
            json.put("micMuted", micMuted)

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

            micMuted = json.optBoolean("micMuted", false)

            gainSeekBar.progress = (playbackGain * 10).toInt()
            gainText.text = "Speaker Amplification: x${"%.1f".format(playbackGain)}"

            updateMuteButton()

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
}