package com.hmgapp.yeogiseotayo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PickupEngineTest {

    // ---------- 가짜 지도: 동서로 곧은 왕복 도로 하나 ----------
    // W(교차로) ──── 600m ──── E(교차로), 각 방향 2차로 50km/h. 양 끝에서 유턴 가능.
    private val origin = LatLng(37.5, 127.0)
    private fun at(xEast: Double, yNorth: Double) = Geo.fromXY(xEast, yNorth, origin)

    private fun straightRoad(crosswalkAt: Double? = null): RoadMap {
        val w = Node("W", "101", at(0.0, 0.0), "서쪽교차로")
        val e = Node("E", "101", at(600.0, 0.0), "동쪽교차로")
        val eastbound = Link("EB", "W", "E", 2, "104", "000", 50, 0, 0, "시험로", listOf(w.pos, at(300.0, 0.0), e.pos))
        val westbound = Link("WB", "E", "W", 2, "104", "000", 50, 0, 0, "시험로", listOf(e.pos, at(300.0, 0.0), w.pos))
        val cws = listOfNotNull(crosswalkAt?.let { Crosswalk(at(it, 0.0), 4, true) })
        return RoadMap(mapOf("W" to w, "E" to e), listOf(eastbound, westbound), cws, emptyList())
    }

    @Test fun 길가는_진행방향_오른쪽이다() {
        val map = straightRoad()
        // 차는 동쪽으로 진행, 타는 사람은 길 남쪽 (동쪽으로 갈 때 오른쪽 = 남쪽)
        val r = PickupEngine(map).choose(PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -25.0)))
        val best = assertNotNullAndGet(r.best)
        assertEquals("EB", best.road.link.id)
        assertTrue("길가는 도로 중심보다 남쪽", Geo.toXY(best.curb, origin).second < -5)
        assertTrue("길을 건너지 않음", best.crossings.isEmpty())
    }

    @Test fun 반대편에_세우려면_유턴_시간이_더_든다() {
        val map = straightRoad()
        // 타는 사람이 길 북쪽: 북쪽 길가(서쪽 방향 링크)에 서려면 동쪽 끝에서 유턴해야 함
        val r = PickupEngine(map).choose(PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, 25.0)))
        val all = listOfNotNull(r.best) + r.alternatives
        val north = all.filter { it.road.link.id == "WB" }
        val south = all.filter { it.road.link.id == "EB" }
        assertTrue(north.isNotEmpty() || south.isNotEmpty())
        north.forEach { assertTrue("유턴 포함 추가 시간 > 90초", it.carExtraSec > 90) }
        south.forEach { assertTrue("남쪽 길가는 사람이 길을 건넘", it.crossings.contains("시험로")) }
    }

    @Test fun 횡단보도와_교차로_주변에는_세우지_않는다() {
        val map = straightRoad(crosswalkAt = 300.0)
        val engine = PickupEngine(map)
        val r = engine.choose(PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -25.0)))
        val best = assertNotNullAndGet(r.best)
        val x = Geo.toXY(best.road.link.pts.let { Geo.pointAlong(it, best.road.along).first }, origin).first
        assertTrue("횡단보도(300m)에서 12m 이상", kotlin.math.abs(x - 300.0) >= 12.0)
        assertTrue("교차로에서 15m 이상", x >= 15.0 && x <= 585.0)
        assertTrue((r.rejected["횡단보도 주변 정차 금지"] ?: 0) > 0)
    }

    @Test fun 걷기_힘들면_1분_안쪽만_고른다() {
        val map = straightRoad()
        val r = PickupEngine(map).choose(
            PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -25.0), needs = setOf(Need.HARD_TO_WALK))
        )
        (listOfNotNull(r.best) + r.alternatives).forEach { assertTrue(it.walkSec <= 60.0) }
    }

    @Test fun 넓고_빠른_간선은_정차_위험이_크다() {
        val engine = PickupEngine(straightRoad())
        val alley = Link("A", "x", "y", 1, "107", "000", 30, 0, 0, "골목", listOf(at(0.0, 0.0), at(50.0, 0.0)))
        val arterial = Link("B", "x", "y", 3, "103", "000", 60, 0, 0, "간선", listOf(at(0.0, 0.0), at(50.0, 0.0)))
        assertTrue(engine.stopPenalty(alley, 999.0) < engine.stopPenalty(arterial, 999.0))
    }

    // ---------- 실제 데이터: 노량진역 ----------
    private fun noryangjin(): RoadMap? {
        val f = listOf("src/main/assets/map_noryangjin.txt", "app/src/main/assets/map_noryangjin.txt").map(::File).firstOrNull { it.exists() }
        return f?.let { RoadMap.parse(it.readText()) }
    }

    @Test fun 노량진역_만남장소는_법규를_지키고_간선_고속도로를_피한다() {
        val map = noryangjin() ?: return
        val req = PickupRequest(
            car = LatLng(37.51300, 126.93300), carHeadingDeg = 85.0,      // 노량진로 서쪽에서 동쪽으로 오는 중
            rider = LatLng(37.51385, 126.94255),                           // 노량진역 앞
            destination = LatLng(37.50484, 126.93918),                     // 원래 목적지: 장승배기역
        )
        val r = PickupEngine(map).choose(req)
        val best = assertNotNullAndGet(r.best)
        assertTrue("목적지까지 시간이 계산됨", r.destinationUsed)
        val intersections = map.nodes.values.filter { it.isIntersection }
        for (c in listOf(best) + r.alternatives) {
            assertTrue(c.road.link.pickupAllowed)
            assertTrue(c.nearestCrosswalkM >= 12.0)
            val center = Geo.pointAlong(c.road.link.pts, c.road.along).first
            assertTrue(intersections.all { Geo.dist(it.pos, center) >= 15.0 })
            assertTrue(c.walkSec <= 180.0)
        }
        println("노량진역 결과: ${best.summary()}  @ ${"%.6f,%.6f".format(best.curb.lat, best.curb.lon)}")
        r.alternatives.forEach { println("  대안: ${it.summary()}") }
        println("  검토 ${r.considered}곳, 제외 ${r.rejected}")
    }

    @Test fun 비가_오면_덜_걷는_곳을_고른다() {
        val map = noryangjin() ?: return
        val base = PickupRequest(car = LatLng(37.51300, 126.93300), carHeadingDeg = 85.0, rider = LatLng(37.51385, 126.94255),
            destination = LatLng(37.50484, 126.93918))
        val engine = PickupEngine(map)
        val dry = assertNotNullAndGet(engine.choose(base).best)
        val rain = assertNotNullAndGet(engine.choose(base.copy(needs = setOf(Need.RAIN))).best)
        // 비 올 때는 걸음이 느려 같은 거리도 시간이 늘어나므로 "거리"로 비교한다
        assertTrue("비 올 때 걷는 거리 ≤ 맑을 때", Geo.dist(rain.curb, base.rider) <= Geo.dist(dry.curb, base.rider) + 0.01)
        assertTrue("비 올 때 길을 더 건너지 않음", rain.crossings.size <= dry.crossings.size)
        println("맑음: ${dry.summary()}\n비: ${rain.summary()}")
    }

    @Test fun 현장확인_정차불가_구간에는_세우지_않는다() {
        val base = straightRoad()
        // 남쪽 길가(동쪽 방향 링크의 오른쪽, 도로 중심에서 6.4m) 200~400m 구간을 가드레일로 막음
        val zone = NoStopZone(at(200.0, -6.4), at(400.0, -6.4), "가드레일", "시험")
        val map = RoadMap(base.nodes, base.links, base.crosswalks, base.busStops, listOf(zone))
        val r = PickupEngine(map).choose(PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -25.0)))
        val best = assertNotNullAndGet(r.best)
        for (c in listOf(best) + r.alternatives) {
            assertTrue("막힌 구간 밖", map.noStopZoneAt(c.curb) == null)
        }
        assertTrue((r.rejected["현장 확인: 가드레일"] ?: 0) > 0)
    }

    @Test fun 노량진역_건너편_가드레일_구간은_고르지_않는다() {
        val map = noryangjin() ?: return
        assertTrue("현장 확인 구간이 지도에 들어 있음", map.noStopZones.isNotEmpty())
        val r = PickupEngine(map).choose(
            PickupRequest(car = LatLng(37.51300, 126.93300), carHeadingDeg = 85.0, rider = LatLng(37.51385, 126.94255))
        )
        val best = assertNotNullAndGet(r.best)
        (listOf(best) + r.alternatives).forEach { assertTrue(map.noStopZoneAt(it.curb) == null) }
        println("가드레일 반영 후(목적지 없음): ${best.summary()} @ ${"%.6f,%.6f".format(best.curb.lat, best.curb.lon)}")
        r.alternatives.forEach { println("  대안: ${it.summary()} @ ${"%.6f,%.6f".format(it.curb.lat, it.curb.lon)}") }
    }

    @Test fun 현장에서_정차가능으로_확인된_길가를_우선한다() {
        val base = straightRoad()
        // 남쪽 길가 360~400m 를 "노란 점선"으로 확인 (타는 사람은 300m 지점이라 조금 더 걸어야 함)
        val ok = CurbOkZone(at(360.0, -6.4), at(400.0, -6.4), "노란 점선", "시험")
        val map = RoadMap(base.nodes, base.links, base.crosswalks, base.busStops, okZones = listOf(ok))
        val r = PickupEngine(map).choose(PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -25.0)))
        val best = assertNotNullAndGet(r.best)
        assertEquals(FieldStatus.VERIFIED_OK, best.field)
        assertTrue(best.fieldNote.contains("노란 점선"))
    }

    @Test fun 걷기_힘든데_1분_안에_설_곳이_없으면_넓혀_찾고_알린다() {
        val base = straightRoad()
        // 타는 사람 근처(240~360m) 양쪽 길가를 모두 막음
        val zones = listOf(
            NoStopZone(at(240.0, -6.4), at(360.0, -6.4), "펜스", "시험"),
            NoStopZone(at(360.0, 6.4), at(240.0, 6.4), "노란 겹선", "시험"),
        )
        val map = RoadMap(base.nodes, base.links, base.crosswalks, base.busStops, zones)
        val r = PickupEngine(map).choose(
            PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -12.0), needs = setOf(Need.HARD_TO_WALK))
        )
        val best = assertNotNullAndGet(r.best)
        assertTrue("상한을 넓혀 찾음", r.relaxedWalk)
        assertTrue(best.walkSec > 60.0)
        assertTrue(r.message.contains("정차 금지"))
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}

