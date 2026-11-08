package com.hmgapp.yeogiseotayo.engine

import kotlin.math.roundToInt

/** 타는 사람이 누르는 "사정 버튼" */
enum class Need { RAIN, LUGGAGE, HARD_TO_WALK, NOT_READY }

data class PickupRequest(
    val car: LatLng,
    val carHeadingDeg: Double,
    val rider: LatLng,
    /** 원래 목적지(집 등). 있으면 "돌아가는 시간"까지 계산 */
    val destination: LatLng? = null,
    val needs: Set<Need> = emptySet(),
)

/** 조정 가능한 값 모음. 근거는 각 줄 주석 */
data class EngineConfig(
    val sampleStepM: Double = 10.0,
    val walkSpeedMps: Double = 1.2,          // 보통 걸음
    val walkDetour: Double = 1.3,            // 직선 거리 → 실제 걷는 거리 보정
    val maxWalkSec: Double = 180.0,          // 기본 걷기 상한 3분
    val hardWalkMaxSec: Double = 60.0,       // "걷기 힘들어요" 1분
    // 도로교통법 제32조: 교차로 가장자리·도로 모퉁이 5m, 횡단보도 10m, 버스정류장 표지 10m 이내 정차 금지
    val intersectionClearM: Double = 15.0,   // 노드는 교차로 중심이라 5m + 교차로 반폭 여유
    val crosswalkClearM: Double = 12.0,      // 10m + 횡단보도 반폭
    val busStopClearM: Double = 12.0,
    val busStopCautionM: Double = 40.0,      // 이 안이면 버스가 들어오므로 감점
    val laneWidthM: Double = 3.2,
    val stopBaseSec: Double = 120.0,         // 정차 위험 벌점의 기준값
    val crossingBaseSec: Double = 25.0,      // 길 하나 건널 때 신호 대기 등
    val crossingPerLaneSec: Double = 6.0,
    val alternativeMinGapM: Double = 40.0,
    val verifiedOkBonusSec: Double = 90.0,   // 현장에서 "정차 가능"이 확인된 길가 우선
    val unverifiedPenaltySec: Double = 0.0,  // 확인 안 된 길가 감점 (기본 0: 공공데이터만으로 판단)
    val parkingEntrySec: Double = 60.0,      // 주차장: 입구 차단기·안쪽 이동·회차해 나오는 시간
    val parkingSnapM: Double = 40.0,         // 주차장 입구를 도로에 맞출 때 최대 거리
)

/** 길가에서 태우는가, 도로 밖(주차장·현관 앞)에서 태우는가 */
enum class CandidateKind { CURB, OFF_ROAD }

/** 이 길가를 현장에서 확인했는가 */
enum class FieldStatus { VERIFIED_OK, UNVERIFIED }

data class Candidate(
    val curb: LatLng,             // 실제로 서는 길가 지점
    val road: RoadPoint,
    val walkSec: Double,
    val crossings: List<String>,  // 건너야 하는 도로 이름
    val crossingSec: Double,
    val carToHereSec: Double,
    val carExtraSec: Double,      // 바로 목적지로 갈 때보다 더 걸리는 시간
    val stopPenaltySec: Double,
    val nearestCrosswalkM: Double,
    val nearestBusStopM: Double,
    val score: Double,
    val field: FieldStatus = FieldStatus.UNVERIFIED,
    val fieldNote: String = "현장 미확인",
    val kind: CandidateKind = CandidateKind.CURB,
    /** 사람에게 보여 줄 장소 이름 (주차장 이름, 없으면 길 이름) */
    val placeName: String = road.link.name.ifBlank { "이름 없는 길" },
) {
    fun summary(): String = buildString {
        if (kind == CandidateKind.OFF_ROAD) append("$placeName · 도로 밖(입구: ${road.link.name.ifBlank { "이름 없는 길" }})")
        else append("$placeName · 한 방향 ${road.link.lanes}차로 · 제한 ${road.link.maxSpeedKmh}km/h")
        append(" · 걷기 ${fmt(walkSec)}")
        if (crossings.isNotEmpty()) append(" (${crossings.joinToString()} 건넘)")
        append(" · 차 추가 ${fmt(carExtraSec)} · 정차위험 ${stopPenaltySec.roundToInt()}")
        if (kind == CandidateKind.CURB) append(" · 횡단보도 ${nearestCrosswalkM.roundToInt()}m")
        append(" · $fieldNote")
    }

    companion object {
        fun fmt(sec: Double): String {
            val s = sec.roundToInt().coerceAtLeast(0)
            return if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"
        }
    }
}

