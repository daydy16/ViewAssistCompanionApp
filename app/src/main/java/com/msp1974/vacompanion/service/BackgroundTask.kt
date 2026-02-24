package com.msp1974.vacompanion.service

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.res.AssetManager
import android.media.AudioManager
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.wyoming.Zeroconf
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.SoundClipPlayer
import com.msp1974.vacompanion.audio.AudioManager as AudManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.microwakeword.CombinedWakeWordProvider
import com.msp1974.vacompanion.microwakeword.WakeWordDetector
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.WakeWords
import com.msp1974.vacompanion.wyoming.WyomingCallback
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import com.msp1974.viewassistcompanionapp.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.util.Date
import kotlin.concurrent.thread

enum class AudioRouteOption { NONE, DETECT, PROCESS_NO_DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val firebase = FirebaseManager.getInstance()
    private var config: APPConfig = APPConfig.getInstance(context)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var audioInJob: Job? = null
    private var holdDetectionLevelJob: Job? = null
    private var lastWakeWordDetectionScore = 0f

    // microWakeWord detector replaces WakeWordEngine
    private var wakeWordDetector: WakeWordDetector? = null

    val zeroConf: Zeroconf = Zeroconf(context)

    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    val audioDSP: AudioDSP = AudioDSP()
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: WyomingTCPServer

    private var motionTask = CameraBackgroundTask(context)

