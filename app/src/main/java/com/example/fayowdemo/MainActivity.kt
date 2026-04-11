package com.example.fayowdemo

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

// Point d'int r t avec texte associ
data class PointInteret(
    val id: String,
    val position: LatLng,
    val message: String,
    val status: PoiStatus = PoiStatus.VALIDATED,  // ? Ajout du champ status
    val creatorUid: String? = null
)

// POI en attente de mod ration
data class PendingPoi(
    val id: String,
    val message: String
)
enum class PoiStatus {
    INITIATED,   // Brouillon personnel
    PROPOSED,    // Propos    la mod ration
    VALIDATED    // Valid  par mod rateur
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
    // Dans MainActivity.kt
// --- AJOUTER CETTE PROPRI T    LA CLASSE ---
    private val poiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sncf.fayow.POI_LU") {
                val poiId = intent.getStringExtra("poiId")
                poiId?.let { id ->
                    Log.d("MainActivity", "R ception du POI lu : $id")
                    // Suppression du cercle de la carte
                    poiCircles[id]?.remove() // Supprime le cercle visuellement
                    poiCircles.remove(id)    // Retire l'entr e de notre map
                    // Mise   jour de poisLusIds si n cessaire pour  viter de le re-charger
                    if (!poisLusIds.contains(id)) {
                        poisLusIds.add(id)
                    }
                }
            }
        }
    }
    private var poisLusLoaded = false
    private var isRequestingPermissions = false  // ?? NOUVEAU FLAG
    private val poisLusIds = mutableSetOf<String>()
    // ? Launcher pour demander la permission localisation FINE ( tape 1)
    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isRequestingPermissions = false  // ?? R INITIALISER LE FLAG

        if (isGranted) {
            Log.d("MainActivity", "? Permission FINE accord e via Launcher")
            requestBackgroundLocationPermission()
        } else {
            Log.e("MainActivity", "? Permission FINE refus e via Launcher")

            // V rifier L' TAT R EL de la permission (pas juste shouldShowRequestPermissionRationale)
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
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission requise")
            .setMessage("Vous avez refus  d finitivement la permission de localisation. Pour utiliser l'application, vous devez l'activer manuellement dans les param tres.")
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
    // ? Launcher pour demander la permission localisation arri re-plan ( tape 2)
    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "? Permission BACKGROUND accord e via Launcher")
        } else {
            Log.w("MainActivity", "?? Permission BACKGROUND refus e via Launcher (fonctionnalit s limit es)")
            Toast.makeText(
                this,
                "Les alertes en arri re-plan ne fonctionneront pas quand l' cran est  teint.",
                Toast.LENGTH_LONG
            ).show()
        }
        // QU'ELLE SOIT ACCORD E OU REFUS E, ON D MARRE LES SERVICES APR S CETTE  TAPE
        startAppFeatures(); // ?? NOUVEL APPEL
    }
    private fun startAppFeatures() {
        // S'assurer que l'authentification est OK avant de d marrer
        if (!isAuthenticated) {
            Log.w("MainActivity", "StartAppFeatures appel e mais utilisateur non authentifi .")
            // G rer le cas o  l'utilisateur n'est pas encore connect
            // Peut- tre attendre l' v nement de connexion pour appeler startAppFeatures()
            return;
        }

        Log.d("MainActivity", "D marrage des fonctionnalit s de l'application...")

        // Initialiser la carte si elle ne l'est pas encore
        if (!::mMap.isInitialized) {
            initializeMapView()
        }

        // D marrer les mises   jour de localisation si la permission FINE est l
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            Log.w("MainActivity", "Impossible de d marrer les mises   jour de localisation: permission FINE manquante.")
        }

        // D marrer le service de localisation (il g rera lui-m me les restrictions BACKGROUND)
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
    // Map pour stocker les cercles des POIs, avec leur ID comme cl
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
    private val pointsInteret = mutableListOf<PointInteret>()
    private val pointsDejaDeclenches = mutableSetOf<String>()
    private var currentLocation: Location? = null
    private var isAuthenticated by mutableStateOf(false)
    private lateinit var textToSpeech: TextToSpeech
    private var currentDialog: AlertDialog? = null
    private var isSpeakingPoi = false  // Indique si un POI est en cours de lecture
    private var currentPoiId: String? = null  // ID du POI actuellement lu
    private var pendingPoi: PointInteret? = null  // POI en attente (un seul   la fois)
    /*private var hasShownPermissionDeniedToast = false*/

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
            android.util.Log.d("TTS", "Moteur TTS arr t  et lib r .")
        }
        super.onDestroy()
        stopLocationService()
    }
    /*
        private fun ajouterPoisDeTest() {
            val firestore =
                FirebaseFirestore.getInstance() // Pour ajout de points en masse, utiliser ce format et r activer le "ajouter points de tests" dans oncreate

            val poisATester = listOf(
                // Rue de Lappe
                mapOf(
                    "lat" to 48.8539240,
                    "lng" to 2.3722890,
                    "message" to "Ici, vous  tes   l extr mit  ouest de la rue de Lappe : remarquez la perspective vers la place de la Bastille et l organisation typique des rues du faubourg Saint-Antoine.",
                    "approved" to true,
                    "creatorUid" to "i7IiNZfZdLXtRzjauduvHGUtLMt1",
                    "createdAt" to com.google.firebase.Timestamp.now()
                ),
                mapOf(
                    "lat" to 48.8538500,
                    "lng" to 2.3725000,
                    "message" to "Observez l'architecture des immeubles du d but du XXe si cle, souvent avec des fa ades en pierre de taille et des ornements discrets, t moins de l' volution urbaine du quartier.",
                    "approved" to true,
                    "creatorUid" to "i7IiNZfZdLXtRzjauduvHGUtLMt1",
                    "createdAt" to com.google.firebase.Timestamp.now()
                )
            )

            for (poiData in poisATester) {
                // ? G n re un ID bas  sur le timestamp (comme dans ton app)
                val id = System.currentTimeMillis().toString()  // Ex: "1773079406607"


                // Ajoute l'ID dans les donn es du POI
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


                // ?? Petite pause pour  viter d'avoir le m me timestamp pour 2 POIs
                Thread.sleep(10)  // Pause de 10ms entre chaque cr ation
            }
        }
    */
    // ? Fonction pour v rifier si ACCESS_FINE_LOCATION est accord e
    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ? Fonction pour v rifier si ACCESS_BACKGROUND_LOCATION est accord e
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
            Log.d("MainActivity", "?? Demande de permission d j  en cours, ignor e.")
            return
        }

        isRequestingPermissions = true
        Log.d("MainActivity", "?? Demande de permission ACCESS_FINE_LOCATION")

        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.d("MainActivity", "?? Affichage d'une explication avant de demander la permission")
            AlertDialog.Builder(this)
                .setTitle("Permission de localisation")
                .setMessage("Cette application a besoin d'acc der   votre position   tout moment pour vous guider vers les  points d'int r t. Veuillez valider 'toujours autoriser'")
                .setPositiveButton("Autoriser") { _, _ ->
                    fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Refuser") { _, _ ->
                    isRequestingPermissions = false  // ?? R initialiser le flag
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

        // V rifier si la permission est d j  accord e
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "?? Permission BACKGROUND d j  accord e")
            startLocationService()
            return
        }

        // V rifier si on peut montrer un rationale
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            Log.d("MainActivity", "?? Affichage d'une explication pour BACKGROUND_LOCATION")
            AlertDialog.Builder(this)
                .setTitle("Toujours autoriser l'acc s position...")
                .setMessage("... dans l' cran suivant, puis revenir en arri re. Cette permission permet   l'application de vous alerter m me quand l' cran est  teint.")
                .setPositiveButton("OK") { _, _ ->
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Annuler") { _, _ ->
                    Toast.makeText(
                        this,
                        "Les alertes ne fonctionneront pas quand l' cran est  teint.",
                        Toast.LENGTH_LONG
                    ).show()
                    startLocationService() // D marrer quand m me (mais limit )
                }
                .show()
        } else {
            // Demander directement
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // ? Nouvelle version de startLocationService()
    private fun startLocationService() {
        Log.d("MainActivity", "Tentative de d marrage du LocationService")

        // On v rifie qu'on a au moins la permission FINE_LOCATION
        // La permission BACKGROUND_LOCATION est g r e avant d'appeler cette fonction dans onCreate
        if (!hasFineLocationPermission()) {
            Log.e("MainActivity", "? Impossible de d marrer le service : ACCESS_FINE_LOCATION non accord e.")
            Toast.makeText(this, "Impossible de d marrer le service de localisation sans permission.", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "?? Service d marr  en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "?? Service d marr ")
        }
    }

    // ? NOUVELLE FONCTION : D marrage r el du service
    /*private fun startLocationServiceInternal() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "?? Service d marr  en foreground")
        } else {
            startService(serviceIntent)
            Log.d("MainActivity", "?? Service d marr ")
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
                Log.d("POI", "? ${poisLusIds.size} POIs d j  lus charg s")
                poisLusLoaded = true
                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur chargement POIs lus: ${e.message}")
                poisLusLoaded = true   // on consid re charg  m me en cas d erreur
                onComplete() // on continue quand m me
            }
    }

    private fun chargerPoisDeclenches() {
        val prefs = getSharedPreferences("FayowPrefs", Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet("pois_declenches", emptySet()) ?: emptySet()
        pointsDejaDeclenches.clear()
        pointsDejaDeclenches.addAll(savedSet)
        android.util.Log.d("POI", "Charg s ${pointsDejaDeclenches.size} POIs d j  d clench s depuis les pr f rences.")
        android.util.Log.d("POI", "Contenu de pointsDejaDeclenches : $pointsDejaDeclenches")  // AJOUTE CE LOG


        // 2. Charge les POIs lus depuis Firestore (sous-collection readPois)
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.w("POI", "Utilisateur non connect , impossible de charger les POIs lus")
            return
        }

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(currentUid)
            .collection("readPois")  // ? Utilise la sous-collection (comme dans LocationService)
            .get()
            .addOnSuccessListener { documents ->
                val poisLus = documents.map { it.id }  // R cup re les IDs des POIs lus
                pointsDejaDeclenches.addAll(poisLus)  // Fusionne avec les POIs locaux
                Log.d("POI", "Apr s Firestore, pointsDejaDeclenches : $pointsDejaDeclenches (${pointsDejaDeclenches.size}  l ments)")

                // 3. Sauvegarde le r sultat fusionn  dans les SharedPreferences pour les prochains lancements
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
        android.util.Log.d("POI", "Sauvegarde de ${pointsDejaDeclenches.size} POIs d clench s.")
    }
    /*
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        private fun reinitialiserPoisDeclenches() {
            // Vider la liste en m moire
            pointsDejaDeclenches.clear()

            // Vider ce qui est stock  en local
            val prefs = getSharedPreferences("FayowPrefs", MODE_PRIVATE)
            prefs.edit().remove("pois_declenches").apply()

            // Rafra chir la carte avec la derni re position connue
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
                            // Si aucune position en cache, demander une nouvelle mise   jour
                            requestSingleLocationUpdate()
                        }
                    }
                    .addOnFailureListener {
                        requestSingleLocationUpdate()
                    }
            } else {
                requestLocationPermission()
            }

            Toast.makeText(this, "? Tous les POIs sont   nouveau disponibles !", Toast.LENGTH_SHORT).show()
        }

     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun reinitialiserPoisDeclenches() {
        Log.d("REINIT", "?? D but de la r initialisation")

        // Vider la liste en m moire des POIs d clench s (ceux qui ont d j  parl )
        pointsDejaDeclenches.clear()
        Log.d("REINIT", "? pointsDejaDeclenches vid  (${pointsDejaDeclenches.size}  l ments)")

        // Vider les pr f rences locales (si tu stockes pointsDejaDeclenches l )
        val prefs = getSharedPreferences("FayowPrefs", MODE_PRIVATE)
        prefs.edit().remove("pois_declenches").apply()
        Log.d("REINIT", "? Pr f rences locales vid es pour 'pois_declenches'")

        // ? IMPORTANT : Vider aussi les POIs lus (ceux qui ont  t  marqu s dans Firestore)
        poisLusIds.clear()
        Log.d("REINIT", "? poisLusIds vid  (${poisLusIds.size}  l ments)")

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
                            Log.d("REINIT", "? ${documents.size()} POIs lus supprim s de Firestore")
                        }
                        .addOnFailureListener { e ->
                            Log.e("REINIT", "? Erreur commit suppression Firestore : ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("REINIT", "? Erreur r cup ration POIs lus Firestore : ${e.message}")
                }
        } else {
            Log.w("REINIT", "?? Utilisateur non connect , impossible de supprimer les POIs lus de Firestore.")
        }

        // Rafra chir la carte pour afficher tous les POIs
        Log.d("REINIT", "??? Rafra chissement de la carte avec ${pointsInteret.size} POIs")
        rafraichirCarte(currentLocation)

        Toast.makeText(this, "? Tous les POIs sont   nouveau disponibles !", Toast.LENGTH_SHORT).show()
    }
    /*private fun apresConnexion(uid: String) {
        chargerPoisLus(uid) {
            chargerPointsInteret() // ou ta fonction actuelle de chargement de POIs
        }
    }*/

    private fun marquerPoiCommeLu(uid: String, poiId: String) {
        if (poisLusIds.contains(poiId)) return // d j  fait

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
                Log.d("POI", "? POI $poiId marqu  comme lu pour $uid")
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur lors de la sauvegarde du POI lu: ${e.message}")
            }
    }

    // ? NOUVELLE FONCTION : Demander une seule mise   jour de localisation
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
                            android.util.Log.d("LOCATION", "?? Nouvelle position obtenue : ${newLocation.latitude}, ${newLocation.longitude}")
                        }
                        fusedLocationClient.removeLocationUpdates(this) // Arr ter apr s avoir re u la position
                    }
                },
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("LOCATION", "? Erreur de permission pour la mise   jour GPS : ${e.message}")
        }
    }*/

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
                        "La langue (Fran ais) n'est pas support e ou les donn es sont manquantes."
                    )
                    val installIntent = Intent()
                    installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    startActivity(installIntent)
                    Toast.makeText(
                        this,
                        "Veuillez installer les donn es vocales Fran aises pour la synth se vocale.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.d("TTS", "Moteur TTS initialis  en Fran ais.")
                    isTtsReady = true
                }
            } else {
                Log.e("TTS", " chec de l'initialisation du moteur TTS. Status: $status")
                Toast.makeText(
                    this,
                    " chec de l'initialisation de la synth se vocale.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d("TTS", "D but de la lecture : $utteranceId")
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

            @Deprecated("D pr ci , mais doit  tre impl ment ")
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


        // 5. Nettoyage pr ventif des fragments au d marrage (gard  de ton code)
        if (savedInstanceState != null) {
            val fragment = supportFragmentManager.findFragmentById(R.id.map)
            if (fragment != null) {
                Log.d(
                    "ONCREATE",
                    "?? Fragment de carte trouv  dans savedInstanceState, suppression pr ventive."
                )
                supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
                supportFragmentManager.executePendingTransactions()
            }
        }

        // 6. Charger les POIs d j  d clench s
        chargerPoisDeclenches()

        // 7. V rifie si l'utilisateur est d j  connect  et g re l'UI en cons quence
        val currentUser = auth.currentUser
        if (currentUser != null) {
            Log.d("AUTH", "Utilisateur d j  connect : ${currentUser.email}")
            isAuthenticated = true

            // ? Nettoyer les fragments existants AVANT de charger la vue
            val existingFragment = supportFragmentManager.findFragmentById(R.id.map)
            if (existingFragment != null) {
                Log.d("ONCREATE", "?? Fragment existant d tect , suppression...")
                supportFragmentManager.beginTransaction()
                    .remove(existingFragment)
                    .commitNowAllowingStateLoss() // commitNow pour ex cution imm diate
            }

            // ? Charger la vue principale
            setContentView(R.layout.activity_main)

            // ? Initialiser la carte et les boutons
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

    // --- 2. AJOUTE ONSTART (juste apr s onCreate) ---
    override fun onStart() {
        super.onStart()
        // Enregistre le receiver pour  couter les POIs lus
        val filter = IntentFilter("com.sncf.fayow.POI_LU")
        LocalBroadcastManager.getInstance(this).registerReceiver(poiUpdateReceiver, filter)
    }

    // --- 3. AJOUTE ONSTOP (apr s onStart) ---
    override fun onStop() {
        super.onStop()
        // D senregistre le receiver pour  viter les fuites
        LocalBroadcastManager.getInstance(this).unregisterReceiver(poiUpdateReceiver)
    }

    private fun resetFirebaseState() {
        android.util.Log.d("AUTH", "?? R initialisation de l' tat Firebase")
        auth = Firebase.auth

        firestore.clearPersistence()
            .addOnSuccessListener {
                android.util.Log.d("AUTH", "Cache Firestore nettoy ")
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
        android.util.Log.d("MODERATION", "===== D BUT CHECK MODERATOR =====")
        val email = auth.currentUser?.email
        android.util.Log.d("MODERATION", "Email actuel: $email")
        android.util.Log.d("MODERATION", "Liste mod rateurs: $moderatorEmails")

        isModerator = email != null && moderatorEmails.contains(email)
        android.util.Log.d("MODERATION", "isModerator: $isModerator")
        val btnModeration = findViewById<Button>(R.id.btnModeration)
        if (btnModeration != null) {
            btnModeration.visibility = if (isModerator) View.VISIBLE else View.GONE
            android.util.Log.d("MODERATION", "Bouton mod ration visible: ${btnModeration.visibility == View.VISIBLE}")
        } else {
            android.util.Log.e("MODERATION", "Bouton mod ration non trouv !")
        }
        android.util.Log.d("MODERATION", "===== FIN CHECK MODERATOR =====")
    }

    private fun rafraichirCarte(location: Location?) {
        android.util.Log.d("CARTE", "?? rafraichirCarte appel ")
        if (!::mMap.isInitialized) return
        mMap.clear()
        locationMarker = null
        for (poi in pointsInteret) {

            // ? Ne pas afficher les POIs VALIDATED d j  lus
            //    mais laisser visibles les PROPOSED et INITIATED m me s'ils sont "lus"
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) {
                continue
            }

            val shouldDisplay = when (poi.status) {
                PoiStatus.VALIDATED -> !pointsDejaDeclenches.contains(poi.id)
                PoiStatus.PROPOSED  -> true   // ? cercle vert toujours visible
                PoiStatus.INITIATED -> true   // ? cercle orange toujours visible
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
                        .clickable(poi.status == PoiStatus.INITIATED) // ? Clickable si brouillon
                )

                // ? Stocker l'association cercle ? POI pour g rer les clics
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
            android.util.Log.d("CARTE", "?? Recr ation du marqueur utilisateur")
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
                        Log.d("POI", "?? Tous les POIs lus ont  t  r initialis s pour $uid")
                        onComplete()
                    }
                    .addOnFailureListener { e ->
                        Log.e("POI", "Erreur lors de la r initialisation des POIs lus: ${e.message}")
                        onComplete()
                    }
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur lors de la r cup ration des POIs lus: ${e.message}")
                onComplete()
            }
    }
*/
    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun playPendingPoi(location: Location) {
        val poi = pendingPoi ?: return
        // ? NOUVEAU : V rifie si le POI a d j   t  lu
        if (poisLusIds.contains(poi.id)) {
            Log.d("MainActivity", "POI ${poi.id} d j  lu, ignor  dans playPendingPoi")
            pendingPoi = null  // ? ON VIDE ICI car onDone() ne sera jamais appel
            return
        }
        // ? 1. Ta logique existante (d clenchement,  tat, UI)
        pointsDejaDeclenches.add(poi.id)
        sauvegarderPoisDeclenches()
        rafraichirCarte(location)
        isSpeakingPoi = true
        currentPoiId = poi.id

        // ? 2. Ta pop-up existante
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(poi.message)
            .setPositiveButton("OK", null)
            .setOnDismissListener {
                currentDialog = null
            }
            .create()
        currentDialog?.show()

        // ? 3. Ton annonce vocale existante
        val utteranceId = "poi_message_${poi.id}"
        textToSpeech.speak(
            poi.message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        // ? 4. NOUVEAU : Marque le POI comme "lu" APRES avoir lanc  l'annonce
        //    (mais sans attendre la fin de la lecture)
        val currentUid = auth.currentUser?.uid
        Log.d("DEBUG_UID", "UID actuel : $currentUid") //   placer avant l'appel   marquerPoiCommeLu()
        if (currentUid != null && poi.status == PoiStatus.VALIDATED && !poisLusIds.contains(poi.id)) {
            marquerPoiCommeLu(currentUid, poi.id)  // ? Seuls les violets (VALIDATED) sont marqu s comme lus
            Log.d("POI", "?? POI ${poi.id} marqu  comme lu apr s annonce vocale")
        } else if (currentUid == null) {
            Log.d("POI", "?? Utilisateur non connect  ? POI ${poi.id} non marqu  comme lu")

        } else {
            Log.d("POI", "POI d j  lu ou non VALIDATED (PROPOSED/INITIATED), ignor ")  //
        }

        // ? 5. Ton log existant (que tu m'as mentionn )
        Log.d("TTS", "Lecture du message pour POI ${poi.id} lanc e avec utteranceId: $utteranceId")
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
        android.util.Log.d("TTS", "Lecture du message : ${poi.message}")
    }
*/
    private fun verifierPointsInteret(location: Location) {
        if (!poisLusLoaded) {
            // "poisLusIds pas encore charg , on ne d clenche rien"
            return
        }
        val currentLatLng = LatLng(location.latitude, location.longitude)
        var poiFound = false
        for (poi in pointsInteret) {
// ? Jamais de d clenchement auto pour les brouillons
            if (poi.status == PoiStatus.INITIATED) continue

// ? On ne bloque par poisLusIds que les VALIDATED
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) continue

            // ? NOUVEAU : V rifie aussi si le POI est dans poisLusIds (Firestore)
            if (poisLusIds.contains(poi.id)) {
                Log.d("MainActivity", "POI ${poi.id} d j  lu (depuis Firestore), ignor ")
                continue
            }

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
                    Log.d("MainActivity", "Annonce vocale d j  en cours, POI ${poi.id} ignor ")
                    continue
                }
                if (pendingPoi != null) {
                    Log.d("MainActivity", "Un POI est d j  en attente, POI ${poi.id} ignor ")
                    continue
                }
                pendingPoi = poi
                Log.d("MainActivity", "POI ${poi.id} d tect  et en attente")
                playPendingPoi(location)
                break
            }
        }
        // ? Si aucun POI n'est trouv  et qu'un POI est en attente,
        // cela signifie que l'utilisateur est sorti de la zone avant la lecture
        if (!poiFound && pendingPoi != null) {
            Log.d("MainActivity", "Utilisateur sorti de la zone, pendingPoi annul ")
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
                    Log.d("AUTH", "? Connexion r ussie pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Connexion r ussie", Toast.LENGTH_SHORT).show()
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

                    // ? Initialiser la carte avec un l ger d lai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ? D marrer le service de localisation si les permissions sont OK
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
                        is com.google.firebase.FirebaseNetworkException -> "Probl me de connexion r seau."
                        else -> "Erreur de connexion : ${task.exception?.message ?: "V rifiez vos identifiants."}"
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
                    Log.d("AUTH", "? Compte cr   pour ${auth.currentUser?.email}")
                    Toast.makeText(this, "Compte cr   avec succ s", Toast.LENGTH_SHORT).show()
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

                    // ? Initialiser la carte avec un l ger d lai
                    Handler(Looper.getMainLooper()).postDelayed({
                        initializeMapView()

                        // ? D marrer le service de localisation si les permissions sont OK
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
                        is com.google.firebase.auth.FirebaseAuthUserCollisionException -> "Cet email est d j  utilis ."
                        is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> "Mot de passe trop faible (6 caract res minimum)."
                        is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> "Format d'email invalide."
                        is com.google.firebase.FirebaseNetworkException -> "Probl me de connexion r seau."
                        else -> "Erreur d'inscription : ${task.exception?.message ?: "V rifiez vos informations."}"
                    }
                    Log.e("AUTH", "? Erreur inscription: ${task.exception?.message}")
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun initializeMapView() {
        try {
            // ? Timeout de s curit  pour d tecter les initialisations bloqu es
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e("INIT", "?? Timeout : Initialisation trop longue")
                runOnUiThread {
                    Toast.makeText(this, "Probl me d'initialisation de la carte", Toast.LENGTH_LONG).show()
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

            // ? Charger d'abord les POIs d j  lus par l'utilisateur
            val currentUid = auth.currentUser?.uid
            if (currentUid != null) {
                chargerPoisLus(currentUid) {
                    // ? Une fois les POIs lus charg s, on charge tous les POIs
                    chargerPointsInteret()
                }
            } else {
                // ? Pas connect  ? charge les POIs normalement
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
                else Toast.makeText(this, "Acc s r serv  aux mod rateurs", Toast.LENGTH_SHORT).show()
            }

            // Bouton R afficher tous les POIs
            val btnReafficher = findViewById<Button>(R.id.btnReafficher)
            btnReafficher?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("R initialiser les POIs")
                    .setMessage("Voulez-vous vraiment r afficher tous les points d'int r t ?")
                    .setPositiveButton("Oui") { _, _ ->
                        if (hasFineLocationPermission()) {
                            reinitialiserPoisDeclenches()
                        } else {
                            Toast.makeText(this, "Permission de localisation n cessaire", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }

            // Bouton D connexion
            val btnLogout = findViewById<Button>(R.id.btnLogout)
            btnLogout?.setOnClickListener {
                Log.d("AUTH", "?? D connexion demand e")

                // Arr ter le service de localisation
                stopLocationService()

                // Arr ter les mises   jour de localisation
                stopLocationUpdates()

                // Nettoyage des donn es
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

                // Retour   l' cran d'authentification
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
                    Log.e("LOGOUT", "Erreur lors de la d connexion: ${e.message}", e)
                    Toast.makeText(this, "Erreur critique. Red marrage...", Toast.LENGTH_LONG).show()
                    finish()
                    startActivity(intent)
                }
            }

            // ? V rifier si l'utilisateur est mod rateur
            checkIfModerator()

            // ? Initialiser le client de localisation (si pas d j  fait dans onCreate)
            if (!::fusedLocationClient.isInitialized) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                Log.d("INIT", "?? FusedLocationClient initialis ")
            }

            // ? Initialiser locationRequest (si pas d j  fait)
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build()
                Log.d("INIT", "?? LocationRequest initialis ")
            }

            // ? Initialiser locationCallback (si pas d j  fait)
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation
                        if (location == null) {
                            Log.w("LOCATION", "? Aucune localisation re ue.")
                            return
                        }
                        Log.d("LOCATION", "? Localisation: Lat=${location.latitude}, Lng=${location.longitude}")
                        currentLocation = location
                        updateLocationMarker(location)
                        verifierPointsInteret(location)
                    }
                }
                Log.d("INIT", "?? LocationCallback initialis ")
            }

            // ? Initialiser le fragment de carte Google Maps
            val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
            if (mapFragment != null) {
                mapFragment.getMapAsync(this)
                Log.d("INIT", "??? Fragment de carte initialis ")
            } else {
                Log.e("INIT", "? Fragment de carte non trouv  dans le layout")
                Toast.makeText(this, "Erreur : fragment de carte introuvable", Toast.LENGTH_LONG).show()
            }

            // ? Annuler le timeout (tout s'est bien pass )
            timeoutHandler.removeCallbacks(timeoutRunnable)
            Log.d("INIT", "? Initialisation de la vue termin e avec succ s")

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
        // ? AJOUT : Listener de clic sur les cercles
        mMap.setOnCircleClickListener { circle ->
            val poiId = circle.tag as? String ?: return@setOnCircleClickListener
            val poi = pointsInteret.find { it.id == poiId } ?: return@setOnCircleClickListener

            if (poi.status == PoiStatus.INITIATED) {
                showEditMyPoiDialog(poi)
            }
        }
        // ? FIN AJOUT

        if (!hasFineLocationPermission()) {
            Log.w("MainActivity", "?? Permission de localisation non accord e dans onMapReady")
            // ?? Ne redemander QUE si aucune demande n'est en cours
            if (!isRequestingPermissions) {
                requestFineLocationPermission()
            }
            return
        }
        // ? D sactiver le bouton "Ma position" et la couche de localisation
        mMap.isMyLocationEnabled = false
        googleMap.uiSettings.isMyLocationButtonEnabled = true  // ? Garde le bouton de recentrage
        try {
            googleMap.setMyLocationEnabled(false)  // D sactive d finitivement le marqueur bleu
        } catch (e: SecurityException) {
            Log.e("MAP", "Erreur permission localisation", e)
        }

        // ? Charger les POIs depuis Firestore
        chargerPointsInteret()

        // ? R cup rer la derni re position connue et centrer la carte
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLocation = it
                updateLocationMarker(it)
                rafraichirCarte(it)
                // Centrer la cam ra sur la position actuelle
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(it.latitude, it.longitude),
                    15f
                ))
                Log.d("MainActivity", "?? Carte centr e sur : ${it.latitude}, ${it.longitude}")
            } ?: run {
                Log.w("MainActivity", "?? Aucune position connue dans lastLocation")
            }
        }

        // ? D marrer les mises   jour GPS en temps r el
        startLocationUpdates()

        Log.d("MainActivity", "? Carte Google Maps pr te et configur e")
    }

    private fun onAddPoiClicked() {
        val location = currentLocation
        if (location == null) {
            Toast.makeText(this, "Localisation indisponible", Toast.LENGTH_SHORT).show()
            return
        }
        val editText = EditText(this)
        editText.hint = "Message du point d'int r t"
        AlertDialog.Builder(this)
            .setTitle("Nouveau point d'int r t")
            .setMessage("Entrez le message   afficher   cet endroit:")
            .setView(editText)
            .setPositiveButton("Enregistrer") { _, _ ->
                val message = editText.text.toString().ifBlank { "Point d'int r t" }
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
        val id = System.currentTimeMillis().toString()
        val currentUser = auth.currentUser
        val poiData = hashMapOf(
            "id" to id,
            "lat" to location.latitude,
            "lng" to location.longitude,
            "message" to message,
            "creatorUid" to (currentUser?.uid ?: ""),
            "createdAt" to com.google.firebase.Timestamp.now(),
            "status" to "initiated",  // ? Nouveau : statut "initiated"
            "approved" to false
        )
        firestore.collection("pois")
            .document(id)
            .set(poiData)
            .addOnSuccessListener {
                Toast.makeText(this, "Brouillon de POI enregistr . Vous pouvez le modifier avant de le proposer.", Toast.LENGTH_LONG).show()
                chargerPointsInteret() // ? Recharger pour afficher le nouveau POI INITIATED
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun chargerPointsInteret() {
        val currentUser = auth.currentUser
        val currentUid = currentUser?.uid

        pointsInteret.clear()

        // 1?? Charger les POIs VALIDATED (visibles par tous)
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
                android.util.Log.d("POI", "? ${pointsInteret.size} POIs VALIDATED charg s")
// 2?? Charger les POIs de l'utilisateur (INITIATED + PROPOSED)
                if (currentUid != null) {
                    if (isModerator) {
                        // ? NOUVEAU : Mod rateur voit TOUS les PROPOSED + ses POIs perso
                        chargerPoisProposedPourModerateur(currentUid)
                    } else {
                        // ? EXISTANT : Utilisateur normal voit seulement ses POIs perso
                        chargerMesPoisEnAttente(currentUid)
                    }
                } else {
                    rafraichirCarte(currentLocation) // ? EXISTANT (inchang )
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
                android.util.Log.d("POI", "? ${pointsInteret.size} POIs totaux (dont mes brouillons/propos s)")
                rafraichirCarte(currentLocation)
            }
            .addOnFailureListener { e ->
                android.util.Log.e("POI", "Erreur chargement mes POIs: ${e.message}")
                rafraichirCarte(currentLocation)
            }
    }

    /**
     * Charge TOUS les POIs PROPOSED (pour mod ration) + les POIs perso de l'utilisateur
     */
    private fun chargerPoisProposedPourModerateur(uid: String) {
        // 1?? Charge d'abord TOUS les POIs PROPOSED
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
                    //  vite les doublons (au cas o )
                    if (pointsInteret.none { it.id == poi.id }) {
                        pointsInteret.add(poi)
                    }
                }
                Log.d("POI", "? ${proposedResult.size()} POIs PROPOSED charg s pour mod ration")

                // 2?? Charge ensuite les POIs perso (INITIATED) de l'utilisateur
                chargerMesPoisEnAttente(uid)
            }
            .addOnFailureListener { e ->
                Log.e("POI", "Erreur chargement POIs PROPOSED: ${e.message}")
                // M me en cas d'erreur, charge au moins ses POIs perso
                chargerMesPoisEnAttente(uid)
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

        // Masquer la checkbox du mod rateur (on n'en a pas besoin ici)
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
                        Toast.makeText(this, "Brouillon mis   jour", Toast.LENGTH_SHORT).show()
                        chargerPointsInteret()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNeutralButton("Proposer   la mod ration") { _, _ ->
                val newMessage = editMessage.text.toString().trim()
                firestore.collection("pois").document(poi.id)
                    .update(mapOf(
                        "message" to newMessage,
                        "status" to "proposed"
                    ))
                    .addOnSuccessListener {
                        Toast.makeText(this, "POI propos    la mod ration !", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "Aucun point en attente de mod ration", Toast.LENGTH_SHORT).show()
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
                    .setTitle("Points   mod rer")
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
            .setTitle("Mod rer POI")
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
                        val msg = if (validated) "POI valid  !" else "POI mis   jour"
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
        // ? V rifier la permission avec la nouvelle fonction
        if (!hasFineLocationPermission()) {
            Log.w("LOCATION", "?? Permission de localisation manquante.")
            return
        }

        try {
            // ? Initialiser locationRequest si ce n'est pas d j  fait
            if (!::locationRequest.isInitialized) {
                locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    3000L // Mise   jour toutes les 3 secondes
                )
                    .setMinUpdateIntervalMillis(1000L) // Minimum 1 seconde entre chaque mise   jour
                    .build()

                Log.d("LOCATION", "?? LocationRequest initialis ")
            }

            // ? Initialiser locationCallback si ce n'est pas d j  fait
            if (!::locationCallback.isInitialized) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            currentLocation = location
                            updateLocationMarker(location)
                            Log.d("LOCATION", "?? Position mise   jour : ${location.latitude}, ${location.longitude}")

                            // ? OPTIONNEL : V rifier les POIs proches (si tu veux que MainActivity le fasse aussi)
                            // verifierProximitePois(location)
                        }
                    }
                }
                Log.d("LOCATION", "?? LocationCallback initialis ")
            }

            // ? Arr ter les mises   jour existantes avant d'en d marrer de nouvelles
            fusedLocationClient.removeLocationUpdates(locationCallback)

            // ? D marrer les nouvelles mises   jour
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            Log.d("LOCATION", "?? Mises   jour de localisation d marr es.")
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
            android.util.Log.e("LOCATION", "? mMap non initialis , impossible de cr er/mettre   jour le marqueur.")
            return
        }
        val currentLatLng = LatLng(location.latitude, location.longitude)
        val customMarkerIcon = bitmapDescriptorFromVector(this, R.drawable.outline_arrow_circle_up_24)
        if (locationMarker == null) {
            // Cr ation du marqueur
            android.util.Log.d("LOCATION", "? Cr ation du marqueur de localisation   Lat=${location.latitude}, Lng=${location.longitude}")
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
            // D placer la cam ra vers la position initiale
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16f))
            android.util.Log.d("LOCATION", "?? Cam ra d plac e vers la position initiale.")
        } else {
            // Mise   jour du marqueur existant
            android.util.Log.d("LOCATION", "?? Mise   jour du marqueur de localisation   Lat=${location.latitude}, Lng=${location.longitude}")
            locationMarker?.apply {
                position = currentLatLng
                rotation = currentAzimuth
            }
            // Animer la cam ra pour suivre le d placement (optionnel, peut  tre d sactiv  si trop g nant)
            mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng))
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onResume() {
        super.onResume()

        val uid = auth.currentUser?.uid
        if (uid != null) {
            chargerPoisLus(uid) {
                // Une fois poisLusIds   jour, on rafra chit la carte
                rafraichirCarte(null)
            }
        }

        // Enregistrement des capteurs
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        // Si toutes les permissions sont accord es ET authentifi
        if (hasAllLocationPermissions() && isAuthenticated) {
            startAppFeatures()
        }
        // ?? Ne redemander que si AUCUNE demande en cours ET pas authentifi
        else if (!hasFineLocationPermission() && isAuthenticated && !isRequestingPermissions) {
            requestFineLocationPermission()
        }
    }

    // V rifie si le LocationService est d j  en cours d'ex cution
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

        // Arr ter les capteurs
        sensorManager.unregisterListener(this)

        // Arr ter les mises   jour de localisation de l'activit  pour  conomiser la batterie
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
