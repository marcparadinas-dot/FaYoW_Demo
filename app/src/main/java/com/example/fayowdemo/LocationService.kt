package com.example.fayowdemo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class LocationService : Service(), TextToSpeech.OnInitListener {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var textToSpeech: TextToSpeech
    private var currentLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Gestion des POIs
    private val firestore = FirebaseFirestore.getInstance()
    private val triggeredPois = mutableSetOf<String>()  // POIs déjà déclenchés
    private val PROXIMITY_THRESHOLD = 20.0  // Distance en mètres
    private var isTtsReady = false

    companion object {
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service créé")

        // Initialiser le TTS
        textToSpeech = TextToSpeech(this, this)

        // Acquérir un WakeLock
        acquireWakeLock()

        // Initialiser le client de localisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Créer une notification pour le service en avant-plan
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré")
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service détruit")

        // Libérer le WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("LocationService", "WakeLock libéré")
            }
        }

        stopLocationUpdates()
        textToSpeech.shutdown()
    }

    // ✅ Implémentation obligatoire de TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "La langue française n'est pas supportée.")
                isTtsReady = false
            } else {
                Log.d("TTS", "TTS prêt en français.")
                isTtsReady = true
            }
        } else {
            Log.e("TTS", "Erreur d'initialisation du TTS.")
            isTtsReady = false
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FayowDemo::LocationWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L /* 10 minutes */)
            Log.d("LocationService", "WakeLock acquis")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Diffusion des POIs",
                NotificationManager.IMPORTANCE_LOW  // Notification discrète
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FayowDemo")
            .setContentText("Diffusion des POIs en cours...")
            .setSmallIcon(R.drawable.ic_baseline_directions_walk_24)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)  // L'utilisateur ne peut pas la fermer
            .build()
    }

    private fun startLocationUpdates() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    Log.d("LocationService", "Nouvelle position : ${location.latitude}, ${location.longitude}")
                    verifierPointsInteret(location)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("LocationService", "Mises à jour de localisation démarrées")
        } else {
            Log.e("LocationService", "Permission de localisation manquante")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationService", "Mises à jour de localisation arrêtées")
    }

    private fun verifierPointsInteret(location: Location) {
        Log.d("LocationService", "Vérification des POIs à ${location.latitude}, ${location.longitude}")

        // Récupérer les POIs approuvés depuis Firestore
        firestore.collection("pois")
            .whereEqualTo("approved", true)
            .get()
            .addOnSuccessListener { documents ->
                for (doc in documents) {
                    val poiId = doc.id
                    val lat = doc.getDouble("lat") ?: continue
                    val lng = doc.getDouble("lng") ?: continue
                    val message = doc.getString("message") ?: continue

                    // Calculer la distance
                    val poiLocation = Location("").apply {
                        latitude = lat
                        longitude = lng
                    }
                    val distance = location.distanceTo(poiLocation)

                    Log.d("LocationService", "POI $poiId à ${distance}m")

                    // Si proche ET pas encore déclenché
                    if (distance <= PROXIMITY_THRESHOLD && !triggeredPois.contains(poiId)) {
                        Log.d("LocationService", "🎯 POI déclenché : $message")
                        speak(message)
                        triggeredPois.add(poiId)
                    }

                    // Si l'utilisateur s'éloigne, réinitialiser le POI
                    if (distance > PROXIMITY_THRESHOLD * 2) {
                        if (triggeredPois.remove(poiId)) {
                            Log.d("LocationService", "🔄 POI réinitialisé : $poiId")
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("LocationService", "❌ Erreur récupération POIs : ${e.message}")
            }
    }

    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.w("TTS", "TTS pas encore prêt, message ignoré : $text")
            return
        }

        if (::textToSpeech.isInitialized) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poi_message")
            Log.d("TTS", "📢 Lecture du message : $text")
        } else {
            Log.e("TTS", "TTS non initialisé")
        }
    }
}

