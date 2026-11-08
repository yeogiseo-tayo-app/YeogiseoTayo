package com.hmgapp.yeogiseotayo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.hmgapp.yeogiseotayo.engine.CandidateKind
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.RoadMap
import com.hmgapp.yeogiseotayo.trip.TripState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object MapColors {
    val bg = Color(0xFF0F1318)
    val road = Color(0xFF3A4452)
    val roadBlocked = Color(0xFF4A2F33)   // 고속·고가 등 정차 불가 도로
    val noStop = Color(0xFFE5484D)        // 현장 확인: 정차 불가
    val ok = Color(0xFF30A46C)            // 현장 확인: 정차 가능
    val parking = Color(0xFF3E63DD)       // 도로 밖 타는 곳
    val crosswalk = Color(0xFF8B95A3)
    val rider = Color(0xFFFF6B6B)
    val car = Color(0xFF4CC2FF)
    val chosen = Color(0xFF3DD68C)
    val alt = Color(0xFFB8C4D0)
}

/** 노량진역 주변 지도: 도로·현장 확인 구간·주차장·타는 사람·차·만남 장소 */
@Composable
fun MapCanvas(map: RoadMap?, s: TripState?, spanM: Double = 700.0, modifier: Modifier = Modifier) {
    // clipToBounds: 지도 밖(왼쪽 패널)으로 선이 넘어가지 않게
    Canvas(modifier.clipToBounds().background(MapColors.bg)) {
        if (map == null || s == null) return@Canvas
        val center = s.rider.pos
        val scale = min(size.width, size.height) / spanM
        fun p(ll: LatLng): Offset {
            val (x, y) = Geo.toXY(ll, center)
            return Offset((size.width / 2 + x * scale).toFloat(), (size.height / 2 - y * scale).toFloat())
        }
        val reach = spanM * 1.2

        for (l in map.links) {
            if (l.pts.all { Geo.dist(it, center) > reach }) continue
            val path = Path()
            l.pts.forEachIndexed { i, pt -> val o = p(pt); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, if (l.pickupAllowed) MapColors.road else MapColors.roadBlocked, style = Stroke(width = 2f + l.lanes * 2.2f))
        }
        for (c in map.crosswalks) if (Geo.dist(c.pos, center) < reach) drawCircle(MapColors.crosswalk, 3f, p(c.pos))
        for (z in map.noStopZones) drawLine(MapColors.noStop, p(z.a), p(z.b), strokeWidth = 7f)
        for (z in map.okZones) drawLine(MapColors.ok, p(z.a), p(z.b), strokeWidth = 7f)
        for (pk in map.parkingSpots) {
            val o = p(pk.pickup)
            drawRect(MapColors.parking, topLeft = o - Offset(10f, 10f), size = Size(20f, 20f))
            drawLine(MapColors.parking, p(pk.entrance), o, strokeWidth = 3f)
        }

        // 대안·만남 장소
        s.result?.alternatives?.forEach { drawCircle(MapColors.alt, 9f, p(it.curb), style = Stroke(3f)) }
        s.chosen?.let { c ->
            val o = p(c.curb)
            drawLine(MapColors.chosen, p(s.rider.pos), o, strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawCircle(MapColors.chosen.copy(alpha = 0.25f), 30f, o)
            drawCircle(MapColors.chosen, 14f, o)
            if (c.kind == CandidateKind.OFF_ROAD) drawCircle(Color.White, 5f, o)
        }

        // 타는 사람
        drawCircle(MapColors.rider.copy(alpha = 0.3f), 22f, p(s.rider.pos))
        drawCircle(MapColors.rider, 10f, p(s.rider.pos))

        // 차 (진행 방향 화살표)
        s.car?.let { car ->
            val o = p(car)
            val h = Math.toRadians(s.carHeadingDeg)
            fun pt(angle: Double, r: Float) = Offset(o.x + (r * sin(h + angle)).toFloat(), o.y - (r * cos(h + angle)).toFloat())
            val tri = Path().apply {
                val a = pt(0.0, 22f); val b = pt(Math.toRadians(140.0), 16f); val c = pt(Math.toRadians(-140.0), 16f)
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
            }
            drawPath(tri, MapColors.car)
        }
    }
}
