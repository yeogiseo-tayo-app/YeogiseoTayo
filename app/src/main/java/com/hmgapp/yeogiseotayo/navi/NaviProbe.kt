package com.hmgapp.yeogiseotayo.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 시험용 장소. 노량진역 주변 (동작구). */
data class Place(val name: String, val lat: Double, val lon: Double, val address: String)

object ProbePlaces {
    /** "원래 목적지" 역할 (집 가는 길이라고 가정) */
    val HOME = Place("숭실대입구역", 37.49626, 126.95389, "서울 동작구 상도로 지하 378")
    /** 만남 장소 역할: 노량진역 앞 노량진로 남쪽 길가 */
    val PICKUP = Place("노량진역 앞 (여기서 타요)", 37.51355, 126.94220, "서울 동작구 노량진로 156") // 동쪽 방향 길가
}

/**
 * NaviHelper 로 순정 내비에 목적지·경유지를 넣고, 돌아오는 이벤트를 모두 기록한다.
 * 확인할 것: ① 경유지가 실제로 경로에 들어가는지 ② remainTime 단위 ③ onWayPointArrived 가 오는지
 */
@SuppressLint("MissingPermission") // NAVI_ROUTE / NAVI_ROUTE_SEARCH 는 Normal 권한 (매니페스트만으로 허용)
class NaviProbe(context: Context, private val log: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(context.applicationContext)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var routeAskedAt = 0L

    private fun out(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        android.util.Log.i("NaviProbe", line)
        main.post { log(line) }
    }

    private inline fun call(name: String, block: () -> Unit) {
        try {
            block(); out("→ $name 요청 보냄")
        } catch (t: Throwable) {
            out("✕ $name 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            out("● onRouteStarted (${since()}): $routeStartInfo")
        override fun onRoutingCompleted() = out("● onRoutingCompleted")
        override fun onReRouteEnded() = out("● onReRouteEnded")
        override fun onRouteCancelled() = out("● onRouteCancelled")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            out("● onWaypointChanged (${since()}): $waypointChangedInfo")
        override fun onDestinationChanged() = out("● onDestinationChanged")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            out("★ onDestinationArrived: $destinationArrivedInfo")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            out("★ onWayPointArrived: $waypointArrivedInfo")
        override fun onError(errorCode: NaviErrorCode) = out("✕ onError: $errorCode")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = out(
            "● 목적지: ${destinationInfo.name} 남은거리=${destinationInfo.remainDistance} 남은시간=${destinationInfo.remainTime}"
        )
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = out(
            "● 경유지 ${waypointInfoList.size}개: " + waypointInfoList.joinToString { "${it.name} 남은거리=${it.remainDistance} 남은시간=${it.remainTime}" }
        )
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) =
            out("● 경로 상태: 안내중=${routeStateInfo.isRouting} 모드=${routeStateInfo.naviMode}")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = out(
            "● 내비 현재 위치: %.5f, %.5f 방향=%.0f".format(
                currentLocationInfo.latitude, currentLocationInfo.longitude, currentLocationInfo.heading
            )
        )
        override fun onNavigationStatus(status: NavigationStatus) = out("● 내비 상태: $status")
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = out(
            "● 주행정보: 목적지 ${drivingInfo.destination.distance}m/${drivingInfo.destination.duration} · 경유지 " +
                drivingInfo.waypoints.joinToString { "${it.name} ${it.distance}m/${it.duration}" }
        )
    }

    private fun since(): String =
        if (routeAskedAt == 0L) "-" else "요청 후 %.1f초".format((System.currentTimeMillis() - routeAskedAt) / 1000.0)

    fun connect() {
        out("연결 시도…")
        navi.addListener(listener)
        navi.initialize(
            onConnected = { out("✔ NaviHelper 연결됨") },
            onFailed = { out("✕ 연결 실패: ${it.javaClass.simpleName} ${it.message}") },
        )
    }

    /** 1) 원래 목적지(집) 설정 */
    fun setHome() = call("목적지 설정(${ProbePlaces.HOME.name})") {
        routeAskedAt = System.currentTimeMillis()
        val p = ProbePlaces.HOME
        navi.requestRoute(RouteInfo(latitude = p.lat, longitude = p.lon, poiName = p.name, poiId = "", address = p.address, poiSubId = ""))
    }

    /** 2) 만남 장소를 경유지(FIRST)로 추가 — 이 앱의 핵심 동작 */
    fun addPickupWaypoint() = call("경유지 추가(${ProbePlaces.PICKUP.name})") {
        routeAskedAt = System.currentTimeMillis()
        val p = ProbePlaces.PICKUP
        navi.addWaypoint(
            RequestWaypointInfo(latitude = p.lat, longitude = p.lon, poiName = p.name, waypointIndex = WaypointIndex.FIRST,
                poiId = "", address = p.address, poiSubId = "")
        )
    }

    fun askInfo() {
        call("목적지 정보") { navi.getDestinationInfo() }
        call("경유지 정보") { navi.getWaypointInfo() }
        call("경로 상태") { navi.getRouteStateInfo() }
        call("내비 현재 위치") { navi.getCurrentLocationInfo() }
    }

    fun removePickup() = call("경유지 삭제") { navi.removeWaypoint(WaypointIndex.FIRST) }
    fun cancel() = call("경로 취소") { navi.cancelRoute() }

    fun release() {
        navi.removeListener(listener)
        navi.release()
    }
}
