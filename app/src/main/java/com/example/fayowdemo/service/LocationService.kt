package com.example.fayowdemo.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener

    // -------------------------------------------------------------------------
    // État des POIs
    //
    // poisLusIdsPermanents : lus dans Firestore (persistant entre sessions)
    // poisSession          : PROPOSED déclenchés dans la session (non Firestore)
    // poisReaffiches       : temporairement réactivés par l'utilisateur
    // poisLusEffectifs     : ensemble calculé utilisé pour filtrer les déclenchements
    // pointsDejaDeclenches : évite le double déclenchement dans la session courante
    // -------------------------------------------------------------------------

    private val poisLusIdsPermanents = mutableSetOf<String>()
    private val poisSession          = mutableSetOf<String>()
    private val poisReaffiches       = mutableSetOf<String>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private val poiDocuments         = mutableMapOf<String, PoiData>()

    private val poisLusEffectifs: Set<String>
        get() = (poisLusIdsPermanents + poisSession) - poisReaffiches

    private var arePoiDocumentsLoaded = false
    private var isPoisLusReady        = false
    private var isLoadingPoisLus      = false
    private var isTtsReady            = false
    private var isSpeakingPoi         = false

    private val PROXIMITY_THRESHOLD = 20.0

    // -------------------------------------------------------------------------
    // Localisation
    // -------------------------------------------------------------------------

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null

    // -------------------------------------------------------------------------
    // Text-to-Speech
    // -------------------------------------------------------------------------

    private lateinit var textToSpeech: TextToSpeech

    // -------------------------------------------------------------------------
    // BroadcastReceiver — reçoit les demandes de réaffichage de MainActivity
    // -------------------------------------------------------------------------

    private val reafficherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REAFFICHER_POIS -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = intent.getSerializableExtra("ids") as? HashSet<String> ?: return
                    poisReaffiches.addAll(ids)
                    // Retire aussi ces ids de pointsDejaDeclenches pour permettre
                    // un nouveau déclenchement
                    pointsDejaDeclenches.removeAll(ids)
                    Log.d("LocationService", "${ids.size} POIs réaffichés en session")
                }
                ACTION_REINITIALISER_REAFFICHAGE -> {
                    poisReaffiches.clear()
                    Log.d("LocationService", "Réaffichage réinitialisé")
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID    = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1

        // Actions broadcast émises vers MainActivity
        const val ACTION_POI_DECLENCHE = "com.sncf.fayow.POI_DECLENCHE"
        const val ACTION_POI_LU        = "com.sncf.fayow.POI_LU"

        // Actions broadcast reçues depuis MainActivity
        const val ACTION_REAFFICHER_POIS         = "com.sncf.fayow.REAFFICHER_POIS"
        const val ACTION_REINITIALISER_REAFFICHAGE = "com.sncf.fayow.REINITIALISER_REAFFICHAGE"
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

        // Enregistrement du receiver pour les demandes de réaffichage
        val filter = IntentFilter().apply {
            addAction(ACTION_REAFFICHER_POIS)
            addAction(ACTION_REINITIALISER_REAFFICHAGE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(reafficherReceiver, filter)

        // Chargement des POIs depuis Firestore
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
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                isPoisLusReady = false
                poisSession.clear()
                pointsDejaDeclenches.clear()
                if (!isLoadingPoisLus) chargerPoisLus(user.uid)
            } else {
                poisLusIdsPermanents.clear()
                poisSession.clear()
                poisReaffiches.clear()
                pointsDejaDeclenches.clear()
                isPoisLusReady = false
            }
        }
        auth.addAuthStateListener(authStateListener)

        // Chargement initial si déjà connecté
        auth.currentUser?.let { chargerPoisLus(it.uid) }

        attendreEtDemarrer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service détruit")
        auth.removeAuthStateListener(authStateListener)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reafficherReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
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
                val voixMasculine = textToSpeech.voices?.find {
                    it.locale.language == "fr" && (
                            it.name.contains("male", ignoreCase = true) ||
                                    it.name.contains("frc",  ignoreCase = true) ||
                                    it.name.contains("wavenet-b", ignoreCase = true) ||
                                    it.name.contains("wavenet-d", ignoreCase = true)
                            )
                }
                if (voixMasculine != null) {
                    textToSpeech.voice = voixMasculine
                    Log.d("TTS", "Voix masculine activée : ${voixMasculine.name}")
                }

                // Quand le TTS termine, on signale à MainActivity que la parole est finie
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) {
                        isSpeakingPoi = false
                        val intent = Intent(ACTION_POI_DECLENCHE).apply {
                            putExtra("utteranceDone", true)
                            putExtra("utteranceId", utteranceId)
                        }
                        LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
                    }
                    @Deprecated("Déprécié")
                    override fun onError(utteranceId: String) {
                        isSpeakingPoi = false
                    }
                })

                isTtsReady = true
                Log.d("TTS", "TTS prêt en français")
            }
        } else {
            Log.e("TTS", "Erreur initialisation TTS")
            isTtsReady = false
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!isTtsReady || !::textToSpeech.isInitialized) {
            Log.w("TTS", "TTS non prêt, message ignoré")
            return
        }
        isSpeakingPoi = true
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
                poisLusIdsPermanents.clear()
                poisLusIdsPermanents.addAll(ids)
                isPoisLusReady   = true
                isLoadingPoisLus = false
                Log.d("LocationService", "${poisLusIdsPermanents.size} POIs lus chargés")
            },
            onError = {
                isPoisLusReady   = true
                isLoadingPoisLus = false
                Log.e("LocationService", "Erreur chargement POIs lus : ${it.message}")
            }
        )
    }

    private fun marquerPoiCommeLu(poiId: String) {
        val uid = auth.currentUser?.uid ?: return
        if (poisLusIdsPermanents.contains(poiId)) return

        poisLusIdsPermanents.add(poiId)
        poiRepository.marquerPoiCommeLu(uid, poiId,
            onSuccess = {
                Log.d("LocationService", "POI $poiId marqué comme lu dans Firestore")
                // Informe MainActivity pour qu'il supprime le cercle
                val intent = Intent(ACTION_POI_LU).apply {
                    putExtra("poiId", poiId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            },
            onError = {
                Log.e("LocationService", "Erreur marquage POI lu : ${it.message}")
                poisLusIdsPermanents.remove(poiId)
            }
        )
    }

    // =========================================================================
    // Localisation et détection de proximité
    // =========================================================================

    private fun attendreEtDemarrer() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isPoisLusReady && arePoiDocumentsLoaded) {
                Log.d("LocationService", "Données prêtes, démarrage localisation")
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
        if (isSpeakingPoi) return

        for ((poiId, poiData) in poiDocuments) {
            val poiLocation = Location("").apply {
                latitude  = poiData.latitude
                longitude = poiData.longitude
            }
            val distance = location.distanceTo(poiLocation)

            // Déclenchement
            if (distance <= PROXIMITY_THRESHOLD
                && !pointsDejaDeclenches.contains(poiId)
                && !poisLusEffectifs.contains(poiId)
            ) {
                Log.d("LocationService", "POI $poiId déclenché (distance=${distance}m)")
                pointsDejaDeclenches.add(poiId)

                val utteranceId = "poi_message_$poiId"

                // Broadcast vers MainActivity AVANT le speak
                // pour que le dialog s'affiche en même temps que le TTS
                val broadcastIntent = Intent(ACTION_POI_DECLENCHE).apply {
                    putExtra("poiId",       poiId)
                    putExtra("message",     poiData.message)
                    putExtra("utteranceId", utteranceId)
                    putExtra("utteranceDone", false)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

                speak(poiData.message, utteranceId)

                // Marquage Firestore uniquement pour les VALIDATED
                when (poiData.status) {
                    PoiStatus.VALIDATED -> marquerPoiCommeLu(poiId)
                    PoiStatus.PROPOSED  -> {
                        // Lu en session uniquement, pas dans Firestore
                        poisSession.add(poiId)
                        Log.d("LocationService", "POI $poiId PROPOSED — lu en session uniquement")
                    }
                    PoiStatus.INITIATED -> { /* ne devrait pas arriver */ }
                }

                break // un seul POI à la fois
            }

            // Réinitialisation quand on s'éloigne (permet un nouveau déclenchement si réaffiché)
            if (distance > PROXIMITY_THRESHOLD * 2) {
                pointsDejaDeclenches.remove(poiId)
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