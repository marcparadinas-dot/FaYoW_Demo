package com.example.fayowdemo

import android.Manifest
import PoiManager
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
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
// ? NOUVEAU : Classe pour stocker les données d'un POI

class LocationService : Service(), TextToSpeech.OnInitListener {

    // Au début de la classe LocationService, avec les autres variables
    // ✅ NOUVEAU : Déclaration de la propriété en haut de la classe (avec les autres variables)
    private lateinit var checkPoisReceiver: BroadcastReceiver

    //private val poiQueue = mutableListOf<PointInteret>()  // File d'attente des POIs
    private var isCurrentlySpeaking = false              // Bloque les lectures multiples
    private var isLocationServiceInitialized =
        false  // ✅ Flag pour éviter les doubles initialisations
    private lateinit var poiManager: PoiManager
    private val poisLusIds = mutableSetOf<String>()

    // ? NOUVEAU : Stocke les POIs approuvés en mémoire
    private val poiDocuments = mutableMapOf<String, PointInteret>()
    private var arePoiDocumentsLoaded = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var textToSpeech: TextToSpeech
    private var currentLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Gestion des POIs
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val triggeredPois = mutableSetOf<String>()  // POIs déjà déclenchés
    private val PROXIMITY_THRESHOLD = 20.0  // Distance en mètres
    private var isTtsReady = false
    private var isPoisLusReady = false  // ? NOUVEAU : indique si poisLusIds est chargé
    private var isLoadingPoisLus = false  // ? NOUVEAU : empêche les appels multiples

