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
    // État du drag & drop
    // -------------------------------------------------------------------------

    /**
     * Marqueur draggable temporaire — non utilisé dans cette implémentation
     * (le drag est géré directement via le onTouchListener dans MainActivity).
     * Conservé pour compatibilité future.
     */
    private var dragMarker: Marker? = null

    /**
     * Cercle fantôme affiché sous le marker pendant le drag,
     * pour visualiser la future position du POI.
     */
    private var dragGhostCircle: Circle? = null

    /**
     * ID du POI en cours de déplacement. Exposé en lecture pour que
     * MainActivity sache quel POI est concerné lors du onMarkerDragEnd.
     * Null quand aucun drag n'est en cours.
     */
    var poiEnDeplacement: String? = null
        private set

    /**
     * Position d'origine du POI avant le drag, pour permettre l'annulation.
     */
    private var positionOrigineDrag: LatLng? = null

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
    // Affichage des POIs — Mode Normal
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

        annulerDragSiEnCours() // Nettoyer tout drag en cours avant de redessiner

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

    // -------------------------------------------------------------------------
    // Affichage des POIs — Mode Parcourir
    // -------------------------------------------------------------------------

    /**
     * Mode Parcourir : affiche l'historique de l'utilisateur sur la carte.
     *
     * Couleurs :
     * - Vert   : POIs VALIDATED déjà lus par l'utilisateur → cliquables (dialog texte)
     * - Orange : POIs INITIATED (brouillons de l'utilisateur) → cliquables (édition + déplaçables)
     * - Gris   : POIs PROPOSED → affichés, non cliquables
     *
     * Pas de marqueur de position. La caméra est centrée une seule fois sur la
     * position de l'utilisateur, sans recentrage automatique par la suite.
     *
     * @return Map poiId → message, uniquement pour les cercles verts (lus).
     */
    fun afficherCarteParcourir(
        map: GoogleMap,
        tousLesPois: List<PointInteret>,
        poisLusIds: Set<String>,
        location: Location?
    ): Map<String, String> {

        annulerDragSiEnCours() // Nettoyer tout drag en cours avant de redessiner

        map.clear()
        locationMarker = null
        poiCircles.clear()

        val poisLusMessages = mutableMapOf<String, String>() // id → message (cercles verts)

        for (poi in tousLesPois) {
            val (strokeColor, fillColor, isClickable) = when {

                // Vert : POIs VALIDATED déjà lus par l'utilisateur
                poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id) -> Triple(
                    Color.argb(220, 46, 125, 50),
                    Color.argb(140, 76, 175, 80),
                    true
                )

                // Orange : POIs INITIATED (brouillons), cliquables + déplaçables via clic long
                poi.status == PoiStatus.INITIATED -> Triple(
                    Color.argb(220, 230, 81, 0),
                    Color.argb(140, 255, 152, 0),
                    true
                )

                // Gris : POIs PROPOSED, non cliquables
                poi.status == PoiStatus.PROPOSED -> Triple(
                    Color.argb(180, 97, 97, 97),
                    Color.argb(100, 158, 158, 158),
                    false
                )

                // VALIDATED non lus : absents du mode Parcourir
                else -> continue
            }

            val circle = map.addCircle(
                CircleOptions()
                    .center(poi.position)
                    .radius(20.0)
                    .strokeColor(strokeColor)
                    .fillColor(fillColor)
                    .strokeWidth(3f)
                    .clickable(isClickable)
            )
            circle.tag = poi.id
            poiCircles[poi.id] = circle

            // Mémoriser le message des POIs lus (cercles verts) pour le dialog
            if (poi.status == PoiStatus.VALIDATED && poisLusIds.contains(poi.id)) {
                poisLusMessages[poi.id] = poi.message
            }

            // Pour les brouillons INITIATED : afficher le marqueur orange (comme en mode normal)
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

        // Centrer la carte sur la position de l'utilisateur — une seule fois, sans suivi
        location?.let {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16f)
            )
        }

        Log.d("MapManager", "Mode Parcourir : ${poisLusMessages.size} POIs lus affichés")
        return poisLusMessages
    }

    // -------------------------------------------------------------------------
    // Drag & Drop — déplacement d'un POI INITIATED
    // -------------------------------------------------------------------------

    /**
     * Démarre le mode drag pour un POI INITIATED.
     *
     * Actions :
     * 1. Mémorise l'ID et la position d'origine
     * 2. Masque le cercle d'origine
     * 3. Crée un cercle fantôme semi-transparent à la même position
     *
     * Le déplacement du cercle fantôme est ensuite piloté en temps réel
     * par mettreAJourDragGhost(), appelé depuis le onTouchListener de MainActivity.
     *
     * @param map  La carte Google Maps active
     * @param poi  Le POI à déplacer
     */
    fun demarrerDragPoi(map: GoogleMap, poi: PointInteret) {
        if (poi.status != PoiStatus.INITIATED) return

        // Si un drag était déjà en cours sur un autre POI, l'annuler proprement
        annulerDragSiEnCours()

        poiEnDeplacement    = poi.id
        positionOrigineDrag = poi.position

        // Masquer le cercle d'origine pendant le drag
        poiCircles[poi.id]?.isVisible = false

        // Cercle fantôme : représente visuellement la nouvelle position pendant le glissement
        dragGhostCircle = map.addCircle(
            CircleOptions()
                .center(poi.position)
                .radius(20.0)
                .strokeColor(Color.argb(220, 255, 100, 0))
                .fillColor(Color.argb(80, 255, 152, 0))
                .strokeWidth(4f)
                .zIndex(10f)
        )

        Log.d("MapManager", "Drag démarré pour POI ${poi.id} à ${poi.position}")
    }

    /**
     * Appelé en continu pendant le drag (onMarkerDrag) pour déplacer
     * le cercle fantôme en temps réel et donner un retour visuel immédiat.
     *
     * @param nouvellePosition  Position courante du marker pendant le glissement
     */
    fun mettreAJourDragGhost(nouvellePosition: LatLng) {
        dragGhostCircle?.center = nouvellePosition
    }

    /**
     * Valide le déplacement : déplace le cercle d'origine à la nouvelle position
     * et nettoie les éléments temporaires de drag.
     *
     * La sauvegarde Firestore est gérée par MainActivity (via poiRepository).
     *
     * @param nouvellePosition  Position finale confirmée par l'utilisateur
     */
    fun validerDrag(nouvellePosition: LatLng) {
        val poiId = poiEnDeplacement ?: return

        // Déplacer le cercle d'origine à la nouvelle position et le rendre visible
        poiCircles[poiId]?.apply {
            center    = nouvellePosition
            isVisible = true
        }

        nettoyerDrag()
        Log.d("MapManager", "Drag validé pour POI $poiId → $nouvellePosition")
    }

    /**
     * Annule le drag : remet le cercle d'origine à sa position initiale
     * et nettoie les éléments temporaires.
     */
    fun annulerDrag() {
        val poiId = poiEnDeplacement ?: return

        // Remettre le cercle d'origine visible (il n'a pas bougé, seul le marker a bougé)
        poiCircles[poiId]?.isVisible = true

        nettoyerDrag()
        Log.d("MapManager", "Drag annulé pour POI $poiId")
    }

    /**
     * Annule silencieusement le drag si un drag est en cours.
     * Appelé avant tout rafraîchissement ou nettoyage de carte.
     */
    private fun annulerDragSiEnCours() {
        if (poiEnDeplacement != null) annulerDrag()
    }

    /** Libère les ressources du drag (cercle fantôme). */
    private fun nettoyerDrag() {
        dragMarker?.remove()
        dragMarker = null
        dragGhostCircle?.remove()
        dragGhostCircle = null
        poiEnDeplacement    = null
        positionOrigineDrag = null
    }

    // -------------------------------------------------------------------------
    // Suppression d'un cercle (après lecture)
    // -------------------------------------------------------------------------

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
     * Ne doit être appelée qu'en mode normal (pas en mode Parcourir).
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