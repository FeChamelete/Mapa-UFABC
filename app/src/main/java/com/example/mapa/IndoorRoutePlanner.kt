package com.example.mapa

import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot

internal class IndoorRoutePlanner(pathsGeoJson: String, campusGeoJson: String) {
    internal data class Coordinate(val longitude: Double, val latitude: Double)
    internal data class RoutePlan(
        val coordinatesByFloor: Map<Int, List<Coordinate>>,
        val floorSequence: List<Int>,
        val transferInstructions: List<String>,
    )

    private data class Vertex(
        val coordinate: Coordinate,
        val floor: Int,
        val blockId: String,
        val towerId: String,
        val destinationCode: String? = null,
    )
    private data class Edge(val to: Int, val cost: Double, val instruction: String? = null)

    private val vertices = mutableListOf<Vertex>()
    private val graph = mutableListOf<MutableList<Edge>>()
    private val walkwaySegments = mutableListOf<Pair<Int, Int>>()
    private val entriesByDestination = mutableMapOf<String, MutableList<Int>>()
    private val fallbackCoordinatesByCode = readCampusCenters(campusGeoJson)

    init {
        readPaths(pathsGeoJson)
        connectNearbyWalkways()
        connectVerticalTransfers()
    }

    fun hasLocation(code: String): Boolean = entriesByDestination.containsKey(code)

    fun findRoute(
        originCode: String,
        destinationCode: String,
        blockId: String,
        originFloor: Int? = null,
        destinationFloor: Int? = null,
    ): RoutePlan? {
        val origins = entriesByDestination[originCode].orEmpty().filter {
            vertices[it].blockId == blockId && (originFloor == null || vertices[it].floor == originFloor)
        }
        val destinations = entriesByDestination[destinationCode].orEmpty().filter {
            vertices[it].blockId == blockId && (destinationFloor == null || vertices[it].floor == destinationFloor)
        }
        if (origins.isEmpty() || destinations.isEmpty()) return null

        val distances = DoubleArray(vertices.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(vertices.size) { -1 }
        val previousInstruction = arrayOfNulls<String>(vertices.size)
        val queue = PriorityQueue(compareBy<Pair<Int, Double>> { it.second })
        origins.forEach { distances[it] = 0.0; queue += it to 0.0 }
        val destinationSet = destinations.toHashSet()
        var reached = -1

        while (queue.isNotEmpty()) {
            val (current, cost) = queue.remove()
            if (cost != distances[current]) continue
            if (current in destinationSet) { reached = current; break }
            for (edge in graph[current]) {
                val candidate = cost + edge.cost
                if (candidate < distances[edge.to]) {
                    distances[edge.to] = candidate
                    previous[edge.to] = current
                    previousInstruction[edge.to] = edge.instruction
                    queue += edge.to to candidate
                }
            }
        }
        if (reached < 0) return null

        val routeNodes = mutableListOf<Int>()
        val instructions = mutableListOf<String>()
        var cursor = reached
        while (cursor >= 0) {
            routeNodes += cursor
            previousInstruction[cursor]?.let(instructions::add)
            cursor = previous[cursor]
        }
        routeNodes.reverse()
        instructions.reverse()

        val pointsByFloor = linkedMapOf<Int, MutableList<Coordinate>>()
        val floorSequence = mutableListOf<Int>()
        routeNodes.forEach { nodeId ->
            val vertex = vertices[nodeId]
            if (floorSequence.lastOrNull() != vertex.floor) floorSequence += vertex.floor
            val points = pointsByFloor.getOrPut(vertex.floor) { mutableListOf() }
            if (points.lastOrNull() != vertex.coordinate) points += vertex.coordinate
        }
        return RoutePlan(pointsByFloor.mapValues { it.value.toList() }, floorSequence, instructions)
    }

    private fun readPaths(geoJson: String) {
        val features = JSONObject(geoJson).optJSONArray("features") ?: return
        val pathNodesByLevel = mutableMapOf<String, MutableList<Int>>()
        val entryNodes = mutableListOf<Int>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val floor = properties.optInt("andar", -1)
            if (floor !in 0..4 || properties.optString("bloco_id") != "bloco_a") continue
            val blockId = properties.optString("bloco_id")
            val towerId = properties.optString("torre_id")
            val geometry = feature.optJSONObject("geometry") ?: continue
            when (properties.optString("tipo")) {
                "caminho" -> {
                    val coordinates = geometry.optJSONArray("coordinates")?.asLineCoordinates() ?: continue
                    if (coordinates.size < 2) continue
                    val levelKey = levelKey(blockId, towerId, floor)
                    val lineNodes = coordinates.map { coordinate ->
                        addVertex(Vertex(coordinate, floor, blockId, towerId)).also {
                            pathNodesByLevel.getOrPut(levelKey) { mutableListOf() }.add(it)
                        }
                    }
                    lineNodes.zipWithNext().forEach { (from, to) ->
                        addEdge(from, to, distanceMeters(vertices[from].coordinate, vertices[to].coordinate))
                        walkwaySegments.add(from to to)
                    }
                }
                "ponto_entrada" -> {
                    val destination = properties.optString("destino_codigo")
                        .takeUnless { it.isBlank() || it == "null" } ?: continue
                    val coordinate = geometry.optJSONArray("coordinates")?.asCoordinate()
                        ?: fallbackCoordinatesByCode[destination]
                        ?: continue
                    val node = addVertex(Vertex(coordinate, floor, blockId, towerId, destination))
                    entriesByDestination.getOrPut(destination) { mutableListOf() }.add(node)
                    entryNodes += node
                }
            }
        }

        for (entry in entryNodes) {
            val point = vertices[entry]
            val candidates = pathNodesByLevel[levelKey(point.blockId, point.towerId, point.floor)].orEmpty()
            val nearest = candidates.minByOrNull { distanceMeters(point.coordinate, vertices[it].coordinate) } ?: continue
            val distance = distanceMeters(point.coordinate, vertices[nearest].coordinate)
            if (distance <= MAX_ENTRANCE_SNAP_METERS) addEdge(entry, nearest, distance)
        }
    }

