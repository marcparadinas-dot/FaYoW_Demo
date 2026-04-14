package com.example.fayowdemo.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fayowdemo.MainActivity
import com.example.fayowdemo.R
import com.example.fayowdemo.model.PoiData
import com.example.fayowdemo.model.PoiStatus
import com.example.fayowdemo.repository.PoiRepository
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

class LocationService : Service(), TextToSpeech.OnInitListener {

    // -------------------------------------------------------------------------
    // Dépendances
    // -------------------------------------------------------------------------

    private val poiRepository = PoiRepository()
    private val auth = FirebaseAuth.getInstance()

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    private val poisLusIds = mutableSetOf<String>()
    private val poiDocuments = mutableMapOf<String, PoiData>()
    private val triggeredPois = mutableSetOf<String>()

    private var arePoiDocumentsLoaded = false
    private var isPoisLusReady = false
    private var isLoadingPoisLus = false
    private var isTtsReady = false

    private val PROXIMITY_THRESHOLD = 20.0

    // -------------------------------------------------------------------------
    // Localisation
    // -------------------------------------------------------------------------

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // -------------------------------------------------------------------------
    // Text-to-Speech
    // -------------------------------------------------------------------------

    private lateinit var textToSpeech: TextToSpeech

    companion object {
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    // =========================================================================
    // Cycle de vie
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service créé")

        textToSpeech = TextToSpeech(this, this)
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Chargement des POIs approuvés via le repository
        poiRepository.chargerPoisApprouves(
            onSuccess = { poiMap ->
                poiDocuments.clear()
                poiDocuments.putAll(poiMap)
                arePoiDocumentsLoaded = true
                Log.d("LocationService", "${poiDocuments.size} POIs chargés en mémoire")
            },
            onError = {
                arePoiDocumentsLoaded = true
                Log.e("LocationService", "Erreur chargement POIs : ${it.message}")
            }
        )

        // Écoute des changements d'authentification
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                isPoisLusReady = false
                triggeredPois.clear()
                if (!isLoadingPoisLus) chargerPoisLus(user.uid)
            } else {
                poisLusIds.clear()
                triggeredPois.clear()
                isPoisLusReady = false
            }
        }

        auth.currentUser?.let { chargerPoisLus(it.uid) }

        attendreEtDemarrer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré")
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service détruit")
        wakeLock?.let { if (it.isHeld) it.release() }
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (::textToSpeech.isInitialized) textToSpeech.shutdown()
    }

    // =========================================================================
    // Text-to-Speech
    // =========================================================================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Langue française non supportée")
                isTtsReady = false
            } else {
                // Tentative de sélection d'une voix masculine
                val voixMasculine = textToSpeech.voices?.find {
                    it.locale.language == "fr" && (
                            it.name.contains("male", ignoreCase = true) ||
                                    it.name.contains("frc", ignoreCase = true) ||
                                    it.name.contains("wavenet-b", ignoreCase = true) ||
                                    it.name.contains("wavenet-d", ignoreCase = true)
                            )
                }
                if (voixMasculine != null) {
                    textToSpeech.voice = voixMasculine
                    Log.d("TTS", "Voix masculine activée : ${voixMasculine.name}")
                }
                isTtsReady = true
                Log.d("TTS", "TTS prêt en français")
            }
        } else {
            Log.e("TTS", "Erreur initialisation TTS")
            isTtsReady = false
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady || !::textToSpeech.isInitialized) {
            Log.w("TTS", "TTS non prêt, message ignoré")
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poi_message")
        Log.d("TTS", "Lecture : $text")
    }

    // =========================================================================
    // Chargement des données
    // =========================================================================

    private fun chargerPoisLus(uid: String) {
        if (isLoadingPoisLus) return
        isLoadingPoisLus = true

        poiRepository.chargerPoisLus(uid,
            onSuccess = { ids ->
                poisLusIds.clear()
                poisLusIds.addAll(ids)
                isPoisLusReady = true
                isLoadingPoisLus = false
                Log.d("LocationService", "${poisLusIds.size} POIs lus chargés")
            },
            onError = {
                isPoisLusReady = true
                isLoadingPoisLus = false
                Log.e("LocationService", "Erreur chargement POIs lus : ${it.message}")
            }
        )
    }

    private fun marquerPoiCommeLu(poiId: String) {
        val uid = auth.currentUser?.uid ?: return
        if (poisLusIds.contains(poiId)) return

        poisLusIds.add(poiId)
        poiRepository.marquerPoiCommeLu(uid, poiId,
            onSuccess = {
                Log.d("LocationService", "POI $poiId marqué comme lu")
                val intent = Intent("com.sncf.fayow.POI_LU").apply {
                    putExtra("poiId", poiId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            },
            onError = {
                Log.e("LocationService", "Erreur marquage POI lu : ${it.message}")
                poisLusIds.remove(poiId)
            }
        )
    }

    // =========================================================================
    // Localisation et détection de proximité
    // =========================================================================

    private fun attendreEtDemarrer() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isPoisLusReady && arePoiDocumentsLoaded) {
                Log.d("LocationService", "Données prêtes, démarrage")
                startLocationUpdates()
            } else {
                Log.d("LocationService", "En attente (poisLus=$isPoisLusReady, pois=$arePoiDocumentsLoaded)")
                attendreEtDemarrer()
            }
        }, 500)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    verifierPointsInteret(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        Log.d("LocationService", "Mises à jour de localisation démarrées")
    }

    private fun verifierPointsInteret(location: Location) {
        if (!isPoisLusReady || !arePoiDocumentsLoaded) return

        for ((poiId, poiData) in poiDocuments) {
            val poiLocation = Location("").apply {
                latitude = poiData.latitude
                longitude = poiData.longitude
            }
            val distance = location.distanceTo(poiLocation)

            if (distance <= PROXIMITY_THRESHOLD
                && !triggeredPois.contains(poiId)
                && !poisLusIds.contains(poiId)
            ) {
                Log.d("LocationService", "POI $poiId déclenché")
                Log.d("FAYOWDEBUG", "Déclenchement POI $poiId | poisLusIds=$poisLusIds | triggeredPois=$triggeredPois") // ← ici
                speak(poiData.message)

                triggeredPois.add(poiId)

                // On marque comme lu uniquement les POIs VALIDATED
                if (poiData.status == PoiStatus.VALIDATED) {
                    marquerPoiCommeLu(poiId)
                } else {
                    Log.d("LocationService", "POI $poiId PROPOSED — non marqué comme lu")
                }
            }

            if (distance > PROXIMITY_THRESHOLD * 2 && triggeredPois.remove(poiId)) {
                Log.d("LocationService", "POI $poiId réinitialisé")
            }
        }

    }

    // =========================================================================
    // Notification foreground
    // =========================================================================

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FayowDemo::LocationWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Diffusion des POIs",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FayowDemo")
            .setContentText("Diffusion des POIs en cours...")
            .setSmallIcon(R.drawable.ic_baseline_directions_walk_24)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}