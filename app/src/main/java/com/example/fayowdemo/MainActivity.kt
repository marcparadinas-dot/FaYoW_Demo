package com.example.fayowdemo

import android.app.ActivityManager
import android.Manifest
import android.content.Context
import android.content.Intent
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
import com.example.fayowdemo.ui.theme.FayowDemoTheme
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

// Point d'intérêt avec texte associé
data class PointInteret(
    val id: String,
    val position: LatLng,
    val message: String,
    val status: PoiStatus = PoiStatus.VALIDATED,  // ✅ Ajout du champ status
    val creatorUid: String? = null
)

// POI en attente de modération
data class PendingPoi(
    val id: String,
    val message: String
)
enum class PoiStatus {
    INITIATED,   // Brouillon personnel
    PROPOSED,    // Proposé à la modération
    VALIDATED    // Validé par modérateur
}

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
    private var isRequestingPermissions = false  // 🚨 NOUVEAU FLAG

    // ✅ Launcher pour demander la permission localisation FINE (étape 1)
    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isRequestingPermissions = false  // 🚨 RÉINITIALISER LE FLAG

        if (isGranted) {
            Log.d("MainActivity", "✅ Permission FINE accordée via Launcher")
            requestBackgroundLocationPermission()
        } else {
            Log.e("MainActivity", "❌ Permission FINE refusée via Launcher")

            // Vérifier L'ÉTAT RÉEL de la permission (pas juste shouldShowRequestPermissionRationale)
            val actualPermissionState = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            if (actualPermissionState != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // VRAIMENT refusé définitivement
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

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission requise")
            .setMessage("Vous avez refusé définitivement la permission de localisation. Pour utiliser l'application, vous devez l'activer manuellement dans les paramètres.")
            .setPositiveButton("Ouvrir les paramètres") { _, _ ->
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

    // ✅ Launcher pour demander la permission localisation arrière-plan (étape 2)
    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "✅ Permission BACKGROUND accordée via Launcher")
        } else {
            Log.w("MainActivity", "⚠️ Permission BACKGROUND refusée via Launcher (fonctionnalités limitées)")
            Toast.makeText(
                this,
                "Les alertes en arrière-plan ne fonctionneront pas quand l'écran est éteint.",
                Toast.LENGTH_LONG
            ).show()
        }
        // QU'ELLE SOIT ACCORDÉE OU REFUSÉE, ON DÉMARRE LES SERVICES APRÈS CETTE ÉTAPE
        startAppFeatures(); // 🚨 NOUVEL APPEL
    }
    private fun startAppFeatures() {
        // S'assurer que l'authentification est OK avant de démarrer
        if (!isAuthenticated) {
            Log.w("MainActivity", "StartAppFeatures appelée mais utilisateur non authentifié.")
            // Gérer le cas où l'utilisateur n'est pas encore connecté
            // Peut-être attendre l'événement de connexion pour appeler startAppFeatures()
            return;
        }

        Log.d("MainActivity", "Démarrage des fonctionnalités de l'application...")

        // Initialiser la carte si elle ne l'est pas encore
        if (!::mMap.isInitialized) {
            initializeMapView()
        }

        // Démarrer les mises à jour de localisation si la permission FINE est là
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            Log.w("MainActivity", "Impossible de démarrer les mises à jour de localisation: permission FINE manquante.")
        }

        // Démarrer le service de localisation (il gérera lui-même les restrictions BACKGROUND)
        startLocationService()
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
    private var isTtsReady = false
    private var isModerator: Boolean = false
    private lateinit var mMap: GoogleMap
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
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var auth: FirebaseAuth
    private val firestore = Firebase.firestore
    private val pointsInteret = mutableListOf<PointInteret>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private var currentLocation: Location? = null
    private var isAuthenticated by mutableStateOf(false)
    private lateinit var textToSpeech: TextToSpeech
    private var currentDialog: AlertDialog? = null
    private var isSpeakingPoi = false  // Indique si un POI est en cours de lecture
    private var currentPoiId: String? = null  // ID du POI actuellement lu
    private var pendingPoi: PointInteret? = null  // POI en attente (un seul à la fois)
    private var hasShownPermissionDeniedToast = false

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
            android.util.Log.d("TTS", "Moteur TTS arrêté et libéré.")
        }
        super.onDestroy()
        stopLocationService()
    }
