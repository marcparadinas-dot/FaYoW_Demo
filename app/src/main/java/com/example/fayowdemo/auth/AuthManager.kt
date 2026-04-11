package com.example.fayowdemo.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fayowdemo.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.app
interface AuthActions {
    fun onSignUp(email: String, password_input: String)
    fun onSignIn(email: String, password_input: String)
}
class AuthManager(private val activity: AppCompatActivity) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // Emails autorisés à modérer
    private val moderatorEmails = listOf(
        "marc.paradinas@gmail.com",
        "marc.paradinas@wanadoo.fr"
    )

    var isModerator: Boolean = false
        private set

    // Callbacks
    var onSignInSuccess: (() -> Unit)? = null
    var onSignUpSuccess: (() -> Unit)? = null
    var onSignOutComplete: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // État de l'authentification
    // -------------------------------------------------------------------------

    fun getCurrentUser() = auth.currentUser

    fun isUserLoggedIn() = auth.currentUser != null

    // -------------------------------------------------------------------------
    // Connexion / Inscription / Déconnexion
    // -------------------------------------------------------------------------

    fun signIn(email: String, password: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(activity, "Pas de connexion Internet", Toast.LENGTH_SHORT).show()
            return
        }

        resetFirebaseState()
        Log.d("AuthManager", "Tentative de connexion avec $email")

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    Log.d("AuthManager", "Connexion réussie pour ${auth.currentUser?.email}")
                    Toast.makeText(activity, "Connexion réussie", Toast.LENGTH_SHORT).show()
                    onSignInSuccess?.invoke()
                } else {
                    val message = getAuthErrorMessage(task.exception)
                    Log.e("AuthManager", "Erreur connexion : ${task.exception?.message}")
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    fun signUp(email: String, password: String) {
        if (!isNetworkAvailable()) {
            Toast.makeText(activity, "Pas de connexion Internet", Toast.LENGTH_SHORT).show()
            return
        }

        resetFirebaseState()
        Log.d("AuthManager", "Tentative d'inscription avec $email")

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    Log.d("AuthManager", "Compte créé pour ${auth.currentUser?.email}")
                    Toast.makeText(activity, "Compte créé avec succès", Toast.LENGTH_SHORT).show()
                    onSignUpSuccess?.invoke()
                } else {
                    val message = getSignUpErrorMessage(task.exception)
                    Log.e("AuthManager", "Erreur inscription : ${task.exception?.message}")
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    fun signOut() {
        Log.d("AuthManager", "Déconnexion demandée")
        auth.signOut()
        isModerator = false
        onSignOutComplete?.invoke()
    }

    // -------------------------------------------------------------------------
    // Modération
    // -------------------------------------------------------------------------

    /** Vérifie si l'utilisateur connecté est modérateur et met à jour le bouton. */
    fun checkIfModerator() {
        val email = auth.currentUser?.email
        isModerator = email != null && moderatorEmails.contains(email)

        Log.d("AuthManager", "Email : $email | isModerator : $isModerator")

        val btnModeration = activity.findViewById<Button>(R.id.btnModeration)
        btnModeration?.visibility = if (isModerator) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------------
    // Utilitaires privés
    // -------------------------------------------------------------------------

    private fun resetFirebaseState() {
        Log.d("AuthManager", "Réinitialisation de l'état Firebase")
        firestore.clearPersistence()
            .addOnSuccessListener { Log.d("AuthManager", "Cache Firestore nettoyé") }
            .addOnFailureListener { Log.e("AuthManager", "Erreur nettoyage cache : ${it.message}") }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
        }
    }

    private fun getAuthErrorMessage(exception: Exception?): String {
        return when (exception) {
            is com.google.firebase.auth.FirebaseAuthInvalidUserException ->
                "Cet utilisateur n'existe pas."
            is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException ->
                "Mot de passe incorrect."
            is com.google.firebase.FirebaseNetworkException ->
                "Problème de connexion réseau."
            else ->
                "Erreur de connexion : ${exception?.message ?: "Vérifiez vos identifiants."}"
        }
    }

    private fun getSignUpErrorMessage(exception: Exception?): String {
        return when (exception) {
            is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                "Cet email est déjà utilisé."
            is com.google.firebase.auth.FirebaseAuthWeakPasswordException ->
                "Mot de passe trop faible (6 caractères minimum)."
            is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException ->
                "Format d'email invalide."
            is com.google.firebase.FirebaseNetworkException ->
                "Problème de connexion réseau."
            else ->
                "Erreur d'inscription : ${exception?.message ?: "Vérifiez vos informations."}"
        }
    }
}