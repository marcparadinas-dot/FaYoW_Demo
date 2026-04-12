package com.example.fayowdemo.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.fayowdemo.model.PointInteret
import com.example.fayowdemo.model.PoiStatus
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class CommuneManager(private val context: Context) {

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    private var communeActuelle: String? = null
    private var polygoneActuel: List<Pair<Double, Double>> = emptyList()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // Text-to-Speech dédié aux annonces de commune
    // -------------------------------------------------------------------------

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    fun initialiserTts(onReady: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.FRENCH)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("CommuneManager", "Langue française non supportée")
                    isTtsReady = false
                } else {
                    // Voix féminine pour distinguer des annonces POI
                    val voixFeminine = tts?.voices?.find {
                        it.locale.language == "fr" && (
                                it.name.contains("female", ignoreCase = true) ||
                                        it.name.contains("frf", ignoreCase = true) ||
                                        it.name.contains("wavenet-a", ignoreCase = true) ||
                                        it.name.contains("wavenet-c", ignoreCase = true)
                                )
                    }
                    if (voixFeminine != null) {
                        tts?.voice = voixFeminine
                        Log.d("CommuneManager", "Voix féminine activée : ${voixFeminine.name}")
                    } else {
                        Log.w("CommuneManager", "Aucune voix féminine trouvée, voix par défaut")
                    }
                    isTtsReady = true
                    Log.d("CommuneManager", "TTS commune prêt")
                    onReady()
                }
            } else {
                Log.e("CommuneManager", "Échec initialisation TTS commune")
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Point d'entrée principal
    // -------------------------------------------------------------------------

    /**
     * À appeler à chaque nouvelle position GPS.
     * Détecte la commune, et si elle a changé, récupère son polygone
     * et déclenche l'annonce vocale.
     */
    fun verifierCommune(
        latitude: Double,
        longitude: Double,
        pointsInteret: List<PointInteret>,
        poisLusIds: Set<String>
    ) {
        scope.launch {
            try {
                val commune = obtenirNomCommune(latitude, longitude) ?: return@launch
                Log.d("CommuneManager", "Commune détectée : $commune")

                // Annonce uniquement si la commune a changé
                if (commune == communeActuelle) return@launch
                communeActuelle = commune

                // Récupérer le polygone via Nominatim
                val polygone = obtenirPolygoneCommune(commune) ?: run {
                    Log.w("CommuneManager", "Polygone introuvable pour $commune")
                    // Annonce sans comptage si polygone indisponible
                    withContext(Dispatchers.Main) {
                        annoncerCommune(commune, null, null)
                    }
                    return@launch
                }
                polygoneActuel = polygone

                // Compter les POIs dans la commune
                val poisDansCommune = pointsInteret.filter { poi ->
                    poi.status == PoiStatus.VALIDATED &&
                            estDansPolygone(poi.position.latitude, poi.position.longitude, polygone)
                }
                val totalCommune = poisDansCommune.size
                val lusCommune = poisDansCommune.count { poisLusIds.contains(it.id) }

                Log.d("CommuneManager", "$commune : $totalCommune POIs, $lusCommune lus")

                withContext(Dispatchers.Main) {
                    annoncerCommune(commune, totalCommune, lusCommune)
                }

            } catch (e: Exception) {
                Log.e("CommuneManager", "Erreur détection commune : ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Geocoder — nom de la commune
    // -------------------------------------------------------------------------

    private suspend fun obtenirNomCommune(latitude: Double, longitude: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+ : version asynchrone
                    var result: String? = null
                    val latch = java.util.concurrent.CountDownLatch(1)
                    Geocoder(context, Locale.FRENCH).getFromLocation(
                        latitude, longitude, 1
                    ) { addresses ->
                        result = addresses.firstOrNull()?.locality
                        latch.countDown()
                    }
                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    result
                } else {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.FRENCH)
                        .getFromLocation(latitude, longitude, 1)
                        ?.firstOrNull()?.locality
                }
            } catch (e: Exception) {
                Log.e("CommuneManager", "Erreur Geocoder : ${e.message}")
                null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Nominatim — polygone de la commune
    // -------------------------------------------------------------------------

    private suspend fun obtenirPolygoneCommune(commune: String): List<Pair<Double, Double>>? {
        return withContext(Dispatchers.IO) {
            try {
                val nomEncoded = URLEncoder.encode(commune, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?q=${nomEncoded}&format=json&polygon_geojson=1&limit=1&countrycodes=fr"

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "FayowDemo/1.0")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val jsonArray = JSONArray(response)
                if (jsonArray.length() == 0) return@withContext null

                val geometry = jsonArray
                    .getJSONObject(0)
                    .getJSONObject("geojson")

                extrairePolygone(geometry)

            } catch (e: Exception) {
                Log.e("CommuneManager", "Erreur Nominatim : ${e.message}")
                null
            }
        }
    }

    private fun extrairePolygone(geometry: JSONObject): List<Pair<Double, Double>>? {
        return try {
            val type = geometry.getString("type")
            val coordinates = geometry.getJSONArray("coordinates")

            // Récupère le premier anneau du polygone (contour extérieur)
            val ring = when (type) {
                "Polygon"      -> coordinates.getJSONArray(0)
                "MultiPolygon" -> coordinates
                    .getJSONArray(0)  // Premier polygone
                    .getJSONArray(0)  // Premier anneau
                else -> return null
            }

            (0 until ring.length()).map { i ->
                val point = ring.getJSONArray(i)
                Pair(point.getDouble(1), point.getDouble(0)) // lat, lng
            }
        } catch (e: Exception) {
            Log.e("CommuneManager", "Erreur extraction polygone : ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Algorithme point-dans-polygone (Ray Casting)
    // -------------------------------------------------------------------------

    private fun estDansPolygone(
        lat: Double,
        lng: Double,
        polygone: List<Pair<Double, Double>>
    ): Boolean {
        var dedans = false
        var i = 0
        var j = polygone.size - 1
        while (i < polygone.size) {
            val (latI, lngI) = polygone[i]
            val (latJ, lngJ) = polygone[j]
            if ((lngI > lng) != (lngJ > lng) &&
                lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI
            ) {
                dedans = !dedans
            }
            j = i++
        }
        return dedans
    }

    // -------------------------------------------------------------------------
    // Annonce vocale
    // -------------------------------------------------------------------------

    private fun annoncerCommune(commune: String, total: Int?, lus: Int?) {
        if (!isTtsReady) {
            Log.w("CommuneManager", "TTS non prêt pour l'annonce de commune")
            return
        }

        val message = when {
            total == null -> "Vous êtes à $commune."
            total == 0    -> "Vous êtes à $commune. Cette commune ne contient pas encore d'anecdote FayoW."
            lus == null   -> "Vous êtes à $commune. Cette commune contient $total anecdote${if (total > 1) "s" else ""} FayoW."
            lus == 0      -> "Vous êtes à $commune. Cette commune contient $total anecdote${if (total > 1) "s" else ""} FayoW, vous n'en avez encore lu aucune."
            lus == total  -> "Vous êtes à $commune. Cette commune contient $total anecdote${if (total > 1) "s" else ""} FayoW, vous les avez toutes lues !"
            else          -> "Vous êtes à $commune. Cette commune contient $total anecdote${if (total > 1) "s" else ""} FayoW, vous en avez lu $lus."
        }

        Log.d("CommuneManager", "Annonce : $message")
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "commune_announcement")
    }
}