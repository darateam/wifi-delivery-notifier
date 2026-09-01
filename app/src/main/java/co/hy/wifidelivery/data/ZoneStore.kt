package co.hy.wifidelivery.data

import android.content.Context
import co.hy.wifidelivery.model.ApStat
import co.hy.wifidelivery.model.Zone
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events
import co.hy.wifidelivery.model.Member
import co.hy.wifidelivery.sms.MessageComposer
import co.hy.wifidelivery.sms.SmsSender
import co.hy.wifidelivery.wifi.RouteScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 사무실 구역 저장소 */
class ZoneStore private constructor(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val cache = LinkedHashMap<String, Zone>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<Zone> = cache.values.sortedBy { it.name }

    @Synchronized
    fun withSignature(): List<Zone> = cache.values.filter { it.hasSignature }

    @Synchronized
    fun get(id: String): Zone? = cache[id]

    @Synchronized
    fun add(name: String, description: String): Zone {
        val z = Zone(id = UUID.randomUUID().toString(), name = name, description = description)
        cache[z.id] = z
        persist()
        return z
    }

    @Synchronized
    fun delete(id: String) {
        cache.remove(id)
        persist()
    }

    /**
     * 구역 서명은 점이 아니라 면이라 여러 지점을 합쳐야 한다.
     * 그래서 덮어쓰지 않고 항상 누적 병합한다.
     */
    @Synchronized
    fun mergeSignature(
        id: String,
        fresh: List<ApStat>,
        freshRounds: Int,
        pressureIndex: Double? = null,
        alpha: Double = 0.4
    ) {
        val z = cache[id] ?: return
        if (pressureIndex != null) z.pressureIndex = pressureIndex
        if (z.signature.isEmpty()) {
            z.signature = fresh
            z.scanRounds = freshRounds
        } else {
            val old = z.signature.associateBy { it.bssid }
            val merged = LinkedHashMap<String, ApStat>()
            fresh.forEach { f ->
                val o = old[f.bssid]
                merged[f.bssid] = if (o == null) f.copy(hitRatio = f.hitRatio * alpha)
                else ApStat(
                    bssid = f.bssid,
                    ssid = f.ssid.ifBlank { o.ssid },
                    meanRssi = o.meanRssi * (1 - alpha) + f.meanRssi * alpha,
                    hitRatio = o.hitRatio * (1 - alpha) + f.hitRatio * alpha,
                    // 구역 안 여러 지점을 합치면 편차가 커지는 게 정상이다.
                    // 이 편차 자체가 "구역 안에서 얼마나 흔들리는 AP인가"를 담는다.
                    stdRssi = maxOf(o.stdRssi, f.stdRssi),
                    is5Ghz = f.is5Ghz || o.is5Ghz
                )
            }
            old.forEach { (b, o) ->
                if (!merged.containsKey(b)) merged[b] = o.copy(hitRatio = o.hitRatio * (1 - alpha))
            }
            z.signature = merged.values.filter { it.hitRatio >= 0.15 }
                .sortedByDescending { it.meanRssi }
            z.scanRounds += freshRounds
        }
        z.updatedAt = System.currentTimeMillis()
        persist()
    }

    private fun buildJson(): JSONArray {
        val arr = JSONArray()
        cache.values.forEach { z ->
            val sig = JSONArray()
            z.signature.forEach { ap ->
                sig.put(
                    JSONObject()
                        .put("bssid", ap.bssid).put("ssid", ap.ssid)
                        .put("meanRssi", ap.meanRssi).put("hitRatio", ap.hitRatio)
                        .put("stdRssi", ap.stdRssi).put("is5Ghz", ap.is5Ghz)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", z.id).put("name", z.name)
                    .put("description", z.description)
                    .put("scanRounds", z.scanRounds).put("updatedAt", z.updatedAt)
                    .put("pressureIndex", z.pressureIndex ?: JSONObject.NULL)
                    .put("signature", sig)
            )
        }
        return arr
    }

    private fun persist() {
        runCatching { file.writeText(buildJson().toString()) }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sigArr = o.optJSONArray("signature") ?: JSONArray()
                val sig = ArrayList<ApStat>(sigArr.length())
                for (j in 0 until sigArr.length()) {
                    val a = sigArr.getJSONObject(j)
                    sig.add(
                        ApStat(
                            bssid = a.getString("bssid"),
                            ssid = a.optString("ssid"),
                            meanRssi = a.getDouble("meanRssi"),
                            hitRatio = a.getDouble("hitRatio"),
                            stdRssi = a.optDouble("stdRssi", ApStat.DEFAULT_STD),
                            is5Ghz = a.optBoolean("is5Ghz", false)
                        )
                    )
                }
                val z = Zone(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    description = o.optString("description"),
                    signature = sig,
                    scanRounds = o.optInt("scanRounds"),
                    updatedAt = o.optLong("updatedAt"),
                    pressureIndex = if (o.isNull("pressureIndex")) null else o.optDouble("pressureIndex")
                )
                cache[z.id] = z
            }
        }
    }

    companion object {
        private const val FILE_NAME = "zones.json"

        @Volatile
        private var instance: ZoneStore? = null

        fun get(context: Context): ZoneStore =
            instance ?: synchronized(this) {
                instance ?: ZoneStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 배송 완료 처리 한 곳.
 *
 * 자동 확정, 확인 모드 탭, 구분 선택, 구역 목록 탭 — 완료 경로가 넷이라
 * 고객별 알림 설정 확인을 각자 하게 두면 반드시 한 군데가 빠진다.
 * 전부 여기를 통과시킨다.
 */
object DeliveryCompleter {

    enum class Result { SENT, SKIPPED_OPT_OUT, FAILED }

    /**
     * @param mode 텔레메트리용 완료 경로 이름 (auto / confirm / disambiguated / zone_pick)
     */
    fun complete(context: Context, member: Member, mode: String): Result {
        val settings = Settings(context)
        val store = MemberStore.get(context)

        // 문자를 원치 않는 고객. 감지와 배송 완료 처리는 그대로 하고 발송만 건너뛴다.
        if (!member.notifyEnabled) {
            store.markNotified(member.id)
            RouteScope(context).advanceTo(member)
            EventQueue.get(context).enqueue(
                Events.notificationSent(context, member.id, "${mode}_optout", false, settings.dwellSeconds)
            )
            return Result.SKIPPED_OPT_OUT
        }

        val composed = MessageComposer.compose(context, member, MessageComposer.Kind.DELIVERY_COMPLETE)
        val ok = SmsSender.send(context, member.phone, composed.body)
        if (ok) {
            store.markNotified(member.id)
            RouteScope(context).advanceTo(member)
        }
        EventQueue.get(context).enqueue(
            Events.notificationSent(context, member.id, mode, ok, settings.dwellSeconds)
                .put("is_ad", composed.isAd)
                .put("ad_skip_reason", composed.adSkipReason ?: org.json.JSONObject.NULL)
        )

        // 완료된 뒤에야 다음 집 예고를 보낸다. 순서가 반대면
        // 배송이 실패했는데 다음 고객이 기다리는 상황이 된다.
        if (ok) notifyNext(context, member)

        return if (ok) Result.SENT else Result.FAILED
    }

    /**
     * 다음 순번 고객에게 방문 예정 안내.
     *
     * 순번이 정확해야 의미가 있다. 건너뛰거나 부재중 처리가 잦으면
     * 엉뚱한 사람이 기다리게 되므로, 경로 관리가 안 되는 상태에서는
     * 이 기능을 켜지 않는 게 낫다.
     */
    private fun notifyNext(context: Context, justDelivered: Member) {
        if (justDelivered.routeOrder <= 0) return
        val store = MemberStore.get(context)
        val next = store.nextInRoute(justDelivered.routeOrder) ?: return
        if (!next.advanceNoticeEnabled || !next.notifyEnabled) return

        // 하루에 두 번 이상 예고가 가지 않게 막는다.
        if (System.currentTimeMillis() - next.lastAdvanceNoticeAt < 12 * 3600_000L) return

        val composed = MessageComposer.compose(context, next, MessageComposer.Kind.ADVANCE_NOTICE)
        val sent = SmsSender.send(context, next.phone, composed.body)
        if (sent) {
            next.lastAdvanceNoticeAt = System.currentTimeMillis()
            store.upsert(next)
        }
        EventQueue.get(context).enqueue(
            Events.advanceNoticeSent(context, next.id, justDelivered.id, sent, composed.isAd)
        )
    }

    fun message(member: Member, result: Result): String = when (result) {
        Result.SENT -> "${member.name} 발송 완료"
        Result.SKIPPED_OPT_OUT -> "${member.name} 배송 완료 (문자 미수신 고객)"
        Result.FAILED -> "${member.name} 발송 실패"
    }
}
