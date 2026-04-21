package com.example.fayowdemo.ui.map

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.gms.maps.MapView

/**
 * MapView personnalisée qui permet d'intercepter les événements tactiles
 * AVANT que Google Maps ne les consomme.
 *
 * Le problème avec setOnTouchListener sur le fragment Maps : Maps intercepte
 * les touches en interne via ses vues enfants, et notre listener ne les reçoit
 * jamais ou trop tard. En surchargeant dispatchTouchEvent ici, on est dans la
 * hiérarchie AVANT Maps — on reçoit tous les events en premier.
 *
 * Usage dans activity_main.xml :
 *   <com.example.fayowdemo.ui.map.InterceptMapView
 *       android:id="@+id/mapView"
 *       ... />
 *
 * Usage dans MainActivity :
 *   val mapView = findViewById<InterceptMapView>(R.id.mapView)
 *   mapView.touchInterceptor = { event -> /* retourner true pour capturer */ }
 */
class InterceptMapView(
    context: Context,
    attrs: AttributeSet?
) : MapView(context, attrs) {

    // Constructeur secondaire pour instanciation programmatique sans AttributeSet
    constructor(context: Context) : this(context, null)

    /**
     * Callback appelé sur chaque MotionEvent AVANT que Maps ne le traite.
     * - Retourner true  → l'event est capturé ici, Maps ne le reçoit PAS
     * - Retourner false → l'event est transmis normalement à Maps
     */
    var touchInterceptor: ((MotionEvent) -> Boolean)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Proposer l'event à notre intercepteur en premier
        if (touchInterceptor?.invoke(ev) == true) {
            return true // Capturé — Maps ne voit pas cet event
        }
        return super.dispatchTouchEvent(ev) // Transmis normalement à Maps
    }
}