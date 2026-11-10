package com.hmgapp.yeogiseotayo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hmgapp.yeogiseotayo.engine.Candidate
import com.hmgapp.yeogiseotayo.engine.CandidateKind
import com.hmgapp.yeogiseotayo.engine.FieldStatus
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.Need
import com.hmgapp.yeogiseotayo.engine.RoadMap
import com.hmgapp.yeogiseotayo.location.CarLocation
import com.hmgapp.yeogiseotayo.navi.NaviLink
import com.hmgapp.yeogiseotayo.rider.RiderInfo
import com.hmgapp.yeogiseotayo.rider.RiderLink
import com.hmgapp.yeogiseotayo.trip.Phase
import com.hmgapp.yeogiseotayo.trip.Place
import com.hmgapp.yeogiseotayo.trip.TripController
import com.hmgapp.yeogiseotayo.trip.TripState
import com.hmgapp.yeogiseotayo.ui.MapCanvas
import com.hmgapp.yeogiseotayo.ui.MapColors
import com.hmgapp.yeogiseotayo.voice.VoiceLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 시연 설정 (노량진역). 타는 사람 위치는 웹 링크에서 받는다 (시연 링크는 이 위치로 고정) */
object Demo {
    val RIDER = Place("엄마", LatLng(37.51385, 126.94255))
    const val FIREBASE_DB = "https://yeogiseo-tayo-default-rtdb.asia-southeast1.firebasedatabase.app"
    const val WEB_HOST = "yeogiseo-tayo.web.app"
    /** true 면 링크에 &demo=1 → 타는 사람 위치를 RIDER 로 고정 (실제 GPS 대신) */
    const val DEMO_LINK = true
    val DESTINATION = Place("장승배기역", LatLng(37.50484, 126.93918))
    const val MAP_ASSET = "map_noryangjin.txt"
}

class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private var state by mutableStateOf<TripState?>(null)
    private var roadMap by mutableStateOf<RoadMap?>(null)
    private var naviStatus by mutableStateOf("연결 중")
    private var riderInfo by mutableStateOf(RiderInfo())
    private var landmark by mutableStateOf<Pair<LatLng, String>?>(null)
    private val logs = mutableStateListOf<String>()

    private lateinit var navi: NaviLink
    private lateinit var voice: VoiceLink
    private lateinit var location: CarLocation
    private var trip: TripController? = null
    private lateinit var riderLink: RiderLink

    private fun log(msg: String) { main.post { logs.add("${fmt.format(Date())}  $msg"); if (logs.size > 60) logs.removeAt(0) } }

    /** 타는 사람 웹 화면에서 온 값 → 여정에 반영 (작업 스레드) */
    private fun applyRider(info: RiderInfo) {
        val t = trip ?: return
        info.pos?.let { p -> if (Geo.dist(p, t.state.rider.pos) > 25) t.setRider(Place(t.state.rider.label, p)) }
        if (!t.state.riderWalking && info.needs != t.state.needs) t.setNeeds(info.needs)
        if (info.walking) t.riderStartedWalking()
    }

    /** FusedLocation 권한은 위험 권한(dangerous) → 매니페스트 + 실행 중 요청 둘 다 필요 (11/9 확인) */
    private val locationPermissions = arrayOf(
        "pleos.car.permission.FUSED_LOCATION",
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, "pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (hasLocationPermission()) location.start()
        else log("위치 권한이 없어요: ${granted.filterValues { !it }.keys.joinToString()} — 설정에서 허용해 주세요")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navi = NaviLink(this, ::log).also {
            it.destination = Demo.DESTINATION.label to Demo.DESTINATION.pos  // 시연: 원래 목적지는 장승배기역
            it.connect()
        }
        voice = VoiceLink(this, ::log).also { it.connect() }
        location = CarLocation(this, ::log) { pos, heading, t -> worker.execute { trip?.onLocation(pos, heading, t) } }
        riderLink = RiderLink(Demo.FIREBASE_DB, BuildConfig.TMAP_APP_KEY, BuildConfig.KAKAO_REST_KEY, ::log,
            onRider = { info -> main.post { riderInfo = info }; worker.execute { applyRider(info) } },
            onWalks = { walks -> worker.execute { trip?.refine(walks) } },
            onLandmark = { at, name -> main.post { landmark = at to name } },
        ).also { it.start(Demo.RIDER.label, Demo.DESTINATION.label) }
        val riderUrl = riderLink.link(Demo.WEB_HOST, Demo.DEMO_LINK)

        worker.execute {
            val m = RoadMap.parse(assets.open(Demo.MAP_ASSET).bufferedReader().use { it.readText() })
            val tc = TripController(m, navi, voice, riderLink, Demo.RIDER, Demo.DESTINATION,
                onState = { s -> riderLink.publish(s); main.post { state = s; naviStatus = navi.status } })
            trip = tc
            main.post { roadMap = m; state = tc.state }
            log("지도 불러옴: 도로 ${m.links.size} · 현장 확인 불가 ${m.noStopZones.size} · 가능 ${m.okZones.size} · 도로 밖 ${m.parkingSpots.size}")
        }

        if (hasLocationPermission()) location.start() else askLocation.launch(locationPermissions)
        main.postDelayed(ticker, 1000)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen(
                    s = state, map = roadMap, naviStatus = naviStatus, logs = logs,
                    rider = riderInfo, riderUrl = riderUrl, landmark = landmark,
                    onDecide = { worker.execute { trip?.decide() } },
                    onReset = { worker.execute { trip?.reset(); riderLink.resetTrip() } },
                    onNeed = { n -> worker.execute { trip?.let { t -> t.setNeeds(if (n in t.state.needs) t.state.needs - n else t.state.needs + n) } } },
                    onDemoDestination = { navi.setDestination(Demo.DESTINATION.label, Demo.DESTINATION.pos) },
                    onDemoRiderWalks = { worker.execute { trip?.riderStartedWalking() } },
                )
            }
        }
    }

    /** 1초마다: 차가 멈춰 위치가 안 들어와도 도착·출발 판단 */
    private val ticker = object : Runnable {
        override fun run() { worker.execute { trip?.tick() }; main.postDelayed(this, 1000) }
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        location.stop(); navi.release(); voice.release(); riderLink.stop(); worker.shutdown()
        super.onDestroy()
    }
}

