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
import androidx.activity.compose.setContent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fayowdemo.auth.AuthActions
import com.example.fayowdemo.auth.AuthManager
import com.example.fayowdemo.model.PendingPoi
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiStatus
import com.example.fayowdemo.repository.PoiRepository
import com.example.fayowdemo.service.LocationService
import com.example.fayowdemo.ui.PermissionManager
import com.example.fayowdemo.ui.map.MapManager
import com.example.fayowdemo.ui.theme.FayowDemoTheme
import com.google.android.gms.location.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment

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

    private val pointsInteret        = mutableListOf<PointInteret>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private val poisLusIds           = mutableSetOf<String>()
    private var poisLusLoaded        = false
    private var isAuthenticated      = false
    private var pointsInteretCharges = false

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
    // Nullable pour permettre la réinitialisation à la reconnexion
    private var locationCallback: LocationCallback? = null

    // -------------------------------------------------------------------------
    // Capteurs (boussole)
    // -------------------------------------------------------------------------

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val gravity           = FloatArray(3)
    private val geomagnetic       = FloatArray(3)
    private val rotationMatrix    = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentAzimuth    = 0f

    // -------------------------------------------------------------------------
    // Dialog POI
    // -------------------------------------------------------------------------

    private var currentDialog: AlertDialog? = null

    // -------------------------------------------------------------------------
    // BroadcastReceivers
    // -------------------------------------------------------------------------

    /**
     * POI_DECLENCHE :
     *   utteranceDone=false → afficher le dialog
     *   utteranceDone=true  → fermer le dialog
     */
    private val poiDeclencheReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_POI_DECLENCHE) return

            val utteranceDone = intent.getBooleanExtra("utteranceDone", false)

            if (utteranceDone) {
                currentDialog?.dismiss()
                currentDialog = null
                return
            }

            val poiId   = intent.getStringExtra("poiId")   ?: return
            val message = intent.getStringExtra("message") ?: return

            // Mettre à jour la carte
            pointsDejaDeclenches.add(poiId)
            if (::mMap.isInitialized) {
                mapManager.rafraichirCarte(
                    mMap, pointsInteret, poisLusIds,
                    pointsDejaDeclenches, currentLocation, currentAzimuth
                )
            }

            // Afficher le dialog synchronisé avec le TTS
            currentDialog?.dismiss()
            currentDialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Information")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setOnDismissListener { currentDialog = null }
                .create()
            currentDialog?.show()
        }
    }

    /**
     * POI_LU : POI VALIDATED marqué lu dans Firestore → supprimer le cercle.
     */
    private val poiLuReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_POI_LU) return
            val poiId = intent.getStringExtra("poiId") ?: return
            Log.d("MainActivity", "POI lu reçu : $poiId")
            poisLusIds.add(poiId)
            mapManager.supprimerCerclePoi(poiId)
        }
    }

    /**
     * SYNC_ETAT : reçu depuis LocationService après ACTION_MAIN_STARTED.
     * Contient les IDs lus pendant la veille → met à jour la carte.
     */
    private val syncEtatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_SYNC_ETAT) return
            @Suppress("UNCHECKED_CAST")
            val ids = intent.getSerializableExtra("poisLusPendantVeille") as? HashSet<String>
                ?: return
            if (ids.isEmpty()) return

            Log.d("MainActivity", "Sync état reçu : ${ids.size} POIs lus pendant la veille")
            poisLusIds.addAll(ids)
            pointsDejaDeclenches.addAll(ids)
            if (::mMap.isInitialized) {
                mapManager.rafraichirCarte(
                    mMap, pointsInteret, poisLusIds,
                    pointsDejaDeclenches, currentLocation, currentAzimuth
                )
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

        permissionManager = PermissionManager(this)
        val (fineLauncher, backgroundLauncher) = permissionManager.creerLaunchers()
        permissionManager.enregistrerLaunchers(fineLauncher, backgroundLauncher)

        authManager = AuthManager(this)
        mapManager  = MapManager(this)

        configurerCallbacksAuth()
        configurerCallbacksPermissions()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer  = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (savedInstanceState != null) {
            supportFragmentManager.findFragmentById(R.id.map)?.let { fragment ->
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }

        if (authManager.isUserLoggedIn()) {
            isAuthenticated = true
            afficherEcranCarte()
        } else {
            afficherEcranAuth()
        }
    }

    override fun onStart() {
        super.onStart()
        val bm = LocalBroadcastManager.getInstance(this)
        bm.registerReceiver(poiDeclencheReceiver, IntentFilter(LocationService.ACTION_POI_DECLENCHE))
        bm.registerReceiver(poiLuReceiver,        IntentFilter(LocationService.ACTION_POI_LU))
        bm.registerReceiver(syncEtatReceiver,     IntentFilter(LocationService.ACTION_SYNC_ETAT))
        // NE PAS envoyer ACTION_MAIN_STARTED ici — la carte n'est pas encore prête.
        // Il sera envoyé depuis onMapReady.
    }

    override fun onStop() {
        super.onStop()
        val bm = LocalBroadcastManager.getInstance(this)
        bm.unregisterReceiver(poiDeclencheReceiver)
        bm.unregisterReceiver(poiLuReceiver)
        bm.unregisterReceiver(syncEtatReceiver)
        bm.sendBroadcast(Intent(LocationService.ACTION_MAIN_STOPPED))
    }

    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer,  SensorManager.SENSOR_DELAY_UI)

        if (permissionManager.hasAllLocationPermissions() && isAuthenticated && ::mMap.isInitialized) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onDestroy() {
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
        supportFragmentManager.findFragmentById(R.id.map)?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commitNowAllowingStateLoss()
        }
        setContentView(R.layout.activity_main)
        Handler(Looper.getMainLooper()).postDelayed({
            if (permissionManager.hasFineLocationPermission()) {
                if (permissionManager.hasBackgroundLocationPermission()) {
                    demarrerFonctionnalites()
                } else {
                    initialiserVueCarte()
                    permissionManager.demanderPermissions()
                }
            } else {
                initialiserVueCarte()
                permissionManager.demanderPermissions()
            }
        }, 500)
    }

    // =========================================================================
    // Configuration des callbacks
    // =========================================================================

    private fun configurerCallbacksAuth() {
        authManager.onSignInSuccess = {
            isAuthenticated      = true
            pointsInteretCharges = false
            poisLusIds.clear()
            pointsDejaDeclenches.clear()
            pointsInteret.clear()
            currentDialog?.dismiss()
            currentDialog = null
            // Relancer l'Activity proprement pour éviter le freeze dû au
            // mélange Compose/XML et à l'état du fragment manager
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        }
        authManager.onSignUpSuccess = {
            isAuthenticated      = true
            pointsInteretCharges = false
            poisLusIds.clear()
            pointsDejaDeclenches.clear()
            pointsInteret.clear()
            currentDialog?.dismiss()
            currentDialog = null
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        }
        authManager.onSignOutComplete = {
            isAuthenticated = false
            poisLusIds.clear()
            pointsDejaDeclenches.clear()
            pointsInteret.clear()
            currentLocation = null
            locationCallback = null
            currentDialog?.dismiss()
            currentDialog = null
            stopLocationService()
            stopLocationUpdates()
            Toast.makeText(this, "Déconnecté", Toast.LENGTH_SHORT).show()
            // Relancer proprement pour retrouver un état vierge
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun configurerCallbacksPermissions() {
        permissionManager.onAllPermissionsGranted = {
            demarrerFonctionnalites()
        }
        permissionManager.onBackgroundPermissionDenied = {
            demarrerFonctionnalites()
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

        findViewById<Button>(R.id.btn_add_poi)?.setOnClickListener {
            onAddPoiClicked()
        }
        findViewById<Button>(R.id.btnModeration)?.setOnClickListener {
            if (authManager.isModerator) showModerationDialog()
            else Toast.makeText(this, "Accès réservé aux modérateurs", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnReafficher)?.setOnClickListener {
            if (permissionManager.hasFineLocationPermission()) {
                afficherDialogReafficher()
            } else {
                Toast.makeText(this, "Permission de localisation nécessaire", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnLogout)?.setOnClickListener {
            authManager.signOut()
        }

        authManager.checkIfModerator()

        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        if (!::locationRequest.isInitialized) {
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
        }

        // locationCallback nullable → recréé si nécessaire
        if (locationCallback == null) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation ?: return
                    currentLocation = location
                    if (::mMap.isInitialized) {
                        mapManager.updateLocationMarker(mMap, location, currentAzimuth)
                    }
                }
            }
        }

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

        // Envoie ACTION_MAIN_STARTED avec retries pour absorber la race condition
        // entre onMapReady et le démarrage effectif de LocationService
        val bm = LocalBroadcastManager.getInstance(this)
        val mainStartedIntent = Intent(LocationService.ACTION_MAIN_STARTED)
        bm.sendBroadcast(mainStartedIntent)
        Handler(Looper.getMainLooper()).postDelayed({ bm.sendBroadcast(mainStartedIntent) }, 1000)
        Handler(Looper.getMainLooper()).postDelayed({ bm.sendBroadcast(mainStartedIntent) }, 2000)
        Handler(Looper.getMainLooper()).postDelayed({ bm.sendBroadcast(mainStartedIntent) }, 3000)
        Handler(Looper.getMainLooper()).postDelayed({ bm.sendBroadcast(mainStartedIntent) }, 5000)

        Log.d("MainActivity", "Carte prête, ACTION_MAIN_STARTED envoyé")
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
                Log.d("FAYOWDEBUG", "chargerPoisValides répondu : ${poisValides.size} POIs")
                pointsInteret.addAll(poisValides)
                if (uid != null) {
                    if (authManager.isModerator) {
                        poiRepository.chargerPoisPourModerateur(uid,
                            onSuccess = { autres ->
                                pointsInteret.addAll(autres.filter { p ->
                                    pointsInteret.none { it.id == p.id }
                                })
                                finaliserChargementPois()
                            },
                            onError = {
                                Log.e("MainActivity", "Erreur chargement POIs modérateur")
                                finaliserChargementPois()
                            }
                        )
                    } else {
                        poiRepository.chargerMesPois(uid,
                            onSuccess = { mesPois ->
                                pointsInteret.addAll(mesPois.filter { p ->
                                    pointsInteret.none { it.id == p.id }
                                })
                                finaliserChargementPois()
                            },
                            onError = {
                                Log.e("MainActivity", "Erreur chargement mes POIs")
                                finaliserChargementPois()
                            }
                        )
                    }
                } else {
                    finaliserChargementPois()
                }
            },
            onError = {
                Toast.makeText(this, "Erreur chargement POIs : ${it.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun finaliserChargementPois() {
        if (::mMap.isInitialized) {
            mapManager.rafraichirCarte(
                mMap, pointsInteret, poisLusIds,
                pointsDejaDeclenches, currentLocation, currentAzimuth
            )
        }
        pointsInteretCharges = true
    }

    // =========================================================================
    // Réaffichage des POIs
    // =========================================================================

    private fun afficherDialogReafficher() {
        val options = arrayOf(
            "Réafficher les anecdotes d'un secteur",
            "Réafficher les anecdotes depuis une date",
            "Réafficher toutes les anecdotes"
        )
        AlertDialog.Builder(this)
            .setTitle("Réafficher des anecdotes")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> reafficherParSecteur()
                    1 -> reafficherDepuisDate()
                    2 -> reafficherTout()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Envoie les IDs à réafficher au service et met à jour la carte locale.
     * poisLusIds local est mis à jour pour que la carte reflète l'état correct.
     */
    private fun envoyerReaffichageAuService(ids: Set<String>) {
        poisLusIds.removeAll(ids)
        pointsDejaDeclenches.removeAll(ids)

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(LocationService.ACTION_REAFFICHER_POIS).apply {
                putExtra("ids", HashSet(ids))
            }
        )

        if (::mMap.isInitialized) {
            mapManager.rafraichirCarte(
                mMap, pointsInteret, poisLusIds,
                pointsDejaDeclenches, currentLocation, currentAzimuth
            )
        }
    }

    private fun reafficherParSecteur() {
        val location = currentLocation ?: run {
            Toast.makeText(this, "Localisation indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        val rayons       = arrayOf("100 mètres", "500 mètres", "1 kilomètre", "5 kilomètres")
        val rayonsMetres = listOf(100f, 500f, 1000f, 5000f)

        AlertDialog.Builder(this)
            .setTitle("Choisissez un rayon")
            .setItems(rayons) { _, which ->
                val rayon = rayonsMetres[which]
                val poisDansLeRayon = pointsInteret.filter { poi ->
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        poi.position.latitude, poi.position.longitude,
                        results
                    )
                    results[0] <= rayon
                }.map { it.id }.toSet()

                envoyerReaffichageAuService(poisDansLeRayon)
                Toast.makeText(
                    this,
                    "${poisDansLeRayon.size} anecdote(s) réaffichée(s) dans ce secteur",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun reafficherDepuisDate() {
        val uid      = authManager.getCurrentUser()?.uid ?: return
        val periodes = arrayOf("Aujourd'hui", "Cette semaine", "Ce mois-ci", "Cette année")
        val calendar = java.util.Calendar.getInstance()

        AlertDialog.Builder(this)
            .setTitle("Réafficher depuis...")
            .setItems(periodes) { _, which ->
                calendar.time = java.util.Date()
                when (which) {
                    0 -> calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    1 -> calendar.add(java.util.Calendar.DAY_OF_YEAR, -7)
                    2 -> calendar.add(java.util.Calendar.MONTH, -1)
                    3 -> calendar.add(java.util.Calendar.YEAR, -1)
                }
                poiRepository.chargerPoisLusDepuisDate(uid, calendar.time,
                    onSuccess = { ids ->
                        envoyerReaffichageAuService(ids)
                        Toast.makeText(this, "${ids.size} anecdote(s) réaffichée(s)", Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun reafficherTout() {
        AlertDialog.Builder(this)
            .setTitle("Réafficher toutes les anecdotes")
            .setMessage("Toutes les anecdotes déjà lues seront réaffichées temporairement. Votre historique de lecture reste conservé.")
            .setPositiveButton("Confirmer") { _, _ ->
                envoyerReaffichageAuService(HashSet(poisLusIds))
                Toast.makeText(this, "Toutes les anecdotes sont réaffichées", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =========================================================================
    // Dialogs POI (ajout / édition / modération)
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
                    onError = {
                        Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEditMyPoiDialog(poi: PointInteret) {
        val dialogView  = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage = dialogView.findViewById<EditText>(R.id.editPoiMessage).apply {
            setText(poi.message)
        }
        dialogView.findViewById<CheckBox>(R.id.checkApproved).visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("Modifier votre brouillon")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                poiRepository.mettreAJourPoi(
                    poi.id,
                    mapOf("message" to editMessage.text.toString().trim()),
                    onSuccess = {
                        Toast.makeText(this, "Brouillon mis à jour", Toast.LENGTH_SHORT).show()
                        chargerPointsInteret()
                    },
                    onError = {
                        Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNeutralButton("Proposer à la modération") { _, _ ->
                poiRepository.mettreAJourPoi(
                    poi.id,
                    mapOf("message" to editMessage.text.toString().trim(), "status" to "proposed"),
                    onSuccess = {
                        Toast.makeText(this, "POI proposé à la modération !", Toast.LENGTH_SHORT).show()
                        chargerPointsInteret()
                    },
                    onError = {
                        Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showModerationDialog() {
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
                val titles = pendingPois.mapIndexed { i, p ->
                    "${i + 1}. ${p.message.take(40)}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Points à modérer")
                    .setItems(titles) { _, which -> showPoiEditDialog(pendingPois[which]) }
                    .setNegativeButton("Fermer", null)
                    .show()
            }
    }

    private fun showPoiEditDialog(poi: PendingPoi) {
        val dialogView    = layoutInflater.inflate(R.layout.dialog_edit_poi, null)
        val editMessage   = dialogView.findViewById<EditText>(R.id.editPoiMessage).apply {
            setText(poi.message)
        }
        val checkApproved = dialogView.findViewById<CheckBox>(R.id.checkApproved).apply {
            isChecked = false
            text      = "Valider cette anecdote"
        }
        AlertDialog.Builder(this)
            .setTitle("Modérer POI")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->
                val updates = mutableMapOf<String, Any>(
                    "message" to editMessage.text.toString().trim()
                )
                if (checkApproved.isChecked) {
                    updates["status"]   = "validated"
                    updates["approved"] = true
                }
                poiRepository.mettreAJourPoi(poi.id, updates,
                    onSuccess = {
                        val msg = if (checkApproved.isChecked) "POI validé !" else "POI mis à jour"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (checkApproved.isChecked) chargerPointsInteret()
                    },
                    onError = {
                        Toast.makeText(this, "Erreur : ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            if (locationCallback == null) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation ?: return
                        currentLocation = location
                        if (::mMap.isInitialized) {
                            mapManager.updateLocationMarker(mMap, location, currentAzimuth)
                        }
                    }
                }
            }
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                fusedLocationClient.requestLocationUpdates(
                    locationRequest, it, Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Erreur permission localisation : ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
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
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}