package com.example.fayowdemo

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fayowdemo.auth.AuthManager
import com.example.fayowdemo.model.PendingPoi
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiStatus
import com.example.fayowdemo.repository.PoiRepository
import com.example.fayowdemo.ui.PermissionManager
import com.example.fayowdemo.ui.map.MapManager
import com.example.fayowdemo.ui.theme.FayowDemoTheme
import com.google.android.gms.location.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import androidx.activity.compose.setContent
import com.example.fayowdemo.auth.AuthActions
import com.example.fayowdemo.service.LocationService

@RequiresApi(Build.VERSION_CODES.CUPCAKE)
class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    // -------------------------------------------------------------------------
    // Managers
    // -------------------------------------------------------------------------

    private lateinit var authManager: AuthManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var mapManager: MapManager
    private val poiRepository = PoiRepository()

    // -------------------------------------------------------------------------
    // État de l'application
    // -------------------------------------------------------------------------

    private val pointsInteret = mutableListOf<PointInteret>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private val poisLusIds = mutableSetOf<String>()
    private var poisLusLoaded = false
    private var isAuthenticated = false

    // -------------------------------------------------------------------------
    // Carte
    // -------------------------------------------------------------------------

    private lateinit var mMap: GoogleMap
    private var currentLocation: Location? = null

    // -------------------------------------------------------------------------
    // Localisation
    // -------------------------------------------------------------------------

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    // -------------------------------------------------------------------------
    // Capteurs (boussole)
    // -------------------------------------------------------------------------

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentAzimuth = 0f

    // -------------------------------------------------------------------------
    // Text-to-Speech
    // -------------------------------------------------------------------------

    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    private var isSpeakingPoi = false
    private var currentPoiId: String? = null
    private var pendingPoi: PointInteret? = null
    private var currentDialog: AlertDialog? = null

    // -------------------------------------------------------------------------
    // BroadcastReceiver (POI lu depuis LocationService)
    // -------------------------------------------------------------------------

    private val poiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sncf.fayow.POI_LU") {
                val poiId = intent.getStringExtra("poiId") ?: return
                Log.d("MainActivity", "POI lu reçu : $poiId")
                mapManager.supprimerCerclePoi(poiId)
                poisLusIds.add(poiId)
            }
        }
    }

    // =========================================================================
    // Cycle de vie
    // =========================================================================

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 1. Managers — PermissionManager en premier (enregistre les launchers)
        permissionManager = PermissionManager(this)
        val (fineLauncher, backgroundLauncher) = permissionManager.creerLaunchers()
        permissionManager.enregistrerLaunchers(fineLauncher, backgroundLauncher)

        authManager = AuthManager(this)
        mapManager = MapManager(this)

        // 2. Callbacks des managers
        configurerCallbacksAuth()
        configurerCallbacksPermissions()

        // 3. Text-to-Speech
        initialiserTts()

        // 4. Localisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 5. Capteurs
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // 6. Nettoyage préventif du fragment carte (rotation d'écran)
        if (savedInstanceState != null) {
            supportFragmentManager.findFragmentById(R.id.map)?.let { fragment ->
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }

        // 7. Charger les POIs déjà déclenchés (SharedPreferences)
        chargerPoisDeclenches()

        // 8. Navigation initiale
        if (authManager.isUserLoggedIn()) {
            isAuthenticated = true
            afficherEcranCarte()
        } else {
            afficherEcranAuth()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.sncf.fayow.POI_LU")
        LocalBroadcastManager.getInstance(this).registerReceiver(poiUpdateReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(poiUpdateReceiver)
    }

    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        val uid = authManager.getCurrentUser()?.uid
        if (uid != null) {
            poiRepository.chargerPoisLus(uid,
                onSuccess = { ids ->
                    poisLusIds.clear()
                    poisLusIds.addAll(ids)
                    if (::mMap.isInitialized) {
                        mapManager.rafraichirCarte(
                            mMap, pointsInteret, poisLusIds,
                            pointsDejaDeclenches, currentLocation, currentAzimuth
                        )
                    }
                },
                onError = { Log.e("MainActivity", "Erreur chargement POIs lus : ${it.message}") }
            )
        }

        if (permissionManager.hasAllLocationPermissions() && isAuthenticated) {
            demarrerFonctionnalites()
        } else if (!permissionManager.hasFineLocationPermission() && isAuthenticated) {
            permissionManager.demanderPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        stopLocationService()
        super.onDestroy()
    }

    // =========================================================================
    // Navigation entre écrans
    // =========================================================================

    private fun afficherEcranAuth() {
        setContent {
            FayowDemoTheme {
                AuthScreen(authActions = object : AuthActions {
                    override fun onSignUp(email: String, password_input: String) {
                        authManager.signUp(email, password_input)
                    }
                    override fun onSignIn(email: String, password_input: String) {
                        authManager.signIn(email, password_input)
                    }
                })
            }
        }
    }

    private fun afficherEcranCarte() {
        // Nettoyer le fragment existant si nécessaire
        supportFragmentManager.findFragmentById(R.id.map)?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitNowAllowingStateLoss()
        }

        setContentView(R.layout.activity_main)
        Handler(Looper.getMainLooper()).postDelayed({
            initialiserVueCarte()
            if (permissionManager.hasFineLocationPermission()) {
                if (permissionManager.hasBackgroundLocationPermission()) {
                    demarrerFonctionnalites()
                } else {
                    permissionManager.demanderPermissions()
                }
            } else {
                permissionManager.demanderPermissions()
            }
        }, 500)
    }

    // =========================================================================
    // Configuration des callbacks
    // =========================================================================

    private fun configurerCallbacksAuth() {
        authManager.onSignInSuccess = {
            isAuthenticated = true
            afficherEcranCarte()
        }
        authManager.onSignUpSuccess = {
            isAuthenticated = true
            afficherEcranCarte()
        }
        authManager.onSignOutComplete = {
            isAuthenticated = false
            pointsInteret.clear()
            pointsDejaDeclenches.clear()
            sauvegarderPoisDeclenches()
            currentLocation = null
            stopLocationService()
            stopLocationUpdates()
            afficherEcranAuth()
            Toast.makeText(this, "Déconnecté", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configurerCallbacksPermissions() {
        permissionManager.onAllPermissionsGranted = {
            demarrerFonctionnalites()
        }
        permissionManager.onBackgroundPermissionDenied = {
            demarrerFonctionnalites() // Démarre quand même, mais en mode limité
        }
    }

    // =========================================================================
    // Initialisation de la carte
    // =========================================================================

    private fun initialiserVueCarte() {
        val uid = authManager.getCurrentUser()?.uid
        if (uid != null) {
            poiRepository.chargerPoisLus(uid,
                onSuccess = { ids ->
                    poisLusIds.clear()
                    poisLusIds.addAll(ids)
                    poisLusLoaded = true
                    chargerPointsInteret()
                },
                onError = {
                    poisLusLoaded = true
                    chargerPointsInteret()
                }
            )
        } else {
            chargerPointsInteret()
        }

        // Bouton Ajouter POI
        findViewById<Button>(R.id.btn_add_poi)?.setOnClickListener {
            onAddPoiClicked()
        }

        // Bouton Modération
        findViewById<Button>(R.id.btnModeration)?.setOnClickListener {
            if (authManager.isModerator) showModerationDialog()
            else Toast.makeText(this, "Accès réservé aux modérateurs", Toast.LENGTH_SHORT).show()
        }

        // Bouton Réafficher
        findViewById<Button>(R.id.btnReafficher)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Réinitialiser les anecdotes")
                .setMessage("Voulez-vous vraiment réafficher toutes les anecdotes ?")
                .setPositiveButton("Oui") { _, _ ->
                    if (permissionManager.hasFineLocationPermission()) {
                        reinitialiserPoisDeclenches()
                    } else {
                        Toast.makeText(this, "Permission de localisation nécessaire", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // Bouton Déconnexion
        findViewById<Button>(R.id.btnLogout)?.setOnClickListener {
            authManager.signOut()
        }

        authManager.checkIfModerator()

        // Initialiser le client de localisation
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        // Initialiser locationRequest
        if (!::locationRequest.isInitialized) {
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
        }

        // Initialiser locationCallback
        if (!::locationCallback.isInitialized) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation ?: return
                    currentLocation = location
                    mapManager.updateLocationMarker(mMap, location, currentAzimuth)
                    verifierPointsInteret(location)
                }
            }
        }

        // Initialiser le fragment Google Maps
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        if (mapFragment != null) {
            mapFragment.getMapAsync(this)
        } else {
            Log.e("MainActivity", "Fragment de carte introuvable")
            Toast.makeText(this, "Erreur : fragment de carte introuvable", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresPermission(anyOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mapManager.initialiserCarte(mMap)

        mMap.setOnCircleClickListener { circle ->
            val poiId = circle.tag as? String ?: return@setOnCircleClickListener
            val poi = pointsInteret.find { it.id == poiId } ?: return@setOnCircleClickListener
            if (poi.status == PoiStatus.INITIATED) showEditMyPoiDialog(poi)
        }

        if (!permissionManager.hasFineLocationPermission()) {
            permissionManager.demanderPermissions()
            return
        }

        chargerPointsInteret()

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                mapManager.updateLocationMarker(mMap, it, currentAzimuth)
                mapManager.rafraichirCarte(
                    mMap, pointsInteret, poisLusIds,
                    pointsDejaDeclenches, it, currentAzimuth
                )
            }
        }

        startLocationUpdates()
        Log.d("MainActivity", "Carte prête")
    }

    // =========================================================================
    // Fonctionnalités de l'application
    // =========================================================================

    private fun demarrerFonctionnalites() {
        if (!isAuthenticated) return
        if (!::mMap.isInitialized) initialiserVueCarte()
        if (permissionManager.hasFineLocationPermission()) startLocationUpdates()
        startLocationService()
    }

    // =========================================================================
    // Gestion des POIs
    // =========================================================================

    private fun chargerPointsInteret() {
        val uid = authManager.getCurrentUser()?.uid
        pointsInteret.clear()

        poiRepository.chargerPoisValides(
            onSuccess = { poisValides ->
                pointsInteret.addAll(poisValides)
                if (uid != null) {
                    if (authManager.isModerator) {
                        poiRepository.chargerPoisPourModerateur(uid,
                            onSuccess = { autres ->
                                pointsInteret.addAll(autres.filter { p -> pointsInteret.none { it.id == p.id } })
                                if (::mMap.isInitialized) {
                                    mapManager.rafraichirCarte(
                                        mMap, pointsInteret, poisLusIds,
                                        pointsDejaDeclenches, currentLocation, currentAzimuth
                                    )
                                }
                            },
                            onError = { Log.e("MainActivity", "Erreur chargement POIs modérateur") }
                        )
                    } else {
                        poiRepository.chargerMesPois(uid,
                            onSuccess = { mesPois ->
                                pointsInteret.addAll(mesPois.filter { p -> pointsInteret.none { it.id == p.id } })
                                if (::mMap.isInitialized) {
                                    mapManager.rafraichirCarte(
                                        mMap, pointsInteret, poisLusIds,
                                        pointsDejaDeclenches, currentLocation, currentAzimuth
                                    )
                                }
                            },
                            onError = { Log.e("MainActivity", "Erreur chargement mes POIs") }
                        )
                    }
                } else {
                    if (::mMap.isInitialized) {
                        mapManager.rafraichirCarte(
                            mMap, pointsInteret, poisLusIds,
                            pointsDejaDeclenches, currentLocation, currentAzimuth
                        )
                    }
                }
            },
            onError = { Toast.makeText(this, "Erreur chargement POIs : ${it.message}", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun verifierPointsInteret(location: Location) {
        if (!poisLusLoaded) return

        val currentLatLng = LatLng(location.latitude, location.longitude)
        var poiFound = false

        for (poi in pointsInteret) {
            if (poi.status == PoiStatus.INITIATED) continue
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) continue
            if (poisLusIds.contains(poi.id)) continue
            if (pointsDejaDeclenches.contains(poi.id)) continue  // ← ligne manquante

            val results = FloatArray(1)
            Location.distanceBetween(
                currentLatLng.latitude, currentLatLng.longitude,
                poi.position.latitude, poi.position.longitude,
                results
            )

            if (results[0] <= 20f) {
                poiFound = true
                if (isSpeakingPoi || pendingPoi != null) continue
                pendingPoi = poi
                playPendingPoi(location)
                break
            }
        }

        if (!poiFound && pendingPoi != null) {
            pendingPoi = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun playPendingPoi(location: Location) {
        val poi = pendingPoi ?: return
        val uid = authManager.getCurrentUser()?.uid

        if (poisLusIds.contains(poi.id)) {
            pendingPoi = null
            return
        }

        pointsDejaDeclenches.add(poi.id)
        sauvegarderPoisDeclenches()
        mapManager.rafraichirCarte(
            mMap, pointsInteret, poisLusIds,
            pointsDejaDeclenches, location, currentAzimuth
        )

        isSpeakingPoi = true
        currentPoiId = poi.id

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(poi.message)
            .setPositiveButton("OK", null)
            .setOnDismissListener { currentDialog = null }
            .create()
        currentDialog?.show()

        val utteranceId = "poi_message_${poi.id}"
        textToSpeech.speak(poi.message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        if (uid != null && poi.status == PoiStatus.VALIDATED && !poisLusIds.contains(poi.id)) {
            poiRepository.marquerPoiCommeLu(uid, poi.id,
                onSuccess = { Log.d("MainActivity", "POI ${poi.id} marqué comme lu") },
                onError = { Log.e("MainActivity", "Erreur marquage POI lu") }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun reinitialiserPoisDeclenches() {
        pointsDejaDeclenches.clear()
        getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
            .edit().remove("pois_declenches").apply()

        poisLusIds.clear()

        val uid = authManager.getCurrentUser()?.uid
        if (uid != null) {
            poiRepository.reinitialiserPoisLus(uid,
                onSuccess = { Log.d("MainActivity", "POIs lus réinitialisés") },
                onError = { Log.e("MainActivity", "Erreur réinitialisation POIs lus") }
            )
        }

        if (::mMap.isInitialized) {
            mapManager.rafraichirCarte(
                mMap, pointsInteret, poisLusIds,
                pointsDejaDeclenches, currentLocation, currentAzimuth
            )
        }
        Toast.makeText(this, "Tous les POIs sont à nouveau disponibles !", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // Dialogs POI
    // =========================================================================

    private fun onAddPoiClicked() {
        val location = currentLocation ?: run {
            Toast.makeText(this, "Localisation indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        val editText = EditText(this).apply { hint = "Message de l'anecdote" }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle anecdote")
            .setMessage("Entrez le message à afficher à cet endroit :")
            .setView(editText)
            .setPositiveButton("Enregistrer") { _, _ ->
                val message = editText.text.toString().ifBlank { "Point d'intérêt" }
                val uid = authManager.getCurrentUser()?.uid ?: return@setPositiveButton
                poiRepository.ajouterPoi(
                    latitude   = location.latitude,
                    longitude  = location.longitude,
                    message    = message,
                    creatorUid = uid,
                    onSuccess  = {
                        Toast.makeText(this, "Brouillon enregistré.", Toast.LENGTH_LONG).show()
                        chargerPointsInteret()
                    },
                    onError = { Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEditMyPoiDialog(poi: PointInteret) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage = dialogView.findViewById<EditText>(R.id.editPoiMessage).apply {
            setText(poi.message)
        }
        dialogView.findViewById<CheckBox>(R.id.checkApproved).visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("Modifier votre brouillon")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                poiRepository.mettreAJourPoi(poi.id, mapOf("message" to editMessage.text.toString().trim()),
                    onSuccess = { Toast.makeText(this, "Brouillon mis à jour", Toast.LENGTH_SHORT).show(); chargerPointsInteret() },
                    onError   = { Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNeutralButton("Proposer à la modération") { _, _ ->
                poiRepository.mettreAJourPoi(poi.id,
                    mapOf("message" to editMessage.text.toString().trim(), "status" to "proposed"),
                    onSuccess = { Toast.makeText(this, "POI proposé à la modération !", Toast.LENGTH_SHORT).show(); chargerPointsInteret() },
                    onError   = { Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showModerationDialog() {
        poiRepository.chargerPoisValides(
            onSuccess = { _ ->
                // On recharge directement les PROPOSED pour la modération
            },
            onError = {}
        )
        // Charger les POIs en attente de modération
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("pois")
            .whereEqualTo("status", "proposed")
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    Toast.makeText(this, "Aucune anecdote en attente", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val pendingPois = result.documents.map { doc ->
                    PendingPoi(id = doc.id, message = doc.getString("message") ?: "")
                }
                val titles = pendingPois.mapIndexed { i, p -> "${i + 1}. ${p.message.take(40)}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Points à modérer")
                    .setItems(titles) { _, which -> showPoiEditDialog(pendingPois[which]) }
                    .setNegativeButton("Fermer", null)
                    .show()
            }
    }

    private fun showPoiEditDialog(poi: PendingPoi) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage = dialogView.findViewById<EditText>(R.id.editPoiMessage).apply { setText(poi.message) }
        val checkApproved = dialogView.findViewById<CheckBox>(R.id.checkApproved).apply {
            isChecked = false
            text = "Valider cette anecdote"
        }
        AlertDialog.Builder(this)
            .setTitle("Modérer POI")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                val updates = mutableMapOf<String, Any>("message" to editMessage.text.toString().trim())
                if (checkApproved.isChecked) {
                    updates["status"] = "validated"
                    updates["approved"] = true
                }
                poiRepository.mettreAJourPoi(poi.id, updates,
                    onSuccess = {
                        val msg = if (checkApproved.isChecked) "POI validé !" else "POI mis à jour"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (checkApproved.isChecked) chargerPointsInteret()
                    },
                    onError = { Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =========================================================================
    // SharedPreferences — POIs déclenchés
    // =========================================================================

    private fun chargerPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        pointsDejaDeclenches.addAll(
            prefs.getStringSet("pois_declenches", emptySet()) ?: emptySet()
        )
    }

    private fun sauvegarderPoisDeclenches() {
        getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
            .edit().putStringSet("pois_declenches", pointsDejaDeclenches).apply()
    }

    // =========================================================================
    // Localisation
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!permissionManager.hasFineLocationPermission()) return
        try {
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build()
            }
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation ?: return
                        currentLocation = location
                        if (::mMap.isInitialized) {
                            mapManager.updateLocationMarker(mMap, location, currentAzimuth)
                        }
                        verifierPointsInteret(location)
                    }
                }
            }
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Erreur permission localisation : ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun startLocationService() {
        if (!permissionManager.hasFineLocationPermission()) return
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopLocationService() {
        stopService(Intent(this, LocationService::class.java))
    }

    // =========================================================================
    // Capteurs (boussole)
    // =========================================================================

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                System.arraycopy(event.values, 0, gravity, 0, event.values.size)
            Sensor.TYPE_MAGNETIC_FIELD ->
                System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
        }
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
// La rotation est gérée par updateLocationMarker() dans MapManager
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =========================================================================
    // Text-to-Speech
    // =========================================================================

    private fun initialiserTts() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.FRENCH)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Langue française non supportée")
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } else {
                    isTtsReady = true
                    Log.d("TTS", "TTS initialisé en français")
                }
            } else {
                Log.e("TTS", "Échec initialisation TTS")
            }
        }

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                if (utteranceId.startsWith("poi_message_")) {
                    runOnUiThread {
                        currentDialog?.dismiss()
                        currentDialog = null
                        isSpeakingPoi = false
                        currentPoiId = null
                        pendingPoi = null
                    }
                }
            }
            @Deprecated("Déprécié")
            override fun onError(utteranceId: String) {
                runOnUiThread {
                    currentDialog?.dismiss()
                    currentDialog = null
                    isSpeakingPoi = false
                    currentPoiId = null
                    pendingPoi = null
                }
            }
        })
    }
}