private val needLabels = listOf(
    Need.RAIN to "비 와요", Need.LUGGAGE to "짐 많아요", Need.HARD_TO_WALK to "걷기 힘들어요", Need.NOT_READY to "바로 못 나가요",
)

private fun fmtSec(sec: Double?): String {
    if (sec == null) return "-"
    val s = sec.roundToInt().coerceAtLeast(0)
    return if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    s: TripState?, map: RoadMap?, naviStatus: String, logs: List<String>,
    rider: RiderInfo, riderUrl: String, landmark: Pair<LatLng, String>?,
    onDecide: () -> Unit, onReset: () -> Unit, onNeed: (Need) -> Unit,
    onDemoDestination: () -> Unit, onDemoRiderWalks: () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Color(0xFF111418))) {
        Column(
            Modifier.weight(0.42f).fillMaxHeight().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("여기서 타요", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("가족·지인을 태우러 갈 때, 막히지 않는 딱 한 곳", fontSize = 16.sp, color = Color(0xFF9AA4B2))

            PhaseCard(s)
            s?.chosen?.let { c -> ResultCard(c, s, landmark?.takeIf { Geo.dist(it.first, c.curb) < 1.0 }?.second) }
            if (s == null || !s.moving) RiderCard(rider, riderUrl, s)

            if (s == null || !s.moving) {
                if (!rider.joined) Text("타는 사람 사정 (링크를 열기 전 시연용)", fontSize = 14.sp, color = Color(0xFF9AA4B2))
                if (!rider.joined) needLabels.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (n, label) ->
                            FilterChip(selected = s?.needs?.contains(n) == true, onClick = { onNeed(n) },
                                label = { Text(label, fontSize = 15.sp, maxLines = 1, softWrap = false) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDecide, enabled = s?.car != null,
                        colors = ButtonDefaults.buttonColors(containerColor = MapColors.chosen, contentColor = Color.Black)) {
                        Text(if (s?.phase == Phase.GUIDING) "다시 정하기" else "만남 장소 정하기", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onReset) { Text("처음으로", fontSize = 18.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDemoDestination) { Text("시연: 원래 목적지 안내", fontSize = 15.sp) }
                    OutlinedButton(onClick = onDemoRiderWalks, enabled = s?.phase == Phase.GUIDING) { Text("시연: 타는 사람 출발", fontSize = 15.sp) }
                }
            } else {
                Text("주행 중에는 화면 대신 음성으로만 안내해요", fontSize = 20.sp, color = Color(0xFFFFD166))
            }

            val car = s?.car
            Text(
                "차 위치 " + (car?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "받는 중") +
                    " · 시속 ${((s?.speedMps ?: 0.0) * 3.6).roundToInt()}km · 내비 $naviStatus",
                fontSize = 13.sp, color = Color(0xFF6B7684),
            )
        }

        Column(Modifier.weight(0.58f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MapCanvas(map, s, modifier = Modifier.fillMaxSize())
                Legend(Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            Column(Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF181D24)).padding(10.dp)) {
                logs.takeLast(7).forEach { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCDD5DF)) }
            }
        }
    }
}

