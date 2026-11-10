package com.hmgapp.yeogiseotayo.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

/**
 * 차량 신호 (Android Automotive 차량 속성 — Pleos Vehicle SDK 문서가 권하는 GEAR_SELECTION 방식).
 * - 차속(PERF_VEHICLE_SPEED, m/s): 정차·주행 판단을 GPS 추정보다 정확하게
 * - 기어(GEAR_SELECTION): P 면 "섰다"가 확실 → 도착을 바로 판단, P 인 동안은 출발로 보지 않음
 * 받지 못하면(권한·에뮬레이터 설정) 조용히 GPS 판단만 쓴다.
 */
class VehicleLink(context: Context, private val log: (String) -> Unit, private val onChange: (Double?, String?) -> Unit) {

    private val app = context.applicationContext
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    @Volatile private var speed: Double? = null
    @Volatile private var gear: String? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speed = (value.value as? Float)?.toDouble()?.let { kotlin.math.abs(it) }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val g = gearName(value.value as? Int)
                    if (g != gear) log("차량 기어: $g")
                    gear = g
                }
                else -> return
            }
            onChange(speed, gear)
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { log("차량 신호 오류: $propertyId") }
    }

    fun start() {
        runCatching {
            val c = Car.createCar(app) ?: return log("차량 신호: 연결 실패")
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = pm
            val okSpeed = runCatching { pm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }.getOrDefault(false)
            val okGear = runCatching { pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }.getOrDefault(false)
            log("차량 신호: 차속 ${if (okSpeed) "받는 중" else "없음"} · 기어 ${if (okGear) "받는 중" else "없음"}")
        }.onFailure { log("차량 신호 시작 실패: ${it.message}") }
    }

    fun stop() {
        runCatching { props?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
    }

    private fun gearName(v: Int?): String? = when (v) {
        null -> null
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        else -> if (v >= VehicleGear.GEAR_FIRST) "D" else "?"
    }
}
