package com.hmgapp.yeogiseotayo.engine

/** 표준노드링크 노드. type 101 = 교차로 */
data class Node(val id: String, val type: String, val pos: LatLng, val name: String) {
    val isIntersection get() = type == "101"
}

/**
 * 표준노드링크 링크 (한 방향). 좌표는 진행 방향 순서라 "진행 방향 오른쪽 = 길가(정차하는 쪽)".
 * lanes 는 한 방향 차로 수.
 */
data class Link(
    val id: String, val from: String, val to: String, val lanes: Int, val roadRank: String,
    val roadType: String, val maxSpeedKmh: Int, val connect: Int, val roadUse: Int,
    val name: String, val pts: List<LatLng>,
) {
    val length: Double = Geo.polylineLength(pts)

    /** 고속·도시고속도로, 고가·터널·지하차도, 연결로(램프), 전용도로는 태우는 곳 후보에서 뺀다 */
    val pickupAllowed: Boolean
        get() = roadRank !in setOf("101", "102") && roadType == "000" && connect == 0 && roadUse == 0

    /** 도심 평균 주행 속도 (제한속도의 60%, 최소 15km/h) m/s */
    val travelSpeed: Double get() = maxOf(15.0, maxSpeedKmh * 0.6) / 3.6

    /** 양방향 도로를 하나로 묶는 열쇠 (횡단 판정 중복 방지) */
    val roadKey: String get() = if (from < to) "$from-$to" else "$to-$from"
}

data class Crosswalk(val pos: LatLng, val lanes: Int, val signal: Boolean)
data class BusStop(val id: String, val pos: LatLng, val name: String)

/**
 * 현장 확인(로드뷰 등)으로 찾은 정차 불가 길가 구간. 공공데이터에 없는 가드레일·펜스·택시 승강장 등.
 * 길가 지점이 이 선분에서 [widthM] 안이면 후보에서 뺀다.
 */
data class NoStopZone(val a: LatLng, val b: LatLng, val reason: String, val checkedOn: String, val widthM: Double = 6.0)

/** 현장 확인으로 "잠깐 정차 가능"이 확인된 길가 구간 (노란 점선, 흰 선, 승하차 구역 등) */
data class CurbOkZone(val a: LatLng, val b: LatLng, val reason: String, val checkedOn: String, val widthM: Double = 6.0)

/**
 * 도로 밖에서 태우는 곳 (주차장·건물 현관 앞 회차로 등). 차는 [entrance]로 들어가고, 사람은 [pickup]에서 탄다.
 * 도로를 막지 않으므로 정차 위험이 없다.
 */
data class ParkingSpot(
    val name: String, val entrance: LatLng, val pickup: LatLng,
    val note: String, val checkedOn: String, val verified: Boolean,
)