@Composable
private fun PhaseCard(s: TripState?) {
    val (title, color) = when (s?.phase) {
        null -> "지도를 불러오는 중" to Color(0xFF9AA4B2)
        Phase.READY -> (if (s.car == null) "차 위치를 받는 중" else "정차 중에 '만남 장소 정하기'를 눌러 주세요") to Color(0xFF9AA4B2)
        Phase.GUIDING -> "만남 장소로 가는 중" to MapColors.car
        Phase.ARRIVED -> "도착 — 곧 탑니다" to MapColors.chosen
        Phase.PICKED_UP -> "탑승 완료 · 원래 목적지로" to MapColors.chosen
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1B2129), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        if (s != null && s.phase == Phase.GUIDING) {
            Spacer(Modifier.height(6.dp))
            Text("도착 예정 ${fmtSec(s.etaSec)}", fontSize = 20.sp, color = Color.White)
            val call = s.callOutInSec
            Text(
                when {
                    s.calledOut -> "${s.rider.label}에게 \"지금 나오세요\"를 보냈어요"
                    call != null -> "${fmtSec(call)} 뒤 ${s.rider.label}에게 \"지금 나오세요\""
                    else -> ""
                }, fontSize = 17.sp, color = Color(0xFFFFD166),
            )
        }
        if (s != null && s.lastVoice.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("🔊 ${s.lastVoice}", fontSize = 15.sp, color = Color(0xFFB8C4D0))
        }
    }
}

/** 타는 사람 링크: QR(휴대폰으로 찍기) + 참여 상태 */
@Composable
private fun RiderCard(r: RiderInfo, url: String, s: TripState?) {
    val qr = remember(url) { qrBitmap(url, 360) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B2129), RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!r.joined || s?.phase == Phase.READY) Image(qr, "타는 사람 링크 QR", Modifier.size(120.dp).background(Color.White).padding(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = s?.rider?.label ?: "타는 사람"
            val (t, c) = when {
                r.walking -> "$label: 만남 장소로 걸어가는 중" to MapColors.chosen
                r.joined -> "$label: 링크 열었음 ✓" to MapColors.chosen
                else -> "${label}에게 링크를 보내 주세요" to Color(0xFFFFD166)
            }
            Text(t, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c)
            if (r.joined && r.needs.isNotEmpty())
                Text("사정: " + needLabels.filter { it.first in r.needs }.joinToString { it.second }, fontSize = 15.sp, color = Color.White)
            if (!r.joined) Text("휴대폰 카메라로 QR을 찍으면 앱 설치 없이 열려요", fontSize = 14.sp, color = Color(0xFF9AA4B2))
            if (r.demo) Text("시연 모드 · 위치 고정", fontSize = 13.sp, color = Color(0xFF6B7684))
            Text(url.removePrefix("https://"), fontSize = 12.sp, color = Color(0xFF6B7684))
        }
    }
}

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
    for (y in 0 until size) for (x in 0 until size)
        bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

@Composable
private fun ResultCard(c: Candidate, s: TripState, landmark: String?) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF14261D), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(c.placeName, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MapColors.chosen)
        Text(if (c.kind == CandidateKind.OFF_ROAD) "도로 밖에서 태워요 · 길을 막지 않음"
            else "진행 방향 오른쪽 길가" + (landmark?.let { " · $it 앞" } ?: ""),
            fontSize = 16.sp, color = Color(0xFFCDE9D9))
        Spacer(Modifier.height(8.dp))
        Text("걷기 ${fmtSec(s.realWalkSec ?: c.walkSec)}" + (if (s.realWalkSec != null) " (TMAP)" else "") + (if (c.crossings.isNotEmpty()) " · ${c.crossings.joinToString()} 건넘" else " · 길 건너지 않음"),
            fontSize = 16.sp, color = Color.White)
        Text("차가 더 가는 시간 ${fmtSec(c.carExtraSec)} · 정차 위험 ${c.stopPenaltySec.roundToInt()}", fontSize = 16.sp, color = Color.White)
        Text(if (c.field == FieldStatus.VERIFIED_OK) "✔ ${c.fieldNote}" else "현장 미확인 (공공데이터로만 판단)",
            fontSize = 15.sp, color = if (c.field == FieldStatus.VERIFIED_OK) MapColors.chosen else Color(0xFFFFB86B))
        s.result?.let { r ->
            if (r.relaxedWalk) Text(r.message, fontSize = 15.sp, color = Color(0xFFFFD166))
            val top = r.rejected.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} ${it.value}" }
            if (top.isNotBlank()) Text("뺀 곳: $top", fontSize = 13.sp, color = Color(0xFF8B95A3))
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(modifier.background(Color(0xCC111418), RoundedCornerShape(10.dp)).padding(10.dp)) {
        listOf(
            MapColors.rider to "타는 사람", MapColors.car to "내 차", MapColors.chosen to "만남 장소",
            MapColors.noStop to "현장 확인: 정차 불가", MapColors.ok to "현장 확인: 정차 가능", MapColors.parking to "도로 밖 타는 곳",
        ).forEach { (c, t) -> Text("● $t", color = c, fontSize = 13.sp) }
    }
}
