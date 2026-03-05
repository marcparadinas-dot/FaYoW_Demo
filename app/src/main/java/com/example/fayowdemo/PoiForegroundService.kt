package com.example.fayowdemo
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

class PoiForegroundService : Service(), TextToSpeech.OnInitListener {  // ← Ajout de l'interface ici

    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false

    override fun onCreate() {
        super.onCreate()
        textToSpeech = TextToSpeech(this, this)  // "this" car la classe implémente OnInitListener
        Log.d("SERVICE", "Service créé")
    }

    // Méthode obligatoire de TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRENCH)
            isTtsReady = (result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE)
            Log.d("TTS", "TTS initialisé avec succès. Langue française disponible : $isTtsReady")
        } else {
            Log.e("TTS", "Échec de l'initialisation du TTS. Status : $status")
            isTtsReady = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        // ... (ton code pour la notification)
    }

    override fun onDestroy() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