data class PickupResult(
    val best: Candidate?,
    val alternatives: List<Candidate>,
    val considered: Int,
    val rejected: Map<String, Int>,
    val carStart: RoadPoint?,
    /** 목적지를 지도 위에서 찾아 "돌아가는 시간"까지 계산했는가 (지도 밖이면 false) */
    val destinationUsed: Boolean = false,
    /** "걷기 힘들어요"인데 상한 안에 설 곳이 없어 기본 상한으로 넓혀 찾았는가 */
    val relaxedWalk: Boolean = false,
    /** 운전자·타는 사람에게 그대로 보여 줄 수 있는 한 줄 설명 */
    val message: String = "",
)

/**
 * 만남 장소 한 점을 고르는 판단 엔진. 안드로이드에 의존하지 않는 순수 계산.
 * 점수 = 걷는 시간×가중 + 길 건너기×가중 + 차가 더 가는 시간 + 정차 위험 벌점 (낮을수록 좋음)
 */
class PickupEngine(private val map: RoadMap, private val cfg: EngineConfig = EngineConfig()) {

    private val router = Router(map)
    private val intersections = map.nodes.values.filter { it.isIntersection }

    fun choose(req: PickupRequest): PickupResult {
        val rejected = linkedMapOf<String, Int>()
        fun reject(why: String) { rejected[why] = (rejected[why] ?: 0) + 1 }

        val carStart = map.snap(req.car, req.carHeadingDeg)?.let { RoadPoint(it.first, it.second) }
            ?: return PickupResult(null, emptyList(), 0, mapOf("차 위치를 도로에 맞출 수 없음" to 1), null)
        val fwd = router.forward(carStart)
        val bwd = req.destination?.let { d -> map.snap(d, null)?.let { router.backward(RoadPoint(it.first, it.second)) } }
        val directToDest = bwd?.timeFrom(carStart)

        val hard = Need.HARD_TO_WALK in req.needs
        val first = collect(req, if (hard) cfg.hardWalkMaxSec else cfg.maxWalkSec, fwd, bwd, directToDest, rejected)
        var cands = first.first
        var considered = first.second
        var relaxed = false
        if (cands.isEmpty() && hard) {
            // 1분 안에 합법적으로 설 곳이 없음 → 기본 상한으로 넓혀 가장 가까운 곳을 찾고 솔직하게 알린다
            val second = collect(req, cfg.maxWalkSec, fwd, bwd, directToDest, linkedMapOf())
            cands = second.first; considered += second.second; relaxed = cands.isNotEmpty()
        }
        cands.sortBy { it.score }
        val best = cands.firstOrNull()
        val alts = ArrayList<Candidate>()
        for (c in cands.drop(1)) {
            if (alts.size >= 3) break
            if ((listOfNotNull(best) + alts).all { Geo.dist(it.curb, c.curb) >= cfg.alternativeMinGapM }) alts += c
        }
        val msg = when {
            best == null -> "걸어서 ${Candidate.fmt(cfg.maxWalkSec)} 안에 합법적으로 설 수 있는 길가가 없어요"
            relaxed -> "가까운 길가는 모두 정차 금지예요. 가장 가까운 곳은 걸어서 ${Candidate.fmt(best.walkSec)}이에요"
            best.kind == CandidateKind.OFF_ROAD -> "${best.placeName} · 걷기 ${Candidate.fmt(best.walkSec)}"
            else -> "${best.placeName} 길가 · 걷기 ${Candidate.fmt(best.walkSec)}"
        }
        return PickupResult(best, alts, considered, rejected, carStart, destinationUsed = directToDest != null,
            relaxedWalk = relaxed, message = msg)
    }

