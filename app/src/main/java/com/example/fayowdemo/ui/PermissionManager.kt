package com.example.fayowdemo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: AppCompatActivity) {

    // Indique si une demande de permission est déjà en cours
    private var isRequestingPermissions = false

    // Callback appelé quand toutes les permissions sont accordées
    var onAllPermissionsGranted: (() -> Unit)? = null

    // Callback appelé quand la permission background est refusée
    // (l'app peut quand même fonctionner, mais de façon limitée)
    var onBackgroundPermissionDenied: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Launchers — à appeler dans onCreate() de l'Activity
    // -------------------------------------------------------------------------

    /**
     * Crée et retourne les deux launchers de permission.
     * À appeler UNE SEULE FOIS dans onCreate(), avant tout autre code.
     *
     * Exemple d'utilisation dans MainActivity :
     *   val (fineLauncher, backgroundLauncher) = permissionManager.creerLaunchers()
     *   permissionManager.enregistrerLaunchers(fineLauncher, backgroundLauncher)
     */
    fun creerLaunchers(): Pair<ActivityResultLauncher<String>, ActivityResultLauncher<String>> {
        val fineLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            isRequestingPermissions = false
            if (isGranted) {
                Log.d("PermissionManager", "Permission FINE accordée")
                demanderBackgroundLocation()
            } else {
                Log.e("PermissionManager", "Permission FINE refusée")
                val realState = ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (realState != PackageManager.PERMISSION_GRANTED &&
                    !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                ) {
                    afficherDialogParametres()
                } else {
                    Toast.makeText(
                        activity,
                        "La localisation est essentielle pour l'application.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val backgroundLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d("PermissionManager", "Permission BACKGROUND accordée")
                onAllPermissionsGranted?.invoke()
            } else {
                Log.w("PermissionManager", "Permission BACKGROUND refusée")
                Toast.makeText(
                    activity,
                    "Les alertes en arrière-plan ne fonctionneront pas quand l'écran est éteint.",
                    Toast.LENGTH_LONG
                ).show()
                onBackgroundPermissionDenied?.invoke()
            }
        }

        return Pair(fineLauncher, backgroundLauncher)
    }

    private lateinit var fineLocationLauncher: ActivityResultLauncher<String>
    private lateinit var backgroundLocationLauncher: ActivityResultLauncher<String>

    /** Enregistre les launchers créés par creerLaunchers(). */
    fun enregistrerLaunchers(
        fineLauncher: ActivityResultLauncher<String>,
        backgroundLauncher: ActivityResultLauncher<String>
    ) {
        fineLocationLauncher = fineLauncher
        backgroundLocationLauncher = backgroundLauncher
    }

    // -------------------------------------------------------------------------
    // Vérification des permissions
    // -------------------------------------------------------------------------

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pas nécessaire avant Android 10
        }
    }

    fun hasAllLocationPermissions(): Boolean {
        return hasFineLocationPermission() && hasBackgroundLocationPermission()
    }

    // -------------------------------------------------------------------------
    // Demandes de permissions
    // -------------------------------------------------------------------------

    /** Point d'entrée : démarre la chaîne de demandes de permissions. */
    fun demanderPermissions() {
        if (isRequestingPermissions) {
            Log.d("PermissionManager", "Demande déjà en cours, ignorée")
            return
        }
        when {
            hasFineLocationPermission() && hasBackgroundLocationPermission() -> {
                onAllPermissionsGranted?.invoke()
            }
            hasFineLocationPermission() -> {
                demanderBackgroundLocation()
            }
            else -> {
                demanderFineLocation()
            }
        }
    }

    private fun demanderFineLocation() {
        isRequestingPermissions = true
        Log.d("PermissionManager", "Demande ACCESS_FINE_LOCATION")

        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(activity)
                .setTitle("Permission de localisation")
                .setMessage("Cette application a besoin d'accéder à votre position pour vous guider vers les points d'intérêt. Veuillez valider 'Toujours autoriser'.")
                .setPositiveButton("Autoriser") { _, _ ->
                    fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Refuser") { _, _ ->
                    isRequestingPermissions = false
                    Toast.makeText(
                        activity,
                        "L'application ne peut pas fonctionner sans localisation.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .show()
        } else {
            fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun demanderBackgroundLocation() {
        Log.d("PermissionManager", "Demande ACCESS_BACKGROUND_LOCATION")

        if (hasBackgroundLocationPermission()) {
            onAllPermissionsGranted?.invoke()
            return
        }

        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            AlertDialog.Builder(activity)
                .setTitle("Cocher 'Toujours autoriser'...")
                .setMessage("...dans l'écran suivant autorisant la position, puis revenir en arrière. Cette permission permet à l'application de vous alerter même quand l'écran est éteint.")
                .setPositiveButton("OK") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Annuler") { _, _ ->
                    Toast.makeText(
                        activity,
                        "Les alertes ne fonctionneront pas quand l'écran est éteint.",
                        Toast.LENGTH_LONG
                    ).show()
                    onBackgroundPermissionDenied?.invoke()
                }
                .show()
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun afficherDialogParametres() {
        AlertDialog.Builder(activity)
            .setTitle("Permission requise")
            .setMessage("Vous avez refusé définitivement la permission de localisation. Pour utiliser l'application, vous devez l'activer manuellement dans les paramètres.")
            .setPositiveButton("Ouvrir les paramètres") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Quitter") { _, _ ->
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }
}