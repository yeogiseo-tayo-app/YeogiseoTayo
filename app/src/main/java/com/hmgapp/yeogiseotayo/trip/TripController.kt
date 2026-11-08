package com.hmgapp.yeogiseotayo.trip

import com.hmgapp.yeogiseotayo.engine.Candidate
import com.hmgapp.yeogiseotayo.engine.CandidateKind
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.Need
import com.hmgapp.yeogiseotayo.engine.PickupEngine
import com.hmgapp.yeogiseotayo.engine.PickupRequest
import com.hmgapp.yeogiseotayo.engine.PickupResult
import com.hmgapp.yeogiseotayo.engine.RoadMap
import com.hmgapp.yeogiseotayo.engine.RoadPoint
import com.hmgapp.yeogiseotayo.engine.Router

/** 순정 내비에 하는 일 (안드로이드 쪽에서 NaviHelper 로 구현) */
interface NaviPort {
    fun addPickupWaypoint(name: String, at: LatLng)
    fun removePickupWaypoint()
}

/** 음성 안내 (안드로이드 쪽에서 Pleos TTS 로 구현) */
interface VoicePort {
    fun say(text: String)
}

/** 타는 사람에게 보내는 알림 (11/8 웹 링크·Firebase 로 구현, 지금은 화면 기록) */
interface RiderPort {
    fun meetingPlaceChanged(c: Candidate, message: String)
    fun comeOutNow(c: Candidate)
    fun carArrived(c: Candidate)
}

enum class Phase { READY, GUIDING, ARRIVED, PICKED_UP }

data class Place(val label: String, val pos: LatLng)

data class TripState(
    val phase: Phase = Phase.READY,
    val car: LatLng? = null,
    val carHeadingDeg: Double = 0.0,
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

    /** 타는 사람이 걷기 시작함 → 장소 고정 */
    fun riderStartedWalking() { emit(state.copy(riderWalking = true)); log("타는 사람이 출발했어요 — 만남 장소 고정") }

    /** 만남 장소 정하기 (정차 중 버튼, 또는 사정이 바뀌었을 때) */
    fun decide(reason: String? = null) {
        val car = state.car ?: run { log("차 위치를 아직 모릅니다"); return }
        val res = engine.choose(PickupRequest(car, state.carHeadingDeg, state.rider.pos, state.destination?.pos, state.needs))
        val best = res.best
        if (best == null) {
            emit(state.copy(result = res, chosen = null))
            speak("근처에 합법적으로 설 수 있는 곳이 없어요. ${state.rider.label} 쪽으로 천천히 가 주세요.")
            return
        }
        val changed = state.chosen?.let { Geo.dist(it.curb, best.curb) > 15 } ?: true
        emit(state.copy(phase = Phase.GUIDING, result = res, chosen = best, calledOut = false))
        if (changed) {
            navi.addPickupWaypoint(best.placeName, best.curb)
            val head = if (reason != null) "$reason. 만남 장소가 바뀌었어요. " else ""
            speak(head + announce(best, res))
            riderPort.meetingPlaceChanged(best, res.message)
        }
        updateEta()
    }

    fun announce(c: Candidate, res: PickupResult): String {
        val where = if (c.kind == CandidateKind.OFF_ROAD) "${c.placeName}이에요. 주차장으로 들어가서 타는 곳 앞에 세워 주세요"
        else "${c.placeName}, 진행 방향 오른쪽 길가예요"
        val pre = if (res.relaxedWalk) "가까운 길가는 모두 정차 금지예요. " else ""
        return "${pre}만남 장소는 $where."
    }

    /** 차 위치가 들어올 때마다 (FusedLocation) */
    fun onLocation(pos: LatLng, headingDeg: Double?, timeMs: Long = clock()) {
        val prev = lastFix
        var speed = state.speedMps
        var heading = headingDeg ?: state.carHeadingDeg
        if (prev != null && timeMs > prev.second) {
            val d = Geo.dist(prev.first, pos)
            val inst = d / ((timeMs - prev.second) / 1000.0)
            speed = 0.6 * inst + 0.4 * state.speedMps
            if ((headingDeg == null || headingDeg == 0.0) && d > 2.0) heading = Geo.bearing(prev.first, pos)
        }
        lastFix = pos to timeMs
        emit(state.copy(car = pos, carHeadingDeg = heading, speedMps = speed))

        when (state.phase) {
            Phase.GUIDING -> { updateEta(); checkCallOut(); checkArrival(timeMs) }
            Phase.ARRIVED -> checkDeparture(timeMs)
            else -> Unit
        }
    }

    private fun updateEta() {
        val c = state.chosen ?: return
        val car = state.car ?: return
        val start = map.snap(car, state.carHeadingDeg)?.let { RoadPoint(it.first, it.second) }
        val toGate = start?.let { router.forward(it).timeTo(c.road) }
        val eta = when {
            Geo.dist(car, c.curb) < ARRIVE_RADIUS_M -> 0.0
            toGate == null -> state.etaSec
            c.kind == CandidateKind.OFF_ROAD -> toGate + 30.0   // 입구에서 타는 곳까지
            else -> toGate
        }
        val callOut = eta?.let { it - c.walkSec - COME_OUT_MARGIN_SEC }
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
