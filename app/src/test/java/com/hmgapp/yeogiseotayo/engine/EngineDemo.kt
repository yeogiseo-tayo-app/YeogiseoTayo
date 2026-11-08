package com.hmgapp.yeogiseotayo.engine

import java.io.File

/** 개발용: 노량진역 시나리오 결과를 TSV 로 내보내 지도로 그려 본다 (앱에는 포함되지 않음) */
fun main(args: Array<String>) {
    val map = RoadMap.parse(File(args.getOrElse(0) { "src/main/assets/map_noryangjin.txt" }).readText())
    val engine = PickupEngine(map)
    val base = PickupRequest(
        car = LatLng(37.51300, 126.93300), carHeadingDeg = 85.0,
        rider = LatLng(37.51385, 126.94255), destination = LatLng(37.50484, 126.93918),
    )
    val cases = listOf("기본" to base, "비" to base.copy(needs = setOf(Need.RAIN)),
        "걷기힘듦" to base.copy(needs = setOf(Need.HARD_TO_WALK)),
        "목적지없음" to base.copy(destination = null))
    val out = StringBuilder("case\trank\tlat\tlon\tscore\twalk\tcross\textra\tstop\tdesc\n")
    for ((name, req) in cases) {
        val r = engine.choose(req)
        (listOfNotNull(r.best) + r.alternatives).forEachIndexed { i, c ->
            out.append("$name\t$i\t${c.curb.lat}\t${c.curb.lon}\t${"%.0f".format(c.score)}\t${"%.0f".format(c.walkSec)}\t" +
                "${"%.0f".format(c.crossingSec)}\t${"%.0f".format(c.carExtraSec)}\t${"%.0f".format(c.stopPenaltySec)}\t${c.summary()}\n")
        }
    }
    File(args.getOrElse(1) { "/tmp/demo.tsv" }).writeText(out.toString())
    print(out)
}
