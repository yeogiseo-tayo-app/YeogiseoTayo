package com.hmgapp.yeogiseotayo.trip

import com.hmgapp.yeogiseotayo.engine.Candidate
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.Link
import com.hmgapp.yeogiseotayo.engine.Need
import com.hmgapp.yeogiseotayo.engine.Node
import com.hmgapp.yeogiseotayo.engine.RoadMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripControllerTest {
    private val origin = LatLng(37.5, 127.0)
    private fun at(x: Double, y: Double) = Geo.fromXY(x, y, origin)

    private fun map(): RoadMap {
        val w = Node("W", "101", at(0.0, 0.0), ""); val e = Node("E", "101", at(1200.0, 0.0), "")
        val eb = Link("EB", "W", "E", 2, "104", "000", 50, 0, 0, "시험로", listOf(w.pos, at(600.0, 0.0), e.pos))
        val wb = Link("WB", "E", "W", 2, "104", "000", 50, 0, 0, "시험로", listOf(e.pos, at(600.0, 0.0), w.pos))
        return RoadMap(mapOf("W" to w, "E" to e), listOf(eb, wb), emptyList(), emptyList())
    }

    private class Fakes : NaviPort, VoicePort, RiderPort {
        val events = ArrayList<String>()
        override fun addPickupWaypoint(name: String, at: LatLng) { events += "navi+ $name" }
        override fun removePickupWaypoint() { events += "navi-" }
        override fun say(text: String) { events += "voice $text" }
        override fun meetingPlaceChanged(c: Candidate, message: String) { events += "rider place" }
        override fun comeOutNow(c: Candidate) { events += "rider comeout" }
        override fun carArrived(c: Candidate) { events += "rider arrived" }
    }

    @Test fun 정하기_나오세요_도착_출발까지_한_번에() {
        val f = Fakes()
        var t = 0L
        val tc = TripController(map(), f, f, f, Place("엄마", at(900.0, -25.0)), null, clock = { t })
        // 차는 서쪽 100m 에서 동쪽으로 정차 중
        tc.onLocation(at(100.0, -3.0), 90.0, t)
        tc.decide()
        assertEquals(Phase.GUIDING, tc.state.phase)
        val c = tc.state.chosen; assertNotNull(c)
        assertTrue(f.events.any { it.startsWith("navi+") })
        assertTrue("처음엔 아직 나오라고 안 함", !tc.state.calledOut)
        // 7m/s 로 길가 지점을 향해 달림
        var x = 100.0
        val target = Geo.toXY(c!!.curb, origin).first
        while (x < target) { t += 1000; x = minOf(target, x + 7.0); tc.onLocation(at(x, -6.4), 90.0, t) }
        assertTrue("가는 도중 나오라고 알림", f.events.contains("rider comeout"))
        // 도착해서 4초 정지
        repeat(5) { t += 1000; tc.onLocation(at(target, -6.4), 90.0, t) }
        assertEquals(Phase.ARRIVED, tc.state.phase)
        assertTrue(f.events.contains("rider arrived"))
        // 다시 출발
        repeat(6) { t += 1000; x += 8.0; tc.onLocation(at(x, -3.0), 90.0, t) }
        assertEquals(Phase.PICKED_UP, tc.state.phase)
        assertEquals("navi-", f.events.last { it.startsWith("navi") })
        println(f.events.joinToString("\n"))
    }

    @Test fun 걷기_시작_전에는_사정이_바뀌면_다시_정하고_후에는_고정() {
        val f = Fakes()
        val tc = TripController(map(), f, f, f, Place("엄마", at(600.0, 25.0)), null, clock = { 0L })
        tc.onLocation(at(100.0, -3.0), 90.0, 0L)
        tc.decide()
        val first = tc.state.chosen!!
        tc.setNeeds(setOf(Need.HARD_TO_WALK))
        // 걸음이 느려져 같은 곳도 "시간"은 늘어나므로 거리·길 건너기로 비교
        val rider = tc.state.rider.pos
        assertTrue("걷기 힘들면 덜 걷거나 길을 덜 건너는 곳",
            Geo.dist(tc.state.chosen!!.curb, rider) <= Geo.dist(first.curb, rider) + 0.01 &&
                tc.state.chosen!!.crossings.size <= first.crossings.size)
        val second = tc.state.chosen!!
        tc.riderStartedWalking()
        tc.setNeeds(setOf(Need.RAIN))
        assertEquals("걷기 시작 후에는 고정", second.curb, tc.state.chosen!!.curb)
    }

    @Test fun 노량진역_실제_데이터로_정하기() {
        val f = listOf("src/main/assets/map_noryangjin.txt", "app/src/main/assets/map_noryangjin.txt").map(::File).firstOrNull { it.exists() } ?: return
        val fk = Fakes()
        val tc = TripController(RoadMap.parse(f.readText()), fk, fk, fk,
            Place("엄마", LatLng(37.51385, 126.94255)), Place("장승배기역", LatLng(37.50484, 126.93918)), clock = { 0L })
        tc.onLocation(LatLng(37.51300, 126.93300), 85.0, 0L)
        tc.decide()
        println("노량진: ${tc.state.chosen?.summary()} / 도착 예정 ${tc.state.etaSec?.toInt()}초 / 나오세요까지 ${tc.state.callOutInSec?.toInt()}초")
        println(fk.events.joinToString("\n"))
        assertNotNull(tc.state.chosen)
    }
}
