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
import com.example.fayowdemo.location.CommuneManager
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiData
import com.example.fayowdemo.model.PoiStatus
import com.example.fayowdemo.repository.PoiRepository
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

class LocationService : Service(), TextToSpeech.OnInitListener {

    // -------------------------------------------------------------------------
    // Dépendances
    // -------------------------------------------------------------------------

    private val poiRepository = PoiRepository()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener
    private lateinit var communeManager: CommuneManager

    // -------------------------------------------------------------------------
    // État des POIs
    //
    // poisLusIdsPermanents : lus dans Firestore (persistant entre sessions)
    // poisSession          : PROPOSED déclenchés dans la session (non Firestore)
    // poisReaffiches       : temporairement réactivés par l'utilisateur
    // poisLusEffectifs     : calculé = (permanents + session) - reaffiches
    // pointsDejaDeclenches : évite le double déclenchement dans la session
    // poisLusPendantVeille : accumulés pendant onStop de MainActivity → sync au réveil
    // -------------------------------------------------------------------------

    private val poisLusIdsPermanents = mutableSetOf<String>()
    private val poisSession          = mutableSetOf<String>()
    private val poisReaffiches       = mutableSetOf<String>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private val poiDocuments         = mutableMapOf<String, PoiData>()
    private val poisLusPendantVeille = mutableSetOf<String>()

    private val poisLusEffectifs: Set<String>
        get() = (poisLusIdsPermanents + poisSession) - poisReaffiches

    private var arePoiDocumentsLoaded = false
    private var isPoisLusReady        = false
    private var isLoadingPoisLus      = false
    private var isTtsReady            = false
    private var isSpeakingPoi         = false
    private var mainActivityActive    = false

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
    // Text-to-Speech POIs
    // -------------------------------------------------------------------------

    private lateinit var textToSpeech: TextToSpeech