    private fun collect(
        req: PickupRequest, maxWalk: Double, fwd: Router.Forward, bwd: Router.Backward?, directToDest: Double?,
        rejected: MutableMap<String, Int>,
    ): Pair<ArrayList<Candidate>, Int> {
        fun reject(why: String) { rejected[why] = (rejected[why] ?: 0) + 1 }
        val hard = Need.HARD_TO_WALK in req.needs
        val walkSpeed = cfg.walkSpeedMps * when {
            hard -> 0.7
            Need.LUGGAGE in req.needs || Need.RAIN in req.needs -> 0.9
            else -> 1.0
        }
        val wWalk = 1.0 * (if (Need.RAIN in req.needs) 1.4 else 1.0) * (if (Need.LUGGAGE in req.needs) 1.3 else 1.0) * (if (hard) 2.0 else 1.0)
        val wCross = (if (Need.LUGGAGE in req.needs) 1.5 else 1.0) * (if (hard) 2.0 else 1.0)
        val wStop = if (Need.NOT_READY in req.needs) 1.5 else 1.0
        val searchRadius = maxWalk * walkSpeed / cfg.walkDetour

        val cands = ArrayList<Candidate>()
        var considered = 0
        for (link in map.links) {
            // 꼭짓점이 아니라 선분까지의 거리로 본다 (긴 도로 한가운데에 사람이 있어도 빠뜨리지 않게)
            if ((1 until link.pts.size).none { Geo.distToSegment(req.rider, link.pts[it - 1], link.pts[it]).first < searchRadius + 60 }) continue
            if (!link.pickupAllowed) { reject("고속·고가·램프 등 정차 불가 도로"); continue }
            var next = cfg.sampleStepM / 2
            while (next < link.length) {
                val at = next
                next += cfg.sampleStepM
                considered++
                val (center, heading) = Geo.pointAlong(link.pts, at)
                // 진행 방향 오른쪽 길가 = 한 방향 차로 폭만큼 오른쪽
                val curb = Geo.offset(center, heading + 90, link.lanes * cfg.laneWidthM)

                val walkStraight = Geo.dist(curb, req.rider)
                val walkSec = walkStraight * cfg.walkDetour / walkSpeed
                if (walkSec > maxWalk) { reject("걷기 상한 초과"); continue }
                if (intersections.any { Geo.dist(it.pos, center) < cfg.intersectionClearM }) { reject("교차로 주변 정차 금지"); continue }
                val cw = map.crosswalks.minOfOrNull { Geo.dist(it.pos, center) } ?: Double.POSITIVE_INFINITY
                if (cw < cfg.crosswalkClearM) { reject("횡단보도 주변 정차 금지"); continue }
                val bs = map.busStops.minOfOrNull { Geo.dist(it.pos, curb) } ?: Double.POSITIVE_INFINITY
                if (bs < cfg.busStopClearM) { reject("버스정류장 주변 정차 금지"); continue }
                val zone = map.noStopZoneAt(curb)
                if (zone != null) { reject("현장 확인: ${zone.reason}"); continue }

                val here = RoadPoint(link, at)
                val carTo = fwd.timeTo(here)
                if (carTo == null) { reject("차가 갈 수 없는 방향"); continue }
                var extra = carTo
                if (bwd != null && directToDest != null) {
                    val after = bwd.timeFrom(here)
                    if (after == null) { reject("목적지로 이어지지 않음"); continue }
                    extra = carTo + after - directToDest
                }

                val crossed = crossedRoads(req.rider, curb, link)
                val crossSec = crossed.sumOf { cfg.crossingBaseSec + cfg.crossingPerLaneSec * it.second }
                val stop = stopPenalty(link, bs) * wStop

                val ok = map.okZoneAt(curb)
                val field = if (ok != null) FieldStatus.VERIFIED_OK else FieldStatus.UNVERIFIED
                val fieldScore = if (ok != null) -cfg.verifiedOkBonusSec else cfg.unverifiedPenaltySec
                val score = walkSec * wWalk + crossSec * wCross + extra + stop + fieldScore
                cands += Candidate(curb, here, walkSec, crossed.map { it.first }, crossSec, carTo, extra, stop, cw, bs, score,
                    field, ok?.let { "현장 확인: ${it.reason}" } ?: "현장 미확인")
            }
        }
        // 도로 밖에서 태우는 곳 (주차장·현관 앞)
        for (p in map.parkingSpots) {
            considered++
            val walkSec = Geo.dist(p.pickup, req.rider) * cfg.walkDetour / walkSpeed
            if (walkSec > maxWalk) { reject("걷기 상한 초과"); continue }
            val snapped = map.snap(p.entrance, null, cfg.parkingSnapM)
            if (snapped == null) { reject("주차장 입구를 도로에 맞출 수 없음"); continue }
            val gate = RoadPoint(snapped.first, snapped.second)
            val toGate = fwd.timeTo(gate)
            if (toGate == null) { reject("차가 갈 수 없는 방향"); continue }
            val carTo = toGate + cfg.parkingEntrySec
            var extra = carTo
            if (bwd != null && directToDest != null) {
                val after = bwd.timeFrom(gate)
                if (after == null) { reject("목적지로 이어지지 않음"); continue }
                extra = carTo + after - directToDest
            }
            val crossed = crossedRoads(req.rider, p.pickup, gate.link)
            val crossSec = crossed.sumOf { cfg.crossingBaseSec + cfg.crossingPerLaneSec * it.second }
            val fieldScore = if (p.verified) -cfg.verifiedOkBonusSec else cfg.unverifiedPenaltySec
            val score = walkSec * wWalk + crossSec * wCross + extra + 0.0 + fieldScore
            cands += Candidate(
                p.pickup, gate, walkSec, crossed.map { it.first }, crossSec, carTo, extra, 0.0,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, score,
                if (p.verified) FieldStatus.VERIFIED_OK else FieldStatus.UNVERIFIED,
                if (p.verified) "현장 확인: ${p.note}" else "현장 미확인",
                CandidateKind.OFF_ROAD, p.name,
            )
        }
        return cands to considered
    }

