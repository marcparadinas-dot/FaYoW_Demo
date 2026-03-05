
package com.example.fayowdemo

import android.Manifest
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
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
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
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
    val message: String
)


// POI en attente de modération
data class PendingPoi(
    val id: String,
    val message: String
)


@RequiresApi(Build.VERSION_CODES.CUPCAKE)
class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {
    private var isTtsReady =false
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


    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
            android.util.Log.d("TTS", "Moteur TTS arrêté et libéré.")
        }
        super.onDestroy()
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


    private fun reinitialiserPoisDeclenches() {
        // Vider la liste en mémoire
        pointsDejaDeclenches.clear()


        // Vider ce qui est stocké en local
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
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
                        android.util.Log.d("LOCATION", "✅ Position récupérée après réinitialisation : ${lastLocation.latitude}, ${lastLocation.longitude}")
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


        // ✅ Initialisation du moteur Text-to-Speech
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.FRENCH)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    android.util.Log.e("TTS", "La langue (Français) n'est pas supportée ou les données sont manquantes.")
                    val installIntent = Intent()
                    installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    startActivity(installIntent)
                    Toast.makeText(this, "Veuillez installer les données vocales Françaises pour la synthèse vocale.", Toast.LENGTH_LONG).show()
                } else {
                    android.util.Log.d("TTS", "Moteur TTS initialisé en Français.")
                    isTtsReady = true // Marquer le TTS comme prêt
                }
            } else {
                android.util.Log.e("TTS", "Échec de l'initialisation du moteur TTS. Status: $status")
                Toast.makeText(this, "Échec de l'initialisation de la synthèse vocale.", Toast.LENGTH_LONG).show()
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                android.util.Log.d("TTS", "Début de la lecture : $utteranceId")
            }

            override fun onDone(utteranceId: String) {
                android.util.Log.d("TTS", "Fin de la lecture : $utteranceId")
                runOnUiThread {
                    if (utteranceId.startsWith("poi_message_")) {
                        currentDialog?.dismiss()
                        currentDialog = null
                        isSpeakingPoi = false
                        currentPoiId = null
                        pendingPoi = null  // Plus de POI en attente
                    }
                }
            }

            @Deprecated("Déprécié, mais doit être implémenté")
            override fun onError(utteranceId: String) {
                android.util.Log.e("TTS", "Erreur de lecture : $utteranceId")
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






        // ✅ AJOUT : Nettoyage préventif des fragments au démarrage
        if (savedInstanceState != null) {
            val fragment = supportFragmentManager.findFragmentById(R.id.map)
            if (fragment != null) {
                android.util.Log.d("ONCREATE", "🧹 Fragment de carte trouvé dans savedInstanceState, suppression préventive.")
                supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }


        // Initialisation propre de Firebase
        Firebase.app
        auth = Firebase.auth
        resetFirebaseState()


        // ✅ AJOUT : Charger les POIs déjà déclenchés
        chargerPoisDeclenches()


        // Vérifie si l'utilisateur est déjà connecté
        val currentUser = auth.currentUser
        if (currentUser != null) {
            android.util.Log.d("AUTH", "Utilisateur déjà connecté: ${currentUser.email}")
            isAuthenticated = true
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
    /*
 private fun masquerCercleWithAnimation(poi: PointInteret) {
 Créer un cercle temporaire pour l'animation


    val circle = mMap.addCircle(
            CircleOptions()
                .center(poi.position)
                .radius(30.0)
                .strokeColor(Color.argb(30, 190, 30, 250))
                .fillColor(Color.argb(60, 190, 30, 250))
                .strokeWidth(3f)
                .zIndex(100f)  // Assurer qu'il est au-dessus
        )


        // Animation de disparition
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1000  // 1 seconde
            addUpdateListener { animation ->
                val alpha = animation.animatedValue as Float
                circle.fillColor = Color.argb((60 * alpha).toInt(), 190, 30, 250)
                circle.strokeColor = Color.argb((30 * alpha).toInt(), 190, 30, 250)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                                    circle.remove()  // Supprimer le cercle après l'animation
                    mMap.clear()
                    chargerPointsInteret()
                    currentLocation?.let { updateLocationMarker(it) }
                }
            })
        }
        animator.start()
    }
 */


    private fun rafraichirCarte(location: Location?) {
        android.util.Log.d("CARTE", "🔍 rafraichirCarte appelé avec location = ${location?.latitude}, ${location?.longitude}")


        if (!::mMap.isInitialized) return


        mMap.clear()


        // ✅ CRITIQUE : Réinitialiser la référence au marqueur après avoir vidé la carte
        locationMarker = null
        android.util.Log.d("CARTE", "🗑️ Marqueur réinitialisé à null après mMap.clear()")


        // Redessiner tous les cercles NON déclenchés
        for (poi in pointsInteret) {
            if (!pointsDejaDeclenches.contains(poi.id)) {
                mMap.addCircle(
                    CircleOptions()
                        .center(poi.position)
                        .radius(15.0)
                        .strokeColor(Color.argb(30, 190, 30, 250))
                        .fillColor(Color.argb(60, 190, 30, 250))
                        .strokeWidth(3f)
                )
            }
        }


        // Réafficher le pointeur utilisateur
        location?.let { loc ->
            android.util.Log.d("CARTE", "📍 Recréation du marqueur avec position : ${loc.latitude}, ${loc.longitude}")
            updateLocationMarker(loc)
        } ?: run {
            android.util.Log.w("CARTE", "⚠️ Impossible de réafficher le pointeur : position inconnue.")
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

            if (distanceEnMetres <= 10f) {
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


        android.util.Log.d("AUTH", "Tentative de connexion avec $email")
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    android.util.Log.d("AUTH", "✅ Connexion réussie pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Connexion réussie", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true


                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()
                    }, 500)
                } else {
                    val errorMessage = when (task.exception) {
                        is com.google.firebase.auth.FirebaseAuthInvalidUserException -> "Cet utilisateur n'existe pas."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Mot de passe incorrect."
                        is com.google.firebase.FirebaseNetworkException -> "Problème de connexion réseau."
                        else -> "Erreur de connexion : ${task.exception?.message ?: "Vérifiez vos identifiants."}"
                    }
                    android.util.Log.e("AUTH", "❌ Erreur connexion: ${task.exception?.message}")
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


        android.util.Log.d("AUTH", "Tentative d'inscription avec $email")
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    android.util.Log.d("AUTH", "✅ Compte créé pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Compte créé avec succès", Toast.LENGTH_SHORT).show()
                    isAuthenticated = true


                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()
                    }, 500)
                } else {
                    val errorMessage = when (task.exception) {
                        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "Cet email est déjà utilisé."
                        is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> "Mot de passe trop faible (6 caractères minimum)."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Format d'email invalide."
                        is com.google.firebase.FirebaseNetworkException -> "Problème de connexion réseau."
                        else -> "Erreur d'inscription : ${task.exception?.message ?: "Vérifiez vos informations."}"
                    }
                    android.util.Log.e("AUTH", "❌ Erreur inscription: ${task.exception?.message}")
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }


    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun initializeMapView() {
        try {
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                android.util.Log.e("INIT", "⏱️ Timeout : Initialisation trop longue")
                runOnUiThread {
                    Toast.makeText(this, "Problème d'initialisation", Toast.LENGTH_LONG).show()
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
            timeoutHandler.postDelayed(timeoutRunnable, 5000)


            setContentView(R.layout.activity_main)
            chargerPointsInteret()


            // ✅ Bouton Ajouter POI
            val btnAddPoi = findViewById<Button>(R.id.btn_add_poi)
            btnAddPoi.setOnClickListener { onAddPoiClicked() }


            // ✅ Bouton Modération
            val btnModeration = findViewById<Button>(R.id.btnModeration)
            btnModeration.setOnClickListener {
                if (isModerator) showModerationDialog()
                else Toast.makeText(this, "Accès réservé aux modérateurs", Toast.LENGTH_SHORT).show()
            }


            // ✅ Bouton Réafficher (CORRIGÉ : maintenant au bon endroit !)
            val btnReafficher = findViewById<Button>(R.id.btnReafficher)
            btnReafficher.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Réinitialiser les POIs")
                    .setMessage("Voulez-vous vraiment réafficher tous les points d'intérêt ?")
                    .setPositiveButton("Oui") { _, _ ->
                        reinitialiserPoisDeclenches()
                        Toast.makeText(this, "✅ Tous les POIs sont à nouveau disponibles !", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }


            // ✅ Bouton Déconnexion
            val btnLogout = findViewById<Button>(R.id.btnLogout)
            btnLogout.setOnClickListener {
                android.util.Log.d("AUTH", "🚪 Déconnexion demandée")


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
                    android.util.Log.d("LOGOUT", "🧹 Suppression du fragment de carte.")
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
                    android.util.Log.e("LOGOUT", "Erreur: ${e.message}", e)
                    Toast.makeText(this, "Erreur critique. Redémarrage...", Toast.LENGTH_LONG).show()
                    finish()
                    startActivity(intent)
                }
            }


            checkIfModerator()


            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()


            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation
                    if (location == null) {
                        android.util.Log.w("LOCATION", "❌ Aucune localisation reçue.")
                        return
                    }
                    android.util.Log.d("LOCATION", "✅ Localisation: Lat=${location.latitude}, Lng=${location.longitude}")
                    currentLocation = location
                    updateLocationMarker(location)
                    verifierPointsInteret(location)
                }
            }


            val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            mapFragment.getMapAsync(this)


            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)


            timeoutHandler.removeCallbacks(timeoutRunnable)


        } catch (e: Exception) {
            Toast.makeText(this, "Erreur d'initialisation: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("INIT", "❌ Erreur: ${e.message}")
            e.printStackTrace()
        }
    }


    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }


        mMap.isMyLocationEnabled = false
        startLocationUpdates()  // ✅ Démarrer les mises à jour GPS


        // ✅ Si la carte est réinitialisée, forcer une mise à jour GPS
        if (pointsDejaDeclenches.isNotEmpty()) {
            rafraichirCarte(currentLocation)
        }
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
            "approved" to false
        )


        firestore.collection("pois")
            .document(id)
            .set(poiData)
            .addOnSuccessListener {
                Toast.makeText(this, "Point d'intérêt proposé (en attente de validation)", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    private fun chargerPointsInteret() {
        firestore.collection("pois")
            .whereEqualTo("approved", true)
            .get()
            .addOnSuccessListener { result ->
                pointsInteret.clear()
                for (doc in result) {
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(
                            doc.getDouble("lat") ?: 0.0,
                            doc.getDouble("lng") ?: 0.0
                        ),
                        message = doc.getString("message") ?: ""
                    )
                    pointsInteret.add(poi)
                }
                android.util.Log.d("POI", "✅ Chargés: ${pointsInteret.size} POIs depuis Firestore")


                // ✅ Rafraîchir la carte après le chargement
                rafraichirCarte(currentLocation)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur chargement POI: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


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


    private fun showModerationDialog() {
        firestore.collection("pois")
            .whereEqualTo("approved", false)
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    Toast.makeText(this, "Aucun point en attente", Toast.LENGTH_SHORT).show()
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


        AlertDialog.Builder(this)
            .setTitle("Modifier/valider POI")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newMessage = editMessage.text.toString().trim()
                val approved = checkApproved.isChecked


                firestore.collection("pois").document(poi.id)
                    .update(mapOf(
                        "message" to newMessage,
                        "approved" to approved
                    ))
                    .addOnSuccessListener {
                        Toast.makeText(this, "POI mis à jour", Toast.LENGTH_SHORT).show()
                        if (approved) chargerPointsInteret()
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
        if (!hasLocationPermission()) {
            android.util.Log.w("LOCATION", "⚠️ Permission de localisation manquante.")
            return
        }


        try {
            // ✅ Arrêter les mises à jour existantes avant d'en démarrer de nouvelles
            fusedLocationClient.removeLocationUpdates(locationCallback)


            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            android.util.Log.d("LOCATION", "📡 Mises à jour de localisation démarrées.")
        } catch (e: SecurityException) {
            android.util.Log.e("LOCATION", "❌ Erreur de permission : ${e.message}")
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
        val customMarkerIcon = bitmapDescriptorFromVector(this, R.drawable.ic_baseline_directions_walk_24)


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




    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (::mMap.isInitialized) {
                mMap.isMyLocationEnabled = false
                startLocationUpdates()
            }
        }
    }


    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onResume() {
        super.onResume()
        if (::mMap.isInitialized && hasLocationPermission()) startLocationUpdates()
        if (::sensorManager.isInitialized) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }


    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onPause() {
        super.onPause()
        if (::fusedLocationClient.isInitialized) stopLocationUpdates()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
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