    private fun readCampusCenters(geoJson: String): Map<String, Coordinate> {
        val result = mutableMapOf<String, Coordinate>()
        val features = JSONObject(geoJson).optJSONArray("features") ?: return result
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val code = properties.optString("codigo").takeUnless { it.isBlank() || it == "null" } ?: continue
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val points = mutableListOf<Coordinate>()
            fun collect(value: JSONArray) {
                if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
                    points += Coordinate(value.optDouble(0), value.optDouble(1))
                } else {
                    for (index in 0 until value.length()) value.optJSONArray(index)?.let(::collect)
                }
            }
            collect(coordinates)
            if (points.isEmpty()) continue
            val center = Coordinate(
                (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2.0,
                (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2.0,
            )
            result.putIfAbsent(code, center)
        }
        return result
    }

    private fun connectNearbyWalkways() {
        val walkwayNodes = vertices.indices.filter { vertices[it].destinationCode == null }
        val nodesByLevel = walkwayNodes.groupBy {
            levelKey(vertices[it].blockId, vertices[it].towerId, vertices[it].floor)
        }
        val segmentsByLevel = walkwaySegments.groupBy { (from, _) ->
            levelKey(vertices[from].blockId, vertices[from].towerId, vertices[from].floor)
        }

        for ((level, nodes) in nodesByLevel) {
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                val from = nodes[i]
                val to = nodes[j]
                val distance = distanceMeters(vertices[from].coordinate, vertices[to].coordinate)
                if (distance in 0.01..MAX_WALKWAY_JOIN_METERS) addEdge(from, to, distance)
            }

            // Corridor branches often meet the middle of a long corridor segment,
            // so endpoint-to-endpoint proximity alone leaves them disconnected.
            val segments = segmentsByLevel[level].orEmpty()
            for (node in nodes) {
                val point = vertices[node].coordinate
                for ((start, end) in segments) {
                    if (node == start || node == end) continue
                    val projection = projectOntoSegment(
                        point,
                        vertices[start].coordinate,
                        vertices[end].coordinate,
                    )
                    if (projection.distanceMeters > MAX_WALKWAY_JOIN_METERS) continue
                    val segmentLength = distanceMeters(vertices[start].coordinate, vertices[end].coordinate)
                    addEdge(
                        node,
                        start,
                        projection.distanceMeters + projection.fraction * segmentLength,
                    )
                    addEdge(
                        node,
                        end,
                        projection.distanceMeters + (1.0 - projection.fraction) * segmentLength,
                    )
                }
            }
        }
    }

    private data class SegmentProjection(
        val fraction: Double,
        val distanceMeters: Double,
    )

    private fun projectOntoSegment(
        point: Coordinate,
        start: Coordinate,
        end: Coordinate,
    ): SegmentProjection {
        val latitudeScale = METERS_PER_DEGREE
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(point.latitude))
        val startX = start.longitude * longitudeScale
        val startY = start.latitude * latitudeScale
        val endX = end.longitude * longitudeScale
        val endY = end.latitude * latitudeScale
        val pointX = point.longitude * longitudeScale
        val pointY = point.latitude * latitudeScale
        val deltaX = endX - startX
        val deltaY = endY - startY
        val denominator = deltaX * deltaX + deltaY * deltaY
        if (denominator == 0.0) return SegmentProjection(0.0, distanceMeters(point, start))
        val fraction = (
            (pointX - startX) * deltaX + (pointY - startY) * deltaY
        ) / denominator
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val projectedX = startX + clampedFraction * deltaX
        val projectedY = startY + clampedFraction * deltaY
        return SegmentProjection(
            clampedFraction,
            hypot(pointX - projectedX, pointY - projectedY),
        )
    }

    private fun connectVerticalTransfers() {
        entriesByDestination.forEach { (destinationCode, nodeIds) ->
            if (!destinationCode.startsWith("elevadores_")) return@forEach
            for (i in nodeIds.indices) for (j in i + 1 until nodeIds.size) {
                val from = nodeIds[i]
                val to = nodeIds[j]
                if (vertices[from].floor == vertices[to].floor) continue
                val upOrDown = "Elevador: " + vertices[from].floor + "º → " + vertices[to].floor + "º andar"
                val reverse = "Elevador: " + vertices[to].floor + "º → " + vertices[from].floor + "º andar"
                addDirectedEdge(from, to, ELEVATOR_COST, upOrDown)
                addDirectedEdge(to, from, ELEVATOR_COST, reverse)
            }
        }

        val rampNodes = entriesByDestination.flatMap { (code, nodeIds) -> nodeIds.map { code to it } }
            .filter { it.first.startsWith("rampa") }
        val ground = rampNodes.filter { it.first == "rampa_acesso" && vertices[it.second].floor == 0 }
        val first = rampNodes.filter {
            (it.first == "rampas_acesso_1" || it.first == "rampa_acesso_1") && vertices[it.second].floor == 1
        }
        val second = rampNodes.filter {
            (it.first == "rampas_acesso_2" || it.first == "rampa_acesso_2") && vertices[it.second].floor == 2
        }
        ground.forEach { a -> first.forEach { b ->
            addRampPair(a.second, b.second, "Rampa: térreo → 1º andar", "Rampa: 1º → térreo")
        } }
        first.forEach { a -> second.forEach { b ->
            addRampPair(a.second, b.second, "Rampa: 1º → 2º andar", "Rampa: 2º → 1º andar")
        } }
    }

    private fun addRampPair(from: Int, to: Int, up: String, down: String) {
        addDirectedEdge(from, to, RAMP_COST, up)
        addDirectedEdge(to, from, RAMP_COST, down)
    }

    private fun addDirectedEdge(from: Int, to: Int, cost: Double, instruction: String) {
        if (graph[from].none { it.to == to }) graph[from] += Edge(to, cost, instruction)
    }

    private fun addVertex(vertex: Vertex): Int {
        vertices += vertex
        graph.add(mutableListOf())
        return vertices.lastIndex
    }

    private fun addEdge(from: Int, to: Int, cost: Double, instruction: String? = null) {
        if (from == to) return
        if (graph[from].none { it.to == to }) graph[from] += Edge(to, cost, instruction)
        if (graph[to].none { it.to == from }) graph[to] += Edge(from, cost, instruction)
    }

    private fun levelKey(blockId: String, towerId: String, floor: Int): String {
        val level = if (floor <= 2) "shared" else towerId
        return "$blockId|$floor|$level"
    }

    private fun JSONArray.asLineCoordinates(): List<Coordinate>? {
        val result = mutableListOf<Coordinate>()
        for (i in 0 until length()) result += optJSONArray(i)?.asCoordinate() ?: return null
        return result
    }

    private fun JSONArray.asCoordinate(): Coordinate? {
        if (length() < 2 || opt(0) !is Number || opt(1) !is Number) return null
        return Coordinate(optDouble(0), optDouble(1))
    }

    private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val meanLatitude = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dx = (a.longitude - b.longitude) * METERS_PER_DEGREE * cos(meanLatitude)
        val dy = (a.latitude - b.latitude) * METERS_PER_DEGREE
        return hypot(dx, dy)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val MAX_WALKWAY_JOIN_METERS = 2.5
        const val MAX_ENTRANCE_SNAP_METERS = 22.0
        const val ELEVATOR_COST = 8.0
        const val RAMP_COST = 5.0
    }
}