    companion object {
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /*    private fun processPoiQueue() {
        if (isCurrentlySpeaking || !isTtsReady || poiQueue.isEmpty()) return

        val poi = poiQueue.removeAt(0)  // Récupère le premier POI de la file
        speak(poi.message)  // ✅ Utilise la version simplifiée de speak()
    }
*/
    @Override
    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service créé")
        // ✅ NOUVEAU : Déclaration du BroadcastReceiver comme propriété de classe
        checkPoisReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.sncf.fayow.FORCE_CHECK_POIS") {
                    val latitude = intent.getDoubleExtra("latitude", 0.0)
                    val longitude = intent.getDoubleExtra("longitude", 0.0)
                    val location = Location("").apply {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                    Log.d(
                        "LocationService",
                        "Vérification forcée des POIs à ${location.latitude}, ${location.longitude}"
                    )
                    verifierPointsInteret(location)
                }
            }
        }

        // ✅ NOUVEAU : Enregistrement du BroadcastReceiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            checkPoisReceiver,
            IntentFilter("com.sncf.fayow.FORCE_CHECK_POIS")
        )
        // 1. Initialisation de PoiManager et Firebase
        poiManager = PoiManager(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance())

        // 2. Initialisation du TTS (ton code existant)
        textToSpeech = TextToSpeech(this, this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d("TTS", "▶️ Lecture démarrée: $utteranceId")
                isCurrentlySpeaking = true
                // ✅ Envoie un broadcast pour informer MainActivity que la lecture a commencé
                val intent = Intent("com.sncf.fayowdemo.TTS_STARTED").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }

            override fun onDone(utteranceId: String) {
                Log.d("TTS", "⏹️ Lecture terminée: $utteranceId")
                isCurrentlySpeaking = false
                // ✅ Envoie un broadcast pour informer MainActivity que la lecture est terminée
                val intent = Intent("com.sncf.fayowdemo.TTS_DONE").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }

            override fun onError(utteranceId: String) {
                Log.e("TTS", "⚠️ Erreur TTS: $utteranceId")
                isCurrentlySpeaking = false
                // ✅ Envoie un broadcast pour informer MainActivity de l'erreur
                val intent = Intent("com.sncf.fayowdemo.TTS_ERROR").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }
        })

        // 3. Acquisition du WakeLock
        acquireWakeLock()

        // 4. Création du canal de notification
        createNotificationChannel()

        // 5. Démarrage en foreground
        startForeground(NOTIFICATION_ID, createNotification())

        // 6. Chargement des POIs approuvés
        chargerPoisApprouves()

        // 7. Écouteur d'authentification
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                Log.d("LocationService", "Utilisateur connecté. Chargement des POIs lus...")
                isPoisLusReady = false
                triggeredPois.clear()
                if (!isLoadingPoisLus) {
                    chargerPoisLus(user.uid)
                }
            } else {
                Log.d("LocationService", "Utilisateur déconnecté")
                poisLusIds.clear()
                triggeredPois.clear()
                isPoisLusReady = false
            }
        }

        // 8. Si l'utilisateur est déjà connecté, charge les POIs lus
        auth.currentUser?.let { user ->
            chargerPoisLus(user.uid)
        }


        // 9. Attend que tout soit prêt avant de démarrer les mises à jour
        attendreEtDemarrer()
    }


    private fun chargerPoisApprouves() {
        Log.d("LocationService", "Chargement des POIs...")

        val currentUid = auth.currentUser?.uid ?: return
        val isAdmin = currentUid == "TON_ID_ADMIN"  // À remplacer par ta logique d'admin

        // 1. Charge les POIs VALIDATED (pour tout le monde)
        poiManager.chargerPoisValidated { validatedPois ->
            poiDocuments.clear()

            // Ajoute tous les VALIDATED (même logique pour lambda et admin)
            for (poi in validatedPois) {
                poiDocuments[poi.id] = poi
            }
            Log.d("LocationService", "${validatedPois.size} POIs VALIDATED chargés")

            // 2. Charge les POIs PROPOSED (tous pour admin, seulement ceux de l'utilisateur pour lambda)
            if (isAdmin) {
                poiManager.chargerPoisProposed { proposedPois ->
                    for (poi in proposedPois) {
                        poiDocuments[poi.id] = poi  // Admin voit tous les PROPOSED
                    }
                    Log.d("LocationService", "${proposedPois.size} POIs PROPOSED chargés (admin)")
                    chargerPoisUtilisateur(currentUid)  // Charge aussi ses INITIATED
                }
            } else {
                // Lambda : charge ses PROPOSED + INITIATED
                poiManager.chargerPoisUtilisateur(currentUid) { userPois ->
                    for (poi in userPois) {
                        poiDocuments[poi.id] = poi  // Lambda voit ses PROPOSED/INITIATED
                    }
                    Log.d("LocationService", "${userPois.size} POIs utilisateur chargés (lambda)")
                }
            }
        }
    }

    private fun chargerPoisUtilisateur(uid: String) {
        poiManager.chargerPoisUtilisateur(uid) { userPois ->
            for (poi in userPois) {
                // ✅ Ajoute les INITIATED (et PROPOSED pour lambda) de l'utilisateur
                poiDocuments[poi.id] = poi
            }
            Log.d("LocationService", "${userPois.size} POIs INITIATED/PROPOSED chargés (créateur: $uid)")
        }
    }

    private fun attendreEtDemarrer() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isPoisLusReady && arePoiDocumentsLoaded && !isLocationServiceInitialized) {
                Log.d(
                    "LocationService",
                    "Toutes les données sont prêtes, démarrage des mises à jour"
                )
                startLocationUpdates()  // ✅ Appel unique
            } else if (!isLocationServiceInitialized) {
                Log.d(
                    "LocationService",
                    "En attente (poisLus=$isPoisLusReady, pois=$arePoiDocumentsLoaded)"
                )
                attendreEtDemarrer()  // Réessaye si pas encore initialisé
            }
        }, 500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré (onStartCommand)")

        // ✅ Ne redémarre PAS les mises à jour si déjà initialisé
        if (!isLocationServiceInitialized) {
            attendreEtDemarrer()  // Relance seulement si nécessaire
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isLocationServiceInitialized) {
            Log.d("LocationService", "Mises à jour de localisation déjà démarrées, ignoré.")
            return
        }

        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        currentLocation = location
                        Log.d("LocationService", "Nouvelle position : ${location.latitude}, ${location.longitude}")
                        verifierPointsInteret(location)
                        sendLastLocationToActivity()
                    }
                }
            }

            // ✅ Vérifie la permission avant de démarrer
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                isLocationServiceInitialized = true
                Log.d("LocationService", "Mises à jour de localisation démarrées")
            } else {
                Log.e("LocationService", "Permission ACCESS_FINE_LOCATION non accordée.")
                // ✅ Envoie un broadcast pour informer MainActivity de l'erreur de permission
                val intent = Intent("com.sncf.fayowdemo.LOCATION_ERROR").apply {
                    putExtra("error", "Permission non accordée")
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Erreur lors du démarrage des mises à jour de localisation", e)
            // ✅ Envoie un broadcast pour informer MainActivity de l'erreur
            val intent = Intent("com.sncf.fayowdemo.LOCATION_ERROR").apply {
                putExtra("error", e.message ?: "Erreur inconnue")
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d("LocationService", "Service détruit")

        // ✅ Désenregistrement du BroadcastReceiver (une seule fois)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(checkPoisReceiver)
            Log.d("LocationService", "BroadcastReceiver désenregistré")
        } catch (e: IllegalArgumentException) {
            Log.e("LocationService", "Erreur lors du désenregistrement du receiver", e)
        }

        // ✅ Libérer le WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("LocationService", "WakeLock libéré")
        }

        // ✅ Arrêter les mises à jour de localisation
        try {
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            Log.d("LocationService", "Mises à jour de localisation arrêtées")
        } catch (e: Exception) {
            Log.e("LocationService", "Erreur lors de l'arrêt des mises à jour de localisation", e)
        }

        // ✅ Libérer le TTS
        try {
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
                Log.d("LocationService", "TTS arrêté et libéré")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Erreur lors de la libération du TTS", e)
        }
    }

    // ? Implémentation obligatoire de TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "La langue française n'est pas supportée.")
                isTtsReady = false
            } else {
                Log.d("TTS", "TTS prêt en français.")

                // Configuration d'une voix masculine
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
                } else {
                    Log.w("TTS", "Aucune voix masculine trouvée, voix par défaut utilisée")
                    textToSpeech.voices?.filter { it.locale.language == "fr" }?.forEach { voice ->
                        Log.d("TTS_VOICES", "Voix FR disponible : ${voice.name}")
                    }
                }

                isTtsReady = true

                // ✅ NOUVEAU : Relance la vérification des POIs si une position est disponible
                if (currentLocation != null && arePoiDocumentsLoaded && isPoisLusReady) {
                    Log.d("TTS", "Relance vérification POIs après initialisation TTS")
                    verifierPointsInteret(currentLocation!!)
                }
            }
        } else {
            Log.e("TTS", "Erreur d'initialisation du TTS (status=$status)")
            isTtsReady = false
        }

        // Configuration du listener pour les événements TTS
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d("TTS", "▶️ Lecture démarrée: $utteranceId")
                val intent = Intent("com.sncf.fayowdemo.TTS_STARTED").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }

            override fun onDone(utteranceId: String) {
                Log.d("TTS", "⏹️ Lecture terminée: $utteranceId")
                val intent = Intent("com.sncf.fayowdemo.TTS_DONE").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }

            override fun onError(utteranceId: String) {
                Log.e("TTS", "⚠️ Erreur TTS: $utteranceId")
                val intent = Intent("com.sncf.fayowdemo.TTS_ERROR").apply {
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this@LocationService).sendBroadcast(intent)
            }
        })
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


    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationService", "Mises à jour de localisation arrêtées")
    }

    private fun chargerPoisLus(uid: String) {
        if (isLoadingPoisLus) return

        isLoadingPoisLus = true
        Log.d("LocationService", "Chargement des POIs lus via PoiManager pour UID: $uid")

        // Utilise PoiManager pour charger les POIs lus
        // (À ajouter dans PoiManager si ce n'est pas déjà fait)
        poiManager.chargerPoisLus(uid) { poisLus ->
            poisLusIds.clear()
            poisLusIds.addAll(poisLus)
            isPoisLusReady = true
            isLoadingPoisLus = false
            Log.d("LocationService", "${poisLusIds.size} POIs lus chargés via PoiManager")
        }
    }

    private fun marquerPoiCommeLu(poiId: String) {
        val uid = auth.currentUser?.uid ?: return
        if (poisLusIds.contains(poiId)) return

        poisLusIds.add(poiId)
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .document(poiId)
            .set(mapOf("read" to true, "readAt" to com.google.firebase.Timestamp.now()))
            .addOnSuccessListener {
                Log.d("LocationService", "? POI $poiId marqué comme lu")
                val intent = Intent("com.sncf.fayow.POI_LU")
                intent.putExtra("poiId", poiId)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            .addOnFailureListener { e ->
                Log.e("LocationService", "? Erreur sauvegarde POI lu: ${e.message}")
                poisLusIds.remove(poiId)
            }
    }

    private fun sendLastLocationToActivity() {
        currentLocation?.let { location ->
            val intent = Intent("com.sncf.fayow.LOCATION_UPDATE").apply {
                putExtra("latitude", location.latitude)
                putExtra("longitude", location.longitude)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(
                "LocationService",
                "Position envoyée à MainActivity: ${location.latitude}, ${location.longitude}"
            )
        }
    }

    private fun verifierPointsInteret(location: Location) {
        if (!isPoisLusReady || !arePoiDocumentsLoaded || !isTtsReady) {
            Log.d("LocationService", "Conditions non remplies (poisLus=$isPoisLusReady, pois=$arePoiDocumentsLoaded, tts=$isTtsReady)")
            return
        }

        for ((poiId, poi) in poiDocuments) {
            val poiLocation = Location("").apply {
                latitude = poi.position.latitude
                longitude = poi.position.longitude
            }
            val distance = location.distanceTo(poiLocation)

            if (distance <= PROXIMITY_THRESHOLD && !triggeredPois.contains(poiId) && !poisLusIds.contains(poiId)) {
                Log.d("LocationService", "POI $poiId détecté à ${distance}m")
                triggeredPois.add(poiId)
                marquerPoiCommeLu(poiId)

                val utteranceId = "poi_message_$poiId"
                val intent = Intent("com.sncf.fayowdemo.POI_DETECTED").apply {
                    putExtra("poiId", poiId)
                    putExtra("message", poi.message)
                    putExtra("utteranceId", utteranceId)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                // ✅ Lance le TTS UNIQUEMENT si prêt
                if (isTtsReady) {
                    textToSpeech.speak(poi.message, TextToSpeech.QUEUE_ADD, null, utteranceId)
                } else {
                    Log.e("LocationService", "TTS non prêt pour POI $poiId")
                }
                break
            }
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!isTtsReady) {
            Log.e("TTS", "TTS non prêt. Message ignoré: $text")
            return
        }
        isCurrentlySpeaking = true  // ✅ Marque comme "en train de parler"
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d("TTS", "Lecture lancée: $text (ID: $utteranceId)")
    }
}