    // -------------------------------------------------------------------------
    // BroadcastReceiver — reçoit les commandes de MainActivity
    // -------------------------------------------------------------------------

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                ACTION_REAFFICHER_POIS -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = intent.getSerializableExtra("ids") as? HashSet<String> ?: return
                    poisReaffiches.addAll(ids)
                    pointsDejaDeclenches.removeAll(ids)
                    Log.d("LocationService", "${ids.size} POIs réaffichés en session")
                }

                ACTION_REINITIALISER_REAFFICHAGE -> {
                    poisReaffiches.clear()
                    Log.d("LocationService", "Réaffichage réinitialisé")
                }

                ACTION_MAIN_STARTED -> {
                    mainActivityActive = true
                    envoyerSyncEtat()
                }

                ACTION_MAIN_STOPPED -> {
                    mainActivityActive = false
                }
            }
        }
    }

    // =========================================================================
    // Constantes broadcast
    // =========================================================================

    companion object {
        const val CHANNEL_ID      = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1

        // Émis vers MainActivity
        const val ACTION_POI_DECLENCHE = "com.sncf.fayow.POI_DECLENCHE"
        const val ACTION_POI_LU        = "com.sncf.fayow.POI_LU"
        const val ACTION_SYNC_ETAT     = "com.sncf.fayow.SYNC_ETAT"

        // Reçus depuis MainActivity
        const val ACTION_REAFFICHER_POIS           = "com.sncf.fayow.REAFFICHER_POIS"
        const val ACTION_REINITIALISER_REAFFICHAGE = "com.sncf.fayow.REINITIALISER_REAFFICHAGE"
        const val ACTION_MAIN_STARTED              = "com.sncf.fayow.MAIN_STARTED"
        const val ACTION_MAIN_STOPPED              = "com.sncf.fayow.MAIN_STOPPED"
    }

    // =========================================================================
    // Cycle de vie
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service créé")

        textToSpeech = TextToSpeech(this, this)

        communeManager = CommuneManager(this)
        communeManager.initialiserTts()

        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val filter = IntentFilter().apply {
            addAction(ACTION_REAFFICHER_POIS)
            addAction(ACTION_REINITIALISER_REAFFICHAGE)
            addAction(ACTION_MAIN_STARTED)
            addAction(ACTION_MAIN_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver, filter)

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

        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                isPoisLusReady = false
                poisSession.clear()
                poisReaffiches.clear()
                poisLusPendantVeille.clear()
                pointsDejaDeclenches.clear()
                communeManager.reinitialiser()
                if (!isLoadingPoisLus) chargerPoisLus(user.uid)
            } else {
                poisLusIdsPermanents.clear()
                poisSession.clear()
                poisReaffiches.clear()
                poisLusPendantVeille.clear()
                pointsDejaDeclenches.clear()
                isPoisLusReady = false
            }
        }
        auth.addAuthStateListener(authStateListener)
        auth.currentUser?.let { chargerPoisLus(it.uid) }

        attendreEtDemarrer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré/redémarré")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service détruit")
        auth.removeAuthStateListener(authStateListener)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver)
        communeManager.shutdown()
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
    // Synchronisation d'état avec MainActivity
    // =========================================================================

    private fun envoyerSyncEtat() {
        val intent = Intent(ACTION_SYNC_ETAT).apply {
            putExtra("poisLusPendantVeille", HashSet(poisLusPendantVeille))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        poisLusPendantVeille.clear()
        Log.d("LocationService", "Sync état envoyé à MainActivity")
    }

    // =========================================================================
    // Text-to-Speech POIs
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
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) {
                        isSpeakingPoi = false
                        if (mainActivityActive) {
                            val intent = Intent(ACTION_POI_DECLENCHE).apply {
                                putExtra("utteranceDone", true)
                                putExtra("utteranceId", utteranceId)
                            }
                            LocalBroadcastManager.getInstance(this@LocationService)
                                .sendBroadcast(intent)
                        }
                    }
                    @Deprecated("Déprécié")
                    override fun onError(utteranceId: String) {
                        isSpeakingPoi = false
                    }
                })
                isTtsReady = true
                Log.d("TTS", "TTS POIs prêt en français")
            }
        } else {
            Log.e("TTS", "Erreur initialisation TTS POIs")
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
        Log.d("TTS", "Lecture POI : $text")
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
                if (mainActivityActive) {
                    val intent = Intent(ACTION_POI_LU).apply { putExtra("poiId", poiId) }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                } else {
                    poisLusPendantVeille.add(poiId)
                }
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
                    currentLocation = location
                    verifierPointsInteret(location)
                    // Commune : toujours vérifiée, écran allumé ou éteint
                    communeManager.verifierCommune(
                        latitude      = location.latitude,
                        longitude     = location.longitude,
                        pointsInteret = poiDocumentsAsPointsInteret(),
                        poisLusIds    = poisLusEffectifs
                    )
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

    /**
     * Convertit poiDocuments (Map<String, PoiData>) en List<PointInteret>
     * pour l'appel à CommuneManager.verifierCommune().
     */
    private fun poiDocumentsAsPointsInteret(): List<PointInteret> {
        return poiDocuments.map { (id, data) ->
            PointInteret(
                id       = id,
                position = LatLng(data.latitude, data.longitude),
                message  = data.message,
                status   = data.status
            )
        }
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

            if (distance <= PROXIMITY_THRESHOLD
                && !pointsDejaDeclenches.contains(poiId)
                && !poisLusEffectifs.contains(poiId)
            ) {
                Log.d("LocationService", "POI $poiId déclenché (distance=${distance}m)")
                pointsDejaDeclenches.add(poiId)

                val utteranceId = "poi_message_$poiId"

                if (mainActivityActive) {
                    val broadcastIntent = Intent(ACTION_POI_DECLENCHE).apply {
                        putExtra("poiId",         poiId)
                        putExtra("message",       poiData.message)
                        putExtra("utteranceId",   utteranceId)
                        putExtra("utteranceDone", false)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
                }

                speak(poiData.message, utteranceId)

                when (poiData.status) {
                    PoiStatus.VALIDATED -> marquerPoiCommeLu(poiId)
                    PoiStatus.PROPOSED  -> {
                        poisSession.add(poiId)
                        Log.d("LocationService", "POI $poiId PROPOSED — lu en session uniquement")
                    }
                    PoiStatus.INITIATED -> { /* ne devrait pas arriver */ }
                }

                break
            }

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