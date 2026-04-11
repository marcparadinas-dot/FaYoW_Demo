package com.example.fayowdemo

import PoiManager
import android.app.ActivityManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fayowdemo.ui.theme.FayowDemoTheme
import com.example.fayowdemo.PointInteret
import com.example.fayowdemo.PoiStatus
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.CircleOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.app
import java.util.Locale



// Interface pour les actions d'authentification
interface AuthActions {
    fun onSignUp(email: String, password_input: String)
    fun onSignIn(email: String, password_input: String)
}


// POI en attente de modération
data class PendingPoi(
    val id: String,
    val message: String
)


// Fonction utilitaire pour convertir les champs Firestore en statut
fun poiStatusFromFirestore(approved: Boolean?, status: String?): PoiStatus {
    return when (status) {
        "initiated" -> PoiStatus.INITIATED
        "proposed" -> PoiStatus.PROPOSED
        "validated" -> PoiStatus.VALIDATED
        else -> if (approved == true) PoiStatus.VALIDATED else PoiStatus.PROPOSED
    }
}


@RequiresApi(Build.VERSION_CODES.CUPCAKE)
class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {
    //private var currentDialogRef: WeakReference<AlertDialog>? = null  // ✅ Référence faible pour éviter les fuites
    //private var currentPoiIdInDialog: String? = null                  // ✅ Stocke l'ID du POI du pop-up actuelprivate var isDialogShown = false  // ✅ Nouveau flag
// ✅ NOUVEAUX RECEIVERS POUR TTS ET ERREURS (à ajouter avec les autres propriétés)
    private val queueLock = Any()  // Verrou pour synchroniser l'accès à la file
    private var currentUtteranceId: String? = null  // ID du message TTS en cours
    private lateinit var ttsStartReceiver: BroadcastReceiver
    private lateinit var ttsDoneReceiver: BroadcastReceiver
    private lateinit var ttsErrorReceiver: BroadcastReceiver
    private lateinit var locationErrorReceiver: BroadcastReceiver
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var poiMessageQueue = mutableListOf<Pair<String, String>>()
    private var currentDialog: AlertDialog? = null
    private var isProcessingQueue = false
    // Déclaration du BroadcastReceiver avec logs
    private val poiMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sncf.fayowdemo.POI_DETECTED") {
                val message = intent.getStringExtra("message") ?: return
                val poiId = intent.getStringExtra("poiId") ?: return
                val utteranceId = intent.getStringExtra("utteranceId") ?: return

                Log.d("MainActivity", "POI $poiId reçu: $message (utteranceId=$utteranceId)")