class ParkingSpotTest {
    private val origin = LatLng(37.5, 127.0)
    private fun at(x: Double, y: Double) = Geo.fromXY(x, y, origin)

    private fun road(zones: List<NoStopZone>, parks: List<ParkingSpot>): RoadMap {
        val w = Node("W", "101", at(0.0, 0.0), ""); val e = Node("E", "101", at(600.0, 0.0), "")
        val eb = Link("EB", "W", "E", 2, "104", "000", 50, 0, 0, "시험로", listOf(w.pos, at(300.0, 0.0), e.pos))
        val wb = Link("WB", "E", "W", 2, "104", "000", 50, 0, 0, "시험로", listOf(e.pos, at(300.0, 0.0), w.pos))
        return RoadMap(mapOf("W" to w, "E" to e), listOf(eb, wb), emptyList(), emptyList(), zones, parkingSpots = parks)
    }

    @Test fun 길가가_모두_막혀도_주차장_현관에서_태운다() {
        val blocked = listOf(
            NoStopZone(at(15.0, -6.4), at(585.0, -6.4), "펜스", "시험"),
            NoStopZone(at(585.0, 6.4), at(15.0, 6.4), "노란 겹선", "시험"),
        )
        // 길 남쪽 330m 지점 입구, 안쪽 40m 현관
        val park = ParkingSpot("시험 청사", at(330.0, -8.0), at(330.0, -45.0), "현관 앞 회차로", "시험", verified = true)
        val r = PickupEngine(road(blocked, listOf(park))).choose(
            PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -30.0))
        )
        val best = r.best
        assertNotNull(best)
        assertEquals(CandidateKind.OFF_ROAD, best!!.kind)
        assertEquals("시험 청사", best.placeName)
        assertEquals(0.0, best.stopPenaltySec, 0.0)
        assertEquals(FieldStatus.VERIFIED_OK, best.field)
        println("주차장 결과: ${best.summary()} / ${r.message}")
    }

    @Test fun 너무_먼_주차장은_고르지_않는다() {
        val park = ParkingSpot("먼 주차장", at(330.0, -8.0), at(330.0, -400.0), "", "", verified = true)
        val r = PickupEngine(road(emptyList(), listOf(park))).choose(
            PickupRequest(car = at(50.0, -3.0), carHeadingDeg = 90.0, rider = at(300.0, -30.0))
        )
        assertEquals(CandidateKind.CURB, r.best!!.kind)
    }
}
