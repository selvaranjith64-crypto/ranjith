package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.vosk.android.RecognitionListener as VoskListener

class NovaService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var awaitingUntil = 0L
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }
    private val lang: String get() = prefs.getString("lang", "en") ?: "en"

    private var model: Model? = null
    private var voskRec: Recognizer? = null
    private var voskService: SpeechService? = null
    private var fallback = false
    private var wasPlaying = false
    private var userPaused = false
    private var lastPartial = ""
    private var commandStart = 0L
    private var ignoreUntil = 0L
    private var lastSpoken = ""
    private var resumePending = false
    private var partialJob: Runnable? = null
    private val wakeRegex = Regex("\\b(nova|novo|noah|nover|novah|nowa|no va|know va|innova|novel|noa|nova's|நோவா)\\b")

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter.",
        "Why don't scientists trust atoms? They make up everything.",
        "I told my phone I needed space. Now it says storage full.",
        "Why was the computer cold? It left its Windows open."
    )

    private val talk: List<Pair<Regex, List<String>>> = listOf(
        Regex("^(hello|hi|hey|hai|hallo)( there)?$") to listOf("Hello! How can I help you?", "Hi! I'm listening.", "Hey! What can I do for you?"),
        Regex("how are you|how r you|how do you do") to listOf("I'm doing great, thank you for asking. How are you?", "Feeling good and ready to help. How about you?"),
        Regex("^(i am|i'm|im) (fine|good|great|okay|ok|well)") to listOf("That's wonderful to hear!", "Glad you're doing well."),
        Regex("^(i am|i'm|im) (sad|tired|bored|not good|not well|sick)") to listOf("I'm sorry to hear that. I'm here with you. Want me to play some music?", "Take it easy. Should I play something relaxing on Spotify?"),
        Regex("(what('s| is) your name|who are you)") to listOf("I'm Nova, your personal voice assistant."),
        Regex("(who made you|who created you)") to listOf("I was built for you, to help you control your phone with your voice."),
        Regex("(thank you|thanks|thank u)") to listOf("You're welcome!", "Anytime!", "Happy to help."),
        Regex("good morning") to listOf("Good morning! I hope you have a lovely day."),
        Regex("good afternoon") to listOf("Good afternoon! How is your day going?"),
        Regex("good evening") to listOf("Good evening! How can I help?"),
        Regex("good night") to listOf("Good night. Sleep well, and see you tomorrow."),
        Regex("(i love you|love you)") to listOf("That's very kind. I'm always here for you."),
        Regex("(what can you do|help me|what are your commands)") to listOf("I can open apps, make calls, play Spotify, control volume and flashlight, set timers, tell the time, and search the web. Just say Nova first."),
        Regex("(are you there|can you hear me)") to listOf("Yes, I'm here and listening.")
    )

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, "nova")
            .setContentTitle("Nova is listening")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        prefs.edit().putString("status", text).apply()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotif(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (intent?.action == "listen" && running) {
            if (fallback) startListening()
            else if (model != null) {
                stopWake()
                wasPlaying = audio.isMusicActive
                userPaused = false
                startCommandListen()
            }
            return START_STICKY
        }
        if (!running) {
            running = true
            prepareModel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        muteBeep(false)
        applyBt(false)
        handler.removeCallbacksAndMessages(null)
        stopWake()
        try { voskRec?.close() } catch (e: Exception) {}
        try { model?.close() } catch (e: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("en", "IN")) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED || r == TextToSpeech.ERROR) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speaking = false; ignoreUntil = System.currentTimeMillis() + 1500 }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking = false }
            })
            ttsReady = true
        }
    }

    private fun applyBt(on: Boolean) {
        try {
            if (on) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            } else {
                audio.isBluetoothScoOn = false
                audio.stopBluetoothSco()
                audio.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {}
    }

    private fun muteBeep(mute: Boolean) {
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    // ---------------------------------------------------- offline wake word (does not stop music)
    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (n in list) copyAssets("$path/$n", File(dest, n))
        }
    }

    private fun prepareModel() {
        Thread {
            try {
                val dir = File(filesDir, "model")
                val marker = File(filesDir, "model.ok")
                if (!marker.exists()) {
                    if ((assets.list("model")?.size ?: 0) == 0) throw Exception("model missing")
                    handler.post { notify("Preparing voice engine (first time only)…") }
                    dir.deleteRecursively()
                    copyAssets("model", dir)
                    marker.writeText("ok")
                }
                val m = Model(dir.absolutePath)
                handler.post {
                    model = m
                    val bt = prefs.getBoolean("bt", false)
                    applyBt(bt)
                    handler.postDelayed({ startWake() }, if (bt) 1500L else 0L)
                    handler.postDelayed({ watchdog() }, 8000)
                }
            } catch (e: Throwable) {
                handler.post { enterFallback() }
            }
        }.start()
    }

    private fun startWake() {
        if (!running || fallback) return
        if (speaking) { handler.postDelayed({ startWake() }, 250); return }
        try {
            stopWake()
            val rec = voskRec ?: Recognizer(model!!, 16000.0f).also { voskRec = it }
            rec.reset()
            val svc = SpeechService(rec, 16000.0f)
            voskService = svc
            svc.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) { handler.post { onWakePartial(hypothesis) } }
                override fun onResult(hypothesis: String?) { handler.post { onWakeResult(hypothesis) } }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) { handler.post { enterFallback() } }
                override fun onTimeout() {}
            })
            notify("Say \"Nova\"")
        } catch (e: Throwable) {
            enterFallback()
        }
    }

    private fun stopWake() {
        partialJob?.let { handler.removeCallbacks(it) }
        partialJob = null
        voskService?.let { try { it.stop(); it.shutdown() } catch (e: Throwable) {} }
        voskService = null
    }

    private fun onWakePartial(hypothesis: String?) {
        if (voskService == null || lang != "ta") return
        val p = try { JSONObject(hypothesis ?: "").optString("partial", "") } catch (e: Exception) { "" }.lowercase().trim()
        lastPartial = p
        val w = wakeRegex.find(p) ?: return
        if (p.substring(w.range.last + 1).trim().isNotEmpty()) return
        partialJob?.let { handler.removeCallbacks(it) }
        val job = Runnable { if (voskService != null && lastPartial == p) triggerWake(p) }
        partialJob = job
        handler.postDelayed(job, 500)
    }

    private fun triggerWake(heard: String) {
        notify("Heard: $heard")
        stopWake()
        wasPlaying = audio.isMusicActive
        userPaused = false
        startCommandListen()
    }

    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(80)
        } catch (e: Exception) {}
    }

    private fun onWakeResult(hypothesis: String?) {
        if (voskService == null) return
        val text = try { JSONObject(hypothesis ?: "").optString("text", "") } catch (e: Exception) { "" }
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (speaking || now < ignoreUntil) return
        val t = text.lowercase().trim()
        val wake = wakeRegex.find(t)

        if (lang != "ta") {
            // English: Vosk listens all the time and also understands the command, so nothing is handed over
            // and it keeps answering every time, like Alexa.
            val rest = when {
                wake != null -> t.substring(wake.range.last + 1).trim()
                now < awaitingUntil -> t
                else -> return
            }
            if (rest.length > 6 && lastSpoken.contains(rest)) return
            notify("Heard: $text")
            awaitingUntil = 0L
            if (rest.isEmpty()) { awaitingUntil = now + 8000; buzz(); return }
            handle(rest)
            return
        }

        if (wake == null) return
        notify("Heard: $text")
        stopWake()
        wasPlaying = audio.isMusicActive
        userPaused = false
        val rest = t.substring(wake.range.last + 1).trim()
        if (rest.isNotEmpty()) {
            handle(rest)
            afterCommand()
        } else {
            startCommandListen()
        }
    }

    private fun afterCommand() {
        commandStart = 0L
        resumePending = true
        handler.postDelayed({ resumeWhenQuiet() }, 300)
    }

    private fun resumeWhenQuiet() {
        if (!running) { resumePending = false; return }
        if (speaking) { handler.postDelayed({ resumeWhenQuiet() }, 200); return }
        if (wasPlaying && !userPaused && !audio.isMusicActive) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        wasPlaying = false
        try { voskRec?.close() } catch (e: Throwable) {}
        voskRec = null
        val bt = prefs.getBoolean("bt", false)
        if (bt) applyBt(true)
        handler.postDelayed({ resumePending = false; startWake() }, if (bt) 1200L else 250L)
    }

    private fun watchdog() {
        if (!running) return
        handler.postDelayed({ watchdog() }, 6000)
        if (fallback || model == null || speaking || resumePending) return
        val now = System.currentTimeMillis()
        if (commandStart != 0L && now - commandStart > 15000) { afterCommand(); return }
        if (commandStart == 0L && voskService == null) startWake()
    }

    private fun startCommandListen() {
        commandStart = System.currentTimeMillis()
        muteBeep(true)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        recognizer?.startListening(recognizerIntent())
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "ta") "ta-IN" else "en-IN")
        if (lang == "ta") putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN"))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 900L)
        putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 700L)
    }

    // ---------------------------------------------------- basic mode (used only if the voice engine fails)
    private fun enterFallback() {
        if (fallback) return
        fallback = true
        stopWake()
        notify("Basic mode: say \"Nova\" and a command")
        startListening()
    }

    private fun startListening() {
        if (!running) return
        if (speaking) { scheduleRestart(700); return }
        muteBeep(true)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        recognizer?.startListening(recognizerIntent())
    }

    private fun scheduleRestart(delay: Long = 300) {
        handler.postDelayed({ startListening() }, delay)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (fallback) {
            if (!text.isNullOrBlank()) onHeardFallback(text)
            scheduleRestart(if (speaking) 900 else 300)
        } else {
            commandStart = 0L
            if (!text.isNullOrBlank()) {
                notify("Heard: $text")
                val cmd = text.lowercase().replace(Regex("[.,!?]"), "").trim().replace(Regex("^(nova|noah|novo|நோவா)\\s*"), "")
                if (cmd.isNotEmpty()) handle(cmd)
            }
            afterCommand()
        }
    }

    override fun onError(error: Int) {
        if (fallback) scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
        else { commandStart = 0L; afterCommand() }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun onHeardFallback(raw: String) {
        notify("Heard: $raw")
        val t = raw.lowercase().replace(Regex("[.,!?]"), "").trim()
        val now = System.currentTimeMillis()
        val wake = wakeRegex.find(t)
        val cmd = when {
            wake != null -> t.substring(wake.range.last + 1).trim()
            now < awaitingUntil -> t
            else -> return
        }
        awaitingUntil = 0
        if (cmd.isEmpty()) {
            awaitingUntil = now + 9000
            say("Yes?")
            return
        }
        handle(cmd)
        awaitingUntil = System.currentTimeMillis() + 10000
    }

    private fun handle(raw: String) {
        var cmd = raw
        if (Regex("[\\u0B80-\\u0BFF]").containsMatchIn(cmd)) cmd = tamilToEnglish(cmd)
        if (Regex("tamil mode|switch to tamil|speak (in )?tamil").containsMatchIn(cmd)) { setLang("ta"); return }
        if (Regex("english mode|switch to english|speak (in )?english").containsMatchIn(cmd)) { setLang("en"); return }
        if (Regex("^(stop|cancel|quiet|be quiet)$").matches(cmd)) { tts?.stop(); speaking = false; return }
        if (Regex("^(goodbye|bye|exit|stop listening|shut down)$").matches(cmd)) {
            say("Goodbye. Talk to you later."); handler.postDelayed({ stopSelf() }, 2500); return
        }
        if (Regex("what('s| is) the time|what time is it").containsMatchIn(cmd)) {
            say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); return
        }
        if (Regex("today's date|what('s| is) the date|what day is it").containsMatchIn(cmd)) {
            say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())); return
        }
        if (cmd.contains("joke")) { say(jokes[(System.currentTimeMillis() % jokes.size).toInt()]); return }

        if (Regex("battery").containsMatchIn(cmd)) {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            say("Your battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent."); return
        }
        if (Regex("flash ?light|torch").containsMatchIn(cmd)) {
            setTorch(!Regex("off|close|stop").containsMatchIn(cmd)); return
        }
        if (Regex("volume up|louder|increase volume").containsMatchIn(cmd)) { changeVolume(AudioManager.ADJUST_RAISE); say("Volume up."); return }
        if (Regex("volume down|quieter|lower the volume|decrease volume").containsMatchIn(cmd)) { changeVolume(AudioManager.ADJUST_LOWER); say("Volume down."); return }
        if (Regex("max volume|full volume").containsMatchIn(cmd)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI); say("Maximum volume."); return
        }
        if (Regex("^(pause|pause music|pause the music|stop music|stop the music)$").matches(cmd)) { userPaused = true; mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return }
        if (Regex("^(resume|resume music|continue|continue music|play music)$").matches(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return }
        if (Regex("next (song|track)|skip").containsMatchIn(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return }
        if (Regex("(previous|last) (song|track)").containsMatchIn(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return }
        if (Regex("wi-?fi settings|open wi-?fi").containsMatchIn(cmd)) { say("Opening Wi-Fi settings."); launch(Intent(Settings.ACTION_WIFI_SETTINGS)); return }
        if (Regex("bluetooth settings|open bluetooth").containsMatchIn(cmd)) { say("Opening Bluetooth settings."); launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return }
        if (Regex("^(go home|home screen|go to home)$").matches(cmd)) {
            launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); return
        }

        val timer = Regex("timer (?:for )?(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?").find(cmd)
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?\\s+timer").find(cmd)
        if (timer != null) {
            val n = timer.groupValues[1].toDouble()
            val unit = timer.groupValues[2]
            val ms = (n * when { unit.startsWith("h") -> 3600000 ; unit.startsWith("m") -> 60000 ; else -> 1000 }).toLong()
            val word = when { unit.startsWith("h") -> "hour"; unit.startsWith("m") -> "minute"; else -> "second" }
            val label = (if (n == n.toInt().toDouble()) n.toInt().toString() else n.toString()) + " " + word + (if (n == 1.0) "" else "s")
            handler.postDelayed({
                try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) } catch (e: Exception) {}
                say("Your $label timer is done.")
            }, ms)
            say("Timer set for $label.")
            return
        }

        Regex("alarm (?:for|at) (\\d{1,2})(?:[:\\s](\\d{2}))?\\s*(am|pm|a m|p m)?").find(cmd)?.let {
            var h = it.groupValues[1].toInt()
            val mi = it.groupValues[2].ifBlank { "0" }.toInt()
            val ap = it.groupValues[3].replace(" ", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            val ai = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mi)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            say("Alarm set for " + h + ":" + mi.toString().padStart(2, '0') + ".")
            launch(ai); return
        }
        Regex("^(?:navigate to|take me to|directions to|drive to|route to)\\s+(.+)$").find(cmd)?.let {
            say("Starting navigation to " + it.groupValues[1] + ".")
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(it.groupValues[1])))); return
        }
        if (cmd.contains("selfie")) {
            say("Opening the selfie camera.")
            launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra("android.intent.extras.CAMERA_FACING", 1)); return
        }
        if (Regex("take (a )?(photo|picture|pic)|open camera").containsMatchIn(cmd)) {
            say("Opening the camera.")
            launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return
        }
        Regex("^(?:send (?:a )?(?:sms|text)(?: message)? to|sms|text)\\s+(.+?)\\s+(?:saying|that says|say)\\s+(.+)$").find(cmd)?.let {
            val n = findNumber(it.groupValues[1].trim())
            if (n == null) { say("I could not find " + it.groupValues[1] + " in your contacts."); return }
            say("Writing your message. Tap send.")
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", it.groupValues[2])); return
        }
        Regex("^(?:send (?:a )?(?:whatsapp )?message to|whatsapp message to|message|whatsapp)\\s+(.+?)\\s+(?:saying|that says|say)\\s+(.+)$").find(cmd)?.let {
            val n = findNumber(it.groupValues[1].trim())
            if (n == null) { say("I could not find " + it.groupValues[1] + " in your contacts."); return }
            var d = n.filter { c -> c.isDigit() }
            if (d.length == 10) d = "91$d"
            say("Opening WhatsApp. Tap send.")
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(it.groupValues[2])))); return
        }
        Regex("remind me (?:in|after) (\\d+)\\s*(second|sec|minute|min|hour)s? (?:to|about) (.+)").find(cmd)?.let {
            val n = it.groupValues[1].toLong()
            val u = it.groupValues[2]
            val ms = n * (if (u.startsWith("h")) 3600000L else if (u.startsWith("m")) 60000L else 1000L)
            val what = it.groupValues[3]
            handler.postDelayed({ say("Reminder: $what") }, ms)
            say("Okay, I will remind you."); return
        }
        if (Regex("daily briefing|brief me|what's up today|my day").containsMatchIn(cmd)) {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ". Today is " +
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) + ". Your battery is at " +
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent."); return
        }

        for ((rx, replies) in talk) {
            if (rx.containsMatchIn(cmd)) { say(replies[(System.currentTimeMillis() % replies.size).toInt()]); return }
        }

        Regex("^(?:whatsapp\\s+(video\\s+)?call\\s+(.+)|(video\\s+)?call\\s+(.+?)\\s+(?:on|in|using|through|via)\\s+whatsapp)$").find(cmd)?.let {
            val g = it.groupValues
            val who = (if (g[2].isNotBlank()) g[2] else g[4]).trim()
            whatsAppCall(who, g[1].isNotBlank() || g[3].isNotBlank()); return
        }
        Regex("^(?:play|put on|start)\\s+(.+?)\\s+(?:on|in|using)\\s+spotify$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^spotify\\s+(?:play\\s+)?(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^play\\s+(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^(?:call|phone|dial|ring)\\s+(.+)$").find(cmd)?.let { callPerson(it.groupValues[1].removePrefix("to ").trim()); return }
        Regex("^(?:open|launch|start|go to)\\s+(.+)$").find(cmd)?.let { openApp(it.groupValues[1]); return }

        val q = Regex("^(?:search(?: for)?|google|look up)\\s+(.+)$").find(cmd)?.groupValues?.get(1) ?: cmd
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        say("Let me search for $q.")
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("This phone has no flashlight."); return }
            cm.setTorchMode(id, on)
            say(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            say("I could not control the flashlight.")
        }
    }

    private fun changeVolume(direction: Int) {
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playSpotify(query: String) {
        say("Playing $query on Spotify.")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        if (!launch(play)) launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
    }

    private fun whatsAppCall(who: String, video: Boolean) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission for Nova."); return
        }
        val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call" else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        var found: Long? = null
        try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.DISPLAY_NAME + " LIKE ?",
                arrayOf(mime, "%$who%"), null
            )?.use { if (it.moveToFirst()) found = it.getLong(0) }
        } catch (e: Exception) {}
        val dataId = found
        if (dataId != null) {
            say("Calling $who on WhatsApp.")
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.contacts/data/$dataId"), mime).setPackage("com.whatsapp")
            if (launch(i)) return
        }
        val number = findNumber(who)
        if (number == null) { say("I could not find $who in your contacts."); return }
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        say("Opening WhatsApp chat with $who. Tap the call button.")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
    }

    private fun callPerson(who: String) {
        val digits = who.replace(" ", "")
        val number = if (Regex("^\\+?\\d{3,}$").matches(digits)) digits else findNumber(who)
        if (number == null) { say("I could not find $who in your contacts."); return }
        say("Calling $who.")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val canCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        val target = norm(name)
        if (target.isEmpty()) return null
        var best: String? = null
        var bestD = Int.MAX_VALUE
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = norm(c.getString(0) ?: "")
                if (n.isEmpty()) continue
                val d = dist(n, target)
                if (d < bestD) { bestD = d; best = c.getString(1) }
            }
        }
        return if (bestD <= maxOf(1, target.length / 3)) best else null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val aliases = mapOf(
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "googlepay" to "com.google.android.apps.nbu.paisa.user",
        "gpe" to "com.google.android.apps.nbu.paisa.user",
        "phonepe" to "com.phonepe.app",
        "paytm" to "net.one97.paytm",
        "bhim" to "in.org.npci.upiapp",
        "whatsapp" to "com.whatsapp",
        "whatsappbusiness" to "com.whatsapp.w4b",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "spotify" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "maps" to "com.google.android.apps.maps",
        "googlemaps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "playstore" to "com.android.vending",
        "settings" to "com.android.settings",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "netflix" to "com.netflix.mediaclient"
    )

    private fun dist(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
        }
        return d[a.length][b.length]
    }

    private fun openApp(spoken: String) {
        val name = norm(spoken.removePrefix("the ").removeSuffix(" app"))
        val pm = packageManager
        val aliasPkg = aliases[name]
        if (aliasPkg != null) {
            val li = pm.getLaunchIntentForPackage(aliasPkg)
            if (li != null) { say("Opening $spoken."); launch(li); return }
        }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to norm(it.loadLabel(pm).toString()) }
        val match = apps.firstOrNull { it.second == name }
            ?: apps.firstOrNull { it.second.startsWith(name) }
            ?: apps.firstOrNull { name.length >= 3 && it.second.contains(name) }
            ?: (if (name.length >= 4) apps.map { it to dist(it.second, name) }
                .filter { it.second <= maxOf(1, name.length / 4) }
                .minByOrNull { it.second }?.first else null)
        val intent = match?.let { pm.getLaunchIntentForPackage(it.first) }
        if (intent != null) {
            say("Opening $spoken.")
            launch(intent)
        } else {
            say("I could not find an app called $spoken.")
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun setEnglishVoice() {
        val r = tts?.setLanguage(Locale("en", "IN")) ?: TextToSpeech.ERROR
        if (r < 0) tts?.setLanguage(Locale.US)
    }

    private fun say(text: String) {
        notify("Nova: $text")
        if (!ttsReady) return
        var out = text
        val ta = if (lang == "ta") translateTa(text) else null
        if (ta != null) {
            val r = tts?.setLanguage(Locale("ta", "IN")) ?: TextToSpeech.ERROR
            if (r < 0) setEnglishVoice() else out = ta
        } else {
            setEnglishVoice()
        }
        lastSpoken = (text + " " + out).lowercase()
        speaking = true
        val res = tts?.speak(out, TextToSpeech.QUEUE_FLUSH, null, "nova")
        if (res != TextToSpeech.SUCCESS) speaking = false
        handler.postDelayed({ speaking = false }, 9000)
    }

    private fun setLang(l: String) {
        prefs.edit().putString("lang", l).apply()
        say(if (l == "ta") "Tamil mode on." else "English mode on.")
    }

    // ------------------------------------------------------------------ Tamil support
    private val tamilApps = mapOf(
        "வாட்ஸ்அப்" to "whatsapp", "வாட்சப்" to "whatsapp", "வாட்ஸ்ஆப்" to "whatsapp", "வாட்சாப்" to "whatsapp",
        "இன்ஸ்டாகிராம்" to "instagram", "இன்ஸ்டா" to "instagram",
        "யூடியூப்" to "youtube", "யூட்யூப்" to "youtube",
        "ஜிபே" to "gpay", "ஜி பே" to "gpay", "ஜீபே" to "gpay", "கூகுள் பே" to "gpay", "கூகிள் பே" to "gpay",
        "போன்பே" to "phonepe", "போன் பே" to "phonepe", "பேடிஎம்" to "paytm", "பே டிஎம்" to "paytm",
        "ஸ்பாட்டிஃபை" to "spotify", "ஸ்பாட்டிபை" to "spotify", "ஸ்பாடிஃபை" to "spotify",
        "கூகுள் மேப்ஸ்" to "maps", "மேப்ஸ்" to "maps", "ஜிமெயில்" to "gmail", "குரோம்" to "chrome",
        "கேமரா" to "camera", "செட்டிங்ஸ்" to "settings", "அமேசான்" to "amazon", "ஃபிளிப்கார்ட்" to "flipkart",
        "நெட்ஃபிளிக்ஸ்" to "netflix", "டெலிகிராம்" to "telegram", "ப்ளே ஸ்டோர்" to "play store"
    )
    private val tamilNames = mapOf(
        "அம்மா" to "amma", "அப்பா" to "appa", "அண்ணா" to "anna", "அக்கா" to "akka",
        "தம்பி" to "thambi", "தங்கை" to "thangai", "மனைவி" to "wife", "நண்பன்" to "friend"
    )

    private fun tamilToEnglish(s0: String): String {
        val s = s0.replace(Regex("[.,!?]"), "").replace("நோவா", "").trim()
        fun app(x: String) = tamilApps.entries.firstOrNull { x.contains(it.key) }?.value
        fun nm(x: String) = tamilNames.entries.firstOrNull { x.contains(it.key) }?.value
        val callWord = Regex("(கால் பண்ணு|கால் செய்|கால் போடு|போன் பண்ணு|போன் போடு|அழை|கால்)")
        val openWord = Regex("(ஓபன் பண்ணு|ஓபன் செய்|ஓபன்|திறக்கவும்|திறங்க|திற)")
        fun cleanName(x: String): String {
            var n = x
            tamilApps.keys.forEach { n = n.replace(it, "") }
            n = n.replace("வீடியோ", "").replace(Regex("(இல்|ில்|ல்)$"), "").trim()
            n = n.replace(Regex("(வுக்கு|க்கு|யை|ஐ|இடம்|கிட்ட)$"), "").trim()
            return nm(n) ?: n
        }
        val tm = Regex("(\\d+)\\s*(நிமிடம்|நிமிடங்கள்|நிமிடத்துக்கு|மணி நேரம்|வினாடி|விநாடி)").find(s)
        if (tm != null && s.contains("டைமர்")) {
            val unit = if (tm.groupValues[2].startsWith("நிமிட")) "minutes" else if (tm.groupValues[2].startsWith("மணி")) "hours" else "seconds"
            return "set a timer for " + tm.groupValues[1] + " " + unit
        }
        if (Regex("நேரம் என்ன|மணி என்ன|டைம் என்ன").containsMatchIn(s)) return "what time is it"
        if (Regex("தேதி|கிழமை").containsMatchIn(s)) return "what is the date"
        if (s.contains("பேட்டரி")) return "battery"
        if (Regex("வணக்கம்|ஹலோ").containsMatchIn(s)) return "hello"
        if (s.contains("நன்றி")) return "thank you"
        if (Regex("எப்படி இருக்க|நலமா|சுகமா").containsMatchIn(s)) return "how are you"
        if (Regex("உன் பெயர்|உங்கள் பெயர்|நீ யார்").containsMatchIn(s)) return "who are you"
        if (Regex("ஜோக்|நகைச்சுவை|சிரிப்பு").containsMatchIn(s)) return "tell me a joke"
        if (Regex("அடுத்த பாடல்|அடுத்த பாட்டு").containsMatchIn(s)) return "next song"
        if (Regex("முந்தைய பாடல்|முந்தைய பாட்டு").containsMatchIn(s)) return "previous song"
        if (Regex("தொடரவும்|மீண்டும் இயக்கு").containsMatchIn(s)) return "resume"
        if (s.contains("நிறுத்து")) return "pause"
        if (Regex("(ஒலி|சத்தம்|வால்யூம்).*(கூட்டு|அதிகம்|ஏத்து)").containsMatchIn(s)) return "volume up"
        if (Regex("(ஒலி|சத்தம்|வால்யூம்).*(குறை|கம்மி)").containsMatchIn(s)) return "volume down"
        if (Regex("(டார்ச்|ஃபிளாஷ்லைட்).*(ஆஃப்|அணை)").containsMatchIn(s)) return "flashlight off"
        if (Regex("டார்ச்|ஃபிளாஷ்லைட்").containsMatchIn(s)) return "flashlight on"
        if (Regex("ஆங்கிலம்").containsMatchIn(s)) return "english mode"
        if (Regex("தமிழ்").containsMatchIn(s)) return "tamil mode"
        if (callWord.containsMatchIn(s)) {
            val name = cleanName(s.replace(callWord, "").trim())
            val video = if (s.contains("வீடியோ")) "video " else ""
            return if (app(s) == "whatsapp") video + "call " + name + " on whatsapp" else "call $name"
        }
        if (openWord.containsMatchIn(s)) {
            val obj = s.replace(openWord, "").trim()
            return "open " + (app(obj) ?: obj)
        }
        if (Regex("(போடு|ப்ளே|இசை|பாடு)").containsMatchIn(s)) {
            val q = s.replace(Regex("(ஸ்பாட்டிஃபையில்|ஸ்பாட்டிஃபை|ஸ்பாட்டிபை|பாடல்கள்|பாடலை|பாடல்|போடு|ப்ளே பண்ணு|ப்ளே|பாடு|இசை)"), "").trim()
            if (q.isNotEmpty()) return "play $q on spotify"
        }
        if (s.contains("தேடு")) return "search for " + s.replace("தேடு", "").trim()
        val onlyApp = app(s)
        if (onlyApp != null) return "open $onlyApp"
        return s
    }

    private fun translateTa(t: String): String? {
        Regex("^Opening (.+)\\.$").find(t)?.let { return it.groupValues[1] + " திறக்கிறேன்." }
        Regex("^Calling (.+) on WhatsApp\\.$").find(t)?.let { return "வாட்ஸ்அப்பில் " + it.groupValues[1] + " க்கு அழைக்கிறேன்." }
        Regex("^Calling (.+)\\.$").find(t)?.let { return it.groupValues[1] + " க்கு அழைக்கிறேன்." }
        Regex("^Playing (.+) on Spotify\\.$").find(t)?.let { return "ஸ்பாட்டிஃபையில் " + it.groupValues[1] + " இயக்குகிறேன்." }
        Regex("^It is (.+)$").find(t)?.let { return "இப்போது நேரம் " + it.groupValues[1] }
        Regex("^Today is (.+)$").find(t)?.let { return "இன்று " + it.groupValues[1] }
        Regex("^Your battery is at (\\d+) percent\\.$").find(t)?.let { return "பேட்டரி " + it.groupValues[1] + " சதவீதம் உள்ளது." }
        Regex("^Timer set for (.+)\\.$").find(t)?.let { return it.groupValues[1] + " டைமர் வைத்துவிட்டேன்." }
        Regex("^Your (.+) timer is done\\.$").find(t)?.let { return "உங்கள் டைமர் முடிந்தது." }
        Regex("^I could not find an app called (.+)\\.$").find(t)?.let { return it.groupValues[1] + " என்ற ஆப் கிடைக்கவில்லை." }
        Regex("^I could not find (.+) in your contacts\\.$").find(t)?.let { return it.groupValues[1] + " உங்கள் தொடர்புகளில் இல்லை." }
        Regex("^Let me search for (.+)\\.$").find(t)?.let { return it.groupValues[1] + " பற்றி தேடுகிறேன்." }
        return when (t) {
            "Yes?" -> "சொல்லுங்கள்?"
            "Flashlight on." -> "டார்ச் ஆன்."
            "Flashlight off." -> "டார்ச் ஆஃப்."
            "Volume up." -> "ஒலி கூட்டப்பட்டது."
            "Volume down." -> "ஒலி குறைக்கப்பட்டது."
            "Maximum volume." -> "அதிகபட்ச ஒலி."
            "Goodbye. Talk to you later." -> "சரி, பிறகு பேசலாம்."
            "Tamil mode on." -> "தமிழ் முறை இயக்கப்பட்டது."
            "English mode on." -> "ஆங்கில முறை இயக்கப்பட்டது."
            "Hello! How can I help you?", "Hi! I'm listening.", "Hey! What can I do for you?" -> "வணக்கம்! நான் எப்படி உதவலாம்?"
            "I'm doing great, thank you for asking. How are you?", "Feeling good and ready to help. How about you?" -> "நான் நலமாக இருக்கிறேன். நீங்கள் எப்படி இருக்கிறீர்கள்?"
            "I'm Nova, your personal voice assistant." -> "நான் நோவா, உங்கள் குரல் உதவியாளர்."
            "You're welcome!", "Anytime!", "Happy to help." -> "பரவாயில்லை, மகிழ்ச்சி!"
            "Good morning! I hope you have a lovely day." -> "காலை வணக்கம்! இனிய நாளாக அமையட்டும்."
            "Good night. Sleep well, and see you tomorrow." -> "இனிய இரவு வணக்கம். நன்றாக தூங்குங்கள்."
            "Yes, I'm here and listening." -> "ஆம், நான் இங்கே கேட்கிறேன்."
            else -> null
        }
    }

}
