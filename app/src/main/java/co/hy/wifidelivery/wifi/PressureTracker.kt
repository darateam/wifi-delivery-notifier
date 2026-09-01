package co.hy.wifidelivery.wifi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * 기압계 기반 층 구분.
 *
 * 아파트 오탐의 대부분은 옆집이 아니라 **위아래층**이다.
 * 같은 라인 301호와 401호는 보이는 AP 구성이 거의 같고 세기까지 비슷하다.
 * Wi-Fi만으로는 원리상 잘 안 갈리는데, 기압은 층당 약 0.33hPa씩 확실히 다르다.
 *
 * 함정이 하나 있다. **절대 기압은 못 쓴다.** 날씨로 하루에 10hPa 넘게
 * 움직이는데 이건 고도로 환산하면 80m, 아파트 25층 높이다.
 * 그래서 배송 시작 시점에 1층 로비 같은 곳에서 기준을 잡고,
 * 모든 값을 그 기준 대비 상대값으로만 다룬다.
 *
 * 기준은 몇 시간 지나면 만료시킨다. 배송 한 바퀴 도는 동안의 날씨 변화는
 * 보통 0.5hPa 미만이라 무시할 만하지만, 어제 기준을 오늘 쓰면 안 된다.
 */
class PressureTracker(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 최근 측정값의 이동평균 — 기압 센서는 노이즈가 있어 그대로 쓰면 튄다 */
    private var smoothed: Double? = null

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val v = event?.values?.firstOrNull()?.toDouble() ?: return
        smoothed = smoothed?.let { it * 0.8 + v * 0.2 } ?: v
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    val currentHpa: Double? get() = smoothed

    /** 현재 지점을 기준(보통 1층 로비)으로 잡는다 */
    fun setBaseline(): Boolean {
        val v = smoothed ?: return false
        prefs.edit()
            .putFloat(KEY_BASELINE, v.toFloat())
            .putLong(KEY_BASELINE_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun clearBaseline() {
        prefs.edit().remove(KEY_BASELINE).remove(KEY_BASELINE_AT).apply()
    }

    private fun baselineHpa(): Double? {
        if (!prefs.contains(KEY_BASELINE)) return null
        val at = prefs.getLong(KEY_BASELINE_AT, 0L)
        if (System.currentTimeMillis() - at > BASELINE_TTL_MS) return null
        return prefs.getFloat(KEY_BASELINE, 0f).toDouble()
    }

    val baselineAgeMinutes: Long?
        get() {
            val at = prefs.getLong(KEY_BASELINE_AT, 0L)
            if (at == 0L) return null
            return (System.currentTimeMillis() - at) / 60000
        }

    /**
     * 현재 고도 지표 = 기준 기압 - 현재 기압.
     * 값이 클수록 높은 층. 기준이 없거나 만료됐으면 null.
     */
    fun currentIndex(): Double? {
        val base = baselineHpa() ?: return null
        val now = smoothed ?: return null
        return base - now
    }

    fun describe(): String = when {
        !isAvailable -> "기압계 없음"
        baselineHpa() == null -> "기준 미설정"
        else -> {
            val idx = currentIndex()
            val floors = idx?.let { it / HPA_PER_FLOOR }
            "기준 대비 %.2f hPa (약 %.1f개층) · %d분 전 설정".format(
                idx ?: 0.0, floors ?: 0.0, baselineAgeMinutes ?: 0
            )
        }
    }

    companion object {
        private const val PREFS = "pressure"
        private const val KEY_BASELINE = "baseline_hpa"
        private const val KEY_BASELINE_AT = "baseline_at"

        /** 기준 유효 시간 — 이보다 오래되면 날씨 변화를 못 믿는다 */
        private const val BASELINE_TTL_MS = 6L * 3600 * 1000

        /** 층고 약 2.8m 기준 환산치 */
        const val HPA_PER_FLOOR = 0.33

        /**
         * 두 지점의 층 차이가 허용 범위를 넘는지.
         * 기준이 없거나 한쪽 값이 없으면 판정하지 않는다(false).
         */
        fun floorMismatch(stored: Double?, current: Double?, toleranceHpa: Double): Boolean {
            if (stored == null || current == null) return false
            return abs(stored - current) > toleranceHpa
        }
    }
}
