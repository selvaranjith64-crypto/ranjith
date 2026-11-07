package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var pendingAction: String? = null

    private val needed: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Nova"; textSize = 40f })
        root.addView(Button(this).apply {
            text = "Talk to Nova now (no wake word)"
            setOnClickListener { startNova("listen") }
        })
        val btButton = Button(this)
        fun btLabel() = "Bluetooth earphone mic mode: " + (if (prefs.getBoolean("bt", false)) "ON" else "OFF") + " (tap to change, then restart Nova)"
        btButton.text = btLabel()
        btButton.setOnClickListener {
            prefs.edit().putBoolean("bt", !prefs.getBoolean("bt", false)).apply()
            btButton.text = btLabel()
        }
        root.addView(btButton)
        val langStatus = TextView(this).apply {
            textSize = 14f
            text = "Language: " + (if (prefs.getString("lang", "en") == "ta") "தமிழ் (Tamil)" else "English")
        }
        root.addView(langStatus)
        root.addView(Button(this).apply {
            text = "English"
            setOnClickListener { prefs.edit().putString("lang", "en").apply(); langStatus.text = "Language: English" }
        })
        root.addView(Button(this).apply {
            text = "தமிழ் (Tamil)"
            setOnClickListener { prefs.edit().putString("lang", "ta").apply(); langStatus.text = "Language: தமிழ் (Tamil)" }
        })
        root.addView(Button(this).apply {
            text = "Allow \"Display over other apps\""
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Allow \"Do not disturb access\" (silences beeps)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Start Nova"
            setOnClickListener { startNova(null) }
        })
        root.addView(Button(this).apply {
            text = "Stop Nova"
            setOnClickListener { stopService(Intent(this@MainActivity, NovaService::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "\nSay \"Nova\", pause, then:\n\n" +
                "open Instagram / open GPay\ncall Amma\ncall Amma on WhatsApp\nvideo call Amma on WhatsApp\n" +
                "play Anirudh on Spotify\npause / next song / volume up\nflashlight on / off\nbattery level\n" +
                "set an alarm for 6:30 am\nremind me in 10 minutes to drink water\nnavigate to Chennai Central\n" +
                "open camera / take a selfie\nsend message to Amma saying hello\ndaily briefing\n" +
                "set a timer for 5 minutes\nwhat time is it\nhello / how are you / tell me a joke\n\n" +
                "தமிழ்: வாட்ஸ்அப் திற, அம்மாவுக்கு கால் பண்ணு,\nவாட்ஸ்அப்பில் அம்மாவுக்கு கால் பண்ணு, ஜிபே திற,\nநேரம் என்ன, ஒலி கூட்டு, டார்ச் ஆன்\n" +
                "Say \"Tamil mode\" or \"English mode\" to switch."
            textSize = 15f
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startNova(action: String?) {
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val i = Intent(this, NovaService::class.java)
        if (action != null) i.action = action
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startNova(pendingAction)
    }
}
