package com.example.fayowdemo.model

import com.google.android.gms.maps.model.LatLng

// --- Data classes ---

data class PointInteret(
    val id: String,
    val position: LatLng,
    val message: String,
    val status: PoiStatus = PoiStatus.VALIDATED,
    val creatorUid: String? = null
)

data class PendingPoi(
    val id: String,
    val message: String
)

// Utilisé dans LocationService pour le cache mémoire
data class PoiData(
    val latitude: Double,
    val longitude: Double,
    val message: String,
    val status: PoiStatus = PoiStatus.VALIDATED // ← ajouté
)
// --- Enum ---

enum class PoiStatus {
    INITIATED,   // Brouillon personnel
    PROPOSED,    // Proposé à la modération
    VALIDATED    // Validé par un modérateur
}

// --- Fonction utilitaire ---

fun poiStatusFromFirestore(approved: Boolean?, status: String?): PoiStatus {
    return when (status) {
        "initiated" -> PoiStatus.INITIATED
        "proposed"  -> PoiStatus.PROPOSED
        "validated" -> PoiStatus.VALIDATED
        else        -> if (approved == true) PoiStatus.VALIDATED else PoiStatus.PROPOSED
    }
}