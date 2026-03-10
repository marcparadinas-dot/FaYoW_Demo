package com.example.fayowdemo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
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
        val channelId = "pois_channel"

        // Créer un canal de notification (obligatoire pour Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Diffusion des POIs",
                NotificationManager.IMPORTANCE_LOW  // Notification discrète
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // Créer la notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("POIs actifs")
            .setContentText("Diffusion des POIs en cours...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // Remplace par ton icône
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)  // L'utilisateur ne peut pas la fermer
            .build()

        // Démarrer le service en foreground
        startForeground(1, notification)
        Log.d("SERVICE", "Service démarré en foreground")
    }


    override fun onDestroy() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
