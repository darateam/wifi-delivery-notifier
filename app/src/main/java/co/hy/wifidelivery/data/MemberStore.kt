package co.hy.wifidelivery.data

import android.content.Context
import co.hy.wifidelivery.model.ApStat
import co.hy.wifidelivery.model.DeliveryType
import co.hy.wifidelivery.model.Member
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 회원 + 서명 저장소.
 *
 * 외부 의존성 없이 filesDir 안의 단일 JSON 파일로 관리한다.
 * 배송 1개 루트가 보통 100~300세대 규모라 이 정도면 충분하고,
 * 파일을 그대로 꺼내 서버로 올리거나 사람이 열어보고 디버깅하기도 쉽다.
 */
class MemberStore private constructor(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val cache = LinkedHashMap<String, Member>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<Member> = cache.values.sortedBy { it.name }

    /** NFC UID로 회원을 찾는다. 태그 UID는 전역 유일하므로 충돌 걱정이 없다. */
    @Synchronized
    fun findByTag(uid: String): Member? =
        cache.values.firstOrNull { it.nfcTagUid.equals(uid, ignoreCase = true) }

    @Synchronized
    fun tagOwner(uid: String, excludeId: String? = null): Member? =
        cache.values.firstOrNull {
            it.id != excludeId && it.nfcTagUid.equals(uid, ignoreCase = true)
        }

    /**
     * 배송 순서상 다음 고객.
     *
     * 순번이 없거나 마지막이면 null. 건너뛴 순번이 있어도 그 다음으로 넘어간다.
     */
    @Synchronized
    fun nextInRoute(afterOrder: Int): Member? = cache.values
        .filter { it.routeOrder > afterOrder }
        .minByOrNull { it.routeOrder }

    @Synchronized
    fun crossCheckTargets(): List<Member> = cache.values.filter { it.usableForCrossCheck }

    @Synchronized
    fun nextRouteOrder(): Int = (cache.values.maxOfOrNull { it.routeOrder } ?: 0) + 1

    @Synchronized
    fun withSignature(): List<Member> = cache.values.filter { it.hasSignature }

    /**
     * 문앞 서명으로 자동 확정할 대상.
     * 사무실 고객은 여기서 빠진다 — 책상 단위는 Wi-Fi로 못 가르기 때문에
     * 자동 확정 후보에 넣으면 옆자리 오배송만 만든다.
     */
    @Synchronized
    fun autoDetectable(): List<Member> = cache.values.filter { it.autoDetectable }

    @Synchronized
    fun get(id: String): Member? = cache[id]

    @Synchronized
    fun upsert(member: Member) {
        cache[member.id] = member
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        cache.remove(id)
        persist()
    }

    @Synchronized
    fun addMember(
        name: String,
        phone: String,
        address: String,
        routeOrder: Int = 0,
        deliveryType: DeliveryType = DeliveryType.HOME,
        zoneId: String? = null,
        notifyEnabled: Boolean = true,
        wifiAutoDetect: Boolean = true
    ): Member {
        val m = Member(
            id = UUID.randomUUID().toString(),
            name = name,
            phone = phone,
            address = address,
            routeOrder = routeOrder,
            deliveryType = deliveryType,
            zoneId = zoneId,
            notifyEnabled = notifyEnabled,
            wifiAutoDetect = wifiAutoDetect
        )
        cache[m.id] = m
        persist()
        return m
    }

    /**
     * 새로 수집한 서명을 기존 서명과 합친다.
     *
     * 공유기 교체·이사·주변 세대 변화로 서명은 시간이 지나면 반드시 깨진다.
     * 배송이 확정될 때마다 지수이동평균으로 조금씩 끌어당겨 갱신해 두면
     * 몇 달 뒤 매칭률이 무너지는 걸 막을 수 있다.
     */
    @Synchronized
    fun mergeSignature(
        id: String,
        fresh: List<ApStat>,
        freshRounds: Int,
        pressureIndex: Double? = null,
        alpha: Double = 0.3
    ) {
        val m = cache[id] ?: return
        if (pressureIndex != null) m.pressureIndex = pressureIndex
        if (m.signature.isEmpty()) {
            m.signature = fresh
            m.scanRounds = freshRounds
        } else {
            val old = m.signature.associateBy { it.bssid }
            val merged = LinkedHashMap<String, ApStat>()
            fresh.forEach { f ->
                val o = old[f.bssid]
                merged[f.bssid] = if (o == null) {
                    f.copy(hitRatio = f.hitRatio * alpha)
                } else {
                    ApStat(
                        bssid = f.bssid,
                        ssid = f.ssid.ifBlank { o.ssid },
                        meanRssi = o.meanRssi * (1 - alpha) + f.meanRssi * alpha,
                        hitRatio = o.hitRatio * (1 - alpha) + f.hitRatio * alpha,
                        stdRssi = o.stdRssi * (1 - alpha) + f.stdRssi * alpha
                    )
                }
            }
            // 이번에 안 잡힌 기존 AP는 지우지 말고 hitRatio만 감쇠시킨다.
            old.forEach { (bssid, o) ->
                if (!merged.containsKey(bssid)) {
                    merged[bssid] = o.copy(hitRatio = o.hitRatio * (1 - alpha))
                }
            }
            m.signature = merged.values
                .filter { it.hitRatio >= 0.15 }
                .sortedByDescending { it.meanRssi }
            m.scanRounds = m.scanRounds + freshRounds
        }
        m.updatedAt = System.currentTimeMillis()
        persist()
    }

    @Synchronized
    fun markNotified(id: String) {
        cache[id]?.lastNotifiedAt = System.currentTimeMillis()
        persist()
    }

    @Synchronized
    fun exportJson(): String = buildJson().toString(2)

    // ---- 직렬화 ----

    private fun buildJson(): JSONArray {
        val arr = JSONArray()
        cache.values.forEach { m ->
            val sig = JSONArray()
            m.signature.forEach { ap ->
                sig.put(
                    JSONObject()
                        .put("bssid", ap.bssid)
                        .put("ssid", ap.ssid)
                        .put("meanRssi", ap.meanRssi)
                        .put("hitRatio", ap.hitRatio)
                        .put("stdRssi", ap.stdRssi)
                        .put("is5Ghz", ap.is5Ghz)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("phone", m.phone)
                    .put("address", m.address)
                    .put("scanRounds", m.scanRounds)
                    .put("updatedAt", m.updatedAt)
                    .put("lastNotifiedAt", m.lastNotifiedAt)
                    .put("routeOrder", m.routeOrder)
                    .put("pressureIndex", m.pressureIndex ?: JSONObject.NULL)
                    .put("deliveryType", m.deliveryType.name)
                    .put("zoneId", m.zoneId ?: JSONObject.NULL)
                    .put("notifyEnabled", m.notifyEnabled)
                    .put("nfcTagUid", m.nfcTagUid ?: JSONObject.NULL)
                    .put("wifiAutoDetect", m.wifiAutoDetect)
                    .put("advanceNoticeEnabled", m.advanceNoticeEnabled)
                    .put("lastAdvanceNoticeAt", m.lastAdvanceNoticeAt)
                    .put("adConsentAt", m.adConsentAt)
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
                    val s = sigArr.getJSONObject(j)
                    sig.add(
                        ApStat(
                            bssid = s.getString("bssid"),
                            ssid = s.optString("ssid"),
                            meanRssi = s.getDouble("meanRssi"),
                            hitRatio = s.getDouble("hitRatio"),
                            stdRssi = s.optDouble("stdRssi", ApStat.DEFAULT_STD),
                            is5Ghz = s.optBoolean("is5Ghz", false)
                        )
                    )
                }
                val m = Member(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    phone = o.getString("phone"),
                    address = o.optString("address"),
                    signature = sig,
                    scanRounds = o.optInt("scanRounds"),
                    updatedAt = o.optLong("updatedAt"),
                    lastNotifiedAt = o.optLong("lastNotifiedAt"),
                    routeOrder = o.optInt("routeOrder", 0),
                    pressureIndex = if (o.isNull("pressureIndex")) null
                        else o.optDouble("pressureIndex"),
                    deliveryType = runCatching {
                        DeliveryType.valueOf(o.optString("deliveryType", "HOME"))
                    }.getOrDefault(DeliveryType.HOME),
                    zoneId = if (o.isNull("zoneId")) null else o.optString("zoneId").ifBlank { null },
                    notifyEnabled = o.optBoolean("notifyEnabled", true),
                    nfcTagUid = if (o.isNull("nfcTagUid")) null
                        else o.optString("nfcTagUid").ifBlank { null },
                    wifiAutoDetect = o.optBoolean("wifiAutoDetect", true),
                    advanceNoticeEnabled = o.optBoolean("advanceNoticeEnabled", false),
                    lastAdvanceNoticeAt = o.optLong("lastAdvanceNoticeAt"),
                    adConsentAt = o.optLong("adConsentAt")
                )
                cache[m.id] = m
            }
        }
    }

    companion object {
        private const val FILE_NAME = "members.json"

        @Volatile
        private var instance: MemberStore? = null

        fun get(context: Context): MemberStore =
            instance ?: synchronized(this) {
                instance ?: MemberStore(context.applicationContext).also { instance = it }
            }
    }
}

