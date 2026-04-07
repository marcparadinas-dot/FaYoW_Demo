import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import com.example.fayowdemo.PointInteret
import com.example.fayowdemo.PoiStatus
class PoiManager(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    // Utilise PointInteret du package (plus besoin de le redéfinir ici)
    fun chargerPoisValidated(onComplete: (List<PointInteret>) -> Unit) {
        firestore.collection("pois")
            .whereEqualTo("status", "validated")
            .get()
            .addOnSuccessListener { result ->
                val pois = mutableListOf<PointInteret>()
                for (doc in result) {
                    val lat = doc.getDouble("lat") ?: continue
                    val lng = doc.getDouble("lng") ?: continue
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(lat, lng),
                        message = doc.getString("message") ?: "",
                        status = PoiStatus.VALIDATED,
                        creatorUid = doc.getString("creatorUid")
                    )
                    pois.add(poi)
                }
                onComplete(pois)
            }
            .addOnFailureListener { e ->
                Log.e("PoiManager", "Erreur chargement POIs validés: ${e.message}")
                onComplete(emptyList())
            }
    }

    // Charge les POIs PROPOSED (pour modérateurs)
    fun chargerPoisProposed(onComplete: (List<PointInteret>) -> Unit) {
        firestore.collection("pois")
            .whereEqualTo("status", "proposed")
            .get()
            .addOnSuccessListener { result ->
                val pois = mutableListOf<PointInteret>()
                for (doc in result) {
                    val lat = doc.getDouble("lat") ?: continue
                    val lng = doc.getDouble("lng") ?: continue
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(lat, lng),
                        message = doc.getString("message") ?: "",
                        status = PoiStatus.PROPOSED,
                        creatorUid = doc.getString("creatorUid")
                    )
                    pois.add(poi)
                }
                onComplete(pois)
            }
            .addOnFailureListener { e ->
                Log.e("PoiManager", "Erreur chargement POIs proposés: ${e.message}")
                onComplete(emptyList())
            }
    }

    // Charge les POIs INITIATED/PROPOSED de l'utilisateur
    fun chargerPoisUtilisateur(uid: String, onComplete: (List<PointInteret>) -> Unit) {
        firestore.collection("pois")
            .whereEqualTo("creatorUid", uid)
            .whereIn("status", listOf("initiated", "proposed"))
            .get()
            .addOnSuccessListener { result ->
                val pois = mutableListOf<PointInteret>()
                for (doc in result) {
                    val lat = doc.getDouble("lat") ?: continue
                    val lng = doc.getDouble("lng") ?: continue
                    val status = when (doc.getString("status")) {
                        "initiated" -> PoiStatus.INITIATED
                        "proposed" -> PoiStatus.PROPOSED
                        else -> PoiStatus.VALIDATED
                    }
                    val poi = PointInteret(
                        id = doc.getString("id") ?: doc.id,
                        position = LatLng(lat, lng),
                        message = doc.getString("message") ?: "",
                        status = status,
                        creatorUid = uid
                    )
                    pois.add(poi)
                }
                onComplete(pois)
            }
            .addOnFailureListener { e ->
                Log.e("PoiManager", "Erreur chargement POIs utilisateur: ${e.message}")
                onComplete(emptyList())
            }
    }

    // Ajoute un POI (pour remplacer ajouterPointInteret)
    fun ajouterPointInteret(
        lat: Double,
        lng: Double,
        message: String,
        uid: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val id = System.currentTimeMillis().toString()
        val poiData = hashMapOf(
            "id" to id,
            "lat" to lat,
            "lng" to lng,
            "message" to message,
            "creatorUid" to uid,
            "createdAt" to com.google.firebase.Timestamp.now(),
            "status" to "initiated",
            "approved" to false
        )

        firestore.collection("pois")
            .document(id)
            .set(poiData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e) }
    }
    fun chargerPoisLus(uid: String, onComplete: (Set<String>) -> Unit) {
        firestore.collection("users")
            .document(uid)
            .collection("readPois")
            .get()
            .addOnSuccessListener { result ->
                val poisLusIds = result.documents.map { it.id }.toSet()
                onComplete(poisLusIds)
            }
            .addOnFailureListener { e ->
                Log.e("PoiManager", "Erreur chargement POIs lus: ${e.message}")
                onComplete(emptySet())
            }
    }
}