package com.hmgapp.yeogiseotayo.rider

import com.hmgapp.yeogiseotayo.engine.Candidate
import com.hmgapp.yeogiseotayo.engine.CandidateKind
import com.hmgapp.yeogiseotayo.engine.FieldStatus
import com.hmgapp.yeogiseotayo.engine.Geo
import com.hmgapp.yeogiseotayo.engine.Josa
import com.hmgapp.yeogiseotayo.engine.LatLng
import com.hmgapp.yeogiseotayo.engine.Need
import com.hmgapp.yeogiseotayo.trip.RiderPort
import com.hmgapp.yeogiseotayo.trip.TripController
import com.hmgapp.yeogiseotayo.trip.TripState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 타는 사람 웹 화면에서 들어온 값 */
data class RiderInfo(
    val joined: Boolean = false,
    val needs: Set<Need> = emptySet(),
    val walking: Boolean = false,
    val pos: LatLng? = null,
    val demo: Boolean = false,
)

/**
 * 타는 사람 웹 링크 ↔ 차 앱. Firebase Realtime Database 를 REST 로 쓴다
 * (Firebase 안드로이드 SDK·구글 플레이 서비스 없이 동작 — 차량 에뮬레이터에 플레이 서비스가 없을 수 있음).
 *
 *  trips/{id}/info    차가 씀: 타는 사람 이름, 목적지 이름
 *  trips/{id}/meet    차가 씀: 만남 장소 (바뀔 때만)
 *  trips/{id}/status  차가 씀: 단계, 도착 예정, 나올 때까지 남은 시간
 *  trips/{id}/car     차가 씀: 차 위치 (2초마다)
 *  trips/{id}/notice  차가 씀: 알림 한 줄 (지금 나오세요, 도착 등) → 웹에서 진동·소리
 *  trips/{id}/rider   웹이 씀: 참여, 사정 버튼, 출발, 위치
 */
