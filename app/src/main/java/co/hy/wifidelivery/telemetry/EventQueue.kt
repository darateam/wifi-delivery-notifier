package co.hy.wifidelivery.telemetry

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 오프라인 버퍼링 이벤트 큐.
 *
 * 배송 현장은 지하주차장·엘리베이터·복도에서 네트워크가 수시로 끊긴다.
 * 즉시 전송을 시도하고 실패하면 버리는 구조로 만들면 정작 분석에 필요한
 * 경계 구간 로그가 통째로 사라진다. NDJSON으로 로컬에 먼저 append 하고,
 * 네트워크가 살아 있을 때 배치로 밀어 올린다.
 */
class EventQueue private constructor(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun enqueue(event: JSONObject) {
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_BYTES) trimOldest()
                file.appendText(event.toString() + "\n")
            }
        }
    }

    /** 전송할 배치를 꺼낸다. 성공 확인 전까지 파일에서 지우지 않는다. */
    fun peekBatch(limit: Int = BATCH_SIZE): List<String> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            file.useLines { seq -> seq.filter { it.isNotBlank() }.take(limit).toList() }
        }.getOrDefault(emptyList())
    }

    /** 전송에 성공한 개수만큼 앞에서 제거 */
    fun commit(count: Int) = synchronized(lock) {
        if (!file.exists() || count <= 0) return
        runCatching {
            val remaining = file.readLines().filter { it.isNotBlank() }.drop(count)
            if (remaining.isEmpty()) file.delete() else file.writeText(remaining.joinToString("\n") + "\n")
        }
        Unit
    }

    fun pendingCount(): Int = synchronized(lock) {
        if (!file.exists()) return 0
        runCatching { file.readLines().count { it.isNotBlank() } }.getOrDefault(0)
    }

    private fun trimOldest() {
        runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            file.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    companion object {
        private const val FILE_NAME = "telemetry.ndjson"
        private const val MAX_BYTES = 5L * 1024 * 1024
        const val BATCH_SIZE = 200

        @Volatile
        private var instance: EventQueue? = null

        fun get(context: Context): EventQueue =
            instance ?: synchronized(this) {
                instance ?: EventQueue(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 이벤트 생성기.
 *
 * BSSID를 원본 그대로 올리면 안 된다. 고객 집 공유기 MAC 주소가 웨어하우스에
 * 평문으로 쌓이는 순간 특정 가구와 연결 가능한 식별자가 되고, 분석 목적으로는
 * 원본이 전혀 필요 없다. 단말에서 솔트 해시로 바꿔서 올린다.
 * 매칭에 쓰는 원본 BSSID는 단말 로컬에만 남는다.
 */
object Events {

    private const val PREFS = "telemetry"
    private const val KEY_DEVICE = "device_uuid"

    /** 앱 최초 실행 시 생성하는 난수 ID. ANDROID_ID 같은 기기 식별자를 쓰지 않는다. */
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    fun hashBssid(bssid: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((salt + bssid.lowercase()).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun base(context: Context, type: String) = JSONObject()
        .put("event_type", type)
        .put("event_ts", System.currentTimeMillis())
        .put("device_id", deviceId(context))
        .put("app_version", co.hy.wifidelivery.BuildConfig.VERSION_NAME)

    /** 서명 수집 완료 */
    fun signatureCollected(
        context: Context,
        salt: String,
        memberId: String,
        rounds: Int,
        aps: List<co.hy.wifidelivery.model.ApStat>
    ): JSONObject {
        val arr = org.json.JSONArray()
        aps.forEach { ap ->
            arr.put(
                JSONObject()
                    .put("bssid_hash", hashBssid(ap.bssid, salt))
                    .put("rssi_mean", ap.meanRssi)
                    .put("hit_ratio", ap.hitRatio)
            )
        }
        return base(context, "signature_collected")
            .put("member_id", memberId)
            .put("rounds", rounds)
            .put("ap_count", aps.size)
            .put("aps", arr)
    }

    /**
     * 매칭 평가 1회 로그.
     *
     * 이게 이 프로젝트에서 제일 값어치 있는 데이터다.
     * 1등·2등 점수와 마진을 전부 남겨두면, 나중에 임계값을 바꿨을 때
     * 오탐이 어떻게 변했을지를 재수집 없이 오프라인에서 재현할 수 있다.
     */
    fun matchEvaluated(
        context: Context,
        decision: co.hy.wifidelivery.wifi.MatchDecision,
        visibleAps: Int,
        fired: Boolean
    ): JSONObject {
        val best = decision.best
        val runner = decision.runnerUp
        return base(context, "match_evaluated")
            .put("visible_ap_count", visibleAps)
            .put("best_member_id", best?.target?.targetId ?: JSONObject.NULL)
            .put("best_score", best?.score ?: JSONObject.NULL)
            .put("best_coverage", best?.coverage ?: JSONObject.NULL)
            .put("best_rssi_score", best?.rssiScore ?: JSONObject.NULL)
            .put("best_anchor_hit", best?.anchorHit ?: JSONObject.NULL)
            .put("best_common_aps", best?.commonAps ?: JSONObject.NULL)
            .put("runner_up_member_id", runner?.target?.targetId ?: JSONObject.NULL)
            .put("runner_up_score", runner?.score ?: JSONObject.NULL)
            .put("margin", if (best != null) best.score - (runner?.score ?: 0.0) else JSONObject.NULL)
            .put("confident", decision.confident)
            .put("ambiguous", decision.ambiguous)
            .put("tied_count", decision.tied.size)
            .put("best_floor_mismatch", best?.floorMismatch ?: JSONObject.NULL)
            .put("decision_reason", decision.reason)
            .put("fired", fired)
    }

    /**
     * 정답 라벨 1건.
     *
     * 라벨은 현장 왕복의 결과물이라 단말에만 두면 기기 교체 한 번에 날아간다.
     * 웨어하우스에 모아두면 여러 담당자가 찍은 라벨을 합쳐서 튜닝할 수 있다.
     */
    fun labelRecorded(
        context: Context,
        predictedMemberId: String?,
        actualMemberId: String?,
        verdict: String,
        params: JSONObject
    ): JSONObject = base(context, "label_recorded")
        .put("predicted_member_id", predictedMemberId ?: JSONObject.NULL)
        .put("actual_member_id", actualMemberId ?: JSONObject.NULL)
        .put("verdict", verdict)
        .put("params_at_label", params)

    /**
     * NFC 태그와 Wi-Fi 위치가 어긋난 건.
     *
     * 이게 잦아지는 세대는 바구니가 자주 옮겨지거나 서명이 노후한 것이다.
     * 둘 다 사람이 개입해야 하는 신호라 따로 남긴다.
     */
    fun tagLocationConflict(
        context: Context,
        memberId: String,
        score: Double,
        rivalWins: Boolean
    ): JSONObject = base(context, "tag_location_conflict")
        .put("member_id", memberId)
        .put("wifi_score", score)
        .put("rival_wins", rivalWins)

    /** 다음 순번 고객에게 나간 방문 예정 안내 */
    fun advanceNoticeSent(
        context: Context,
        memberId: String,
        triggeredBy: String,
        success: Boolean,
        isAd: Boolean
    ): JSONObject = base(context, "advance_notice_sent")
        .put("member_id", memberId)
        .put("triggered_by_member_id", triggeredBy)
        .put("success", success)
        .put("is_ad", isAd)

    /** 문자 발송 결과 */
    fun notificationSent(
        context: Context,
        memberId: String,
        mode: String,
        success: Boolean,
        dwellSeconds: Int
    ): JSONObject = base(context, "notification_sent")
        .put("member_id", memberId)
        .put("send_mode", mode)
        .put("success", success)
        .put("dwell_seconds", dwellSeconds)
}
