package com.example.mapa

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListPopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.example.mapa.databinding.FragmentMainBinding
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.Normalizer

// ─── IDs das camadas MapLibre ──────────────────────────────────────────────
private const val SOURCE_ID             = "ufabc-source"
private const val LAYER_CAMPUS          = "layer-campus-limite"
private const val LAYER_CAMPUS_LINE     = "layer-campus-limite-line"
private const val LAYER_BLOCOS_FILL     = "layer-blocos-fill"
private const val LAYER_BLOCOS_LINE     = "layer-blocos-line"
private const val LAYER_ROOMS_FILL      = "layer-rooms-fill"
private const val LAYER_ROOMS_LINE      = "layer-rooms-line"
private const val LAYER_ENTRADAS_FILL   = "layer-entradas-fill"
private const val LAYER_ENTRADAS_LINE   = "layer-entradas-line"
private const val LAYER_RAMPAS_FILL     = "layer-rampas-fill"
private const val LAYER_RAMPAS_LINE     = "layer-rampas-line"
private const val LAYER_ELEVADORES_FILL = "layer-elevadores-fill"
private const val LAYER_ELEVADORES_LINE = "layer-elevadores-line"
private const val SEARCH_SOURCE_ID = "search-result-source"
private const val LAYER_SEARCH_FILL = "layer-search-result-fill"
private const val LAYER_SEARCH_LINE = "layer-search-result-line"
private const val ROUTE_SOURCE_ID = "indoor-route-source"
private const val LAYER_ROUTE_LINE = "indoor-route-line"

