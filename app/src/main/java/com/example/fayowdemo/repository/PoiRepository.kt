package com.example.fayowdemo.repository

import android.util.Log
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiData
import com.example.fayowdemo.model.PoiStatus
import com.example.fayowdemo.model.poiStatusFromFirestore
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class PoiRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // -------------------------------------------------------------------------
    // Chargement des POIs
    // -------------------------------------------------------------------------

    /** Charge tous les POIs validés (visibles par tous les utilisateurs). */
    fun chargerPoisValides(
        onSuccess: (List<PointInteret>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("pois")
            .whereEqualTo("status", "validated")
            .get()
            .addOnSuccessListener { result ->
                val pois = result.map { doc ->
                    PointInteret(
                        id         = doc.getString("id") ?: doc.id,
                        position   = LatLng(doc.getDouble("lat") ?: 0.0, doc.getDouble("lng") ?: 0.0),
                        message    = doc.getString("message") ?: "",
                        status     = PoiStatus.VALIDATED,
                        creatorUid = doc.getString("creatorUid")
                    )
                }
                Log.d("PoiRepository", "${pois.size} POIs VALIDATED chargés")
                onSuccess(pois)
            }
            .addOnFailureListener { onError(it) }
    }

    /** Charge les POIs d'un utilisateur (INITIATED + PROPOSED). */
    fun chargerMesPois(
        uid: String,
        onSuccess: (List<PointInteret>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("pois")
            .whereEqualTo("creatorUid", uid)
            .whereIn("status", listOf("initiated", "proposed"))
            .get()
            .addOnSuccessListener { result ->
                val pois = result.map { doc ->
                    PointInteret(
                        id         = doc.getString("id") ?: doc.id,
                        position   = LatLng(doc.getDouble("lat") ?: 0.0, doc.getDouble("lng") ?: 0.0),
                        message    = doc.getString("message") ?: "",
                        status     = poiStatusFromFirestore(
                            doc.getBoolean("approved"),
                            doc.getString("status")
                        ),
                        creatorUid = doc.getString("creatorUid")
                    )
                }
                Log.d("PoiRepository", "${pois.size} POIs personnels chargés")
                onSuccess(pois)
            }
            .addOnFailureListener { onError(it) }
    }

    /** Charge tous les POIs PROPOSED + les POIs perso du modérateur. */
    fun chargerPoisPourModerateur(
        uid: String,
        onSuccess: (List<PointInteret>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("pois")
            .whereEqualTo("status", "proposed")
            .get()
            .addOnSuccessListener { result ->
                val poisProposed = result.map { doc ->
                    PointInteret(
                        id         = doc.getString("id") ?: doc.id,
                        position   = LatLng(doc.getDouble("lat") ?: 0.0, doc.getDouble("lng") ?: 0.0),
                        message    = doc.getString("message") ?: "",
                        status     = PoiStatus.PROPOSED,
                        creatorUid = doc.getString("creatorUid")
                    )
                }
                Log.d("PoiRepository", "${poisProposed.size} POIs PROPOSED chargés")
                // On enchaîne avec les POIs perso du modérateur
                chargerMesPois(
                    uid       = uid,
                    onSuccess = { mesPois -> onSuccess(poisProposed + mesPois) },
                    onError   = onError
                )
            }
            .addOnFailureListener { onError(it) }
    }

    /** Charge les POIs approuvés pour le cache mémoire du LocationService. */
    fun chargerPoisApprouves(
        onSuccess: (Map<String, PoiData>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("pois")
            .whereIn("status", listOf("validated", "proposed")) // ← modifié
            .get()
            .addOnSuccessListener { result ->
                val poiMap = mutableMapOf<String, PoiData>()
                for (doc in result) {
                    val statusString = doc.getString("status") ?: PoiStatus.VALIDATED.name
                    val status = try {
                        PoiStatus.valueOf(statusString.uppercase())
                    } catch (e: IllegalArgumentException) {
                        PoiStatus.VALIDATED
                    }
                    if (status == PoiStatus.INITIATED) continue

                    val lat     = doc.getDouble("lat") ?: continue
                    val lng     = doc.getDouble("lng") ?: continue
                    val message = doc.getString("message") ?: continue
                    poiMap[doc.id] = PoiData(lat, lng, message, status) // ← on passe le statut
                }
                Log.d("PoiRepository", "${poiMap.size} POIs chargés pour le cache")
                onSuccess(poiMap)
            }
            .addOnFailureListener { onError(it) }
    }

    // -------------------------------------------------------------------------
    // Historique de lecture (readPois)
    // -------------------------------------------------------------------------

    /** Charge les IDs des POIs déjà lus par l'utilisateur. */
    fun chargerPoisLus(
        uid: String,
        onSuccess: (Set<String>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .get()
            .addOnSuccessListener { result ->
                val ids = result.map { it.id }.toSet()
                Log.d("PoiRepository", "${ids.size} POIs lus chargés")
                onSuccess(ids)
            }
            .addOnFailureListener { onError(it) }
    }

    /** Marque un POI comme lu pour l'utilisateur courant. */
    fun marquerPoiCommeLu(
        uid: String,
        poiId: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .document(poiId)
            .set(mapOf("read" to true, "readAt" to Timestamp.now()))
            .addOnSuccessListener {
                Log.d("PoiRepository", "POI $poiId marqué comme lu pour $uid")
                onSuccess()
            }
            .addOnFailureListener { onError(it) }
    }

    /** Supprime tous les POIs lus de Firestore (réinitialisation complète). */
    fun reinitialiserPoisLus(
        uid: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .get()
            .addOnSuccessListener { result ->
                val batch = firestore.batch()
                result.forEach { batch.delete(it.reference) }
                batch.commit()
                    .addOnSuccessListener {
                        Log.d("PoiRepository", "${result.size()} POIs lus supprimés")
                        onSuccess()
                    }
                    .addOnFailureListener { onError(it) }
            }
            .addOnFailureListener { onError(it) }
    }

    // -------------------------------------------------------------------------
    // Création et modification de POIs
    // -------------------------------------------------------------------------

    /** Crée un nouveau POI en brouillon (INITIATED) à la position donnée. */
    fun ajouterPoi(
        latitude: Double,
        longitude: Double,
        message: String,
        creatorUid: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val id = System.currentTimeMillis().toString()
        val data = hashMapOf(
            "id"         to id,
            "lat"        to latitude,
            "lng"        to longitude,
            "message"    to message,
            "creatorUid" to creatorUid,
            "createdAt"  to Timestamp.now(),
            "status"     to "initiated",
            "approved"   to false
        )
        firestore.collection("pois").document(id)
            .set(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(it) }
    }

    /** Met à jour le message et/ou le statut d'un POI existant. */
    fun mettreAJourPoi(
        poiId: String,
        updates: Map<String, Any>,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        firestore.collection("pois").document(poiId)
            .update(updates)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(it) }
    }
}