package co.hy.wifidelivery.data

import android.content.Context
import co.hy.wifidelivery.model.LabeledSample
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 정답 라벨 저장소.
 *
 * 라벨 하나가 현장 왕복 한 번의 값이다. 앱을 지우면 날아가는 게 아까우니
 * 내보내기를 붙여뒀고, 텔레메트리로도 함께 올라간다.
 */
class LabelStore private constructor(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val cache = mutableListOf<LabeledSample>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<LabeledSample> = cache.toList()

    @Synchronized
    fun count(): Int = cache.size

    @Synchronized
    fun countBy(verdict: Verdict): Int = cache.count { it.verdict == verdict }

    @Synchronized
    fun add(
        predictedMemberId: String?,
        actualMemberId: String?,
        verdict: Verdict,
        liveRssi: Map<String, Int>,
        params: MatchParams,
        pressureIndex: Double? = null
    ): LabeledSample {
        val sample = LabeledSample(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            predictedMemberId = predictedMemberId,
            actualMemberId = actualMemberId,
            verdict = verdict,
            liveRssi = liveRssi,
            paramsAtLabel = params,
            pressureIndex = pressureIndex
        )
        cache.add(sample)
        persist()
        return sample
    }

    @Synchronized
    fun removeLast(): LabeledSample? {
        val removed = cache.removeLastOrNull()
        if (removed != null) persist()
        return removed
    }

    @Synchronized
    fun clear() {
        cache.clear()
        runCatching { file.delete() }
    }

    @Synchronized
    fun exportJson(): String = buildJson().toString(2)

    private fun buildJson(): JSONArray {
        val arr = JSONArray()
        cache.forEach { s ->
            val rssi = JSONObject()
            s.liveRssi.forEach { (b, r) -> rssi.put(b, r) }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("timestamp", s.timestamp)
                    .put("predictedMemberId", s.predictedMemberId ?: JSONObject.NULL)
                    .put("actualMemberId", s.actualMemberId ?: JSONObject.NULL)
                    .put("verdict", s.verdict.name)
                    .put("liveRssi", rssi)
                    .put("params", s.paramsAtLabel.toJson())
                    .put("pressureIndex", s.pressureIndex ?: JSONObject.NULL)
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
                val rssiObj = o.optJSONObject("liveRssi") ?: JSONObject()
                val rssi = HashMap<String, Int>()
                rssiObj.keys().forEach { k -> rssi[k] = rssiObj.getInt(k) }
                cache.add(
                    LabeledSample(
                        id = o.getString("id"),
                        timestamp = o.getLong("timestamp"),
                        predictedMemberId = o.optString("predictedMemberId").takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        actualMemberId = o.optString("actualMemberId").takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        verdict = runCatching { Verdict.valueOf(o.getString("verdict")) }
                            .getOrDefault(Verdict.NOT_A_DOOR),
                        liveRssi = rssi,
                        paramsAtLabel = o.optJSONObject("params")
                            ?.let { MatchParams.fromJson(it) } ?: MatchParams(),
                        pressureIndex = if (o.isNull("pressureIndex")) null
                            else o.optDouble("pressureIndex")
                    )
                )
            }
        }
    }

    companion object {
        private const val FILE_NAME = "labels.json"

        @Volatile
        private var instance: LabelStore? = null

        fun get(context: Context): LabelStore =
            instance ?: synchronized(this) {
                instance ?: LabelStore(context.applicationContext).also { instance = it }
            }
    }
}