// Centro do campus Santo André e zooms (alinhado ao ufabc_campus.geojson)
private val CAMPUS_CENTER    = LatLng(-23.6450, -46.5279)
private const val ZOOM_CAMPUS    = 17.5
private const val ZOOM_BLOCO     = 18.5
private const val ZOOM_DESTINO   = 20.0
private const val ZOOM_STEP      = 1.0
// Ângulo de rotação da câmera (em graus) para alinhar o eixo do Bloco A e do campus na horizontal
private const val BEARING_CAMPUS = 60.5

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private var map: MapLibreMap? = null

    // Faixa completa de andares por bloco (agregando piso base + torres)
    private val blockFloorRanges = mutableMapOf<String, Pair<Int, Int>>()
    private val searchableFeatures = mutableListOf<SearchFeature>()
    private var searchPopup: ListPopupWindow? = null
    private var activeSearchField: EditText? = null
    private var ignoreSearchTextChange = false
    private var selectedOrigin: SearchFeature? = null
    private var selectedDestination: SearchFeature? = null
    private var indoorRoutePlanner: IndoorRoutePlanner? = null
    private var currentRoutePlan: IndoorRoutePlanner.RoutePlan? = null

    private data class SearchFeature(
        val codigo: String,
        val nome: String,
        val tipo: String,
        val blocoId: String,
        val torreId: String,
        val andar: Int?,
        val featureJson: String,
        val center: LatLng,
    )

    // Estado de navegação
    private var selectedBlocoId: String? = null
    private var selectedAndar: Int = 0
    private var maxAndar: Int = 0
    private var minAndar: Int = 0

    // ─── Ciclo de vida do Fragment ─────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            configureMap(mapLibreMap)
        }

        setupCampusSelector()
        setupZoomControls()
        setupSearchBar()
        setupDetailsCard()
    }

    private fun setupCampusSelector() {
        binding.btnCampusSa.setOnClickListener {
            resetToOverview()
        }
    }

    // ─── Configuração inicial do mapa ──────────────────────────────────────

    private fun configureMap(mapLibreMap: MapLibreMap) {
        mapLibreMap.uiSettings.apply {
            isRotateGesturesEnabled = false
            isCompassEnabled = false   // usamos o nosso próprio
        }

        mapLibreMap.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                campusCameraPosition(CAMPUS_CENTER, ZOOM_CAMPUS)
            )
        )

        mapLibreMap.setStyle(
            Style.Builder().fromUri("asset://style_offline.json")
        ) { style ->
            addGeoJsonSource(style)
            addCampusLimiteLayer(style)
            addBlocosLayers(style)
            addRoomsLayers(style)
            addEntradasLayers(style)
            addRampasLayers(style)
            addElevadoresLayers(style)
            addSearchResultLayers(style)
            addRouteLayer(style)
            setupMapClickListener(mapLibreMap)
        }
    }

    // ─── Source GeoJSON ────────────────────────────────────────────────────

    private fun addGeoJsonSource(style: Style) {
        val geoJson = requireContext().assets
            .open("ufabc_campus.geojson")
            .bufferedReader()
            .use { it.readText() }
        parseBlockFloorRanges(geoJson)
        parseSearchableFeatures(geoJson)
        val pathsGeoJson = requireContext().assets.open("caminhos.geojson")
            .bufferedReader().use { it.readText() }
        indoorRoutePlanner = IndoorRoutePlanner(pathsGeoJson, geoJson)
        style.addSource(GeoJsonSource(SOURCE_ID, geoJson))
        style.addSource(GeoJsonSource(SEARCH_SOURCE_ID, "{\"type\":\"FeatureCollection\",\"features\":[]}"))
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, "{\"type\":\"FeatureCollection\",\"features\":[]}"))
    }

    private fun parseSearchableFeatures(geoJson: String) {
        searchableFeatures.clear()
        val features = JSONObject(geoJson).optJSONArray("features") ?: return
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val nome = properties.optString("nome").takeUnless { it == "null" }.orEmpty()
            val codigo = properties.optString("codigo").takeUnless { it == "null" }.orEmpty()
            val andar = if (properties.has("andar")) properties.optInt("andar")
                else if (properties.has("min_andar")) properties.optInt("min_andar") else null
            if (andar != null && andar > 4) continue
            if (nome.isBlank() && codigo.isBlank()) continue
            val center = feature.optJSONObject("geometry")?.let(::geometryCenter) ?: continue
            searchableFeatures += SearchFeature(
                codigo = codigo,
                nome = nome,
                tipo = properties.optString("tipo"),
                blocoId = properties.optString("bloco_id").takeUnless { it == "null" }.orEmpty(),
                torreId = properties.optString("torre_id").takeUnless { it == "null" }.orEmpty(),
                andar = andar,
                featureJson = feature.toString(),
                center = center,
            )
        }
    }

    private fun geometryCenter(geometry: JSONObject): LatLng? {
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY

        fun collect(value: org.json.JSONArray) {
            if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
                val lon = value.optDouble(0)
                val lat = value.optDouble(1)
                minLon = minOf(minLon, lon)
                maxLon = maxOf(maxLon, lon)
                minLat = minOf(minLat, lat)
                maxLat = maxOf(maxLat, lat)
            } else {
                for (i in 0 until value.length()) value.optJSONArray(i)?.let(::collect)
            }
        }
        collect(coordinates)
        if (!minLon.isFinite() || !minLat.isFinite()) return null
        return LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    }

    private fun addSearchResultLayers(style: Style) {
        style.addLayer(FillLayer(LAYER_SEARCH_FILL, SEARCH_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#FFB300")),
                PropertyFactory.fillOpacity(0.35f),
            )
        })
        style.addLayer(LineLayer(LAYER_SEARCH_LINE, SEARCH_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#E65100")),
                PropertyFactory.lineWidth(3f),
            )
        })
    }

    private fun addRouteLayer(style: Style) {
        style.addLayer(LineLayer(LAYER_ROUTE_LINE, ROUTE_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#1565C0")),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.95f),
            )
        })
    }

    private fun parseBlockFloorRanges(geoJson: String) {
        blockFloorRanges.clear()
        val features = JSONObject(geoJson).optJSONArray("features") ?: return
        for (i in 0 until features.length()) {
            val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val tipo = props.optString("tipo")
            if (tipo != "bloco" && tipo != "torre") continue
            val blocoId = props.optString("bloco_id").takeIf { it.isNotEmpty() } ?: continue
            if (!props.has("min_andar") || !props.has("max_andar")) continue

            val rawMin = props.optInt("min_andar", 0)
            if (rawMin > 4) continue
            val minA = rawMin.coerceAtLeast(0)
            val maxA = props.optInt("max_andar", 0).coerceAtMost(4)
            val current = blockFloorRanges[blocoId]
            blockFloorRanges[blocoId] = if (current == null) {
                minA to maxA
            } else {
                minOf(current.first, minA) to maxOf(current.second, maxA)
            }
        }
    }

    // ─── Filtros de camadas ────────────────────────────────────────────────

    private fun overviewBlocksFilter(): Expression = Expression.all(
        Expression.eq(Expression.get("tipo"), Expression.literal("bloco")),
        Expression.lte(Expression.get("min_andar"), Expression.literal(0)),
    )

    private fun activeFloorFeatureFilter(
        tipo: String,
        blocoId: String? = null,
        andar: Int? = null,
    ): Expression {
        if (blocoId == null || andar == null) {
            return Expression.literal(false)
        }
        return Expression.all(
            Expression.eq(Expression.get("tipo"), Expression.literal(tipo)),
            Expression.eq(Expression.get("bloco_id"), Expression.literal(blocoId)),
            Expression.has("andar"),
            Expression.eq(Expression.get("andar"), Expression.literal(andar)),
        )
    }

    // ─── Camadas MapLibre ──────────────────────────────────────────────────

    private fun addCampusLimiteLayer(style: Style) {
        style.addLayer(FillLayer(LAYER_CAMPUS, SOURCE_ID).apply {
            setFilter(Expression.eq(Expression.get("tipo"), Expression.literal("campus_limite")))
            setProperties(
                PropertyFactory.fillColor("#DDE8F0".toColorInt()),
                PropertyFactory.fillOpacity(0.6f),
            )
        })
        style.addLayer(LineLayer(LAYER_CAMPUS_LINE, SOURCE_ID).apply {
            setFilter(Expression.eq(Expression.get("tipo"), Expression.literal("campus_limite")))
            setProperties(
                PropertyFactory.lineColor("#5B8FA8".toColorInt()),
                PropertyFactory.lineWidth(2f),
            )
        })
    }

    private fun addBlocosLayers(style: Style) {
        // Na visão inicial do campus, exibe apenas o contorno geral dos blocos (sem torres individuais)
        val filter = overviewBlocksFilter()
        style.addLayer(FillLayer(LAYER_BLOCOS_FILL, SOURCE_ID).apply {
            setFilter(filter)
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#4A90E2")),
                PropertyFactory.fillOpacity(0.55f),
            )
        })
        style.addLayer(LineLayer(LAYER_BLOCOS_LINE, SOURCE_ID).apply {
            setFilter(filter)
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#1A5DAB")),
                PropertyFactory.lineWidth(2f),
            )
        })
    }

    private fun addRoomsLayers(style: Style) {
        style.addLayer(FillLayer(LAYER_ROOMS_FILL, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("sala"))
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#A8D5A2")),
                PropertyFactory.fillOpacity(0.80f),
            )
        })
        style.addLayer(LineLayer(LAYER_ROOMS_LINE, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("sala"))
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#2E7D32")),
                PropertyFactory.lineWidth(1.5f),
            )
        })
    }

    private fun addEntradasLayers(style: Style) {
        style.addLayer(FillLayer(LAYER_ENTRADAS_FILL, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("entrada"))
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#4CAF50")),
                PropertyFactory.fillOpacity(0.85f),
            )
        })
        style.addLayer(LineLayer(LAYER_ENTRADAS_LINE, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("entrada"))
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#1B5E20")),
                PropertyFactory.lineWidth(2f),
            )
        })
    }

    private fun addRampasLayers(style: Style) {
        style.addLayer(FillLayer(LAYER_RAMPAS_FILL, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("rampa"))
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#FF9800")),
                PropertyFactory.fillOpacity(0.85f),
            )
        })
        style.addLayer(LineLayer(LAYER_RAMPAS_LINE, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("rampa"))
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#E65100")),
                PropertyFactory.lineWidth(2f),
            )
        })
    }

    private fun addElevadoresLayers(style: Style) {
        style.addLayer(FillLayer(LAYER_ELEVADORES_FILL, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("elevador"))
            setProperties(
                PropertyFactory.fillColor(Color.parseColor("#9C27B0")),
                PropertyFactory.fillOpacity(0.85f),
            )
        })
        style.addLayer(LineLayer(LAYER_ELEVADORES_LINE, SOURCE_ID).apply {
            setFilter(activeFloorFeatureFilter("elevador"))
            setProperties(
                PropertyFactory.lineColor(Color.parseColor("#4A148C")),
                PropertyFactory.lineWidth(2f),
            )
        })
    }

    // ─── Cliques no mapa ───────────────────────────────────────────────────

    private fun setupMapClickListener(mapLibreMap: MapLibreMap) {
        mapLibreMap.addOnMapClickListener { latLng ->
            val pt = mapLibreMap.projection.toScreenLocation(latLng)

            // Prioridade 1: sala, entrada, rampa ou elevador (apenas quando um bloco está selecionado)
            if (selectedBlocoId != null) {
                val roomFeats = mapLibreMap.queryRenderedFeatures(pt, LAYER_ROOMS_FILL)
                if (roomFeats.isNotEmpty()) {
                    roomFeats[0].properties()?.let { p ->
                        showRoomDetails(
                            nome    = p["nome"]?.asString ?: "",
                            blocoId = p["bloco_id"]?.asString ?: "",
                            torreId = p["torre_id"]?.asString ?: "",
                            andar   = p["andar"]?.asInt ?: 0,
                        )
                        return@addOnMapClickListener true
                    }
                }

                val entradaFeats = mapLibreMap.queryRenderedFeatures(pt, LAYER_ENTRADAS_FILL)
                if (entradaFeats.isNotEmpty()) {
                    entradaFeats[0].properties()?.let { p ->
                        showRoomDetails(
                            nome    = p["nome"]?.asString ?: "Entrada",
                            blocoId = p["bloco_id"]?.asString ?: "",
                            torreId = p["torre_id"]?.asString ?: "",
                            andar   = p["andar"]?.asInt ?: 0,
                        )
                        return@addOnMapClickListener true
                    }
                }

                val rampaFeats = mapLibreMap.queryRenderedFeatures(pt, LAYER_RAMPAS_FILL)
                if (rampaFeats.isNotEmpty()) {
                    rampaFeats[0].properties()?.let { p ->
                        showRoomDetails(
                            nome    = p["nome"]?.asString ?: "Rampa de acesso",
                            blocoId = p["bloco_id"]?.asString ?: "",
                            torreId = p["torre_id"]?.asString ?: "",
                            andar   = p["andar"]?.asInt ?: 0,
                        )
                        return@addOnMapClickListener true
                    }
                }

                val elevadorFeats = mapLibreMap.queryRenderedFeatures(pt, LAYER_ELEVADORES_FILL)
                if (elevadorFeats.isNotEmpty()) {
                    elevadorFeats[0].properties()?.let { p ->
                        showRoomDetails(
                            nome    = p["nome"]?.asString ?: "Elevadores",
                            blocoId = p["bloco_id"]?.asString ?: "",
                            torreId = p["torre_id"]?.asString ?: "",
                            andar   = p["andar"]?.asInt ?: 0,
                        )
                        return@addOnMapClickListener true
                    }
                }
            }

            // Prioridade 2: bloco / torre ativa
            val blocoFeats = mapLibreMap.queryRenderedFeatures(pt, LAYER_BLOCOS_FILL)
            if (blocoFeats.isNotEmpty()) {
                blocoFeats[0].properties()?.let { p ->
                    val blocoId = p["bloco_id"]?.asString ?: return@addOnMapClickListener false
                    if (blocoId == selectedBlocoId) {
                        hideRoomDetails()
                        return@addOnMapClickListener true
                    }
                    mapLibreMap.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            campusCameraPosition(latLng, ZOOM_BLOCO)
                        ),
                        600
                    )
                    selectBloco(
                        blocoId      = blocoId,
                        fallbackMinA = p["min_andar"]?.asInt ?: 0,
                        fallbackMaxA = p["max_andar"]?.asInt ?: 0,
                    )
                    return@addOnMapClickListener true
                }
            }

            // Clique fora das salas e fora do bloco ativo: volta à visão geral
            if (selectedBlocoId != null) {
                resetToOverview()
                return@addOnMapClickListener true
            }

            hideRoomDetails()
            false
        }
    }

    // ─── Seleção de bloco ──────────────────────────────────────────────────

    private fun selectBloco(
        blocoId: String,
        fallbackMinA: Int,
        fallbackMaxA: Int,
    ) {
        val range = blockFloorRanges[blocoId] ?: (fallbackMinA to fallbackMaxA)
        selectedBlocoId = blocoId
        minAndar = range.first
        maxAndar = range.second
        selectedAndar = minAndar

        populateFloorButtons(minAndar, maxAndar)
        binding.cardFloorSelector.isVisible = true

        applyFloorFilter(blocoId, selectedAndar)
        highlightFloorButton(selectedAndar)
        hideRoomDetails()
    }

    // ─── Botões de andar (gerados dinamicamente) ───────────────────────────

    private fun populateFloorButtons(min: Int, max: Int) {
        binding.layoutFloorButtons.removeAllViews()

        val sizePx = (36 * resources.displayMetrics.density).toInt()
        val marginPx = (2 * resources.displayMetrics.density).toInt()

        for (andar in max downTo min) {
            val btn = android.widget.TextView(requireContext()).apply {
                text      = andarLabel(andar)
                tag       = andar
                gravity   = android.view.Gravity.CENTER
                textSize  = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(0, marginPx, 0, marginPx)
                }
                background = ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_floor_btn_inactive
                )
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setOnClickListener { onFloorSelected(andar) }
            }
            binding.layoutFloorButtons.addView(btn)
        }
    }

    private fun andarLabel(andar: Int): String = when {
        andar == 0 -> "T"
        andar < 0  -> "${andar}S"
        else       -> andar.toString()
    }

    private fun onFloorSelected(andar: Int) {
        selectedAndar = andar
        val blocoId = selectedBlocoId ?: return
        applyFloorFilter(blocoId, andar)
        highlightFloorButton(andar)
        if (currentRoutePlan == null) hideRoomDetails()
        renderRouteForFloor()
    }

    // ─── Filtro exclusivo: exibe apenas as estruturas (bloco/torres) e elementos do andar ─

    private fun applyFloorFilter(blocoId: String, andar: Int) {
        val style = map?.style ?: return

        val roomFilter = activeFloorFeatureFilter("sala", blocoId, andar)
        (style.getLayer(LAYER_ROOMS_FILL) as? FillLayer)?.setFilter(roomFilter)
        (style.getLayer(LAYER_ROOMS_LINE) as? LineLayer)?.setFilter(roomFilter)

        val entradaFilter = activeFloorFeatureFilter("entrada", blocoId, andar)
        (style.getLayer(LAYER_ENTRADAS_FILL) as? FillLayer)?.setFilter(entradaFilter)
        (style.getLayer(LAYER_ENTRADAS_LINE) as? LineLayer)?.setFilter(entradaFilter)

        val rampaFilter = activeFloorFeatureFilter("rampa", blocoId, andar)
        (style.getLayer(LAYER_RAMPAS_FILL) as? FillLayer)?.setFilter(rampaFilter)
        (style.getLayer(LAYER_RAMPAS_LINE) as? LineLayer)?.setFilter(rampaFilter)

        val elevadorFilter = activeFloorFeatureFilter("elevador", blocoId, andar)
        (style.getLayer(LAYER_ELEVADORES_FILL) as? FillLayer)?.setFilter(elevadorFilter)
        (style.getLayer(LAYER_ELEVADORES_LINE) as? LineLayer)?.setFilter(elevadorFilter)

        // Exibe apenas o bloco ou as torres cujo intervalo min_andar..max_andar contém o andar ativo
        val activeStructureFilter = Expression.all(
            Expression.any(
                Expression.eq(Expression.get("tipo"), Expression.literal("bloco")),
                Expression.eq(Expression.get("tipo"), Expression.literal("torre")),
            ),
            Expression.eq(Expression.get("bloco_id"), Expression.literal(blocoId)),
            Expression.lte(Expression.get("min_andar"), Expression.literal(andar)),
            Expression.gte(Expression.get("max_andar"), Expression.literal(andar)),
        )
        (style.getLayer(LAYER_BLOCOS_FILL) as? FillLayer)?.apply {
            setFilter(activeStructureFilter)
            setProperties(PropertyFactory.fillOpacity(0.45f))
        }
        (style.getLayer(LAYER_BLOCOS_LINE) as? LineLayer)?.setFilter(activeStructureFilter)
    }

    // ─── Destaque do botão de andar ativo ──────────────────────────────────

    private fun highlightFloorButton(andar: Int) {
        val ctx = requireContext()
        for (i in 0 until binding.layoutFloorButtons.childCount) {
            val btn = binding.layoutFloorButtons.getChildAt(i) as? android.widget.TextView ?: continue
            val isActive = (btn.tag as? Int) == andar
            btn.background = ContextCompat.getDrawable(
                ctx,
                if (isActive) R.drawable.bg_floor_btn_active else R.drawable.bg_floor_btn_inactive
            )
            btn.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (isActive) R.color.white else R.color.text_primary
                )
            )
        }
    }

    // ─── Card de detalhes da sala ──────────────────────────────────────────

    private fun setupDetailsCard() {
        binding.btnCloseDetails.setOnClickListener { hideRoomDetails() }
    }

    private fun showRoomDetails(
        nome: String,
        blocoId: String,
        torreId: String,
        andar: Int?,
    ) {
        binding.tvRoomCode.isVisible = false
        binding.tvRoomName.text = nome
        binding.tvRoomLocation.text = listOfNotNull(
            blocoId.takeIf { it.isNotBlank() }?.let(::nomeBlocoLegivel),
            torreId.takeIf { it.isNotBlank() }?.let(::nomeTorreLegivel),
            andar?.let { "${andarLabel(it)}º Andar" },
        ).joinToString(" • ")
        binding.cardDetails.isVisible = true
        updateSideControlsAnchor()
    }

    private fun hideRoomDetails() {
        binding.cardDetails.isVisible = false
        updateSideControlsAnchor()
    }

    private fun updateSideControlsAnchor() {
        val params = binding.zoomControls.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val anchorId = if (binding.cardDetails.isVisible) {
            binding.cardDetails.id
        } else {
            binding.bottomBar.id
        }
        if (params.bottomToTop == anchorId && params.bottomToBottom == -1) return

        params.bottomToTop = anchorId
        params.bottomToBottom = -1
        binding.zoomControls.layoutParams = params
    }

    // ─── Zoom controls ─────────────────────────────────────────────────────

    private fun setupZoomControls() {
        binding.btnZoomIn.setOnClickListener {
            map?.animateCamera(CameraUpdateFactory.zoomBy(ZOOM_STEP), 250)
        }
        binding.btnZoomOut.setOnClickListener {
            map?.animateCamera(CameraUpdateFactory.zoomBy(-ZOOM_STEP), 250)
        }
    }

    // ─── Barra de pesquisa ─────────────────────────────────────────────────

    private fun setupSearchBar() {
        val popup = ListPopupWindow(requireContext()).apply {
            isModal = false
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>()))
            setOnItemClickListener { _, _, position, _ ->
                val result = searchResults.getOrNull(position) ?: return@setOnItemClickListener
                val target = activeSearchField ?: binding.etSearch
                dismiss()
                hideKeyboard()
                if (target == binding.etOrigin) selectedOrigin = result else selectedDestination = result
                ignoreSearchTextChange = true
                target.setText(result.nome.ifBlank { result.codigo })
                target.setSelection(target.text.length)
                ignoreSearchTextChange = false
                invalidateRoute()
            }
        }
        searchPopup = popup

        setupAutocompleteField(binding.etOrigin, isOrigin = true)
        setupAutocompleteField(binding.etSearch, isOrigin = false)
        binding.etOrigin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etSearch.requestFocus()
                true
            } else false
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                calculateRoute()
                hideKeyboard()
                true
            } else false
        }
        binding.btnSearch.setOnClickListener {
            calculateRoute()
            hideKeyboard()
        }
    }

    private val searchResults = mutableListOf<SearchFeature>()

    private fun setupAutocompleteField(field: EditText, isOrigin: Boolean) {
        field.addTextChangedListener { editable ->
            if (ignoreSearchTextChange) return@addTextChangedListener
            val query = editable?.toString().orEmpty()
            if (isOrigin) selectedOrigin = null else selectedDestination = null
            invalidateRoute()
            if (query.isBlank()) {
                searchPopup?.dismiss()
                return@addTextChangedListener
            }
            showSearchMatches(query, field)
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && activeSearchField === field) searchPopup?.dismiss()
        }
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()

    private fun findSearchMatches(query: String): List<SearchFeature> {
        val terms = normalized(query).split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return searchableFeatures
            .filter { feature ->
                val searchableText = normalized("${feature.codigo} ${feature.nome} ${feature.tipo}")
                terms.all(searchableText::contains) &&
                    indoorRoutePlanner?.hasLocation(feature.codigo) == true
            }
            .sortedWith(compareBy<SearchFeature> {
                if (normalized(it.codigo).startsWith(terms.first())) 0 else 1
            }.thenBy { it.nome })
            .take(20)
    }

    private fun formatSearchResult(feature: SearchFeature): String = buildString {
        append(feature.nome.ifBlank { feature.tipo.replaceFirstChar { it.uppercaseChar() } })
        val location = listOfNotNull(
            feature.blocoId.takeIf { it.isNotBlank() }?.let(::nomeBlocoLegivel),
            feature.torreId.takeIf { it.isNotBlank() }?.let(::nomeTorreLegivel),
            feature.andar?.let { "${andarLabel(it)}º andar" },
        )
        if (location.isNotEmpty()) append("\n").append(location.joinToString(" • "))
    }

    private fun showSearchMatches(query: String, field: EditText) {
        val matches = findSearchMatches(query)
        activeSearchField = field
        searchResults.clear()
        searchResults.addAll(matches)
        searchPopup?.anchorView = field
        searchPopup?.setAdapter(ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            matches.map { formatSearchResult(it) },
        ))
        if (matches.isNotEmpty() && field.hasFocus()) {
            searchPopup?.show()
            searchPopup?.listView?.apply {
                divider = ColorDrawable(Color.parseColor("#DADADA"))
                dividerHeight = (1 * resources.displayMetrics.density).toInt()
            }
        } else {
            searchPopup?.dismiss()
        }
    }

    private fun calculateRoute() {
        val origin = selectedOrigin
        val destination = selectedDestination
        if (origin == null || destination == null) {
            Toast.makeText(requireContext(), "Selecione uma origem e um destino nas sugestões", Toast.LENGTH_SHORT).show()
            return
        }
        if (origin.blocoId != "bloco_a" || destination.blocoId != "bloco_a") {
            Toast.makeText(requireContext(), "O protótipo calcula rotas apenas no Bloco A", Toast.LENGTH_SHORT).show()
            return
        }
        val plan = indoorRoutePlanner?.findRoute(
            origin.codigo,
            destination.codigo,
            "bloco_a",
            origin.andar,
            destination.andar,
        )
        if (plan == null) {
            Toast.makeText(requireContext(), "Não foi encontrada uma conexão entre esses locais", Toast.LENGTH_SHORT).show()
            return
        }

        currentRoutePlan = plan
        val style = map?.style ?: return
        style.getSourceAs<GeoJsonSource>(SEARCH_SOURCE_ID)?.setGeoJson(destination.featureJson)
        val range = blockFloorRanges["bloco_a"] ?: (0 to 4)
        val destinationFloor = destination.andar ?: plan.floorSequence.lastOrNull() ?: 0
        selectBloco("bloco_a", range.first, range.second)
        if (destinationFloor in minAndar..maxAndar) onFloorSelected(destinationFloor)
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(campusCameraPosition(destination.center, ZOOM_DESTINO)),
            700,
        )
        showRoomDetails(
            "Rota até " + destination.nome,
            destination.blocoId,
            destination.torreId,
            destination.andar,
        )
        val floors = plan.floorSequence.joinToString(" → ") { floor ->
            if (floor == 0) "Térreo" else "${floor}º andar"
        }
        binding.tvRoomLocation.text =
            (listOf("Andares: $floors") + plan.transferInstructions).joinToString("\n")
        Toast.makeText(requireContext(), "Rota calculada", Toast.LENGTH_SHORT).show()
    }

    private fun invalidateRoute() {
        currentRoutePlan = null
        map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
        )
        hideRoomDetails()
    }

    private fun renderRouteForFloor() {
        val points = currentRoutePlan?.coordinatesByFloor?.get(selectedAndar).orEmpty()
        val source = map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        if (points.size < 2) {
            source.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            return
        }
        val coordinates = org.json.JSONArray()
        points.forEach { coordinates.put(org.json.JSONArray().put(it.longitude).put(it.latitude)) }
        val geometry = JSONObject().put("type", "LineString").put("coordinates", coordinates)
        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometry)
        source.setGeoJson(feature.toString())
    }

    private fun hideKeyboard() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    // ─── Helpers de nomenclatura ───────────────────────────────────────────

    private fun resetToOverview() {
        selectedBlocoId = null
        binding.cardFloorSelector.isVisible = false
        invalidateRoute()
        hideRoomDetails()

        val style = map?.style ?: return

        val hiddenRooms = activeFloorFeatureFilter("sala")
        (style.getLayer(LAYER_ROOMS_FILL) as? FillLayer)?.setFilter(hiddenRooms)
        (style.getLayer(LAYER_ROOMS_LINE) as? LineLayer)?.setFilter(hiddenRooms)

        val hiddenEntradas = activeFloorFeatureFilter("entrada")
        (style.getLayer(LAYER_ENTRADAS_FILL) as? FillLayer)?.setFilter(hiddenEntradas)
        (style.getLayer(LAYER_ENTRADAS_LINE) as? LineLayer)?.setFilter(hiddenEntradas)

        val hiddenRampas = activeFloorFeatureFilter("rampa")
        (style.getLayer(LAYER_RAMPAS_FILL) as? FillLayer)?.setFilter(hiddenRampas)
        (style.getLayer(LAYER_RAMPAS_LINE) as? LineLayer)?.setFilter(hiddenRampas)

        val hiddenElevadores = activeFloorFeatureFilter("elevador")
        (style.getLayer(LAYER_ELEVADORES_FILL) as? FillLayer)?.setFilter(hiddenElevadores)
        (style.getLayer(LAYER_ELEVADORES_LINE) as? LineLayer)?.setFilter(hiddenElevadores)

        val overviewFilter = overviewBlocksFilter()
        (style.getLayer(LAYER_BLOCOS_FILL) as? FillLayer)?.apply {
            setFilter(overviewFilter)
            setProperties(PropertyFactory.fillOpacity(0.55f))
        }
        (style.getLayer(LAYER_BLOCOS_LINE) as? LineLayer)?.setFilter(overviewFilter)

        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                campusCameraPosition(CAMPUS_CENTER, ZOOM_CAMPUS)
            ),
            800
        )
    }

    private fun campusCameraPosition(target: LatLng, zoom: Double): CameraPosition =
        CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .bearing(BEARING_CAMPUS)
            .build()

    private fun nomeBlocoLegivel(id: String) =
        id.replace("bloco_", "Bloco ").replaceFirstChar { it.uppercaseChar() }

    private fun nomeTorreLegivel(id: String) =
        id.replace("torre_", "Torre ").replaceFirstChar { it.uppercaseChar() }

    // ─── Ciclo de vida do MapView ──────────────────────────────────────────

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }

    override fun onDestroyView() {
        binding.mapView.onDestroy()
        super.onDestroyView()
        _binding = null
        map = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }
}