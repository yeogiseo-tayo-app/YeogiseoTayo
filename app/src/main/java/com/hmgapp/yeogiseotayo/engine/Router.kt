package com.hmgapp.yeogiseotayo.engine

import java.util.PriorityQueue

/** 링크 위의 한 지점 */
data class RoadPoint(val link: Link, val along: Double)

/**
 * 링크 단위 최단 시간 계산 (회전 비용 포함). 신호 대기는 회전 종류별 평균값으로 근사한다.
 * - 직진 5초, 우회전 8초, 좌회전 25초, 유턴 90초 (교차로가 아니면 회전 비용 0)
 */
class Router(private val map: RoadMap) {

    fun turnCost(a: Link, b: Link): Double {
        if (b.to == a.from && b.from == a.to) return 90.0 // 유턴 (반대 방향 같은 도로)
        val node = map.nodes[a.to]
        if (node?.isIntersection != true) return 0.0
        val inB = Geo.bearing(a.pts[a.pts.size - 2], a.pts.last())
        val outB = Geo.bearing(b.pts[0], b.pts[1])
        val turn = ((outB - inB) % 360 + 360) % 360 // 0=직진, 90=우회전, 270=좌회전
        return when {
            turn < 35 || turn > 325 -> 5.0
            turn < 150 -> 8.0
            turn <= 210 -> 90.0
            else -> 25.0
        }
    }

    private fun linkTime(l: Link, meters: Double) = meters / l.travelSpeed

    /** 출발점에서 각 링크 "끝"까지 걸리는 시간 */
    class Forward(val start: RoadPoint, val toEnd: Map<Link, Double>, private val router: Router) {
        /** 출발점 → 링크 위 지점 시간 (못 가면 null) */
        fun timeTo(p: RoadPoint): Double? {
            if (p.link == start.link && p.along >= start.along) return (p.along - start.along) / p.link.travelSpeed
            var best = Double.POSITIVE_INFINITY
            for (a in router.predecessors(p.link)) {
                val t = toEnd[a] ?: continue
                best = minOf(best, t + router.turnCost(a, p.link))
            }
            return if (best.isInfinite()) null else best + p.along / p.link.travelSpeed
        }
    }

    /** 각 링크 "끝"에서 도착점까지 걸리는 시간 */
    class Backward(val goal: RoadPoint, val fromStart: Map<Link, Double>, private val router: Router) {
        fun timeFrom(p: RoadPoint): Double? {
            if (p.link == goal.link && p.along <= goal.along) return (goal.along - p.along) / p.link.travelSpeed
            val endToGoal = fromEnd(p.link) ?: return null
            return (p.link.length - p.along) / p.link.travelSpeed + endToGoal
        }

        fun fromEnd(a: Link): Double? {
            var best = Double.POSITIVE_INFINITY
            for (b in router.successors(a)) {
                val t = fromStart[b] ?: continue
                best = minOf(best, router.turnCost(a, b) + t)
            }
            return if (best.isInfinite()) null else best
        }
    }

    fun successors(a: Link) = map.outLinks[a.to].orEmpty()
    fun predecessors(b: Link) = map.inLinks[b.from].orEmpty()

    fun forward(start: RoadPoint): Forward {
        val dist = HashMap<Link, Double>()
        val pq = PriorityQueue<Pair<Link, Double>>(compareBy { it.second })
        val first = linkTime(start.link, start.link.length - start.along)
        dist[start.link] = first; pq.add(start.link to first)
        while (pq.isNotEmpty()) {
            val (a, d) = pq.poll()
            if (d > (dist[a] ?: Double.POSITIVE_INFINITY)) continue
            for (b in successors(a)) {
                val nd = d + turnCost(a, b) + linkTime(b, b.length)
                if (nd < (dist[b] ?: Double.POSITIVE_INFINITY)) { dist[b] = nd; pq.add(b to nd) }
            }
        }
        return Forward(start, dist, this)
    }

    fun backward(goal: RoadPoint): Backward {
        val dist = HashMap<Link, Double>() // 링크 "시작"에서 도착점까지
        val pq = PriorityQueue<Pair<Link, Double>>(compareBy { it.second })
        val first = linkTime(goal.link, goal.along)
        dist[goal.link] = first; pq.add(goal.link to first)
        while (pq.isNotEmpty()) {
            val (b, d) = pq.poll()
            if (d > (dist[b] ?: Double.POSITIVE_INFINITY)) continue
            for (a in predecessors(b)) {
                val nd = linkTime(a, a.length) + turnCost(a, b) + d
                if (nd < (dist[a] ?: Double.POSITIVE_INFINITY)) { dist[a] = nd; pq.add(a to nd) }
            }
        }
        return Backward(goal, dist, this)
    }
}