    /** 도로가 빠르고 넓은 간선일수록, 한 방향 차로가 적어 서면 길을 다 막을수록 위험 */
    fun stopPenalty(link: Link, nearestBusStopM: Double): Double {
        val flow = when {
            link.maxSpeedKmh <= 30 -> 0.3
            link.maxSpeedKmh <= 40 -> 0.5
            link.maxSpeedKmh <= 50 -> 0.8
            link.maxSpeedKmh <= 60 -> 1.0
            else -> 1.5
        }
        val block = when (link.lanes) { 1 -> 1.0; 2 -> 0.6; else -> 0.4 }
        val rank = when (link.roadRank) { "103", "104" -> 1.3; "108" -> 0.8; else -> 1.0 }
        val bus = if (nearestBusStopM < cfg.busStopCautionM) 20.0 else 0.0
        return cfg.stopBaseSec * flow * block * rank + bus
    }

    /** 타는 사람 → 길가 직선이 가로지르는 도로들 (양방향 도로는 한 번만, 차로 수는 합산) */
    private fun crossedRoads(rider: LatLng, curb: LatLng, own: Link): List<Pair<String, Int>> {
        val hit = LinkedHashMap<String, Pair<String, Int>>()
        for (l in map.links) {
            if (!l.pickupAllowed && l.roadType != "000") continue // 고가·지하차도는 아래/위로 지나감
            var crosses = false
            for (i in 1 until l.pts.size) {
                if (Geo.segmentsCross(rider, curb, l.pts[i - 1], l.pts[i])) { crosses = true; break }
            }
            if (!crosses) continue
            // 양방향이 따로 그려진 넓은 길도 이름이 같으면 한 번 건너는 것으로 본다
            val key = l.name.ifBlank { l.roadKey }
            val prev = hit[key]
            hit[key] = (l.name.ifBlank { "이름 없는 길" }) to ((prev?.second ?: 0) + l.lanes)
        }
        // 서는 링크 자체는 길가 쪽이라, 직선이 그 도로 중앙선을 넘을 때만 "건넘"이 된다
        return hit.values.toList()
    }
}
