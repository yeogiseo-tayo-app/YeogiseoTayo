package com.hmgapp.yeogiseotayo.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import com.hmgapp.yeogiseotayo.engine.LatLng

/**
 * 차 위치 (Pleos FusedLocation). 11/6 실측: 순정 내비 위치는 에뮬레이터 GPS 를 따라가지 않으므로
 * 차 위치는 항상 여기서 받는다. GPX 재생을 따라간다 (딜레마 제로에서 확인).
 */
class CarLocation(context: Context, private val log: (String) -> Unit, private val onFix: (LatLng, Double?, Long) -> Unit) {

    private val fused = FusedLocation(context.applicationContext)

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            onFix(LatLng(location.latitude, location.longitude), location.heading.toDouble(), System.currentTimeMillis())
        }
        override fun onError(exception: Exception) { log("위치 오류: ${exception.message}") }
    }

    fun start() {
        runCatching {
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            fused.getLastKnownLocation(listener)
            log("위치 받는 중")
        }.onFailure { log("위치 시작 실패: ${it.message}") }
    }

    fun stop() = runCatching { fused.unregisterFusedLocationCallback(listener); fused.release() }
}