/** 운영 파라미터. 현장에서 튜닝할 값들이라 전부 밖으로 빼 둔다. */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 문앞 체류로 인정할 최소 시간(초) */
    var dwellSeconds: Int
        get() = prefs.getInt("dwellSeconds", 90)
        set(v) = prefs.edit().putInt("dwellSeconds", v).apply()

    /** 스캔 주기(초). 스로틀링을 끄지 않았다면 30 미만으로 내려도 소용없다. */
    var scanIntervalSeconds: Int
        get() = prefs.getInt("scanIntervalSeconds", 30)
        set(v) = prefs.edit().putInt("scanIntervalSeconds", v).apply()

    /**
     * true면 조건 충족 즉시 발송, false면 알림만 띄우고 탭했을 때 발송.
     * 오배송 문자는 되돌릴 수 없으므로 기본값은 확인 모드다.
     */
    var autoSend: Boolean
        get() = prefs.getBoolean("autoSend", false)
        set(v) = prefs.edit().putBoolean("autoSend", v).apply()

    /** 같은 회원에게 재발송을 막는 시간(시간 단위) */
    var cooldownHours: Int
        get() = prefs.getInt("cooldownHours", 8)
        set(v) = prefs.edit().putInt("cooldownHours", v).apply()

    /**
     * 테스트 모드. 켜져 있으면 문자를 절대 보내지 않고
     * 조건 충족 시 정답 라벨링 알림만 띄운다.
     */
    var testMode: Boolean
        get() = prefs.getBoolean("testMode", false)
        set(v) = prefs.edit().putBoolean("testMode", v).apply()

    var smsTemplate: String
        get() = prefs.getString("smsTemplate", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = prefs.edit().putString("smsTemplate", v).apply()

    fun renderSms(name: String): String = smsTemplate.replace("{name}", name)

    /** 다음 순번 고객에게 보내는 방문 예정 안내 */
    var advanceNoticeTemplate: String
        get() = prefs.getString("advanceNoticeTemplate", DEFAULT_ADVANCE) ?: DEFAULT_ADVANCE
        set(v) = prefs.edit().putString("advanceNoticeTemplate", v).apply()

    /** 광고 블록 사용 여부. 꺼져 있으면 어떤 문자에도 광고가 붙지 않는다. */
    var adEnabled: Boolean
        get() = prefs.getBoolean("adEnabled", false)
        set(v) = prefs.edit().putBoolean("adEnabled", v).apply()

    var adTemplate: String
        get() = prefs.getString("adTemplate", "") ?: ""
        set(v) = prefs.edit().putString("adTemplate", v).apply()

    /** 무료 수신거부 번호. 비어 있으면 광고를 붙이지 않는다. */
    var optOutNumber: String
        get() = prefs.getString("optOutNumber", "") ?: ""
        set(v) = prefs.edit().putString("optOutNumber", v).apply()

    companion object {
        const val DEFAULT_ADVANCE =
            "[HY] {name} 고객님, 곧 프레시매니저가 방문할 예정입니다. " +
                "더 필요하신 것이 있으시면 미리 전화 주세요."

        const val DEFAULT_TEMPLATE =
            "[HY] {name} 고객님, 주문하신 제품을 문앞에 배송 완료했습니다. 감사합니다."
    }
}
