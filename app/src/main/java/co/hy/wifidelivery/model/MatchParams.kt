package co.hy.wifidelivery.model

import android.content.Context
import org.json.JSONObject

/**
 * 매칭 파라미터.
 *
 * 원래 SignatureMatcher 안에 상수로 박아뒀던 값들을 전부 밖으로 뺐다.
 * 라벨 기반 재탐색이 이 값들을 바꿔가며 최적점을 찾기 때문에
 * 코드가 아니라 데이터여야 한다.
 */
data class MatchParams(
    /** 확정에 필요한 최소 점수 */
    val scoreThreshold: Double = 0.62,
    /** 1등과 2등의 최소 점수 차 */
    val marginThreshold: Double = 0.10,
    /** 서명 AP 중 최소 이 비율은 보여야 후보로 인정 */
    val minCoverage: Double = 0.55,
    /** 이 dB만큼 어긋나면 RSSI 점수 0점 */
    val rssiTolerance: Double = 18.0,
    /** 최종 점수에서 커버리지가 차지하는 비중 (나머지는 RSSI 점수) */
    val coverageWeight: Double = 0.35,
    /** 앵커 미충족 시 곱하는 감점 계수 */
    val anchorPenalty: Double = 0.5,
    /**
     * AP 희소성(IDF) 반영 정도. 0이면 끔.
     * 그 집에서만 보이는 AP를 더 무겁게 친다 — 인접 세대 구분의 핵심.
     */
    val idfWeight: Double = 0.6,
    /**
     * AP별 표준편차로 편차를 정규화하는 정도. 0이면 끔.
     * 원래 흔들리는 AP의 5dB와 안정적인 AP의 5dB를 다르게 취급한다.
     */
    val stdWeight: Double = 0.5,
    /** 층 판정 허용 오차(hPa). 0.33이 약 한 개 층. */
    val floorToleranceHpa: Double = 0.25,
    /** 층이 어긋날 때 곱하는 감점 계수. 1.0이면 층 판정을 쓰지 않음. */
    val floorPenalty: Double = 0.35
) {
    val rssiWeight: Double get() = 1.0 - coverageWeight

    fun toJson(): JSONObject = JSONObject()
        .put("scoreThreshold", scoreThreshold)
        .put("marginThreshold", marginThreshold)
        .put("minCoverage", minCoverage)
        .put("rssiTolerance", rssiTolerance)
        .put("coverageWeight", coverageWeight)
        .put("anchorPenalty", anchorPenalty)
        .put("idfWeight", idfWeight)
        .put("stdWeight", stdWeight)
        .put("floorToleranceHpa", floorToleranceHpa)
        .put("floorPenalty", floorPenalty)

    fun describe(): String = buildString {
        append("점수 임계 %.2f\n".format(scoreThreshold))
        append("마진 임계 %.2f\n".format(marginThreshold))
        append("최소 커버리지 %.2f\n".format(minCoverage))
        append("RSSI 허용 %.0f dB\n".format(rssiTolerance))
        append("커버리지 비중 %.2f\n".format(coverageWeight))
        append("AP 희소성 반영 %.2f\n".format(idfWeight))
        append("편차 정규화 %.2f\n".format(stdWeight))
        append("층 허용 %.2f hPa · 감점 %.2f".format(floorToleranceHpa, floorPenalty))
    }

    companion object {
        private const val PREFS = "match_params"

        fun fromJson(o: JSONObject): MatchParams = MatchParams(
            scoreThreshold = o.optDouble("scoreThreshold", 0.62),
            marginThreshold = o.optDouble("marginThreshold", 0.10),
            minCoverage = o.optDouble("minCoverage", 0.55),
            rssiTolerance = o.optDouble("rssiTolerance", 18.0),
            coverageWeight = o.optDouble("coverageWeight", 0.35),
            anchorPenalty = o.optDouble("anchorPenalty", 0.5),
            idfWeight = o.optDouble("idfWeight", 0.6),
            stdWeight = o.optDouble("stdWeight", 0.5),
            floorToleranceHpa = o.optDouble("floorToleranceHpa", 0.25),
            floorPenalty = o.optDouble("floorPenalty", 0.35)
        )

        fun load(context: Context): MatchParams {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = p.getString("current", null) ?: return MatchParams()
            return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(MatchParams())
        }

        /** 적용 전 값을 항상 남긴다. 튜닝 결과가 나빠지면 되돌릴 수 있어야 한다. */
        fun save(context: Context, params: MatchParams) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getString("current", null)
            p.edit()
                .putString("previous", prev)
                .putString("current", params.toJson().toString())
                .apply()
        }

        fun rollback(context: Context): MatchParams? {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getString("previous", null) ?: return null
            p.edit().putString("current", prev).remove("previous").apply()
            return runCatching { fromJson(JSONObject(prev)) }.getOrNull()
        }

        fun hasRollback(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .contains("previous")
    }
}

enum class Verdict {
    /** 알고리즘 1등이 실제 배송지와 일치 */
    CORRECT,

    /** 실제로는 다른 세대였음 — actualMemberId에 정답이 들어간다 */
    WRONG_MEMBER,

    /** 배송지 문앞이 아니었음 (복도, 엘리베이터, 이동 중) */
    NOT_A_DOOR
}

/**
 * 정답 라벨 1건.
 *
 * 점수만 저장하면 임계값밖에 못 고친다. 원본 스캔(liveRssi)을 같이 남겨야
 * 나중에 RSSI 허용폭이나 가중치 배분까지 바꿔가며 재계산할 수 있다.
 * 라벨 하나가 현장 왕복 한 번의 값이라 최대한 많이 남기는 쪽이 맞다.
 */
data class LabeledSample(
    val id: String,
    val timestamp: Long,
    /** 라벨 시점 알고리즘의 1등. 보류였으면 null */
    val predictedMemberId: String?,
    /** 실제 정답. NOT_A_DOOR이면 null */
    val actualMemberId: String?,
    val verdict: Verdict,
    /** 라벨 시점 원본 스캔 — 재계산용 */
    val liveRssi: Map<String, Int>,
    /** 라벨 시점 파라미터 (기록용) */
    val paramsAtLabel: MatchParams,
    /** 라벨 시점 기압 지표 — 재계산 시 층 판정을 재현하려면 필요하다 */
    val pressureIndex: Double? = null
)