                // ✅ Ajoute à la file pour afficher le pop-up (sans TTS)
                synchronized(queueLock) {
                    if (!poisLusIds.contains(poiId) && !poiMessageQueue.any { it.first == poiId }) {
                        poiMessageQueue.add(Pair(poiId, message))
                        if (!isProcessingQueue) {
                            processPoiMessageQueue()  // Affiche le pop-up (TTS géré par LocationService)
                        }
                    }
                }
            }
        }
    }
    private var isLocationServiceStarted = false  // ✅ Flag pour éviter les redémarrages
    private var isTtsReady = false
    private var isModerator: Boolean = false
    private lateinit var mMap: GoogleMap
    // Map pour stocker les cercles des POIs, avec leur ID comme clé
    private val poiCircles = mutableMapOf<String, Circle>()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var locationMarker: Marker? = null
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentAzimuth = 0f
    /*private val LOCATION_PERMISSION_REQUEST_CODE = 1*/
    private lateinit var auth: FirebaseAuth
    private val firestore = Firebase.firestore
    private lateinit var poiManager: PoiManager
    private val pointsInteret = mutableListOf<PointInteret>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private var currentLocation: Location? = null
    private var isAuthenticated by mutableStateOf(false)
    private lateinit var textToSpeech: TextToSpeech
    private var isSpeakingPoi = false  // Indique si un POI est en cours de lecture
    private var currentPoiId: String? = null  // ID du POI actuellement lu
    private var pendingPoi: PointInteret? = null  // POI en attente (un seul à la fois)

    /*private var hasShownPermissionDeniedToast = false*/
    // Ajoute ce receiver pour les mises à jour de localisation
    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sncf.fayow.LOCATION_UPDATE") {
                val latitude = intent.getDoubleExtra("latitude", 0.0)
                val longitude = intent.getDoubleExtra("longitude", 0.0)
                val location = Location("").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                }
                runOnUiThread {
                    updateLocationMarker(location)  // ✅ Met à jour le pointeur
                    rafraichirCarte(location)       // ✅ Rafraîchit la carte (sans verifierPointsInteret)
                }
            }
        }
    }
    private val poiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sncf.fayow.POI_LU") {
                val poiId = intent.getStringExtra("poiId")
                poiId?.let { id ->
                    Log.d("MainActivity", "Réception du POI lu : $id")
                    // Suppression du cercle de la carte
                    poiCircles[id]?.remove() // Supprime le cercle visuellement
                    poiCircles.remove(id)    // Retire l'entr e de notre map
                    // Mise à jour de poisLusIds si nécessaire pour éviter de le re-charger
                    if (!poisLusIds.contains(id)) {
                        poisLusIds.add(id)
                    }
                }
            }
        }
    }
    private fun processPoiMessageQueue() {
        synchronized(queueLock) {
            if (isProcessingQueue || poiMessageQueue.isEmpty()) return

            isProcessingQueue = true
            val (poiId, message) = poiMessageQueue.removeAt(0)
            currentUtteranceId = "poi_message_$poiId"

            // ✅ Affiche le pop-up (sans lancer le TTS, car c'est LocationService qui le fait)
            runOnUiThread {
                currentDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Point d'intérêt")
                    .setMessage(message)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                        synchronized(queueLock) { isProcessingQueue = false }
                    }
                    .setOnDismissListener {
                        currentDialog = null
                    }
                    .show()
            }
        }
    }
    // ✅ NOUVEAU : Reçoit les POIs détectés pour afficher le message à l'écran
    private var poisLusLoaded = false
    private var isRequestingPermissions = false  // ?? NOUVEAU FLAG
    private val poisLusIds = mutableSetOf<String>()
    // ? Launcher pour demander la permission localisation FINE (étape 1)
    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isRequestingPermissions = false  // ?? R INITIALISER LE FLAG

        if (isGranted) {
            Log.d("MainActivity", "? Permission FINE accordée via Launcher")
            requestBackgroundLocationPermission()
        } else {
            Log.e("MainActivity", "? Permission FINE refusée via Launcher")

            // Vérifier L' TAT R EL de la permission (pas juste shouldShowRequestPermissionRationale)
            val actualPermissionState = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            if (actualPermissionState != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // VRAIMENT refus  d finitivement
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(
                    this,
                    "La localisation est essentielle pour l'application.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ✅ Fonction pour vérifier si l'activité est au premier plan
    private fun isActivityForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission requise")
            .setMessage("Vous avez refusé définitivement la permission de localisation. Pour utiliser l'application, vous devez l'activer manuellement dans les paramètres.")
            .setPositiveButton("Ouvrir les param tres") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Quitter") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    // ? Launcher pour demander la permission localisation arrière-plan (étape 2)
    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "? Permission BACKGROUND accordée via Launcher")
        } else {
            Log.w("MainActivity", "?? Permission BACKGROUND refusée via Launcher (fonctionnalités limitées)")
            Toast.makeText(
                this,
                "Les alertes en arriére-plan ne fonctionneront pas quand l'écran est éteint.",
                Toast.LENGTH_LONG
            ).show()
        }
        // QU'ELLE SOIT ACCORDEE OU REFUSEE, ON DEMARRE LES SERVICES APRES CETTE ETAPE
        startAppFeatures(); // ?? NOUVEL APPEL
    }
    private fun startAppFeatures() {
        if (!isAuthenticated) {
            Log.w("MainActivity", "startAppFeatures appelée mais utilisateur non authentifié.")
            return
        }

        Log.d("MainActivity", "Démarrage des fonctionnalités de l'application...")

        // Initialise la carte si nécessaire
        if (!::mMap.isInitialized) {
            initializeMapView()
        }

        // Démarre UNIQUEMENT le service (pas de startLocationUpdates() ici)
        if (hasFineLocationPermission()) {
            startLocationService()  // ✅ Conserve uniquement cet appel
        } else {
            Log.w("MainActivity", "Permission FINE_LOCATION manquante pour démarrer le service.")
        }
    }

    private fun hasAllLocationPermissions(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val backgroundLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocationGranted && backgroundLocationGranted
    }



    /*
        private fun ajouterPoisDeTest() {
            val firestore =
                FirebaseFirestore.getInstance() // Pour ajout de points en masse, utiliser ce format

            val poisATester = listOf(
                // Rue de Lappe
                mapOf(
                    "lat" to 48.8539240,
                    "lng" to 2.3722890,
                    "message" to "Ici, vous êtes à l extrémité ouest de la rue de Lappe : remarquez la perspective vers la place de la Bastille et l' 'organisation typique des rues du faubourg Saint-Antoine.",
                    "approved" to true,
                    "creatorUid" to "i7IiNZfZdLXtRzjauduvHGUtLMt1",
                    "createdAt" to com.google.firebase.Timestamp.now()
                ),
                mapOf(
                    "lat" to 48.8538500,
                    "lng" to 2.3725000,
                    "message" to "Observez l'architecture des immeubles du début du XXe siècle, souvent avec des façades en pierre de taille et des ornements discrets, témoins de l'évolution urbaine du quartier.",
                    "approved" to true,
                    "creatorUid" to "i7IiNZfZdLXtRzjauduvHGUtLMt1",
                    "createdAt" to com.google.firebase.Timestamp.now()
                )
            )

            for (poiData in poisATester) {
                // ? Génère un ID basé sur le timestamp (comme dans ton app)
                val id = System.currentTimeMillis().toString()  // Ex: "1773079406607"


                // Ajoute l'ID dans les données du POI
                val poiWithId = poiData.toMutableMap()
                poiWithId["id"] = id


                firestore.collection("pois")
                    .document(id)  // Utilise le timestamp comme ID du document
                    .set(poiWithId)
                    .addOnSuccessListener {
                        Log.d("FirebaseSeed", "POI ajout  avec ID: $id")
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirebaseSeed", "Erreur lors de l'ajout du POI", e)
                    }


                // ?? Petite pause pour éviter d'avoir le même timestamp pour 2 POIs
                Thread.sleep(10)  // Pause de 10ms entre chaque création
            }
        }
    */
    // ? Fonction pour Vérifier si ACCESS_FINE_LOCATION est accordée
    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ? Fonction pour Vérifier si ACCESS_BACKGROUND_LOCATION est accordée
    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pour Android < 10, cette permission n'existe pas
            true
        }
    }

    // ? Fonction pour demander ACCESS_FINE_LOCATION
    private fun requestFineLocationPermission() {
        // ?? Emp cher les appels multiples simultan s
        if (isRequestingPermissions) {
            Log.d("MainActivity", "?? Demande de permission déjà en cours, ignorée.")
            return
        }

        isRequestingPermissions = true
        Log.d("MainActivity", "?? Demande de permission ACCESS_FINE_LOCATION")

        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.d("MainActivity", "?? Affichage d'une explication avant de demander la permission")
            AlertDialog.Builder(this)
                .setTitle("Permission de localisation")
                .setMessage("Cette application a besoin d'accéder à votre position à tout moment pour vous guider vers les  points d'intérêt. Veuillez valider 'toujours autoriser'")
                .setPositiveButton("Autoriser") { _, _ ->
                    fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Refuser") { _, _ ->
                    isRequestingPermissions = false  // ?? Réinitialiser le flag
                    Toast.makeText(this, "L'application ne peut pas fonctionner sans localisation", Toast.LENGTH_LONG).show()
                }
                .show()
        } else {
            fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ? Fonction pour demander ACCESS_BACKGROUND_LOCATION
    private fun requestBackgroundLocationPermission() {
        Log.d("MainActivity", "?? Demande de permission ACCESS_BACKGROUND_LOCATION")

        // Vérifier si la permission est déjà accordée
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "?? Permission BACKGROUND déjà accordée")
            startLocationService()
            return
        }

        // Vérifier si on peut montrer un rationale
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            Log.d("MainActivity", "?? Affichage d'une explication pour BACKGROUND_LOCATION")
            AlertDialog.Builder(this)
                .setTitle("Cocher 'Toujours autoriser' ...")
                .setMessage("... dans l'écran suivant (position), puis revenir en arrière. Cette permission permet à l'application de vous alerter même quand l'écran est éteint.")
                .setPositiveButton("OK") { _, _ ->
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Annuler") { _, _ ->
                    Toast.makeText(
                        this,
                        "Les alertes ne fonctionneront pas quand l'écran est éteint.",
                        Toast.LENGTH_LONG
                    ).show()
                    startLocationService() // Démarrer quand même (mais limité)
                }
                .show()
        } else {
            // Demander directement
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // ? Nouvelle version de startLocationService()

    private fun startLocationService() {
        if (isLocationServiceStarted) {
            Log.d("MainActivity", "LocationService déjà démarré, ignoré.")
            return  // ✅ Évite les redémarrages inutiles
        }

        Log.d("MainActivity", "Tentative de démarrage du LocationService")

        if (!hasFineLocationPermission()) {
            Log.e("MainActivity", "Impossible de démarrer le service : ACCESS_FINE_LOCATION non accordée.")
            Toast.makeText(this, "Impossible de démarrer le service de localisation sans permission.", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "Service démarré en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "Service démarré")
        }

        isLocationServiceStarted = true  // ✅ Marque comme démarré
    }

    // ? NOUVELLE FONCTION : Démarrage réel du service
    /*private fun startLocationServiceInternal() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "?? Service démarré en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "?? Service démarré")
        }
    }*/

    private fun stopLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
    }

    private fun chargerPoisLus(uid: String, onComplete: () -> Unit) {
        poisLusLoaded = false
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .get()
            .addOnSuccessListener { result ->
                poisLusIds.clear()
                for (doc in result) {
                    poisLusIds.add(doc.id) // l'id du doc = poiId
                }
                Log.d("POI", "? ${poisLusIds.size} POIs déjà lus chargés")
                poisLusLoaded = true
                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur chargement POIs lus: ${e.message}")
                poisLusLoaded = true   // on considère chargé même en cas d'erreur
                onComplete() // on continue quand même
            }
    }

    private fun chargerPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet("pois_declenches", emptySet()) ?: emptySet()
        pointsDejaDeclenches.clear()
        pointsDejaDeclenches.addAll(savedSet)
        Log.d("POI", "Chargés ${pointsDejaDeclenches.size} POIs déjà déclenchés depuis les préférences.")
        Log.d("POI", "Contenu de pointsDejaDeclenches : $pointsDejaDeclenches")  // AJOUTE CE LOG


        // 2. Charge les POIs lus depuis Firestore (sous-collection readPois)
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.w("POI", "Utilisateur non connecté, impossible de charger les POIs lus")
            return
        }

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(currentUid)
            .collection("readPois")  // ? Utilise la sous-collection (comme dans LocationService)
            .get()
            .addOnSuccessListener { documents ->
                val poisLus = documents.map { it.id }  // Récupère les IDs des POIs lus
                pointsDejaDeclenches.addAll(poisLus)  // Fusionne avec les POIs locaux
                Log.d("POI", "Après Firestore, pointsDejaDeclenches : $pointsDejaDeclenches (${pointsDejaDeclenches.size}  l ments)")

                // 3. Sauvegarde le résultat fusionné dans les SharedPreferences pour les prochains lancements
                prefs.edit()
                    .putStringSet("pois_declenches", pointsDejaDeclenches)
                    .apply()
            }
            .addOnFailureListener { exception ->
                Log.w("POI", "Erreur lors du chargement des POIs lus depuis Firestore : ", exception)
            }
    }

    private fun sauvegarderPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pois_declenches", pointsDejaDeclenches).apply()
        Log.d("POI", "Sauvegarde de ${pointsDejaDeclenches.size} POIs déclenchés.")
    }
    /*
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        private fun reinitialiserPoisDeclenches() {
            // Vider la liste en mémoire
            pointsDejaDeclenches.clear()

            // Vider ce qui est stocké en local
            val prefs = getSharedPreferences("FayowPrefs", MODE_PRIVATE)
            prefs.edit().remove("pois_declenches").apply()

            // Rafra chir la carte avec la dernière position connue
            rafraichirCarte(currentLocation)

            // ? FORCER UNE MISE   JOUR GPS IMM DIATE
            if (hasLocationPermission()) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { lastLocation ->
                        if (lastLocation != null) {
                            currentLocation = lastLocation
                            updateLocationMarker(lastLocation)
                            Log.d("LOCATION", "? Position r cup r e apr s r initialisation : ${lastLocation.latitude}, ${lastLocation.longitude}")
                        } else {
                            // Si aucune position en cache, demander une nouvelle mise à jour
                            requestSingleLocationUpdate()
                        }
                    }
                    .addOnFailureListener {
                        requestSingleLocationUpdate()
                    }
            } else {
                requestLocationPermission()
            }

            Toast.makeText(this, "? Tous les POIs sont à nouveau disponibles !", Toast.LENGTH_SHORT).show()
        }

     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun reinitialiserPoisDeclenches() {
        Log.d("REINIT", "?? Début de la réinitialisation")

        // Vider la liste en mémoire des POIs déclenchés (ceux qui ont déjà parlé)
        pointsDejaDeclenches.clear()
        Log.d("REINIT", "? pointsDejaDeclenches vid  (${pointsDejaDeclenches.size}  éléments)")

        // Vider les pr f rences locales (si tu stockes pointsDejaDeclenches l )
        val prefs = getSharedPreferences("FayowPrefs", MODE_PRIVATE)
        prefs.edit().remove("pois_declenches").apply()
        Log.d("REINIT", "? Préférences locales vidées pour 'pois_declenches'")

        // ? IMPORTANT : Vider aussi les POIs lus (ceux qui ont été marqués dans Firestore)
        poisLusIds.clear()
        Log.d("REINIT", "? poisLusIds vidé (${poisLusIds.size} éléments)")

        // ? Supprimer les POIs lus de Firestore pour l'utilisateur actuel
        val currentUid = auth.currentUser?.uid
        if (currentUid != null) {
            firestore.collection("users")
                .document(currentUid)
                .collection("readPois")
                .get()
                .addOnSuccessListener { documents ->
                    val batch = firestore.batch() // Utilise un batch pour supprimer efficacement
                    for (doc in documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit()
                        .addOnSuccessListener {
                            Log.d("REINIT", "? ${documents.size()} POIs lus supprimés de Firestore")
                        }
                        .addOnFailureListener { e ->
                            Log.e("REINIT", "? Erreur commit suppression Firestore : ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("REINIT", "? Erreur récupération POIs lus Firestore : ${e.message}")
                }
        } else {
            Log.w("REINIT", "?? Utilisateur non connecté, impossible de supprimer les POIs lus de Firestore.")
        }

        // Rafra chir la carte pouRéafficher tous les POIs
        Log.d("REINIT", "??? Rafraichissement de la carte avec ${pointsInteret.size} POIs")
        rafraichirCarte(currentLocation)

        Toast.makeText(this, "? Tous les POIs sont à nouveau disponibles !", Toast.LENGTH_SHORT).show()
    }
    /*private fun apresConnexion(uid: String) {
        chargerPoisLus(uid) {
            chargerPointsInteret() // ou ta fonction actuelle de chargement de POIs
        }
    }*/

    private fun marquerPoiCommeLu(uid: String, poiId: String) {
        if (poisLusIds.contains(poiId)) return // déjà fait

        poisLusIds.add(poiId)

        val data = mapOf(
            "read" to true,
            "readAt" to com.google.firebase.Timestamp.now()
        )

        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .document(poiId)
            .set(data)
            .addOnSuccessListener {
                Log.d("POI", "? POI $poiId marqué comme lu pour $uid")
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur lors de la sauvegarde du POI lu: ${e.message}")
            }
    }

    // ? NOUVELLE FONCTION : Demander une seule mise à jour de localisation
    /*@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestSingleLocationUpdate() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(),
                object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { newLocation ->
                            currentLocation = newLocation
                            updateLocationMarker(newLocation)
                            Log.d("LOCATION", "?? Nouvelle position obtenue : ${newLocation.latitude}, ${newLocation.longitude}")
                        }
                        fusedLocationClient.removeLocationUpdates(this) // Arrêter après avoir reçu la position
                    }
                },
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LOCATION", "? Erreur de permission pour la mise à jour GPS : ${e.message}")
        }
    }*/

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "🛠️ onCreate appelé")

        // 1. Initialisation des BroadcastReceivers pour les événements TTS
        ttsStartReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.sncf.fayowdemo.TTS_STARTED") {
                    val utteranceId = intent.getStringExtra("utteranceId") ?: return
                    currentUtteranceId = utteranceId
                    Log.d("MainActivity", "🎤 Lecture TTS démarrée (utteranceId=$utteranceId)")

                    // Extraire l'ID du POI
                    val poiId = utteranceId.removePrefix("poi_message_")
                    val message = poiMessageQueue.firstOrNull { it.first == poiId }?.second

                    if (message != null) {
                        runOnUiThread {
                            currentDialog?.dismiss() // Ferme le pop-up précédent

                            // Estime la durée du message (en ms) : ~150 ms par mot + marge
                            val wordCount = message.split(" ").size
                            val estimatedDuration = (wordCount * 350L + 2000L).coerceAtLeast(10000L)

                            currentDialog = AlertDialog.Builder(this@MainActivity)
                                .setTitle("Point d'intérêt")
                                .setMessage(message)
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .setOnDismissListener {
                                    currentDialog = null
                                }
                                .show()

                            // ✅ Timeout DYNAMIQUE basé sur la durée estimée du message
                            timeoutHandler.removeCallbacksAndMessages(null) // Annule le timeout précédent
                            timeoutHandler.postDelayed({
                                if (currentDialog != null && currentUtteranceId == utteranceId) {
                                    Log.w("MainActivity", "Pop-up fermé par timeout pour $utteranceId (durée estimée: ${estimatedDuration}ms)")
                                    currentDialog?.dismiss()
                                    currentDialog = null
                                }
                            }, estimatedDuration)
                        }
                    }
                }
            }
        }

        ttsDoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.sncf.fayowdemo.TTS_DONE") {
                    val utteranceId = intent.getStringExtra("utteranceId") ?: return
                    Log.d("MainActivity", "🎤 Lecture TTS terminée (utteranceId=$utteranceId)")

                    runOnUiThread {
                        // Annule le timeout si le pop-up est toujours ouvert
                        timeoutHandler.removeCallbacksAndMessages(null)

                        if (utteranceId == currentUtteranceId) {
                            currentDialog?.dismiss()
                            currentDialog = null
                            currentUtteranceId = null
                        }
                    }
                }
            }
        }

        ttsErrorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.sncf.fayowdemo.TTS_ERROR") {
                    val utteranceId = intent.getStringExtra("utteranceId") ?: return
                    Log.e("MainActivity", "⚠️ Erreur TTS (utteranceId=$utteranceId)")

                    runOnUiThread {
                        timeoutHandler.removeCallbacksAndMessages(null)
                        Toast.makeText(this@MainActivity, "Erreur de lecture vocale", Toast.LENGTH_SHORT).show()
                        if (utteranceId == currentUtteranceId) {
                            currentDialog?.dismiss()
                            currentDialog = null
                            currentUtteranceId = null
                        }
                    }
                }
            }
        }

        locationErrorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.sncf.fayowdemo.LOCATION_ERROR") {
                    val error = intent.getStringExtra("error") ?: "Erreur inconnue"
                    Log.e("MainActivity", "⚠️ Erreur de localisation: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Erreur: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        // 2. Enregistrement du BroadcastReceiver pour les POIs
        LocalBroadcastManager.getInstance(this).registerReceiver(
            poiMessageReceiver,
            IntentFilter("com.sncf.fayowdemo.POI_DETECTED")
        )
        Log.d("MainActivity", "📡 BroadcastReceiver ENREGISTRÉ pour POI_DETECTED")

        // 3. Initialisation Firebase
        Firebase.app
        auth = Firebase.auth
        resetFirebaseState()
        poiManager = PoiManager(firestore, auth)
        supportActionBar?.hide()

        // 4. SUPPRESSION DE L'INITIALISATION DU TTS (plus nécessaire)
        // textToSpeech = TextToSpeech(this) { ... }  <-- SUPPRIMÉ
        // textToSpeech.setOnUtteranceProgressListener(...)  <-- SUPPRIMÉ

        // 5. Initialisation client de localisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 6. Initialisation des capteurs
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // 7. Nettoyage préventif des fragments
        if (savedInstanceState != null) {
            val fragment = supportFragmentManager.findFragmentById(R.id.map)
            if (fragment != null) {
                Log.d("ONCREATE", "Fragment de carte trouvé dans savedInstanceState, suppression préventive.")
                supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }

        // 8. Charger les POIs déjà déclenchés
        chargerPoisDeclenches()

        // 9. Vérifie si l'utilisateur est déjà connecté
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            Log.d("AUTH", "Utilisateur déjà connecté: ${currentUser.email}")
            isAuthenticated = true
            val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
            if (existingFragment != null) {
                Log.d("ONCREATE", "Fragment existant détecté, suppression...")
                supportFragmentManager.beginTransaction()
                    .remove(existingFragment)
                    .commitNowAllowingStateLoss()
            }
            setContentView(R.layout.activity_main)
            initializeMapView()
        } else {
            isAuthenticated = false
            auth.signOut()
            setContent {
                FayowDemoTheme {
                    val authActions = object : AuthActions {
                        override fun onSignUp(email: String, password_input: String) {
                            signUpUser(email, password_input)
                        }
                        override fun onSignIn(email: String, password_input: String) {
                            signInUser(email, password_input)
                        }
                    }
                    AuthScreen(authActions = authActions)
                }
            }
        }
    }

    // --- 2. AJOUTE ONSTART (juste après onCreate) ---
    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart appelé")

        LocalBroadcastManager.getInstance(this).apply {
            // Enregistre tous les receivers nécessaires
            registerReceiver(poiMessageReceiver, IntentFilter("com.sncf.fayowdemo.POI_DETECTED"))
            registerReceiver(ttsStartReceiver, IntentFilter("com.sncf.fayowdemo.TTS_STARTED"))
            registerReceiver(ttsDoneReceiver, IntentFilter("com.sncf.fayowdemo.TTS_DONE"))
            registerReceiver(ttsErrorReceiver, IntentFilter("com.sncf.fayowdemo.TTS_ERROR"))
            registerReceiver(poiUpdateReceiver, IntentFilter("com.sncf.fayow.POI_LU"))
            registerReceiver(locationUpdateReceiver, IntentFilter("com.sncf.fayow.LOCATION_UPDATE"))
            registerReceiver(locationErrorReceiver, IntentFilter("com.sncf.fayowdemo.LOCATION_ERROR"))
        }
    }
    // --- 3. AJOUTE ONSTOP (apr s onStart) ---
    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "⏸️ onStop appelé")

        LocalBroadcastManager.getInstance(this).apply {
            // Désenregistre les receivers non critiques
            unregisterReceiver(ttsStartReceiver)
            unregisterReceiver(ttsDoneReceiver)
            unregisterReceiver(ttsErrorReceiver)
            unregisterReceiver(locationErrorReceiver)
            unregisterReceiver(locationUpdateReceiver)

            // ✅ Garde poiMessageReceiver et poiUpdateReceiver enregistrés pour l'arrière-plan
        }
        Log.d("MainActivity", "📡 Receivers non critiques désenregistrés (poiMessageReceiver et poiUpdateReceiver restent actifs)")
    }
    private fun resetFirebaseState() {
        Log.d("AUTH", "?? R initialisation de l'état Firebase")
        auth = Firebase.auth

        firestore.clearPersistence()
            .addOnSuccessListener {
                Log.d("AUTH", "Cache Firestore nettoyé")
            }
            .addOnFailureListener { e ->
                Log.e("AUTH", "Erreur nettoyage cache: ${e.message}")
            }
        isAuthenticated = false
        isModerator = false
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "🧹 onDestroy appelé")

        // ✅ Ferme tous les pop-ups ouverts
        currentDialog?.dismiss()
        currentDialog = null

        // ✅ Annule tous les timeouts en attente
        timeoutHandler.removeCallbacksAndMessages(null)

        // ✅ Désenregistre TOUS les BroadcastReceivers (une seule fois par receiver)
        try {
            LocalBroadcastManager.getInstance(this).apply {
                unregisterReceiver(poiMessageReceiver)
                unregisterReceiver(ttsStartReceiver)
                unregisterReceiver(ttsDoneReceiver)
                unregisterReceiver(ttsErrorReceiver)
                unregisterReceiver(locationErrorReceiver)
                unregisterReceiver(poiUpdateReceiver)
                unregisterReceiver(locationUpdateReceiver)
            }
        } catch (e: IllegalArgumentException) {
            Log.e("MainActivity", "Erreur lors du désenregistrement des receivers", e)
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur inattendue lors du nettoyage", e)
        }

        // ✅ Arrête le LocationService
        try {
            val serviceIntent = Intent(this, LocationService::class.java)
            stopService(serviceIntent)
            Log.d("MainActivity", "LocationService arrêté")
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur lors de l'arrêt du LocationService", e)
        }

        // ✅ Réinitialise les flags de traitement
        isProcessingQueue = false
        currentUtteranceId = null
        poiMessageQueue.clear()
    }
    private fun checkIfModerator() {
        val moderatorEmails = listOf(
            "marc.paradinas@gmail.com",
            "marc.paradinas@wanadoo.fr"
        )
        Log.d("MODERATION", "===== D BUT CHECK MODERATOR =====")
        val email = auth.currentUser?.email
        Log.d("MODERATION", "Email actuel: $email")
        Log.d("MODERATION", "Liste modérateurs: $moderatorEmails")

        isModerator = email != null && moderatorEmails.contains(email)
        Log.d("MODERATION", "isModerator: $isModerator")
        val btnModeration = findViewById<Button>(R.id.btnModeration)
        if (btnModeration != null) {
            btnModeration.visibility = if (isModerator) View.VISIBLE else View.GONE
            Log.d("MODERATION", "Bouton modération visible: ${btnModeration.visibility == View.VISIBLE}")
        } else {
            Log.e("MODERATION", "Bouton modération non trouvé !")
        }
        Log.d("MODERATION", "===== FIN CHECK MODERATOR =====")
    }

    private fun rafraichirCarte(location: Location?) {
        Log.d("CARTE", "?? rafraichirCarte appelé")
        if (!::mMap.isInitialized) return
        mMap.clear()
        locationMarker = null
        //log carte
        Log.d("CARTE", "=== Début rafraîchissement carte ===")
        Log.d("CARTE", "| Nombre total de POIs: ${pointsInteret.size}")
        Log.d("CARTE", "| POIs lus (VALIDATED): ${poisLusIds.size}")
        Log.d("CARTE", "| POIs déjà déclenchés: ${pointsDejaDeclenches.size}")
        Log.d("CARTE", "=== Liste des POIs ===")
        pointsInteret.forEach { poi ->
            Log.d("CARTE",
                "| POI ${poi.id} | Status: ${poi.status} | Lu: ${poisLusIds.contains(poi.id)} | " +
                        "Déclenché: ${pointsDejaDeclenches.contains(poi.id)} | " +
                        "Position: (${poi.position.latitude}, ${poi.position.longitude})"
            )
        }
        for (poi in pointsInteret) {

            // ? Ne pas afficher les POIs VALIDATED déjà lus
            //    mais laisser visibles les PROPOSED et INITIATED même s'ils sont "lus"
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) {
                continue
            }


            val shouldDisplay = when (poi.status) {
                PoiStatus.VALIDATED -> !pointsDejaDeclenches.contains(poi.id)
                PoiStatus.PROPOSED  -> true   // ? cercle vert toujours visible
                PoiStatus.INITIATED -> true   // ? cercle orange toujours visible
            }
// logs carte
            Log.d("CARTE",
                "[FILTRE] POI ${poi.id} | Status: ${poi.status} | " +
                        "Affichage: ${shouldDisplay} | " +
                        "Raison: ${if (!shouldDisplay) when {
                            poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id) -> "VALIDATED + lu"
                            poi.status == PoiStatus.VALIDATED && pointsDejaDeclenches.contains(poi.id) -> "VALIDATED + déjà déclenché"
                            else -> "Inconnue"
                        } else "OK"}"
            )