class RiderLink(
    private val dbUrl: String,
    private val tmapKey: String,
    private val kakaoKey: String,
    private val log: (String) -> Unit,
    private val onRider: (RiderInfo) -> Unit,
    /** TMAP 으로 잰 후보들의 걷는 시간 (키 = TripController.curbKey, 값 = 초) */
    private val onWalks: (Map<String, Double>) -> Unit,
    /** 만남 장소 바로 옆 같은 쪽 길가의 가게·은행 이름 (카카오 로컬) */
    private val onLandmark: (LatLng, String) -> Unit = { _, _ -> },
) : RiderPort {

    val tripId: String = newId()
    private val base get() = "$dbUrl/trips/$tripId"
    private val io = Executors.newSingleThreadExecutor()
    private val later = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var cleanup: java.util.concurrent.ScheduledFuture<*>? = null
    private var lastPhase: String? = null
    @Volatile private var running = true
    @Volatile var lastRider = RiderInfo(); private set

    @Volatile private var meetKey: String? = null
    private var statusKey: String? = null
    private var lastCarAt = 0L

    fun link(host: String, demo: Boolean) = "https://$host/?t=$tripId" + if (demo) "&demo=1" else ""
    /** 차 화면 QR: 운전자가 자기 휴대폰으로 찍어 타는 사람에게 카카오톡·문자로 보내는 화면 (여정에 아무것도 쓰지 않음) */
    fun shareLink(host: String, demo: Boolean) = "https://$host/share.html?t=$tripId" + if (demo) "&demo=1" else ""

    fun start(riderLabel: String, destinationLabel: String?) {
        io.execute {
            val info = JSONObject().put("riderLabel", riderLabel).put("destinationLabel", destinationLabel ?: "")
                .put("createdAt", System.currentTimeMillis())
            if (send("PUT", "$base/info.json", info.toString())) log("타는 사람 링크 준비됨 (${tripId.take(6)}…)")
        }
        Thread(::listen, "rider-listen").apply { isDaemon = true }.start()
    }

    fun stop() { running = false; io.shutdown(); later.shutdownNow() }

    /**
     * 탑승 1분 뒤 위치 기록을 지운다 (기획서·문서의 약속: "이번 만남 동안만 공유, 탑승하면 끝, 위치 기록을 남기지 않음").
     * 차 위치·타는 사람 위치·걷는 길을 지우고 info.ended 를 남겨 웹이 "끝난 만남"으로 보이게 한다.
     */
    private fun scheduleCleanup() {
        cleanup?.cancel(false)
        cleanup = later.schedule({
            io.execute {
                val ok = send("PATCH", "$base.json", JSONObject()
                    .put("car", JSONObject.NULL).put("rider/pos", JSONObject.NULL)
                    .put("meet/walkPath", JSONObject.NULL).put("info/ended", System.currentTimeMillis()).toString())
                if (ok) log("탑승 1분이 지나 위치 기록을 지웠어요")
            }
        }, CLEANUP_AFTER_SEC, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** "처음으로": 같은 링크를 그대로 쓰되 사정·출발·만남 장소는 지운다 */
    fun resetTrip() {
        cleanup?.cancel(false); cleanup = null; lastPhase = null
        meetKey = null; statusKey = null
        lastRider = lastRider.copy(needs = emptySet(), walking = false)
        io.execute {
            send("PATCH", "$base.json", JSONObject().put("meet", JSONObject.NULL).put("notice", JSONObject.NULL).put("info/ended", JSONObject.NULL)
                .put("rider/walking", false).put("rider/needs", JSONObject.NULL).toString())
        }
    }

    // ---------------- 차 → 웹 ----------------

    /** 상태가 바뀔 때마다 부른다. 바뀐 부분만 보낸다 */
    fun publish(s: TripState) {
        val c = s.chosen
        val patch = JSONObject()
        val mk = c?.let { TripController.curbKey(it.curb) } ?: "none"
        var walkAfter = false
        if (mk != meetKey) {
            meetKey = mk
            patch.put("meet", c?.let { meetJson(it, s) } ?: JSONObject.NULL)
            walkAfter = c != null
        }
        if (s.phase.name != lastPhase) {
            if (s.phase.name == "PICKED_UP") scheduleCleanup()
            lastPhase = s.phase.name
        }
        val call = s.callOutInSec?.roundToInt()
        val eta = s.etaSec?.roundToInt()
        val sk = "${s.phase}|$eta|$call|${s.calledOut}|${s.riderWalking}|${s.needs}"
        if (sk != statusKey) {
            statusKey = sk
            patch.put("status", JSONObject()
                .put("phase", s.phase.name)
                .put("etaSec", eta ?: JSONObject.NULL)
                .put("callOutInSec", call ?: JSONObject.NULL)
                .put("calledOut", s.calledOut)
                .put("locked", s.riderWalking)
                .put("needs", JSONArray(s.needs.map { it.name }))
                .put("at", System.currentTimeMillis()))
        }
        val now = System.currentTimeMillis()
        val car = s.car
        if (car != null && now - lastCarAt > 2000) {
            lastCarAt = now
            patch.put("car", JSONObject().put("lat", car.lat).put("lon", car.lon)
                .put("heading", s.carHeadingDeg.roundToInt()).put("kmh", (s.speedMps * 3.6).roundToInt()).put("at", now))
        }
        if (patch.length() > 0) io.execute { send("PATCH", "$base.json", patch.toString()) }
        // 11/10: 걷는 길은 만남 장소를 쓴 "뒤에" 붙인다 (먼저 붙이면 만남 장소 전체를 다시 쓰면서 지워짐)
        if (walkAfter && c != null) {
            // 지금 장소 + 엔진의 다른 상위 후보까지 TMAP 으로 잰다 → TripController.refine
            val cands = (listOf(c) + listOfNotNull(s.result?.best) + s.result?.alternatives.orEmpty())
                .distinctBy { TripController.curbKey(it.curb) }.take(4)
            measureWalks(s.rider.pos, cands.map { it.curb }, c.curb)
            fetchLandmark(c)
        }
    }

    private fun meetJson(c: Candidate, s: TripState) = JSONObject()
        .put("name", c.placeName)
        .put("kind", c.kind.name)
        .put("lat", c.curb.lat).put("lon", c.curb.lon)
        .put("walkSec", c.walkSec.roundToInt())
        .put("crossings", JSONArray(c.crossings))
        .put("verified", c.field == FieldStatus.VERIFIED_OK)
        .put("note", if (c.field == FieldStatus.VERIFIED_OK) c.fieldNote else "")
        .put("desc", describe(c, s))
        .put("at", System.currentTimeMillis())

    private fun describe(c: Candidate, s: TripState): String {
        if (c.kind == CandidateKind.OFF_ROAD) return "${c.placeName} 안 타는 곳 (차가 주차장으로 들어가요)"
        val side = compass(Geo.bearing(s.rider.pos, c.curb))
        val d = Geo.dist(s.rider.pos, c.curb).roundToInt()
        return "${c.placeName} 길가 · 지금 계신 곳에서 $side 약 ${d}m"
    }

    private fun compass(b: Double): String {
        val names = listOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")
        return names[(((b % 360 + 360) % 360 + 22.5) / 45).toInt() % 8] + "으로"
    }

    private fun notice(text: String, kind: String) = io.execute {
        send("PUT", "$base/notice.json", JSONObject().put("text", text).put("kind", kind).put("at", System.currentTimeMillis()).toString())
    }

    override fun meetingPlaceChanged(c: Candidate, message: String) = notice("만남 장소: ${c.placeName}", "meet")
    override fun comeOutNow(c: Candidate) = notice("지금 나오세요! ${Josa.ro(c.placeName)} 가 주세요", "comeout")
    override fun carArrived(c: Candidate) = notice("차가 도착했어요", "arrived")
    override fun placeKept(c: Candidate) = notice("사정을 반영했어요 — 지금 장소가 그대로 가장 좋아요", "kept")

    // ---------------- TMAP 보행 경로 ----------------

    private class Walk(val sec: Double, val meters: Double, val path: JSONArray)
    private val walkCache = java.util.concurrent.ConcurrentHashMap<String, Walk>()

    /**
     * 후보 여러 곳까지 TMAP 보행 경로를 잰다 (같은 출발·도착은 다시 묻지 않음).
     * 끝나면 지금 만남 장소의 걷는 길을 웹에 붙이고, 모든 결과를 onWalks 로 넘긴다.
     */
    private fun measureWalks(from: LatLng, targets: List<LatLng>, chosen: LatLng) {
        if (tmapKey.isBlank()) { log("TMAP 키가 없어 걷는 길은 직선으로 보여요"); return }
        io.execute {
            val origin = TripController.curbKey(from)
            var asked = 0
            for (t in targets) {
                val k = origin + ">" + TripController.curbKey(t)
                if (walkCache.containsKey(k)) continue
                tmapWalk(from, t)?.let { walkCache[k] = it; asked++ }
            }
            val result = targets.mapNotNull { t ->
                walkCache[origin + ">" + TripController.curbKey(t)]?.let { TripController.curbKey(t) to it.sec }
            }.toMap()
            val mine = walkCache[origin + ">" + TripController.curbKey(chosen)]
            if (mine != null && meetKey == TripController.curbKey(chosen)) {
                send("PATCH", "$base/meet.json", JSONObject().put("walkPath", mine.path)
                    .put("tmapWalkSec", mine.sec.roundToInt()).put("tmapWalkM", mine.meters.roundToInt()).toString())
            }
            if (asked > 0) log("TMAP 걷는 길: 후보 ${result.size}곳 확인" +
                (mine?.let { " · 지금 장소 ${it.meters.roundToInt()}m · ${it.sec.roundToInt()}초" } ?: ""))
            if (result.isNotEmpty()) onWalks(result)
        }
    }

    private fun tmapWalk(from: LatLng, to: LatLng): Walk? = runCatching {
        val body = JSONObject()
            .put("startX", from.lon.toString()).put("startY", from.lat.toString())
            .put("endX", to.lon.toString()).put("endY", to.lat.toString())
            .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
            .put("startName", "출발").put("endName", "만남 장소")
        val res = http("POST", "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json",
            body.toString(), mapOf("appKey" to tmapKey)) ?: return null
        val feats = JSONObject(res).getJSONArray("features")
        var sec = 0.0; var meters = 0.0
        val path = JSONArray()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.getJSONObject("properties")
            if (i == 0) { sec = p.optDouble("totalTime", 0.0); meters = p.optDouble("totalDistance", 0.0) }
            val g = f.getJSONObject("geometry")
            if (g.getString("type") == "LineString") {
                val cs = g.getJSONArray("coordinates")
                for (k in 0 until cs.length()) {
                    val xy = cs.getJSONArray(k)
                    path.put(JSONArray().put(xy.getDouble(1)).put(xy.getDouble(0)))
                }
            }
        }
        if (sec <= 0) return null
        // 11/10 실측: 출발·도착이 가까우면 TMAP 이 같은 길 위 점으로 맞춰 "1초"를 주기도 함 → 직선 거리보다 짧게는 안 씀
        val straight = Geo.dist(from, to)
        Walk(maxOf(sec, straight / 1.3), maxOf(meters, straight), path)
    }.getOrElse { log("TMAP 걷는 길 실패: ${it.message}"); null }

    // ---------------- 카카오: "○○ 앞" ----------------

    /**
     * 만남 장소에서 35m 안, **같은 쪽 길가**에 있는 가장 가까운 가게·은행·출구를 찾는다.
     * 길 건너편 가게를 말하면 오히려 헷갈리므로 도로 중심선 기준으로 같은 쪽만 쓴다.
     */
    private fun fetchLandmark(c: Candidate) {
        if (c.kind == CandidateKind.OFF_ROAD) return
        if (kakaoKey.isBlank()) { log("카카오 키가 없어 장소 이름은 길 이름만 써요"); return }
        val at = c.curb
        io.execute {
            runCatching {
                val (center, heading) = Geo.pointAlong(c.road.link.pts, c.road.along)
                val curbSide = side(center, heading, at)
                var best: Pair<String, Double>? = null
                for (code in listOf("SW8", "BK9", "CS2", "CE7", "FD6", "PM9", "HP8", "PO3", "MT1")) {
                    val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$code" +
                        "&x=${at.lon}&y=${at.lat}&radius=35&sort=distance&size=5"
                    val res = http("GET", url, null, mapOf("Authorization" to "KakaoAK $kakaoKey")) ?: continue
                    val docs = JSONObject(res).getJSONArray("documents")
                    for (i in 0 until docs.length()) {
                        val d = docs.getJSONObject(i)
                        val p = LatLng(d.getString("y").toDouble(), d.getString("x").toDouble())
                        if (side(center, heading, p) != curbSide) continue
                        val m = Geo.dist(at, p)
                        if (m <= 35 && (best == null || m < best.second)) best = d.getString("place_name") to m
                    }
                }
                if (meetKey != TripController.curbKey(at)) return@runCatching   // 그새 장소가 바뀜
                val b = best ?: run { log("카카오: 같은 쪽 35m 안에 이름 붙일 곳이 없어요"); return@runCatching }
                val name = b.first.trim()
                send("PATCH", "$base/meet.json", JSONObject().put("landmark", name).put("landmarkM", b.second.roundToInt()).toString())
                log("카카오: $name 앞 (${b.second.roundToInt()}m)")
                onLandmark(at, name)
            }.onFailure { log("카카오 장소 이름 실패: ${it.message}") }
        }
    }

    /** 도로 진행 방향 기준 오른쪽(+1)/왼쪽(-1) */
    private fun side(center: LatLng, heading: Double, p: LatLng): Int {
        val diff = ((Geo.bearing(center, p) - heading) % 360 + 360) % 360
        return if (diff in 0.0..180.0) 1 else -1
    }

    // ---------------- 웹 → 차 (실시간 듣기) ----------------

    private fun listen() {
        var wait = 2000L
        while (running) {
            try {
                val conn = (URL("$base/rider.json").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 10_000; readTimeout = 70_000   // Firebase 는 30초마다 keep-alive 를 보냄
                }
                if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                wait = 2000L
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    var event = ""
                    while (running) {
                        val line = r.readLine() ?: break
                        when {
                            line.startsWith("event:") -> {
                                event = line.substringAfter(":").trim()
                                if (event == "cancel" || event == "auth_revoked") break
                            }
                            line.startsWith("data:") && (event == "put" || event == "patch") -> refreshRider()
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (running) log("링크 연결 다시 시도: ${e.message}")
            }
            if (running) { Thread.sleep(wait); wait = (wait * 2).coerceAtMost(30_000) }
        }
    }

    /** 바뀐 부분만 오는 이벤트를 직접 합치지 않고, rider 전체를 다시 읽는다 (작아서 충분) */
    private fun refreshRider() {
        val text = http("GET", "$base/rider.json", null) ?: return
        if (text == "null") return
        val o = JSONObject(text)
        val needsObj = o.optJSONObject("needs")
        val needs = Need.entries.filter { needsObj?.optBoolean(it.name, false) == true }.toSet()
        val p = o.optJSONObject("pos")
        val info = RiderInfo(
            joined = o.has("joinedAt"),
            needs = needs,
            walking = o.optBoolean("walking", false),
            pos = p?.let { LatLng(it.getDouble("lat"), it.getDouble("lon")) },
            demo = o.optBoolean("demo", false),
        )
        if (info != lastRider) {
            if (info.joined && !lastRider.joined) log("타는 사람이 링크를 열었어요" + if (info.demo) " (시연 모드)" else "")
            lastRider = info
            onRider(info)
        }
    }

    // ---------------- HTTP ----------------

    private fun send(method: String, url: String, body: String): Boolean = http(method, url, body) != null

    private fun http(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap()): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            // HttpURLConnection 은 PATCH 를 모름 → Firebase 의 X-HTTP-Method-Override 사용
            if (method == "PATCH") { requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH") }
            else requestMethod = method
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) { log("통신 실패 $code: ${text?.take(80)}"); null } else text ?: ""
    } catch (e: Exception) {
        log("통신 오류: ${e.message}"); null
    }

    companion object {
        const val CLEANUP_AFTER_SEC = 60L
        private const val ABC = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        /** 링크를 아는 사람만 열 수 있게 길고 무작위로 (DB 규칙: 16자 이상) */
        fun newId(): String { val r = SecureRandom(); return (1..20).map { ABC[r.nextInt(ABC.length)] }.joinToString("") }
    }
}
