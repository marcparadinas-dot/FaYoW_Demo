package com.example.fayowdemo

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
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
// ? NOUVEAU : Classe pour stocker les données d'un POI
data class PoiData(
    val latitude: Double,
    val longitude: Double,
    val message: String
)
class LocationService : Service(), TextToSpeech.OnInitListener {

    // Au début de la classe LocationService, avec les autres variables
    private val poisLusIds = mutableSetOf<String>()
    // ? NOUVEAU : Stocke les POIs approuvés en mémoire
    private val poiDocuments = mutableMapOf<String, PoiData>()
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

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service créé")

        // Initialisation de base
        textToSpeech = TextToSpeech(this, this)
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // ? Charge les POIs approuvés (une seule fois)
        chargerPoisApprouves()

        // Écouteur d'authentification
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                Log.d("LocationService", "?? Utilisateur connecté. Chargement des POIs lus...")
                isPoisLusReady = false
                triggeredPois.clear()
                if (!isLoadingPoisLus) {
                    chargerPoisLus(user.uid)
                }
            } else {
                Log.d("LocationService", "?? Utilisateur déconnecté")
                poisLusIds.clear()
                triggeredPois.clear()
                isPoisLusReady = false
            }
        }

        // Si l'utilisateur est déjà connecté, charge les POIs lus
        auth.currentUser?.let { user ->
            chargerPoisLus(user.uid)
        }

        // ? Attend que TOUT soit prêt avant de démarrer
        attendreEtDemarrer()
    }
    private fun chargerPoisApprouves() {
        Log.d("LocationService", "? Chargement des POIs approuvés...")

        firestore.collection("pois")
            .whereEqualTo("approved", true)
            .get()
            .addOnSuccessListener { documents ->
                poiDocuments.clear()
                for (doc in documents) {
                    // ? Récupère le statut du POI
                    val statusString = doc.getString("status") ?: PoiStatus.VALIDATED.name
                    val status = try {
                        PoiStatus.valueOf(statusString)
                    } catch (e: IllegalArgumentException) {
                        PoiStatus.VALIDATED  // Valeur par défaut si le statut est invalide
                    }

                    // ? Ignore les INITIATED (oranges) - ils ne seront jamais en mémoire
                    if (status == PoiStatus.INITIATED) continue

                    val lat = doc.getDouble("lat") ?: continue
                    val lng = doc.getDouble("lng") ?: continue
                    val message = doc.getString("message") ?: continue

                    poiDocuments[doc.id] = PoiData(lat, lng, message)
                }
                arePoiDocumentsLoaded = true
                Log.d("LocationService", "? ${poiDocuments.size} POIs approuvés chargés")
            }
            .addOnFailureListener { e ->
                arePoiDocumentsLoaded = true  // Évite un blocage
                Log.e("LocationService", "? Erreur chargement POIs approuvés", e)
            }
    }
    private fun attendreEtDemarrer() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isPoisLusReady && arePoiDocumentsLoaded) {
                Log.d("LocationService", "? Toutes les données sont prêtes, démarrage des mises à jour")
                startLocationUpdates()
            } else {
                Log.d("LocationService", "? En attente (poisLus=$isPoisLusReady, pois=$arePoiDocumentsLoaded)")
                attendreEtDemarrer()  // Réessaye récursivement
            }
        }, 500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "Service démarré")
        startLocationUpdates()
        return START_STICKY
    }
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        // ? Initialise le client de localisation (manquait dans ton code)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Configuration de la demande de localisation (ton code existant)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        // Callback de localisation (ton code existant)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    Log.d("LocationService", "Nouvelle position : ${location.latitude}, ${location.longitude}")
                    verifierPointsInteret(location)
                }
            }
        }

        // ? Démarre les mises à jour (manquait dans ton code)
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        Log.d("LocationService", "? Mises à jour de localisation démarrées")
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

    // ? Implémentation obligatoire de TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "La langue française n'est pas supportée.")
                isTtsReady = false
            } else {
                Log.d("TTS", "TTS prêt en français.")

                // ? Configurer une voix masculine pour le mode veille
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
                    Log.d("TTS", "? Voix masculine activée : ${voixMasculine.name}")
                } else {
                    Log.w("TTS", "?? Aucune voix masculine trouvée, voix par défaut utilisée")
                    // Optionnel : Lister les voix disponibles pour débogage
                    textToSpeech.voices?.filter { it.locale.language == "fr" }?.forEach { voice ->
                        Log.d("TTS_VOICES", "Voix FR disponible : ${voice.name}")
                    }
                }

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


    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationService", "Mises à jour de localisation arrêtées")
    }
    private fun chargerPoisLus(uid: String) {
        // ? Si déjà en cours de chargement, on ignore
        if (isLoadingPoisLus) {
            Log.d("LocationService", "?? Chargement des POIs lus déjà en cours, appel ignoré")
            return
        }

        isLoadingPoisLus = true  // ? Marque comme "en cours"
        Log.d("LocationService", "? Début chargement POIs lus pour UID: $uid")

        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .get()
            .addOnSuccessListener { result ->
                Log.d("LocationService", "?? Firestore: réponse reçue avec ${result.size()} documents")
                poisLusIds.clear()
                for (doc in result) {
                    poisLusIds.add(doc.id)
                    Log.d("LocationService", "  - POI lu chargé : ${doc.id}")
                }
                isPoisLusReady = true
                isLoadingPoisLus = false  // ? Marque comme "terminé"
                Log.d("LocationService", "? ${poisLusIds.size} POIs lus chargés. Prêt à vérifier.")
            }
            .addOnFailureListener { exception ->
                isPoisLusReady = true
                isLoadingPoisLus = false  // ? Marque comme "terminé" même en cas d'erreur
                Log.e("LocationService", "? ERREUR Firestore lors du chargement des POIs lus", exception)
            }
            .addOnCompleteListener {
                Log.d("LocationService", "?? Requête Firestore terminée (succès ou échec)")
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
    private fun verifierPointsInteret(location: Location) {
        // ? Si les données ne sont pas prêtes, on ne fait RIEN
        if (!isPoisLusReady || !arePoiDocumentsLoaded) {
            Log.d("LocationService", "? Données non prêtes (poisLus=$isPoisLusReady, pois=$arePoiDocumentsLoaded)")
            return
        }

        Log.d("LocationService", "Vérification des POIs à ${location.latitude}, ${location.longitude}")

        val currentUid = auth.currentUser?.uid

        // ? Parcourt les POIs en mémoire (PAS de requête Firestore !)
        for ((poiId, poiData) in poiDocuments) {
            val poiLocation = Location("").apply {
                latitude = poiData.latitude
                longitude = poiData.longitude
            }
            val distance = location.distanceTo(poiLocation)

            Log.d("LocationService", "POI $poiId à ${distance}m")
            Log.d("LocationService", "  DEBUG_ID_COMPARE: poiId='$poiId', poisLusIds.contains=${ poisLusIds.contains(poiId)}, triggered=${triggeredPois.contains(poiId)}")

            // Si proche ET pas encore déclenché ET pas déjà lu
            if (distance <= PROXIMITY_THRESHOLD
                && !triggeredPois.contains(poiId)
                && !poisLusIds.contains(poiId)) {

                Log.d("LocationService", "?? Déclenchement du POI $poiId")
                speak(poiData.message)
                triggeredPois.add(poiId)

                // Marque comme lu si connecté
                if (currentUid != null) {
                    marquerPoiCommeLu(poiId)
                }
            }

            // Si l'utilisateur s'éloigne, réinitialiser le POI
            if (distance > PROXIMITY_THRESHOLD * 2) {
                if (triggeredPois.remove(poiId)) {
                    Log.d("LocationService", "?? POI $poiId réinitialisé (distance: ${distance}m)")
                }
            }
        }
    }
    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.w("TTS", "TTS pas encore prêt, message ignoré : $text")
            return
        }

        if (::textToSpeech.isInitialized) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poi_message")
            Log.d("TTS", "?? Lecture du message : $text")
        } else {
            Log.e("TTS", "TTS non initialisé")
        }
    }
}