// logs carte
            Log.d("CARTE",
                ("| POI ${poi.id} | Status: ${poi.status} | Lu: ${poisLusIds.contains(poi.id)} | " +
                        "Déclenché: ${pointsDejaDeclenches.contains(poi.id)} | " +
                        "Position: (${poi.position.latitude}, ${poi.position.longitude})")
            )
            if (shouldDisplay) {
                val (strokeColor, fillColor) = when (poi.status) {
                    PoiStatus.VALIDATED -> Pair(
                        Color.argb(30, 190, 30, 250),  // Violet/bleu (couleur actuelle)
                        Color.argb(60, 190, 30, 250)
                    )
                    PoiStatus.PROPOSED -> Pair(
                        Color.argb(100, 76, 175, 80),  // Vert
                        Color.argb(80, 76, 175, 80)
                    )
                    PoiStatus.INITIATED -> Pair(
                        Color.argb(150, 255, 152, 0),  // Orange
                        Color.argb(100, 255, 152, 0)
                    )
                }

                val circle = mMap.addCircle(
                    CircleOptions()
                        .center(poi.position)
                        .radius(20.0)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                        .strokeWidth(3f)
                        .clickable(poi.status == PoiStatus.INITIATED) // ? Clickable si brouillon
                )

                // ? Stocker l'association cercle ? POI pour gérer les clics
                circle.tag = poi.id
                // --- STOCKAGE DU CERCLE DANS LA MAP ---
                poiCircles[poi.id] = circle

                // ? Afficher le d but du message pour les POIs INITIATED
                if (poi.status == PoiStatus.INITIATED) {
                    val snippet = poi.message.take(30) + if (poi.message.length > 30) "..." else ""
                    mMap.addMarker(
                        MarkerOptions()
                            .position(poi.position)
                            .title(snippet)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                            .alpha(0.7f)
                    )?.showInfoWindow()
                }
            }
        }
        location?.let { loc ->
            Log.d("CARTE", "?? Recréation du marqueur utilisateur")
            updateLocationMarker(loc)
        }
    }
    /*
    private fun reinitialiserPoisLus(uid: String, onComplete: () -> Unit) {
        val colRef = firestore.collection("users")
            .document(uid)
            .collection("readPois")

        colRef.get()
            .addOnSuccessListener { result ->
                val batch = firestore.batch()
                for (doc in result) {
                    batch.delete(doc.reference)
                }
                batch.commit()
                    .addOnSuccessListener {
                        poisLusIds.clear()
                        Log.d("POI", "?? Tous les POIs lus ont été réinitialisés pour $uid")
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.e("POI", "Erreur lors de la réinitialisation des POIs lus: ${e.message}")
                        onComplete()
                    }
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur lors de la récupération des POIs lus: ${e.message}")
                onComplete()
            }
    }
*/
    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun playPendingPoi(location: Location) {
        val poi = pendingPoi ?: return
        if (poisLusIds.contains(poi.id)) {
            Log.d("MainActivity", "POI ${poi.id} déjà lu, ignoré")
            pendingPoi = null
            return
        }

        pointsDejaDeclenches.add(poi.id)
        sauvegarderPoisDeclenches()
        rafraichirCarte(location)
        isSpeakingPoi = true
        currentPoiId = poi.id

        // ✅ Affiche le pop-up
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(poi.message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener {
                currentDialog = null
                isSpeakingPoi = false
            }
            .create()
        currentDialog?.show()

        // ✅ Utilise QUEUE_ADD pour éviter les interruptions
        //val utteranceId = "poi_message_${poi.id}"
        //textToSpeech.speak(poi.message, TextToSpeech.QUEUE_ADD, null, utteranceId)

        // ✅ Marque comme lu après la lecture
        val currentUid = auth.currentUser?.uid
        if (currentUid != null && poi.status == PoiStatus.VALIDATED) {
            marquerPoiCommeLu(currentUid, poi.id)
        }
    }

    /*
    private fun playPendingPoi(location: Location) {
        val poi = pendingPoi ?: return
        // On marque le POI comme d clench  UNIQUEMENT quand on commence   le lire
        pointsDejaDeclenches.add(poi.id)
        sauvegarderPoisDeclenches()
        rafraichirCarte(location)
        isSpeakingPoi = true
        currentPoiId = poi.id
        // Pop-up pour ce POI
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(poi.message)
            .setPositiveButton("OK", null)
            .setOnDismissListener {
                currentDialog = null
            }
            .create()
        currentDialog?.show()
        val utteranceId = "poi_message_${poi.id}"
        textToSpeech.speak(
            poi.message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        Log.d("TTS", "Lecture du message : ${poi.message}")
    }
*/
    private fun verifierPointsInteret(location: Location) {
// logs carte
        Log.d("MainActivity", "=== Vérification POIs (${pointsInteret.size}) ===")
        Log.d("MainActivity", "| Position utilisateur: (${location.latitude}, ${location.longitude})")
        Log.d("MainActivity", "| POIs lus (Firestore): ${poisLusIds.size}")
        Log.d("MainActivity", "| Lecture en cours: $isSpeakingPoi | POI en attente: ${pendingPoi?.id}")
        if (!poisLusLoaded) {
            // "poisLusIds pas encore chargé, on ne déclenche rien"
            return
        }
        val currentLatLng = LatLng(location.latitude, location.longitude)
        var poiFound = false
        for (poi in pointsInteret) {
// ? Jamais de déclenchement auto pour les brouillons

            if (poi.status == PoiStatus.INITIATED) continue
            Log.d("MainActivity", "[EXCLU] POI ${poi.id} ignoré (INITIATED, brouillon)")
// ? On ne bloque par poisLusIds que les VALIDATED
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) continue
            Log.d("MainActivity", "[EXCLU] POI ${poi.id} ignoré (VALIDATED + lu)")
            // ? NOUVEAU : Vérifie aussi si le POI est dans poisLusIds (Firestore)
            if (poisLusIds.contains(poi.id)) {
                Log.d("MainActivity", "POI ${poi.id} d j  lu (depuis Firestore), ignor ")
                continue
                Log.d("MainActivity", "[EXCLU] POI ${poi.id} ignoré (lu, même non VALIDATED)")
            }

            val results = FloatArray(1)
            Location.distanceBetween(
                currentLatLng.latitude, currentLatLng.longitude,
                poi.position.latitude, poi.position.longitude,
                results
            )
            val distanceEnMetres = results[0]
            // logs carte
            Log.d("MainActivity",
                "[CHECK] POI ${poi.id} | Status: ${poi.status} | " +
                        "Lu (Firestore): ${poisLusIds.contains(poi.id)} | " +
                        "Distance: ${"%.2f".format(distanceEnMetres)}m"
            )
            if (distanceEnMetres <= 20f) {
                Log.d("MainActivity", "[DECLENCHE] POI ${poi.id} déclenché (distance: ${"%.2f".format(distanceEnMetres)}m)")
                poiFound = true
                if (isSpeakingPoi) {
                    Log.d("MainActivity", "Annonce vocale déjà en cours, POI ${poi.id} ignor ")
                    continue
                }
                if (pendingPoi != null) {
                    Log.d("MainActivity", "Un POI est déjà en attente, POI ${poi.id} ignor ")
                    continue
                }
                pendingPoi = poi
                Log.d("MainActivity", "POI ${poi.id} détecté et en attente")
                playPendingPoi(location)
                break
            }
        }
        // ? Si aucun POI n'est trouvé et qu'un POI est en attente,
        // cela signifie que l'utilisateur est sorti de la zone avant la lecture
        if (!poiFound && pendingPoi != null) {
            Log.d("MainActivity", "Utilisateur sorti de la zone, pendingPoi annulé")
            pendingPoi = null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo?.isConnectedOrConnecting == true
        }
    }

    private fun signInUser(email: String, password: String) {
        resetFirebaseState()
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Pas de connexion Internet", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("AUTH", "Tentative de connexion avec $email")
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d("AUTH", "? Connexion réussie pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Connexion réussie", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true

                    // ? Nettoyer les fragments existants AVANT d'initialiser la vue
                    val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
                    if (existingFragment != null) {
                        Log.d("AUTH", "?? Suppression du fragment de carte existant")
                        supportFragmentManager.beginTransaction()
                            .remove(existingFragment)
                            .commitNowAllowingStateLoss()
                    }

                    // ? Charger la vue principale
                    setContentView(R.layout.activity_main)

                    // ? Initialiser la carte avec un léger délai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ? Démarrer le service de localisation si les permissions sont OK
                        if (hasFineLocationPermission()) {
                            if (hasBackgroundLocationPermission()) {
                                startLocationService()
                            } else {
                                requestBackgroundLocationPermission()
                            }
                        } else {
                            requestFineLocationPermission()
                        }
                    }, 500)
                } else {
                    val errorMessage = when (task.exception) {
                        is com.google.firebase.auth.FirebaseAuthInvalidUserException -> "Cet utilisateur n'existe pas."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Mot de passe incorrect."
                        is com.google.firebase.FirebaseNetworkException -> "Problème de connexion réseau."
                        else -> "Erreur de connexion : ${task.exception?.message ?: "Vérifiez vos identifiants."}"
                    }
                    Log.e("AUTH", "? Erreur connexion: ${task.exception?.message}")
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun signUpUser(email: String, password: String) {
        resetFirebaseState()
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Pas de connexion Internet", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("AUTH", "Tentative d'inscription avec $email")
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d("AUTH", "? Compte créé pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Compte créé avec succès", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true

                    // ? Nettoyer les fragments existants AVANT d'initialiser la vue
                    val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
                    if (existingFragment != null) {
                        Log.d("AUTH", "?? Suppression du fragment de carte existant")
                        supportFragmentManager.beginTransaction()
                            .remove(existingFragment)
                            .commitNowAllowingStateLoss()
                    }

                    // ? Charger la vue principale
                    setContentView(R.layout.activity_main)

                    // ? Initialiser la carte avec un léger délai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ? Démarrer le service de localisation si les permissions sont OK
                        if (hasFineLocationPermission()) {
                            if (hasBackgroundLocationPermission()) {
                                startLocationService()
                            } else {
                                requestBackgroundLocationPermission()
                            }
                        } else {
                            requestFineLocationPermission()
                        }
                    }, 500)
                } else {
                    val errorMessage = when (task.exception) {
                        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "Cet email est déjà utilisé."
                        is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> "Mot de passe trop faible (6 caract res minimum)."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Format d'email invalide."
                        is com.google.firebase.FirebaseNetworkException -> "Problème de connexion réseau."
                        else -> "Erreur d'inscription : ${task.exception?.message ?: "Vérifiez vos informations."}"
                    }
                    Log.e("AUTH", "? Erreur inscription: ${task.exception?.message}")
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun initializeMapView() {
        try {
            // ? Timeout de sécurité pour détecter les initialisations bloquées
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e("INIT", "?? Timeout : Initialisation trop longue")
                runOnUiThread {
                    Toast.makeText(this, "Problème d'initialisation de la carte", Toast.LENGTH_LONG).show()
                    auth.signOut()
                    setContent {
                        FayowDemoTheme {
                            val authActions = object : AuthActions {
                                override fun onSignUp(email: String, password_input: String) {
                                    signUpUser(email, password_input)
                                }
                                override fun onSignIn(email: String, password_input: String) {
                                    signInUser(email, password_input)
                                }
                            }
                            AuthScreen(authActions = authActions)
                        }
                    }
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 5000) // 5 secondes de timeout

            // ? Charger d'abord les POIs déjà lus par l'utilisateur
            val currentUid = auth.currentUser?.uid
            if (currentUid != null) {
                chargerPoisLus(currentUid) {
                    // ? Une fois les POIs lus chargés, on charge tous les POIs
                    chargerPointsInteret()
                }
            } else {
                // ? Pas connecté  ? charge les POIs normalement
                chargerPointsInteret()
            }

            // ? Configuration des boutons de l'interface

            // Bouton Ajouter POI
            val btnAddPoi = findViewById<Button>(R.id.btn_add_poi)
            btnAddPoi?.setOnClickListener { onAddPoiClicked() }

            // Bouton Mod ration
            val btnModeration = findViewById<Button>(R.id.btnModeration)
            btnModeration?.setOnClickListener {
                if (isModerator) showModerationDialog()
                else Toast.makeText(this, "Accès réservé aux modérateurs", Toast.LENGTH_SHORT).show()
            }

            // Bouton Réafficher tous les POIs
            val btnReafficher = findViewById<Button>(R.id.btnReafficher)
            btnReafficher?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("R initialiser les POIs")
                    .setMessage("Voulez-vous vraiment réafficher tous les points d'intérêt ?")
                    .setPositiveButton("Oui") { _, _ ->
                        if (hasFineLocationPermission()) {
                            reinitialiserPoisDeclenches()
                        } else {
                            Toast.makeText(this, "Permission de localisation nécessaire", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }

            // Bouton D connexion
            val btnLogout = findViewById<Button>(R.id.btnLogout)
            btnLogout?.setOnClickListener {
                Log.d("AUTH", "?? Déconnexion demandée")

                // Arrêter le service de localisation
                stopLocationService()

                // Arrêter les mises à jour de localisation
                stopLocationUpdates()

                // Nettoyage des données
                pointsInteret.clear()
                pointsDejaDeclenches.clear()
                sauvegarderPoisDeclenches()
                currentLocation = null
                locationMarker?.remove()
                locationMarker = null

                // Nettoyage du fragment de carte
                val mapFragment = supportFragmentManager.findFragmentById(R.id.map)
                if (mapFragment != null) {
                    Log.d("LOGOUT", "?? Suppression du fragment de carte.")
                    supportFragmentManager.beginTransaction()
                        .remove(mapFragment)
                        .commitAllowingStateLoss()
                    supportFragmentManager.executePendingTransactions()
                }

                // D connexion Firebase
                auth.signOut()
                isAuthenticated = false
                isModerator = false

                // Retour à l'écran d'authentification
                try {
                    setContent {
                        FayowDemoTheme {
                            val authActions = object : AuthActions {
                                override fun onSignUp(email: String, password_input: String) {
                                    signUpUser(email, password_input)
                                }
                                override fun onSignIn(email: String, password_input: String) {
                                    signInUser(email, password_input)
                                }
                            }
                            AuthScreen(authActions = authActions)
                        }
                    }
                    Toast.makeText(this, "D connect ", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("LOGOUT", "Erreur lors de la déconnexion: ${e.message}", e)
                    Toast.makeText(this, "Erreur critique. Redémarrage...", Toast.LENGTH_LONG).show()
                    finish()
                    startActivity(intent)
                }
            }

            // ? Vérifier si l'utilisateur est modérateur
            checkIfModerator()

            // ? Initialiser le client de localisation (si pas déjà fait dans onCreate)
            if (!::fusedLocationClient.isInitialized) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                Log.d("INIT", "?? FusedLocationClient initialisé")
            }

            // ? Initialiser locationRequest (si pas déjà fait)
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build()
                Log.d("INIT", "?? LocationRequest initialisé")
            }

            // ? Initialiser locationCallback (si pas déjà fait)
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation
                        if (location == null) {
                            Log.w("LOCATION", "? Aucune localisation reçue.")
                            return
                        }
                        Log.d("LOCATION", "? Localisation: Lat=${location.latitude}, Lng=${location.longitude}")
                        currentLocation = location
                        updateLocationMarker(location)
                        //verifierPointsInteret(location)
                    }
                }
                Log.d("INIT", "?? LocationCallback initialisé")
            }

            // ? Initialiser le fragment de carte Google Maps
            val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
            if (mapFragment != null) {
                mapFragment.getMapAsync(this)
                Log.d("INIT", "??? Fragment de carte initialisé")
            } else {
                Log.e("INIT", "? Fragment de carte non trouvé dans le layout")
                Toast.makeText(this, "Erreur : fragment de carte introuvable", Toast.LENGTH_LONG).show()
            }

            // ? Annuler le timeout (tout s'est bien pass )
            timeoutHandler.removeCallbacks(timeoutRunnable)
            Log.d("INIT", "? Initialisation de la vue terminée avec succès")

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur d'initialisation: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("INIT", "? Erreur d'initialisation: ${e.message}", e)
            e.printStackTrace()
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true

        // Listener de clic sur les cercles
        mMap.setOnCircleClickListener { circle ->
            val poiId = circle.tag as? String ?: return@setOnCircleClickListener
            val poi = pointsInteret.find { it.id == poiId } ?: return@setOnCircleClickListener
            if (poi.status == PoiStatus.INITIATED) {
                showEditMyPoiDialog(poi)
            }
        }

        if (!hasFineLocationPermission()) {
            Log.w("MainActivity", "Permission de localisation non accordée dans onMapReady")
            if (!isRequestingPermissions) {
                requestFineLocationPermission()
            }
            return
        }

        // Configuration de la carte
        mMap.isMyLocationEnabled = false
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        try {
            googleMap.setMyLocationEnabled(false)
        } catch (e: SecurityException) {
            Log.e("MAP", "Erreur permission localisation", e)
        }

        // Charger les POIs depuis Firestore
        chargerPointsInteret()

        // Récupérer la dernière position connue et centrer la carte
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { loc ->
                currentLocation = loc
                updateLocationMarker(loc)
                rafraichirCarte(loc)

                // ✅ 1. Demande une vérification forcée des POIs via LocationService
                val forceCheckIntent = Intent("com.sncf.fayow.FORCE_CHECK_POIS").apply {
                    putExtra("latitude", loc.latitude)
                    putExtra("longitude", loc.longitude)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(forceCheckIntent)
                Log.d("MainActivity", "Demande de vérification forcée des POIs envoyée à LocationService")

                // ✅ 2. Vérification locale en backup (après un délai pour laisser le temps à LocationService)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isSpeakingPoi) {  // ✅ Vérifie qu'aucun POI n'est déjà en cours de lecture
                        verifierPointsInteret(loc)
                        Log.d("MainActivity", "Vérification locale des POIs en backup")
                    }
                }, 2000)  // Délai de 2 secondes

                // Centrer la caméra sur la position actuelle
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude),
                    15f
                ))
                Log.d("MainActivity", "Carte centrée sur : ${loc.latitude}, ${loc.longitude}")
            } ?: run {
                Log.w("MainActivity", "Aucune position connue dans lastLocation")
            }
        }

        // Démarrer les mises à jour GPS en temps réel
        startLocationUpdates()
        Log.d("MainActivity", "Carte Google Maps prête et configurée")
    }
    private fun onAddPoiClicked() {
        val location = currentLocation
        if (location == null) {
            Toast.makeText(this, "Localisation indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        val editText = EditText(this)
        editText.hint = "Message du point d'intérêt"
        AlertDialog.Builder(this)
            .setTitle("Nouveau point d'intérêt")
            .setMessage("Entrez le message à afficher à cet endroit:")
            .setView(editText)
            .setPositiveButton("Enregistrer") { _, _ ->
                val message = editText.text.toString().ifBlank { "Point d'intérêt" }
                ajouterPointInteret(location, message)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
    /*private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }*/

    private fun ajouterPointInteret(location: Location, message: String) {
        val currentUser = auth.currentUser ?: run {
            Toast.makeText(this, "Utilisateur non connecté.", Toast.LENGTH_SHORT).show()
            return
        }

        poiManager.ajouterPointInteret(
            lat = location.latitude,
            lng = location.longitude,
            message = message,
            uid = currentUser.uid,
            onSuccess = {
                Toast.makeText(
                    this,
                    "Brouillon de POI enregistré. Vous pouvez le modifier avant de le proposer.",
                    Toast.LENGTH_LONG
                ).show()
                chargerPointsInteret()
            },
            onFailure = { e ->
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }


    private fun chargerPointsInteret() {
        val currentUser = auth.currentUser
        val currentUid = currentUser?.uid

        pointsInteret.clear()

        // 1. Charger les POIs VALIDATED (visibles par tous)
        poiManager.chargerPoisValidated { validatedPois ->
            pointsInteret.addAll(validatedPois)
            Log.d("POI", "${pointsInteret.size} POIs VALIDATED chargés")

            // 2. Charger les POIs de l'utilisateur (INITIATED + PROPOSED)
            if (currentUid != null) {
                if (isModerator) {
                    // Modérateur voit TOUS les PROPOSED + ses POIs perso
                    chargerPoisProposedPourModerateur(currentUid)
                } else {
                    // Utilisateur normal voit seulement ses POIs perso
                    chargerMesPoisEnAttente(currentUid)
                }
            } else {
                rafraichirCarte(currentLocation) // Utilisateur non connecté
            }
        }
    }
    private fun chargerMesPoisEnAttente(currentUid: String) {
        poiManager.chargerPoisUtilisateur(currentUid) { userPois ->
            pointsInteret.addAll(userPois)
            Log.d("POI", "${userPois.size} POIs utilisateur chargés (INITIATED/PROPOSED)")
            rafraichirCarte(currentLocation)
        }
    }
    /**
     * Charge TOUS les POIs PROPOSED (pour mod ration) + les POIs perso de l'utilisateur
     */
    private fun chargerPoisProposedPourModerateur(currentUid: String) {
        poiManager.chargerPoisProposed { proposedPois ->
            pointsInteret.addAll(proposedPois)
            Log.d("POI", "${proposedPois.size} POIs PROPOSED chargés pour modération")
            rafraichirCarte(currentLocation)
        }
    }

    /*
        private fun validerPoi(poiId: String) {
            firestore.collection("pois").document(poiId)
                .update("approved", true)
                .addOnSuccessListener {
                    Toast.makeText(this, "POI valid !", Toast.LENGTH_SHORT).show()
                    chargerPointsInteret()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
        */
    private fun showEditMyPoiDialog(poi: PointInteret) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage = dialogView.findViewById<EditText>(R.id.editPoiMessage)
        editMessage.setText(poi.message)

        // Masquer la checkbox du modérateur (on n'en a pas besoin ici)
        val checkApproved = dialogView.findViewById<CheckBox>(R.id.checkApproved)
        checkApproved.visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("Modifier votre brouillon")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newMessage = editMessage.text.toString().trim()
                firestore.collection("pois").document(poi.id)
                    .update("message", newMessage)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Brouillon mis à jour", Toast.LENGTH_SHORT).show()
                        chargerPointsInteret()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNeutralButton("Proposer à la modération") { _, _ ->
                val newMessage = editMessage.text.toString().trim()
                firestore.collection("pois").document(poi.id)
                    .update(mapOf(
                        "message" to newMessage,
                        "status" to "proposed"
                    ))
                    .addOnSuccessListener {
                        Toast.makeText(this, "POI proposé à la modération !", Toast.LENGTH_SHORT).show()
                        chargerPointsInteret()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showModerationDialog() {
        firestore.collection("pois")
            .whereEqualTo("status", "proposed")  // ? Charger uniquement les POIs "proposed"
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    Toast.makeText(this, "Aucun point en attente de modération", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val pendingPois = result.documents.map { doc ->
                    PendingPoi(
                        id = doc.id,
                        message = doc.getString("message") ?: ""
                    )
                }
                val titles = pendingPois.mapIndexed { index, poi ->
                    "${index + 1}. ${poi.message.take(40)}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Points à modérer")
                    .setItems(titles) { _, which ->
                        showPoiEditDialog(pendingPois[which])
                    }
                    .setNegativeButton("Fermer", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showPoiEditDialog(poi: PendingPoi) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage = dialogView.findViewById<EditText>(R.id.editPoiMessage)
        val checkApproved = dialogView.findViewById<CheckBox>(R.id.checkApproved)
        editMessage.setText(poi.message)
        checkApproved.isChecked = false
        checkApproved.text = "Valider ce POI"

        AlertDialog.Builder(this)
            .setTitle("Modérer POI")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newMessage = editMessage.text.toString().trim()
                val validated = checkApproved.isChecked

                val updates = mutableMapOf<String, Any>(
                    "message" to newMessage
                )

                if (validated) {
                    updates["status"] = "validated"
                    updates["approved"] = true
                }

                firestore.collection("pois").document(poi.id)
                    .update(updates)
                    .addOnSuccessListener {
                        val msg = if (validated) "POI validé !" else "POI mis à jour"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (validated) chargerPointsInteret()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        // ? Vérifier la permission avec la nouvelle fonction
        if (!hasFineLocationPermission()) {
            Log.w("LOCATION", "?? Permission de localisation manquante.")
            return
        }

        try {
            // ? Initialiser locationRequest si ce n'est pas déjà fait
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    3000L // Mise à jour toutes les 3 secondes
                )
                    .setMinUpdateIntervalMillis(1000L) // Minimum 1 seconde entre chaque mise à jour
                    .build()

                Log.d("LOCATION", "?? LocationRequest initialisé")
            }

            // ? Initialiser locationCallback si ce n'est pas déjà fait
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            currentLocation = location
                            updateLocationMarker(location)
                            Log.d("LOCATION", "?? Position mise à jour : ${location.latitude}, ${location.longitude}")
                       }
                    }
                }
                Log.d("LOCATION", "?? LocationCallback initialisé")
            }

            // ? Arrêter les mises à jour existantes avant d'en démarrer de nouvelles
            fusedLocationClient.removeLocationUpdates(locationCallback)

            // ? Démarrer les nouvelles mises à jour
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            Log.d("LOCATION", "?? Mises à jour de localisation démarrées.")
        } catch (e: SecurityException) {
            Log.e("LOCATION", "? Erreur de permission : ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun updateLocationMarker(location: Location) {
        // ? V rification que la carte est initialis e
        if (!::mMap.isInitialized) {
            Log.e("LOCATION", "? mMap non initialisé, impossible de créer/mettre à jour le marqueur.")
            return
        }
        val currentLatLng = LatLng(location.latitude, location.longitude)
        val customMarkerIcon = bitmapDescriptorFromVector(this, R.drawable.outline_arrow_circle_up_24)
        if (locationMarker == null) {
            // Cr ation du marqueur
            Log.d("LOCATION", "? Création du marqueur de localisation à Lat=${location.latitude}, Lng=${location.longitude}")
            locationMarker = mMap.addMarker(
                MarkerOptions()
                    .position(currentLatLng)
                    .title("Ma position")
                    .snippet("Je suis ici!")
                    .icon(customMarkerIcon)
                    .anchor(0.5f, 0.5f)
                    .rotation(currentAzimuth)
                    .flat(true)
            )
            // Déplacer la caméra vers la position initiale
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16f))
            Log.d("LOCATION", "?? Caméra déplacée vers la position initiale.")
        } else {
            // Mise   jour du marqueur existant
            Log.d("LOCATION", "?? Mise à jour du marqueur de localisation à Lat=${location.latitude}, Lng=${location.longitude}")
            locationMarker?.apply {
                position = currentLatLng
                rotation = currentAzimuth
            }
            // Animer la caméra pour suivre le déplacement (optionnel, peut être désactivé si trop gênant)
            mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng))
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        super.onResume()

        val uid = auth.currentUser?.uid
        if (uid != null) {
            chargerPoisLus(uid) {
                // Une fois poisLusIds à jour, on rafraîchit la carte
                rafraichirCarte(null)
            }
        }

        // Enregistrement des capteurs
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        // Si toutes les permissions sont accordées ET authentifiées
        if (hasAllLocationPermissions() && isAuthenticated) {
            startAppFeatures()
        }
        // ?? Ne redemander que si AUCUNE demande en cours ET pas authentifié
        else if (!hasFineLocationPermission() && isAuthenticated && !isRequestingPermissions) {
            requestFineLocationPermission()
        }
    }

    // Vérifie si le LocationService est déjà en cours d'exécution
    private fun isLocationServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (LocationService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onPause() {
        super.onPause()

        // Arrêter les capteurs
        sensorManager.unregisterListener(this)

        // Arrêter les mises à jour de localisation de l'activité pour économiser la batterie
        stopLocationUpdates()
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
        }
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentAzimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360 + 90).toFloat() % 360
            locationMarker?.rotation = currentAzimuth
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun bitmapDescriptorFromVector(context: AppCompatActivity, @DrawableRes vectorResId: Int): BitmapDescriptor? {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId) ?: return null
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
