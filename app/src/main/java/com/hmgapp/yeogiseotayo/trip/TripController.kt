package com.hmgapp.yeogiseotayo.trip

import com.hmgapp.yeogiseotayo.engine.Candidate
import com.hmgapp.yeogiseotayo.engine.CandidateKind
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.Josa
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.Need
import com.hmgapp.yeogiseotayo.engine.PickupEngine
import com.hmgapp.yeogiseotayo.engine.PickupRequest
import com.hmgapp.yeogiseotayo.engine.PickupResult
import com.hmgapp.yeogiseotayo.engine.RoadMap
import com.hmgapp.yeogiseotayo.engine.RoadPoint
import com.hmgapp.yeogiseotayo.engine.Router
import com.hmgapp.yeogiseotayo.engine.walkSpeedFactor
import com.hmgapp.yeogiseotayo.engine.walkWeight

/** 순정 내비에 하는 일 (안드로이드 쪽에서 NaviHelper 로 구현) */
interface NaviPort {
    fun addPickupWaypoint(name: String, at: LatLng)
    fun removePickupWaypoint()
}

/** 음성 안내 (안드로이드 쪽에서 Pleos TTS 로 구현) */
interface VoicePort {
    fun say(text: String)
}

/** 타는 사람에게 보내는 알림 (rider/RiderLink: 웹 링크 + Firebase) */
interface RiderPort {
    fun meetingPlaceChanged(c: Candidate, message: String)
    fun comeOutNow(c: Candidate)
    fun carArrived(c: Candidate)
    /** 사정이 바뀌었지만 지금 장소가 그대로 가장 좋을 때 */
    fun placeKept(c: Candidate) {}
}

enum class Phase { READY, GUIDING, ARRIVED, PICKED_UP }

data class Place(val label: String, val pos: LatLng)

data class TripState(
    val phase: Phase = Phase.READY,
    val car: LatLng? = null,
    val carHeadingDeg: Double = 0.0,
    /** 차가 한 번이라도 움직여 진행 방향을 알게 됐는가 (멈춘 채 켜면 모름) */
    val headingKnown: Boolean = false,
    val speedMps: Double = 0.0,
    val rider: Place,
    val destination: Place?,
    val needs: Set<Need> = emptySet(),
    val result: PickupResult? = null,
    val chosen: Candidate? = null,
    /** 차가 만남 장소까지 남은 시간(초) */
    val etaSec: Double? = null,
    /** "지금 나오세요"까지 남은 시간(초). 0 이하면 이미 알림 */
    val callOutInSec: Double? = null,
    val calledOut: Boolean = false,
    val riderWalking: Boolean = false,
    /** TMAP 보행 경로로 잰 실제 걷는 시간 (없으면 엔진 추정값) */
    val realWalkSec: Double? = null,
    val lastVoice: String = "",
    val log: List<String> = emptyList(),
) {
    /** 주행 중이면 화면 조작을 숨긴다 (시속 약 7km 이상) */
    val moving: Boolean get() = speedMps > 2.0
}

/**
 * 한 번의 "태우러 가기"를 처음부터 끝까지 이끈다. 안드로이드에 의존하지 않는다.
 * 호출은 한 스레드에서만 한다 (안드로이드 쪽에서 단일 작업 스레드로 보장).
 */