/*
    private fun ajouterPoisDeTest() {
        val firestore =
            FirebaseFirestore.getInstance() // Pour ajout de points en masse, utiliser ce format et réactiver le "ajouter points de tests" dans oncreate

        val poisATester = listOf(
            // Rue de Lappe
            mapOf(
                "lat" to 48.8539240,
                "lng" to 2.3722890,
                "message" to "Ici, vous êtes à l’extrémité ouest de la rue de Lappe : remarquez la perspective vers la place de la Bastille et l’organisation typique des rues du faubourg Saint-Antoine.",
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
            // ✅ Génère un ID basé sur le timestamp (comme dans ton app)
            val id = System.currentTimeMillis().toString()  // Ex: "1773079406607"


            // Ajoute l'ID dans les données du POI
            val poiWithId = poiData.toMutableMap()
            poiWithId["id"] = id


            firestore.collection("pois")
                .document(id)  // Utilise le timestamp comme ID du document
                .set(poiWithId)
                .addOnSuccessListener {
                    Log.d("FirebaseSeed", "POI ajouté avec ID: $id")
                }
                .addOnFailureListener { e ->
                    Log.e("FirebaseSeed", "Erreur lors de l'ajout du POI", e)
                }


            // ⚠️ Petite pause pour éviter d'avoir le même timestamp pour 2 POIs
            Thread.sleep(10)  // Pause de 10ms entre chaque création
        }
    }
*/
    // ✅ Fonction pour vérifier si ACCESS_FINE_LOCATION est accordée
    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ✅ Fonction pour vérifier si ACCESS_BACKGROUND_LOCATION est accordée
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

    // ✅ Fonction pour demander ACCESS_FINE_LOCATION
    private fun requestFineLocationPermission() {
        // 🚨 Empêcher les appels multiples simultanés
        if (isRequestingPermissions) {
            Log.d("MainActivity", "⚠️ Demande de permission déjà en cours, ignorée.")
            return
        }

        isRequestingPermissions = true
        Log.d("MainActivity", "📲 Demande de permission ACCESS_FINE_LOCATION")

        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.d("MainActivity", "ℹ️ Affichage d'une explication avant de demander la permission")
            AlertDialog.Builder(this)
                .setTitle("Permission de localisation")
                .setMessage("Cette application a besoin d'accéder à votre position à tout moment pour vous guider vers les  points d'intérêt. Veuillez valider 'toujours autoriser'")
                .setPositiveButton("Autoriser") { _, _ ->
                    fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Refuser") { _, _ ->
                    isRequestingPermissions = false  // 🚨 Réinitialiser le flag
                    Toast.makeText(this, "L'application ne peut pas fonctionner sans localisation", Toast.LENGTH_LONG).show()
                }
                .show()
        } else {
            fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ✅ Fonction pour demander ACCESS_BACKGROUND_LOCATION
    private fun requestBackgroundLocationPermission() {
        Log.d("MainActivity", "📲 Demande de permission ACCESS_BACKGROUND_LOCATION")

        // Vérifier si la permission est déjà accordée
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "🟢 Permission BACKGROUND déjà accordée")
            startLocationService()
            return
        }

        // Vérifier si on peut montrer un rationale
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            Log.d("MainActivity", "ℹ️ Affichage d'une explication pour BACKGROUND_LOCATION")
            AlertDialog.Builder(this)
                .setTitle("Localisation en arrière-plan")
                .setMessage("Cette permission permet à l'application de vous alerter même quand l'écran est éteint.")
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

    // ✅ Nouvelle version de startLocationService()
    private fun startLocationService() {
        Log.d("MainActivity", "Tentative de démarrage du LocationService")

        // On vérifie qu'on a au moins la permission FINE_LOCATION
        // La permission BACKGROUND_LOCATION est gérée avant d'appeler cette fonction dans onCreate
        if (!hasFineLocationPermission()) {
            Log.e("MainActivity", "❌ Impossible de démarrer le service : ACCESS_FINE_LOCATION non accordée.")
            Toast.makeText(this, "Impossible de démarrer le service de localisation sans permission.", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "🚀 Service démarré en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "🚀 Service démarré")
        }
    }

    // ✅ NOUVELLE FONCTION : Démarrage réel du service
    private fun startLocationServiceInternal() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "🚀 Service démarré en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "🚀 Service démarré")
        }
    }

    private fun stopLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
    }

    private fun chargerPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet("pois_declenches", emptySet()) ?: emptySet()
        pointsDejaDeclenches.clear()
        pointsDejaDeclenches.addAll(savedSet)
        android.util.Log.d("POI", "Chargés ${pointsDejaDeclenches.size} POIs déjà déclenchés depuis les préférences.")
    }

    private fun sauvegarderPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pois_declenches", pointsDejaDeclenches).apply()
        android.util.Log.d("POI", "Sauvegarde de ${pointsDejaDeclenches.size} POIs déclenchés.")
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun reinitialiserPoisDeclenches() {
        // Vider la liste en mémoire
        pointsDejaDeclenches.clear()

        // Vider ce qui est stocké en local
        val prefs = getSharedPreferences("FayowPrefs", MODE_PRIVATE)
        prefs.edit().remove("pois_declenches").apply()

        // Rafraîchir la carte avec la dernière position connue
        rafraichirCarte(currentLocation)

        // ✅ FORCER UNE MISE À JOUR GPS IMMÉDIATE
        if (hasLocationPermission()) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { lastLocation ->
                    if (lastLocation != null) {
                        currentLocation = lastLocation
                        updateLocationMarker(lastLocation)
                        Log.d("LOCATION", "✅ Position récupérée après réinitialisation : ${lastLocation.latitude}, ${lastLocation.longitude}")
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

        Toast.makeText(this, "✅ Tous les POIs sont à nouveau disponibles !", Toast.LENGTH_SHORT).show()
    }

    // ✅ NOUVELLE FONCTION : Demander une seule mise à jour de localisation
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
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
                            android.util.Log.d("LOCATION", "📍 Nouvelle position obtenue : ${newLocation.latitude}, ${newLocation.longitude}")
                        }
                        fusedLocationClient.removeLocationUpdates(this) // Arrêter après avoir reçu la position
                    }
                },
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("LOCATION", "❌ Erreur de permission pour la mise à jour GPS : ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 1. Initialisation du moteur Text-to-Speech
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.FRENCH)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(
                        "TTS",
                        "La langue (Français) n'est pas supportée ou les données sont manquantes."
                    )
                    val installIntent = Intent()
                    installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    startActivity(installIntent)
                    Toast.makeText(
                        this,
                        "Veuillez installer les données vocales Françaises pour la synthèse vocale.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.d("TTS", "Moteur TTS initialisé en Français.")
                    isTtsReady = true
                }
            } else {
                Log.e("TTS", "Échec de l'initialisation du moteur TTS. Status: $status")
                Toast.makeText(
                    this,
                    "Échec de l'initialisation de la synthèse vocale.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d("TTS", "Début de la lecture : $utteranceId")
            }

            override fun onDone(utteranceId: String) {
                Log.d("TTS", "Fin de la lecture : $utteranceId")
                runOnUiThread {
                    if (utteranceId.startsWith("poi_message_")) {
                        currentDialog?.dismiss()
                        currentDialog = null
                        isSpeakingPoi = false
                        currentPoiId = null
                        pendingPoi = null
                    }
                }
            }

            @Deprecated("Déprécié, mais doit être implémenté")
            override fun onError(utteranceId: String) {
                Log.e("TTS", "Erreur de lecture : $utteranceId")
                runOnUiThread {
                    if (utteranceId.startsWith("poi_message_")) {
                        currentDialog?.dismiss()
                        currentDialog = null
                        isSpeakingPoi = false
                        currentPoiId = null
                        pendingPoi = null
                    }
                }
            }
        })

        // 2. Initialisation propre de Firebase
        Firebase.app
        auth = Firebase.auth
        resetFirebaseState()

        // 3. Initialisation client de localisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 4. Initialisation des capteurs (si tu les utilises)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)


        // 5. Nettoyage préventif des fragments au démarrage (gardé de ton code)
        if (savedInstanceState != null) {
            val fragment = supportFragmentManager.findFragmentById(R.id.map)
            if (fragment != null) {
                Log.d(
                    "ONCREATE",
                    "🧹 Fragment de carte trouvé dans savedInstanceState, suppression préventive."
                )
                supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }

        // 6. Charger les POIs déjà déclenchés
        chargerPoisDeclenches()

        // 7. Vérifie si l'utilisateur est déjà connecté et gère l'UI en conséquence
        val currentUser = auth.currentUser
        if (currentUser != null) {
            Log.d("AUTH", "Utilisateur déjà connecté: ${currentUser.email}")
            isAuthenticated = true

            // ✅ Nettoyer les fragments existants AVANT de charger la vue
            val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
            if (existingFragment != null) {
                Log.d("ONCREATE", "🧹 Fragment existant détecté, suppression...")
                supportFragmentManager.beginTransaction()
                    .remove(existingFragment)
                    .commitNowAllowingStateLoss() // commitNow pour exécution immédiate
            }

            // ✅ Charger la vue principale
            setContentView(R.layout.activity_main)

            // ✅ Initialiser la carte et les boutons
            initializeMapView()

        } else {
            isAuthenticated = false
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

    private fun resetFirebaseState() {
        android.util.Log.d("AUTH", "🔄 Réinitialisation de l'état Firebase")
        auth = Firebase.auth

        firestore.clearPersistence()
            .addOnSuccessListener {
                android.util.Log.d("AUTH", "Cache Firestore nettoyé")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("AUTH", "Erreur nettoyage cache: ${e.message}")
            }
        isAuthenticated = false
        isModerator = false
    }

    private fun checkIfModerator() {
        val moderatorEmails = listOf(
            "marc.paradinas@gmail.com",
            "marc.paradinas@wanadoo.fr"
        )
        android.util.Log.d("MODERATION", "===== DÉBUT CHECK MODERATOR =====")
        val email = auth.currentUser?.email
        android.util.Log.d("MODERATION", "Email actuel: $email")
        android.util.Log.d("MODERATION", "Liste modérateurs: $moderatorEmails")

        isModerator = email != null && moderatorEmails.contains(email)
        android.util.Log.d("MODERATION", "isModerator: $isModerator")
        val btnModeration = findViewById<Button>(R.id.btnModeration)
        if (btnModeration != null) {
            btnModeration.visibility = if (isModerator) View.VISIBLE else View.GONE
            android.util.Log.d("MODERATION", "Bouton modération visible: ${btnModeration.visibility == View.VISIBLE}")
        } else {
            android.util.Log.e("MODERATION", "Bouton modération non trouvé!")
        }
        android.util.Log.d("MODERATION", "===== FIN CHECK MODERATOR =====")
    }

    private fun rafraichirCarte(location: Location?) {
        android.util.Log.d("CARTE", "🔍 rafraichirCarte appelé")
        if (!::mMap.isInitialized) return
        mMap.clear()
        locationMarker = null

        for (poi in pointsInteret) {
            val shouldDisplay = when (poi.status) {
                PoiStatus.VALIDATED -> !pointsDejaDeclenches.contains(poi.id)
                PoiStatus.PROPOSED -> !pointsDejaDeclenches.contains(poi.id)
                PoiStatus.INITIATED -> true // ✅ Les brouillons ne disparaissent jamais
            }

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
                        .clickable(poi.status == PoiStatus.INITIATED) // ✅ Clickable si brouillon
                )

                // ✅ Stocker l'association cercle → POI pour gérer les clics
                circle.tag = poi.id

                // ✅ Afficher le début du message pour les POIs INITIATED
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
            android.util.Log.d("CARTE", "📍 Recréation du marqueur utilisateur")
            updateLocationMarker(loc)
        }
    }

    private fun playPendingPoi(location: Location) {
        val poi = pendingPoi ?: return
        // On marque le POI comme déclenché UNIQUEMENT quand on commence à le lire
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
        android.util.Log.d("TTS", "Lecture du message : ${poi.message}")
    }

    private fun verifierPointsInteret(location: Location) {
        val currentLatLng = LatLng(location.latitude, location.longitude)
        var poiFound = false
        for (poi in pointsInteret) {
            if (pointsDejaDeclenches.contains(poi.id)) continue
            val results = FloatArray(1)
            Location.distanceBetween(
                currentLatLng.latitude, currentLatLng.longitude,
                poi.position.latitude, poi.position.longitude,
                results
            )
            val distanceEnMetres = results[0]
            if (distanceEnMetres <= 20f) {
                poiFound = true
                if (isSpeakingPoi) {
                    android.util.Log.d("POI", "Un message est déjà en cours de lecture. POI ignoré : ${poi.id}")
                    continue
                }
                if (pendingPoi != null) {
                    android.util.Log.d("POI", "Un POI est déjà en attente. POI ignoré : ${poi.id}")
                    continue
                }
                pendingPoi = poi
                android.util.Log.d("POI", "POI mis en attente : ${poi.id}")
                playPendingPoi(location)
                break
            }
        }
        // ✅ Si aucun POI n'est trouvé et qu'un POI est en attente,
        // cela signifie que l'utilisateur est sorti de la zone avant la lecture
        if (!poiFound && pendingPoi != null) {
            android.util.Log.d("POI", "Utilisateur sorti de la zone. POI en attente annulé : ${pendingPoi?.id}")
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
                    Log.d("AUTH", "✅ Connexion réussie pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Connexion réussie", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true

                    // ✅ Nettoyer les fragments existants AVANT d'initialiser la vue
                    val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
                    if (existingFragment != null) {
                        Log.d("AUTH", "🧹 Suppression du fragment de carte existant")
                        supportFragmentManager.beginTransaction()
                            .remove(existingFragment)
                            .commitNowAllowingStateLoss()
                    }

                    // ✅ Charger la vue principale
                    setContentView(R.layout.activity_main)

                    // ✅ Initialiser la carte avec un léger délai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ✅ Démarrer le service de localisation si les permissions sont OK
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
                    Log.e("AUTH", "❌ Erreur connexion: ${task.exception?.message}")
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
                    Log.d("AUTH", "✅ Compte créé pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Compte créé avec succès", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true

                    // ✅ Nettoyer les fragments existants AVANT d'initialiser la vue
                    val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
                    if (existingFragment != null) {
                        Log.d("AUTH", "🧹 Suppression du fragment de carte existant")
                        supportFragmentManager.beginTransaction()
                            .remove(existingFragment)
                            .commitNowAllowingStateLoss()
                    }

                    // ✅ Charger la vue principale
                    setContentView(R.layout.activity_main)

                    // ✅ Initialiser la carte avec un léger délai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ✅ Démarrer le service de localisation si les permissions sont OK
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
                        is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> "Mot de passe trop faible (6 caractères minimum)."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Format d'email invalide."
                        is com.google.firebase.FirebaseNetworkException -> "Problème de connexion réseau."
                        else -> "Erreur d'inscription : ${task.exception?.message ?: "Vérifiez vos informations."}"
                    }
                    Log.e("AUTH", "❌ Erreur inscription: ${task.exception?.message}")
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun initializeMapView() {
        try {
            // ✅ Timeout de sécurité pour détecter les initialisations bloquées
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e("INIT", "⏱️ Timeout : Initialisation trop longue")
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

            // ✅ Charger les POIs depuis Firestore
            chargerPointsInteret()

            // ✅ Configuration des boutons de l'interface

            // Bouton Ajouter POI
            val btnAddPoi = findViewById<Button>(R.id.btn_add_poi)
            btnAddPoi?.setOnClickListener { onAddPoiClicked() }

            // Bouton Modération
            val btnModeration = findViewById<Button>(R.id.btnModeration)
            btnModeration?.setOnClickListener {
                if (isModerator) showModerationDialog()
                else Toast.makeText(this, "Accès réservé aux modérateurs", Toast.LENGTH_SHORT).show()
            }

            // Bouton Réafficher tous les POIs
            val btnReafficher = findViewById<Button>(R.id.btnReafficher)
            btnReafficher?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Réinitialiser les POIs")
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

            // Bouton Déconnexion
            val btnLogout = findViewById<Button>(R.id.btnLogout)
            btnLogout?.setOnClickListener {
                Log.d("AUTH", "🚪 Déconnexion demandée")

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
                    Log.d("LOGOUT", "🧹 Suppression du fragment de carte.")
                    supportFragmentManager.beginTransaction()
                        .remove(mapFragment)
                        .commitAllowingStateLoss()
                    supportFragmentManager.executePendingTransactions()
                }

                // Déconnexion Firebase
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
                    Toast.makeText(this, "Déconnecté", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("LOGOUT", "Erreur lors de la déconnexion: ${e.message}", e)
                    Toast.makeText(this, "Erreur critique. Redémarrage...", Toast.LENGTH_LONG).show()
                    finish()
                    startActivity(intent)
                }
            }

            // ✅ Vérifier si l'utilisateur est modérateur
            checkIfModerator()

            // ✅ Initialiser le client de localisation (si pas déjà fait dans onCreate)
            if (!::fusedLocationClient.isInitialized) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                Log.d("INIT", "🔧 FusedLocationClient initialisé")
            }

            // ✅ Initialiser locationRequest (si pas déjà fait)
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build()
                Log.d("INIT", "🔧 LocationRequest initialisé")
            }

            // ✅ Initialiser locationCallback (si pas déjà fait)
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation
                        if (location == null) {
                            Log.w("LOCATION", "❌ Aucune localisation reçue.")
                            return
                        }
                        Log.d("LOCATION", "✅ Localisation: Lat=${location.latitude}, Lng=${location.longitude}")
                        currentLocation = location
                        updateLocationMarker(location)
                        verifierPointsInteret(location)
                    }
                }
                Log.d("INIT", "🔧 LocationCallback initialisé")
            }

            // ✅ Initialiser le fragment de carte Google Maps
            val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
            if (mapFragment != null) {
                mapFragment.getMapAsync(this)
                Log.d("INIT", "🗺️ Fragment de carte initialisé")
            } else {
                Log.e("INIT", "❌ Fragment de carte non trouvé dans le layout")
                Toast.makeText(this, "Erreur : fragment de carte introuvable", Toast.LENGTH_LONG).show()
            }

            // ✅ Annuler le timeout (tout s'est bien passé)
            timeoutHandler.removeCallbacks(timeoutRunnable)
            Log.d("INIT", "✅ Initialisation de la vue terminée avec succès")

        } catch (e: Exception) {
            Toast.makeText(this, "Erreur d'initialisation: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("INIT", "❌ Erreur d'initialisation: ${e.message}", e)
            e.printStackTrace()
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        // ✅ AJOUT : Listener de clic sur les cercles
        mMap.setOnCircleClickListener { circle ->
            val poiId = circle.tag as? String ?: return@setOnCircleClickListener
            val poi = pointsInteret.find { it.id == poiId } ?: return@setOnCircleClickListener

            if (poi.status == PoiStatus.INITIATED) {
                showEditMyPoiDialog(poi)
            }
        }
        // ✅ FIN AJOUT

        if (!hasFineLocationPermission()) {
            Log.w("MainActivity", "⚠️ Permission de localisation non accordée dans onMapReady")
            // 🚨 Ne redemander QUE si aucune demande n'est en cours
            if (!isRequestingPermissions) {
                requestFineLocationPermission()
            }
            return
        }
        // ✅ Désactiver le bouton "Ma position" et la couche de localisation
        mMap.isMyLocationEnabled = false
        googleMap.uiSettings.isMyLocationButtonEnabled = true  // ✅ Garde le bouton de recentrage
        try {
            googleMap.setMyLocationEnabled(false)  // Désactive définitivement le marqueur bleu
        } catch (e: SecurityException) {
            Log.e("MAP", "Erreur permission localisation", e)
        }

        // ✅ Charger les POIs depuis Firestore
        chargerPointsInteret()

        // ✅ Récupérer la dernière position connue et centrer la carte
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                updateLocationMarker(it)
                rafraichirCarte(it)
                // Centrer la caméra sur la position actuelle
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(it.latitude, it.longitude),
                    15f
                ))
                Log.d("MainActivity", "📍 Carte centrée sur : ${it.latitude}, ${it.longitude}")
            } ?: run {
                Log.w("MainActivity", "⚠️ Aucune position connue dans lastLocation")
            }
        }

        // ✅ Démarrer les mises à jour GPS en temps réel
        startLocationUpdates()

        Log.d("MainActivity", "✅ Carte Google Maps prête et configurée")
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
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }
    private fun ajouterPointInteret(location: Location, message: String) {
        val id = System.currentTimeMillis().toString()
        val currentUser = auth.currentUser
        val poiData = hashMapOf(
            "id" to id,
            "lat" to location.latitude,
            "lng" to location.longitude,
            "message" to message,
            "creatorUid" to (currentUser?.uid ?: ""),
            "createdAt" to com.google.firebase.Timestamp.now(),
            "status" to "initiated",  // ✅ Nouveau : statut "initiated"
            "approved" to false
        )
        firestore.collection("pois")
            .document(id)
            .set(poiData)
            .addOnSuccessListener {
                Toast.makeText(this, "Brouillon de POI enregistré. Vous pouvez le modifier avant de le proposer.", Toast.LENGTH_LONG).show()
                chargerPointsInteret() // ✅ Recharger pour afficher le nouveau POI INITIATED
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
    private fun chargerPointsInteret() {
        val currentUser = auth.currentUser
        val currentUid = currentUser?.uid

        pointsInteret.clear()

        // 1️⃣ Charger les POIs VALIDATED (visibles par tous)
        firestore.collection("pois")
            .whereEqualTo("status", "validated")
            .get()
            .addOnSuccessListener { result ->
                for (doc in result) {
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(
                            doc.getDouble("lat") ?: 0.0,
                            doc.getDouble("lng") ?: 0.0
                        ),
                        message = doc.getString("message") ?: "",
                        status = PoiStatus.VALIDATED,
                        creatorUid = doc.getString("creatorUid")
                    )
                    pointsInteret.add(poi)
                }
                android.util.Log.d("POI", "✅ ${pointsInteret.size} POIs VALIDATED chargés")
// 2️⃣ Charger les POIs de l'utilisateur (INITIATED + PROPOSED)
                if (currentUid != null) {
                    if (isModerator) {
                        // ✅ NOUVEAU : Modérateur voit TOUS les PROPOSED + ses POIs perso
                        chargerPoisProposedPourModerateur(currentUid)
                    } else {
                        // ✅ EXISTANT : Utilisateur normal voit seulement ses POIs perso
                        chargerMesPoisEnAttente(currentUid)
                    }
                } else {
                    rafraichirCarte(currentLocation) // ✅ EXISTANT (inchangé)
                }            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur chargement POI: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun chargerMesPoisEnAttente(uid: String) {
        firestore.collection("pois")
            .whereEqualTo("creatorUid", uid)
            .whereIn("status", listOf("initiated", "proposed"))
            .get()
            .addOnSuccessListener { result ->
                for (doc in result) {
                    val status = poiStatusFromFirestore(
                        doc.getBoolean("approved"),
                        doc.getString("status")
                    )
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(
                            doc.getDouble("lat") ?: 0.0,
                            doc.getDouble("lng") ?: 0.0
                        ),
                        message = doc.getString("message") ?: "",
                        status = status,
                        creatorUid = doc.getString("creatorUid")
                    )
                    pointsInteret.add(poi)
                }
                android.util.Log.d("POI", "✅ ${pointsInteret.size} POIs totaux (dont mes brouillons/proposés)")
                rafraichirCarte(currentLocation)
            }
            .addOnFailureListener { e ->
                android.util.Log.e("POI", "Erreur chargement mes POIs: ${e.message}")
                rafraichirCarte(currentLocation)
            }
    }

    /**
     * Charge TOUS les POIs PROPOSED (pour modération) + les POIs perso de l'utilisateur
     */
    private fun chargerPoisProposedPourModerateur(uid: String) {
        // 1️⃣ Charge d'abord TOUS les POIs PROPOSED
        firestore.collection("pois")
            .whereEqualTo("status", "proposed")
            .get()
            .addOnSuccessListener { proposedResult ->
                for (doc in proposedResult) {
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(
                            doc.getDouble("lat") ?: 0.0,
                            doc.getDouble("lng") ?: 0.0
                        ),
                        message = doc.getString("message") ?: "",
                        status = PoiStatus.PROPOSED,
                        creatorUid = doc.getString("creatorUid")
                    )
                    // Évite les doublons (au cas où)
                    if (pointsInteret.none { it.id == poi.id }) {
                        pointsInteret.add(poi)
                    }
                }
                Log.d("POI", "✅ ${proposedResult.size()} POIs PROPOSED chargés pour modération")

                // 2️⃣ Charge ensuite les POIs perso (INITIATED) de l'utilisateur
                chargerMesPoisEnAttente(uid)
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur chargement POIs PROPOSED: ${e.message}")
                // Même en cas d'erreur, charge au moins ses POIs perso
                chargerMesPoisEnAttente(uid)
            }
    }
/*
    private fun validerPoi(poiId: String) {
        firestore.collection("pois").document(poiId)
            .update("approved", true)
            .addOnSuccessListener {
                Toast.makeText(this, "POI validé!", Toast.LENGTH_SHORT).show()
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
            .whereEqualTo("status", "proposed")  // ✅ Charger uniquement les POIs "proposed"
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
        // ✅ Vérifier la permission avec la nouvelle fonction
        if (!hasFineLocationPermission()) {
            Log.w("LOCATION", "⚠️ Permission de localisation manquante.")
            return
        }

        try {
            // ✅ Initialiser locationRequest si ce n'est pas déjà fait
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    3000L // Mise à jour toutes les 3 secondes
                )
                    .setMinUpdateIntervalMillis(1000L) // Minimum 1 seconde entre chaque mise à jour
                    .build()

                Log.d("LOCATION", "🔧 LocationRequest initialisé")
            }

            // ✅ Initialiser locationCallback si ce n'est pas déjà fait
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            currentLocation = location
                            updateLocationMarker(location)
                            Log.d("LOCATION", "📍 Position mise à jour : ${location.latitude}, ${location.longitude}")

                            // ✅ OPTIONNEL : Vérifier les POIs proches (si tu veux que MainActivity le fasse aussi)
                            // verifierProximitePois(location)
                        }
                    }
                }
                Log.d("LOCATION", "🔧 LocationCallback initialisé")
            }

            // ✅ Arrêter les mises à jour existantes avant d'en démarrer de nouvelles
            fusedLocationClient.removeLocationUpdates(locationCallback)

            // ✅ Démarrer les nouvelles mises à jour
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            Log.d("LOCATION", "📡 Mises à jour de localisation démarrées.")
        } catch (e: SecurityException) {
            Log.e("LOCATION", "❌ Erreur de permission : ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    private fun updateLocationMarker(location: Location) {
        // ✅ Vérification que la carte est initialisée
        if (!::mMap.isInitialized) {
            android.util.Log.e("LOCATION", "❌ mMap non initialisé, impossible de créer/mettre à jour le marqueur.")
            return
        }
        val currentLatLng = LatLng(location.latitude, location.longitude)
        val customMarkerIcon = bitmapDescriptorFromVector(this, R.drawable.outline_arrow_circle_up_24)
        if (locationMarker == null) {
            // Création du marqueur
            android.util.Log.d("LOCATION", "✅ Création du marqueur de localisation à Lat=${location.latitude}, Lng=${location.longitude}")
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
            android.util.Log.d("LOCATION", "📍 Caméra déplacée vers la position initiale.")
        } else {
            // Mise à jour du marqueur existant
            android.util.Log.d("LOCATION", "🔄 Mise à jour du marqueur de localisation à Lat=${location.latitude}, Lng=${location.longitude}")
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

    // Enregistrement des capteurs
    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

    // Si toutes les permissions sont accordées ET authentifié
    if (hasAllLocationPermissions() && isAuthenticated) {
        startAppFeatures()
    }
    // 🚨 Ne redemander que si AUCUNE demande en cours ET pas authentifié
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