    fun start() {
        assetManager = context.assets

        // Start wyoming server
        server = WyomingTCPServer(context, config.serverPort, object : WyomingCallback {
            override fun onSatelliteStarted() {
                Timber.i("Background Task - Connection detected")
                setInitialValues()
                startSensors(context)
                startOpenWakeWordDetection()
                startInputAudio()
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                Timber.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                stopOpenWakeWordDetection()
                stopInputAudio()
                stopSensors()
                zeroConf.registerService(config.serverPort)
            }

            override fun onRequestInputAudioStream() {
                Timber.i("Streaming audio to server")
                audioRoute = AudioRouteOption.STREAM
            }

            override fun onReleaseInputAudioStream() {
                Timber.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    audioRoute = AudioRouteOption.PROCESS_NO_DETECT
                    lastWakeWordDetectionScore = 0f

                    scope.launch {
                        delay(2000)
                        audioRoute = AudioRouteOption.DETECT
                    }
                }
            }
        })
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        Timber.d("Background task initialisation completed")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "notificationVolume" -> {
                setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Float)
            }
            "musicVolume" -> {
                setVolume(AudioManager.STREAM_MUSIC, event.newValue as Float)
            }
            "wakeWord" -> {
                scope.launch {
                    if (audioInJob != null && audioInJob!!.isActive) {
                        restartWakeWordDetection()
                    } else if (server.pipelineClient != null) {
                        startOpenWakeWordDetection()
                    }
                }
            }
            "recognitionError" -> {
                if (config.wakeWordSound != "none") {
                    try {
                        SoundClipPlayer(
                            context,
                            R.raw.error
                        ).play()
                    } catch (e: Exception) {
                        Timber.e("Error playing wake word sound: ${e.message.toString()}")
                    }
                }
            }
            "doNotDisturb" -> {
                setDoNotDisturb(event.newValue as Boolean)
                server.sendSetting("do_not_disturb", event.newValue)
            }
            "screenSaver" -> {
                server.sendSetting("screen_saver", event.newValue)
            }
            "restartZeroconf" -> {
                zeroConf.unregisterService()
                scope.launch {
                    delay(2000)
                    zeroConf.registerService(config.serverPort)
                }
            }
            "pairedDeviceID" -> {
                if (config.pairedDeviceID != "") {
                    Timber.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    Timber.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            "currentPath" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("screen_on", state)
                        })
                    }
                )
            }
            "enableMotionDetection" -> {
                val state = event.newValue as Boolean
                if (state) {
                    motionTask.startCamera()
                } else {
                    motionTask.stopCamera()
                }
            }
            "lastMotion" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", true)
                            put("last_motion", config.lastMotion)
                        })
                    }
                )
            }
            "lastActivity" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "motionDetectionSensitivity" -> {
                motionTask.setSensitivity(event.newValue as Int)
            }
            "useVoiceEnhancer", "useAdvancedGain" -> {
                scope.launch {
                    if (audioInJob != null && audioInJob!!.isActive) {
                        restartWakeWordDetection()
                    }
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun setInitialValues() {
        config.doNotDisturb = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)
    }

    fun startSensors(context: Context) {
        sensorRunner = Sensors(context, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        data.map { (key, value) ->
                            if (Helpers.isNumber(value.toString())) {
                                put(key, value.toString().toFloat())
                            } else {
                                put(key, value.toString())
                            }
                        }
                    }
                }
                server.sendStatus(data)
            }
        })
        // Start motion sensor
        if (config.enableMotionDetection) {
            motionTask.startCamera()
        }
    }

    fun stopSensors() {
        sensorRunner?.stop()
        motionTask.stopCamera()
    }

    fun startInputAudio() {
        val audioRecorder = AudioRecorder(context)
        if (audioRecorder.hasRecordPermission()) {
            audioInJob = scope.launch {
                Timber.i("Started input audio")
                audioRecorder.startRecording()
                    .collect { audioChunk ->
                        var audioLevel = audioChunk.floats.max()

                        if (!config.isMuted) {
                            // microWakeWord detection using raw PCM ByteBuffer
                            if (wakeWordDetector != null && audioRoute == AudioRouteOption.DETECT) {
                                val detections = wakeWordDetector!!.detect(audioChunk.raw)
                                for (detection in detections) {
                                    Timber.i("${detection.wakeWordPhrase} wake word detected (probability: ${detection.probability})")
                                    firebase.logEvent(
                                        FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                                            "wake_word" to config.wakeWord,
                                            "prediction" to detection.probability.toString()
                                        )
                                    )
                                    // Wake screen if configured
                                    if (config.screenOnWakeWord) {
                                        config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
                                    }
                                    // Play wake word sound
                                    if (config.wakeWordSound != "none") {
                                        try {
                                            SoundClipPlayer(
                                                context,
                                                context.resources.getIdentifier(
                                                    config.wakeWordSound,
                                                    "raw",
                                                    context.packageName
                                                )
                                            ).play()
                                        } catch (e: Exception) {
                                            Timber.e("Error playing wake word sound: ${e.message.toString()}")
                                        }
                                    }
                                    BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
                                }
                                // Update detection level for diagnostics
                                holdLastDetectionLevel(wakeWordDetector!!.getCurrentMaxProbability())
                            }

                            when (audioRoute) {
                                AudioRouteOption.STREAM -> {
                                    if (config.useAdvancedGain) {
                                        server.sendAudio(audioDSP.floatArrayToByteBuffer(audioChunk.floats))
                                    } else {
                                        val gAudioBuffer = audioDSP.autoGain(audioChunk.floats, config.micGain)
                                        val bAudioBuffer = audioDSP.floatArrayToByteBuffer(gAudioBuffer)
                                        audioLevel = gAudioBuffer.max()
                                        server.sendAudio(bAudioBuffer)
                                    }
                                }
                                else -> {}
                            }
                        } else {
                            audioLevel = 0f
                        }
                        if (config.diagnosticsEnabled) {
                            sendDiagnostics(
                                audioLevel,
                                lastWakeWordDetectionScore
                            )
                        }
                    }
            }
        }
    }

    fun stopInputAudio() {
        if (audioInJob != null && audioInJob!!.isActive) {
            Timber.i("Stopping input audio")
            audioInJob?.cancel()
            audioInJob = null
        }
    }

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        val data = DiagnosticInfo(
            show = config.diagnosticsEnabled,
            audioLevel = audioLevel * 100,
            detectionLevel = detectionLevel * 10,
            detectionThreshold = 0f, // microWakeWord models have built-in thresholds
            wakeWord = config.wakeWord,
            mode = audioRoute
        )
        val event = Event("diagnosticStats", "", data)
        config.eventBroadcaster.notifyEvent(event)
    }

    fun shutdown() {
        Timber.i("Shutting down")
        config.eventBroadcaster.removeListener(this)
        zeroConf.unregisterService()
        motionTask.stopCamera()
        stopInputAudio()
        stopOpenWakeWordDetection()
        stopSensors()
        server.stop()

    }

    private fun startOpenWakeWordDetection() {
        if (config.wakeWord == "none") {
            audioRoute = AudioRouteOption.NONE
            return
        }

        if (wakeWordDetector != null) {
            stopOpenWakeWordDetection()
        }

        try {
            val wakeWordProvider = CombinedWakeWordProvider(context)
            wakeWordDetector = WakeWordDetector(wakeWordProvider)

            // Map the config wake word to the microWakeWord model ID
            val wakeWordId = mapWakeWordId(config.wakeWord)
            val availableIds = wakeWordDetector!!.getAvailableWakeWordIds()

            if (wakeWordId in availableIds) {
                wakeWordDetector!!.setActiveWakeWords(listOf(wakeWordId))
                Timber.i("Started microWakeWord detection with model: $wakeWordId")
                audioRoute = AudioRouteOption.DETECT
            } else {
                Timber.w("Wake word '$wakeWordId' not found in available models: $availableIds")
                wakeWordDetector?.close()
                wakeWordDetector = null
                audioRoute = AudioRouteOption.NONE
            }
        } catch (e: Exception) {
            Timber.e("Failed to start microWakeWord detection: ${e.message}")
            wakeWordDetector?.close()
            wakeWordDetector = null
            audioRoute = AudioRouteOption.NONE
        }
    }

    /**
     * Maps old ONNX-based wake word IDs to microWakeWord model IDs.
     * Handles naming differences between old and new models.
     */
    private fun mapWakeWordId(oldId: String): String {
        return when (oldId) {
            "ok_nabu" -> "okay_nabu"
            "ok_computer" -> "okay_computer"
            "hey_raspy" -> "hey_rhasspy" // old typo in VACA config
            else -> oldId
        }
    }

    private fun holdLastDetectionLevel(detectionLevel: Float, duration: Long = 2000) {
        if (detectionLevel > lastWakeWordDetectionScore) {
            lastWakeWordDetectionScore = detectionLevel
            if (holdDetectionLevelJob != null && holdDetectionLevelJob!!.isActive) {
                holdDetectionLevelJob?.cancel()
            }
            holdDetectionLevelJob = scope.launch {
                delay(duration)
                if (audioRoute == AudioRouteOption.DETECT) {
                    lastWakeWordDetectionScore = 0f
                }
            }
        }
    }

    private fun restartWakeWordDetection() {
        if (audioInJob != null && audioInJob!!.isActive) {
            Timber.i("Restarting wake word detection")
            stopOpenWakeWordDetection()
            stopInputAudio()

            startOpenWakeWordDetection()
            startInputAudio()
        }
    }


    private fun stopOpenWakeWordDetection() {
        Timber.i("Stopping wake word detection")
        audioRoute = AudioRouteOption.NONE

        if (wakeWordDetector != null) {
            wakeWordDetector?.close()
            wakeWordDetector = null
        }

        if (config.diagnosticsEnabled) {
            sendDiagnostics(0f, 0f)
        }

        Timber.d("Wake word detection stopped")
    }

    fun setVolume(stream: Int, volume: Float) {
        try {
            val audioManager = AudManager(context)
            audioManager.setVolume(stream, volume)
        } catch (e: Exception) {
            Timber.d("Error setting volume: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isInDND = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (isInDND != enable) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.d("Setting do not disturb to $enable")
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } else {
                Timber.w("Unable to set do not disturb, notification policy access not granted")
                config.eventBroadcaster.notifyEvent(
                    Event(
                        "showToastMessage",
                        "",
                        "Unable to set do not disturb.  Permission not granted."
                    )
                )
            }
        }
    }
}