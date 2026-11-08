package com.hmgapp.yeogiseotayo.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.trip.NaviPort

/**
 * 순정 내비 연결. 11/6 에뮬레이터 실측 반영:
 * - 경유지를 넣으면 경로가 잠깐 취소(FREE_DRIVE)됐다가 9~13초 뒤 다시 시작 → 넣은 뒤 15초 동안의 취소는 무시
 * - 안내 중 여부는 RouteStateInfo.isRouting 이 아니라 NavigationStatus 로 판단
 */
@SuppressLint("MissingPermission") // NAVI_ROUTE / NAVI_ROUTE_SEARCH 는 Normal 권한
class NaviLink(context: Context, private val log: (String) -> Unit) : NaviPort {

    private val navi = NaviHelper(context.applicationContext)
    @Volatile var status: String = "연결 중"; private set
    @Volatile private var addedAt = 0L
    @Volatile private var hasWaypoint = false
    /** 원래 목적지 (경로가 없을 때 "목적지 + 경유지"를 한 번에 요청하려고 기억) */
    @Volatile var destination: Pair<String, LatLng>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteCancelled() {
            if (System.currentTimeMillis() - addedAt < 15_000) log("내비: 경유지 바꾸는 중 (경로 잠깐 다시 계산)")
            else log("내비: 안내가 취소됐어요")
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            log("내비: 경유지 ${waypointChangedInfo.cmdType} 반영")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = log("내비: 경유지 도착")
        override fun onNavigationStatus(status: NavigationStatus) { this@NaviLink.status = status.name }
        override fun onError(errorCode: NaviErrorCode) = log("내비 오류: $errorCode")
    }

    fun connect() {
        navi.addListener(listener)
        navi.initialize(
            onConnected = { status = "연결됨"; log("내비 연결됨") },
            onFailed = { status = "연결 실패"; log("내비 연결 실패: ${it.message}") },
        )
    }

    /** 시연용: 원래 목적지(집 등)로 안내 시작 */
    private fun routeInfo(name: String, at: LatLng) =
        RouteInfo(latitude = at.lat, longitude = at.lon, poiName = name, poiId = "", address = name, poiSubId = "")

    /** 지금 순정 내비가 경로 안내 중인가 (11/6 실측: isRouting 대신 NavigationStatus 로 판단) */
    private val routing get() = status == NavigationStatus.ROUTE_DRIVE.name

    fun setDestination(name: String, at: LatLng) = runCatching {
        destination = name to at
        navi.requestRoute(routeInfo(name, at))
        log("내비: 원래 목적지 $name 안내 요청")
    }.onFailure { log("내비 목적지 실패: ${it.message}") }

    /**
     * 11/9 실측: 안내 중인 경로 없이 addWaypoint 만 하면 순정 내비가 "경로를 얻지 못했습니다"를 띄움.
     * → 안내 중이면 경유지만 추가, 아니면 "원래 목적지 + 경유지"를 한 번에, 목적지도 모르면 만남 장소를 목적지로.
     */
    override fun addPickupWaypoint(name: String, at: LatLng) {
        runCatching {
            val wp = RequestWaypointInfo(latitude = at.lat, longitude = at.lon, poiName = "여기서 타요 · $name",
                waypointIndex = WaypointIndex.FIRST, poiId = "", address = name, poiSubId = "")
            addedAt = System.currentTimeMillis()
            val dest = destination
            when {
                routing -> {
                    if (hasWaypoint) navi.removeWaypoint(WaypointIndex.FIRST)
                    navi.addWaypoint(wp)
                    hasWaypoint = true
                    log("내비: 안내 중인 경로에 경유지 추가 ($name)")
                }
                dest != null -> {
                    navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(dest.first, dest.second), listOf(wp)))
                    hasWaypoint = true
                    log("내비: ${dest.first}까지 경로 + 경유지 $name 요청")
                }
                else -> {
                    navi.requestRoute(routeInfo("여기서 타요 · $name", at))
                    hasWaypoint = false
                    log("내비: 원래 목적지가 없어 만남 장소($name)를 목적지로 요청")
                }
            }
        }.onFailure { log("내비 경유지 실패: ${it.message}") }
    }

    override fun removePickupWaypoint() {
        if (!hasWaypoint) return
        // 11/9 실측: 경유지를 빼도 넣을 때처럼 경로가 잠깐 취소됐다가 다시 잡힘 → 같은 15초 유예
        runCatching { addedAt = System.currentTimeMillis(); navi.removeWaypoint(WaypointIndex.FIRST); hasWaypoint = false; log("내비: 경유지 삭제") }
            .onFailure { log("내비 경유지 삭제 실패: ${it.message}") }
    }

    fun release() { navi.removeListener(listener); navi.release() }
}
