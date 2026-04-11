package com.example.fayowdemo.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiStatus
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.example.fayowdemo.R

class MapManager(private val context: Context) {

    // Marqueur de position de l'utilisateur
    private var locationMarker: Marker? = null

    // Association poiId -> cercle affiché sur la carte
    private val poiCircles = mutableMapOf<String, Circle>()

    // -------------------------------------------------------------------------
    // Initialisation de la carte
    // -------------------------------------------------------------------------

    /** Configure les options de base de la carte Google Maps. */
    fun initialiserCarte(map: GoogleMap) {
        map.uiSettings.isZoomControlsEnabled = true
        try {
            map.isMyLocationEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = true
        } catch (e: SecurityException) {
            Log.e("MapManager", "Erreur permission localisation : ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Affichage des POIs
    // -------------------------------------------------------------------------

    /**
     * Redessine tous les POIs sur la carte selon leur statut et leur état de lecture.
     * - VALIDATED non lus : cercle violet
     * - PROPOSED           : cercle vert
     * - INITIATED          : cercle orange + marqueur cliquable
     */
    fun rafraichirCarte(
        map: GoogleMap,
        pointsInteret: List<PointInteret>,
        poisLusIds: Set<String>,
        pointsDejaDeclenches: Set<String>,
        location: Location?,
        currentAzimuth: Float
    ) {
        Log.d("MapManager", "Rafraîchissement de la carte avec ${pointsInteret.size} POIs")

        map.clear()
        locationMarker = null
        poiCircles.clear()

        for (poi in pointsInteret) {

            // Les POIs VALIDATED déjà lus ne s'affichent plus
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) continue

            val shouldDisplay = when (poi.status) {
                PoiStatus.VALIDATED -> !pointsDejaDeclenches.contains(poi.id)
                PoiStatus.PROPOSED  -> true
                PoiStatus.INITIATED -> true
            }

            if (!shouldDisplay) continue

            val (strokeColor, fillColor) = when (poi.status) {
                PoiStatus.VALIDATED -> Pair(
                    Color.argb(30, 190, 30, 250),
                    Color.argb(60, 190, 30, 250)
                )
                PoiStatus.PROPOSED  -> Pair(
                    Color.argb(100, 76, 175, 80),
                    Color.argb(80, 76, 175, 80)
                )
                PoiStatus.INITIATED -> Pair(
                    Color.argb(150, 255, 152, 0),
                    Color.argb(100, 255, 152, 0)
                )
            }

            val circle = map.addCircle(
                CircleOptions()
                    .center(poi.position)
                    .radius(20.0)
                    .strokeColor(strokeColor)
                    .fillColor(fillColor)
                    .strokeWidth(3f)
                    .clickable(poi.status == PoiStatus.INITIATED)
            )
            circle.tag = poi.id
            poiCircles[poi.id] = circle

            // Pour les brouillons : affiche un marqueur avec le début du message
            if (poi.status == PoiStatus.INITIATED) {
                val snippet = poi.message.take(30) + if (poi.message.length > 30) "..." else ""
                map.addMarker(
                    MarkerOptions()
                        .position(poi.position)
                        .title(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        .alpha(0.7f)
                )?.showInfoWindow()
            }
        }

        // Replace le marqueur utilisateur si on a une position
        location?.let { updateLocationMarker(map, it, currentAzimuth) }
    }

    /** Supprime visuellement un cercle de POI (après lecture). */
    fun supprimerCerclePoi(poiId: String) {
        poiCircles[poiId]?.remove()
        poiCircles.remove(poiId)
    }

    // -------------------------------------------------------------------------
    // Marqueur de position utilisateur
    // -------------------------------------------------------------------------

    /**
     * Crée ou déplace le marqueur de position de l'utilisateur.
     * Anime la caméra pour suivre le déplacement.
     */
    fun updateLocationMarker(map: GoogleMap, location: Location, azimuth: Float) {
        if (!::bitmapCache.isInitialized) {
            bitmapCache = bitmapDescriptorFromVector(context, R.drawable.outline_arrow_circle_up_24)
        }

        val currentLatLng = LatLng(location.latitude, location.longitude)

        if (locationMarker == null) {
            locationMarker = map.addMarker(
                MarkerOptions()
                    .position(currentLatLng)
                    .title("Ma position")
                    .snippet("Je suis ici!")
                    .icon(bitmapCache)
                    .anchor(0.5f, 0.5f)
                    .rotation(azimuth)
                    .flat(true)
            )
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16f))
            Log.d("MapManager", "Marqueur créé à ${location.latitude}, ${location.longitude}")
        } else {
            locationMarker?.apply {
                position = currentLatLng
                rotation = azimuth
            }
            map.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng))
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaire
    // -------------------------------------------------------------------------

    private lateinit var bitmapCache: BitmapDescriptor

    /** Convertit un drawable vectoriel en BitmapDescriptor pour Google Maps. */
    private fun bitmapDescriptorFromVector(context: Context, @DrawableRes vectorResId: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)!!
        vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        vectorDrawable.draw(Canvas(bitmap))
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}