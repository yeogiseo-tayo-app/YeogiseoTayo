package com.hmgapp.yeogiseotayo.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

/** 좁은 지역(수 km)용 평면 근사 계산. 안드로이드에 의존하지 않는다. */
object Geo {
    private const val M_PER_DEG_LAT = 111_320.0

    private fun mPerDegLon(lat: Double) = M_PER_DEG_LAT * cos(lat * PI / 180)

    /** 기준점 기준 동(x)·북(y) 미터 좌표 */
    fun toXY(p: LatLng, origin: LatLng): Pair<Double, Double> =
        (p.lon - origin.lon) * mPerDegLon(origin.lat) to (p.lat - origin.lat) * M_PER_DEG_LAT

    fun fromXY(x: Double, y: Double, origin: LatLng) =
        LatLng(origin.lat + y / M_PER_DEG_LAT, origin.lon + x / mPerDegLon(origin.lat))

    fun dist(a: LatLng, b: LatLng): Double {
        val (x, y) = toXY(b, a)
        return hypot(x, y)
    }

    /** 북쪽 0°, 시계 방향 */
    fun bearing(a: LatLng, b: LatLng): Double {
        val (x, y) = toXY(b, a)
        return (atan2(x, y) * 180 / PI + 360) % 360
    }

    fun offset(p: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val r = bearingDeg * PI / 180
        return fromXY(meters * sin(r), meters * cos(r), p)
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** 두 방향의 차이 (0~180°) */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }

    /** 점 p 에서 선분 ab 까지 거리(m)와 선분 위 위치 비율 t */
    fun distToSegment(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val (bx, by) = toXY(b, a)
        val (px, py) = toXY(p, a)
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by) to t
    }

    /** 선분 p1p2 와 q1q2 가 교차하는가 (평면 근사) */
    fun segmentsCross(p1: LatLng, p2: LatLng, q1: LatLng, q2: LatLng): Boolean {
        val (ax, ay) = 0.0 to 0.0
        val (bx, by) = toXY(p2, p1)
        val (cx, cy) = toXY(q1, p1)
        val (dx, dy) = toXY(q2, p1)
        fun orient(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
            (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
        val o1 = orient(ax, ay, bx, by, cx, cy)
        val o2 = orient(ax, ay, bx, by, dx, dy)
        val o3 = orient(cx, cy, dx, dy, ax, ay)
        val o4 = orient(cx, cy, dx, dy, bx, by)
        return (o1 * o2 < 0) && (o3 * o4 < 0)
    }

    fun polylineLength(pts: List<LatLng>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += dist(pts[i - 1], pts[i])
        return s
    }

    /** 폴리라인 위 along(m) 지점의 좌표와 그 지점의 진행 방향 */
    fun pointAlong(pts: List<LatLng>, along: Double): Pair<LatLng, Double> {
        var remain = along
        for (i in 1 until pts.size) {
            val seg = dist(pts[i - 1], pts[i])
            if (remain <= seg || i == pts.size - 1) {
                val t = if (seg == 0.0) 0.0 else (remain / seg).coerceIn(0.0, 1.0)
                return lerp(pts[i - 1], pts[i], t) to bearing(pts[i - 1], pts[i])
            }
            remain -= seg
        }
        return pts.first() to 0.0
    }

    fun hypotM(x: Double, y: Double) = sqrt(x * x + y * y)
}