class TripController(
    private val map: RoadMap,
    private val navi: NaviPort,
    private val voice: VoicePort,
    private val riderPort: RiderPort,
    rider: Place,
    destination: Place?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onState: (TripState) -> Unit = {},
) {
    private val engine = PickupEngine(map)
    private val router = Router(map)
    var state = TripState(rider = rider, destination = destination); private set

    private var lastFix: Pair<LatLng, Long>? = null
    private var stoppedSince: Long? = null
    private var movingSince: Long? = null

    companion object {
        const val COME_OUT_MARGIN_SEC = 60.0   // 나올 시각 = 도착 예정 − 걷는 시간 − 1분
        const val ARRIVE_RADIUS_M = 30.0
        const val STOPPED_MPS = 1.5
        const val STOP_HOLD_MS = 3_000L
        const val DEPART_MPS = 3.0
        const val REFINE_MARGIN_SEC = 15.0   // 이 정도 이상 나아야 장소를 바꾼다 (자꾸 바뀌지 않게)
        fun curbKey(p: LatLng) = "%.6f,%.6f".format(java.util.Locale.ROOT, p.lat, p.lon)
    }

    private fun emit(s: TripState) { state = s; onState(s) }
    private fun log(msg: String) = emit(state.copy(log = (state.log + msg).takeLast(30)))
    private fun speak(text: String) { voice.say(text); emit(state.copy(lastVoice = text)); log("음성: $text") }

    fun setRider(p: Place) { emit(state.copy(rider = p)); if (state.phase == Phase.GUIDING && !state.riderWalking) decide(reason = "타는 사람 위치가 바뀌었어요") }
    fun setDestination(p: Place?) = emit(state.copy(destination = p))

    /** 사정 버튼. 걷기 시작 전이면 다시 정한다 */
    fun setNeeds(needs: Set<Need>) {
        emit(state.copy(needs = needs))
        if (state.phase == Phase.GUIDING && !state.riderWalking) decide(reason = "타는 사람 사정이 바뀌었어요")
    }

    /**
     * TMAP 으로 상위 후보들의 **실제 걷는 시간**을 잰 결과 (키 = 후보 지점 "위도,경도" 소수 6자리, 값 = TMAP 초).
     * 엔진은 직선 거리 × 1.3 으로 걷는 시간을 어림하므로, 건물을 돌아가야 하는 곳은 실제로 훨씬 멀 수 있다 (11/10 실측: 1분 40초 → 4분 53초).
     * → 실제 걷는 시간으로 점수를 고쳐 더 나은 곳이 있으면 바꾸고, 아니면 지금 장소의 걷는 시간만 고친다.
     */
    fun refine(walks: Map<String, Double>) {
        if (state.phase != Phase.GUIDING) return
        val res = state.result ?: return
        val cur = state.chosen ?: return
        val speed = walkSpeedFactor(state.needs)
        val w = walkWeight(state.needs)
        fun real(c: Candidate) = walks[curbKey(c.curb)]?.let { it / speed }
        fun fixed(c: Candidate) = real(c)?.let { c.score + w * (it - c.walkSec) }
        val measured = (listOf(cur) + listOfNotNull(res.best) + res.alternatives)
            .distinctBy { curbKey(it.curb) }.mapNotNull { c -> fixed(c)?.let { c to it } }
        if (measured.isEmpty()) return
        val (top, topScore) = measured.minBy { it.second }
        val curScore = fixed(cur) ?: cur.score
        if (!state.riderWalking && Geo.dist(top.curb, cur.curb) > 15 && topScore + REFINE_MARGIN_SEC < curScore) {
            emit(state.copy(chosen = top, realWalkSec = real(top), calledOut = false))
            log("TMAP 걷는 길로 다시 재 보니 ${top.placeName} 쪽이 더 가까워요 (${real(cur)?.toInt()}초 → ${real(top)?.toInt()}초)")
            navi.addPickupWaypoint(top.placeName, top.curb)
            speak("실제 걷는 길을 확인해 보니 ${top.placeName} 쪽이 더 가까워요. 만남 장소를 바꿨어요.")
            riderPort.meetingPlaceChanged(top, "걷는 길을 확인해 다시 정했어요")
        } else {
            val r = real(cur) ?: return
            if (state.realWalkSec == r) return
            emit(state.copy(realWalkSec = r))
            log("걷는 시간을 TMAP 기준 ${r.toInt()}초로 바꿨어요")
        }
        updateEta()
    }

    /** 타는 사람이 걷기 시작함 → 장소 고정 */
    fun riderStartedWalking() { if (state.riderWalking) return; emit(state.copy(riderWalking = true)); log("타는 사람이 출발했어요 — 만남 장소 고정") }

    /** 만남 장소 정하기 (정차 중 버튼, 또는 사정이 바뀌었을 때) */
    fun decide(reason: String? = null) {
        val car = state.car ?: run { log("차 위치를 아직 모릅니다"); return }
        val heading = if (state.headingKnown) state.carHeadingDeg else null
        val res = engine.choose(PickupRequest(car, heading, state.rider.pos, state.destination?.pos, state.needs))
        val best = res.best
        if (best == null && res.carStart == null) {
            emit(state.copy(result = res, chosen = null))
            speak("차 위치를 도로 위에서 찾지 못했어요. 조금 움직인 뒤 다시 정해 주세요.")
            return
        }
        if (best == null) {
            emit(state.copy(result = res, chosen = null))
            speak("근처에 합법적으로 설 수 있는 곳이 없어요. ${state.rider.label} 쪽으로 천천히 가 주세요.")
            return
        }
        val changed = state.chosen?.let { Geo.dist(it.curb, best.curb) > 15 } ?: true
        emit(state.copy(phase = Phase.GUIDING, result = res, chosen = best,
            calledOut = if (changed) false else state.calledOut,
            realWalkSec = if (changed) null else state.realWalkSec))
        if (changed) {
            navi.addPickupWaypoint(best.placeName, best.curb)
            val head = if (reason != null) "$reason. 만남 장소가 바뀌었어요. " else ""
            speak(head + announce(best, res))
            riderPort.meetingPlaceChanged(best, res.message)
        } else if (reason != null) {
            // 다시 정했는데 같은 곳 → 눌렀는데 아무 반응이 없으면 헷갈리므로 결과를 알려 준다
            speak("${state.rider.label} 사정을 반영했어요. 지금 장소가 그대로 가장 좋아요.")
            riderPort.placeKept(best)
        }
        updateEta()
    }

    fun announce(c: Candidate, res: PickupResult): String {
        val where = if (c.kind == CandidateKind.OFF_ROAD) "${Josa.ieyo(c.placeName)}. 주차장으로 들어가서 타는 곳 앞에 세워 주세요"
        else "${c.placeName}, 진행 방향 오른쪽 길가예요"
        val pre = if (res.relaxedWalk) "가까운 길가는 모두 정차 금지예요. " else ""
        return "${pre}만남 장소는 $where."
    }

    /** 차 위치가 들어올 때마다 (FusedLocation) */
    fun onLocation(pos: LatLng, headingDeg: Double?, timeMs: Long = clock()) {
        val prev = lastFix
        var speed = state.speedMps
        // 11/10 실측: 멈춰 있을 때 들어오는 위치는 방향이 0(북쪽)이라 그대로 쓰면 차가 북쪽을 보는 걸로 착각함
        // → 진행 방향은 실제로 움직였을 때(2m 넘게)만 바꾸고, 멈춰 있으면 마지막 방향을 유지
        var heading = state.carHeadingDeg
        var known = state.headingKnown
        if (prev != null && timeMs > prev.second) {
            val d = Geo.dist(prev.first, pos)
            val inst = d / ((timeMs - prev.second) / 1000.0)
            speed = 0.6 * inst + 0.4 * state.speedMps
            if (d > 2.0) { heading = Geo.bearing(prev.first, pos); known = true }
        }
        lastFix = pos to timeMs
        emit(state.copy(car = pos, carHeadingDeg = heading, headingKnown = known, speedMps = speed))

        when (state.phase) {
            Phase.GUIDING -> { updateEta(); checkCallOut(); checkArrival(timeMs) }
            Phase.ARRIVED -> checkDeparture(timeMs)
            else -> Unit
        }
    }

    private fun updateEta() {
        val c = state.chosen ?: return
        val car = state.car ?: return
        val start = map.snap(car, if (state.headingKnown) state.carHeadingDeg else null)?.let { RoadPoint(it.first, it.second) }
        val toGate = start?.let { router.forward(it).timeTo(c.road) }
        val eta = when {
            Geo.dist(car, c.curb) < ARRIVE_RADIUS_M -> 0.0
            toGate == null -> state.etaSec
            c.kind == CandidateKind.OFF_ROAD -> toGate + 30.0   // 입구에서 타는 곳까지
            else -> toGate
        }
        val walk = state.realWalkSec ?: c.walkSec
        val callOut = eta?.let { it - walk - COME_OUT_MARGIN_SEC }
        emit(state.copy(etaSec = eta, callOutInSec = callOut))
    }

    /**
     * 1초마다 불림. 차가 서 있으면 위치가 새로 안 들어올 수 있어서(같은 좌표는 안 보냄)
     * 마지막 위치가 2초 넘게 그대로면 멈춘 것으로 보고 도착·출발을 다시 판단한다.
     */
    fun tick(now: Long = clock()) {
        val last = lastFix ?: return
        if (now - last.second < 2000) return
        if (state.speedMps > 0.0) emit(state.copy(speedMps = 0.0))
        when (state.phase) {
            Phase.GUIDING -> checkArrival(now)
            Phase.ARRIVED -> checkDeparture(now)
            else -> Unit
        }
    }

    private fun checkCallOut() {
        val c = state.chosen ?: return
        val left = state.callOutInSec ?: return
        if (!state.calledOut && left <= 0) {
            emit(state.copy(calledOut = true))
            riderPort.comeOutNow(c)
            speak("${state.rider.label}에게 지금 나오라고 알렸어요.")
        }
    }

    private fun checkArrival(now: Long) {
        val c = state.chosen ?: return
        val car = state.car ?: return
        val near = Geo.dist(car, c.curb) <= ARRIVE_RADIUS_M
        if (near && state.speedMps <= STOPPED_MPS) {
            val since = stoppedSince ?: now.also { stoppedSince = it }
            if (now - since >= STOP_HOLD_MS) {
                stoppedSince = null
                emit(state.copy(phase = Phase.ARRIVED, etaSec = 0.0))
                riderPort.carArrived(c)
                speak("만남 장소에 도착했어요. ${state.rider.label}에게 도착을 알렸어요.")
            }
        } else stoppedSince = null
    }

    private fun checkDeparture(now: Long) {
        if (state.speedMps >= DEPART_MPS) {
            val since = movingSince ?: now.also { movingSince = it }
            if (now - since >= STOP_HOLD_MS) {
                movingSince = null
                emit(state.copy(phase = Phase.PICKED_UP))
                navi.removePickupWaypoint()
                speak(state.destination?.let { "출발합니다. ${it.label}까지 안내를 이어갈게요." } ?: "출발합니다.")
            }
        } else movingSince = null
    }

    fun reset() {
        if (state.phase == Phase.GUIDING || state.phase == Phase.ARRIVED) navi.removePickupWaypoint()
        stoppedSince = null; movingSince = null
        emit(TripState(rider = state.rider, destination = state.destination, car = state.car,
            carHeadingDeg = state.carHeadingDeg, speedMps = state.speedMps, log = state.log + "처음으로"))
    }
}