class RoadMap(
    val nodes: Map<String, Node>,
    val links: List<Link>,
    val crosswalks: List<Crosswalk>,
    val busStops: List<BusStop>,
    val noStopZones: List<NoStopZone> = emptyList(),
    val okZones: List<CurbOkZone> = emptyList(),
    val parkingSpots: List<ParkingSpot> = emptyList(),
) {
    /** 이 길가 지점이 현장 확인 정차 불가 구간 안이면 그 구간 */
    fun noStopZoneAt(curb: LatLng): NoStopZone? =
        noStopZones.firstOrNull { Geo.distToSegment(curb, it.a, it.b).first <= it.widthM }

    /** 이 길가 지점이 현장 확인 "정차 가능" 구간 안이면 그 구간 */
    fun okZoneAt(curb: LatLng): CurbOkZone? =
        okZones.firstOrNull { Geo.distToSegment(curb, it.a, it.b).first <= it.widthM }

    val outLinks: Map<String, List<Link>> = links.groupBy { it.from }
    val inLinks: Map<String, List<Link>> = links.groupBy { it.to }

    /** 위치·진행 방향과 가장 잘 맞는 링크와 그 링크 위 위치(m) */
    fun snap(pos: LatLng, headingDeg: Double?, maxDistM: Double = 40.0): Pair<Link, Double>? {
        var best: Triple<Link, Double, Double>? = null // link, along, cost
        for (l in links) {
            var acc = 0.0
            for (i in 1 until l.pts.size) {
                val a = l.pts[i - 1]; val b = l.pts[i]
                val seg = Geo.dist(a, b)
                val (d, t) = Geo.distToSegment(pos, a, b)
                if (d <= maxDistM) {
                    val headPenalty = headingDeg?.let { h ->
                        val diff = Geo.angleDiff(h, Geo.bearing(a, b))
                        if (diff > 70) Double.POSITIVE_INFINITY else diff * 0.3
                    } ?: 0.0
                    val cost = d + headPenalty
                    if (cost < (best?.third ?: Double.POSITIVE_INFINITY)) best = Triple(l, acc + seg * t, cost)
                }
                acc += seg
            }
        }
        return best?.let { it.first to it.second }
    }

    companion object {
        /** assets/map_*.txt 형식 (tools/build_map_asset.py 참고) */
        fun parse(text: String): RoadMap {
            val nodes = HashMap<String, Node>()
            val links = ArrayList<Link>()
            val cws = ArrayList<Crosswalk>()
            val bss = ArrayList<BusStop>()
            val zones = ArrayList<NoStopZone>()
            val oks = ArrayList<CurbOkZone>()
            val parks = ArrayList<ParkingSpot>()
            for (raw in text.lineSequence()) {
                if (raw.isBlank() || raw.startsWith("#")) continue
                val f = raw.split('|')
                when (f[0]) {
                    "N" -> nodes[f[1]] = Node(f[1], f[2], LatLng(f[3].toDouble(), f[4].toDouble()), f.getOrElse(5) { "" })
                    "L" -> links += Link(
                        id = f[1], from = f[2], to = f[3], lanes = f[4].toIntOrNull() ?: 1, roadRank = f[5],
                        roadType = f[6], maxSpeedKmh = f[7].toIntOrNull() ?: 30, connect = f[8].toIntOrNull() ?: 0,
                        roadUse = f[9].toIntOrNull() ?: 0, name = f[10],
                        pts = f[11].split(';').map { p -> p.split(',').let { LatLng(it[0].toDouble(), it[1].toDouble()) } },
                    )
                    "C" -> cws += Crosswalk(LatLng(f[1].toDouble(), f[2].toDouble()), f[3].toIntOrNull() ?: 0, f[4] == "Y")
                    "B" -> bss += BusStop(f[1], LatLng(f[2].toDouble(), f[3].toDouble()), f[4])
                    // X|위도1|경도1|위도2|경도2|이유|확인한 날|폭m(선택, 기본 6)
                    "X" -> zones += NoStopZone(
                        LatLng(f[1].toDouble(), f[2].toDouble()), LatLng(f[3].toDouble(), f[4].toDouble()),
                        f.getOrElse(5) { "현장 확인" }, f.getOrElse(6) { "" }, f.getOrNull(7)?.toDoubleOrNull() ?: 6.0,
                    )
                    // O|위도1|경도1|위도2|경도2|이유|확인한 날|폭m(선택) — 정차 가능 확인 구간
                    "O" -> oks += CurbOkZone(
                        LatLng(f[1].toDouble(), f[2].toDouble()), LatLng(f[3].toDouble(), f[4].toDouble()),
                        f.getOrElse(5) { "정차 가능" }, f.getOrElse(6) { "" }, f.getOrNull(7)?.toDoubleOrNull() ?: 6.0,
                    )
                    // P|이름|입구위도|입구경도|타는곳위도|타는곳경도|메모|확인한 날|확인여부(Y/N)
                    "P" -> parks += ParkingSpot(
                        f[1], LatLng(f[2].toDouble(), f[3].toDouble()), LatLng(f[4].toDouble(), f[5].toDouble()),
                        f.getOrElse(6) { "" }, f.getOrElse(7) { "" }, f.getOrElse(8) { "N" } == "Y",
                    )
                }
            }
            return RoadMap(nodes, links, cws, bss, zones, oks, parks)
        }
    }
}
