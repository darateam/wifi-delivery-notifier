package co.hy.wifidelivery.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import co.hy.wifidelivery.model.ApStat

/**
 * WifiManager 래퍼.
 *
 * 안드로이드 9 이상은 포그라운드 앱 기준 2분에 4회로 스캔을 제한한다.
 * 제한에 걸려도 예외가 나지 않고 직전 스캔 결과가 그대로 반환되기 때문에,
 * 그냥 getScanResults()만 폴링하면 같은 값이 계속 새 데이터인 척 들어온다.
 * ScanResult.timestamp(기기 부팅 이후 마이크로초)를 기준으로 신선도를 직접 판정한다.
 *
 * 수집 단말은 개발자 옵션 > 네트워킹 > Wi-Fi 스캔 제한을 꺼야 실사용 가능하다.
 */
class WifiScanner(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lastSeenTimestamp = 0L
    private var receiver: BroadcastReceiver? = null

    val isWifiEnabled: Boolean get() = wifiManager.isWifiEnabled

    /**
     * 스캔을 요청한다. 스로틀링에 걸리면 false를 돌려주지만,
     * 그 경우에도 OS 자체 주기 스캔 결과는 계속 갱신되므로 폴링은 계속하면 된다.
     */
    @SuppressLint("MissingPermission")
    fun requestScan(): Boolean = runCatching { wifiManager.startScan() }.getOrDefault(false)

    /** 캐시된 최신 스캔 결과. 권한이 없으면 빈 목록. */
    @SuppressLint("MissingPermission")
    fun currentResults(): List<ScanResult> =
        runCatching { wifiManager.scanResults }.getOrDefault(emptyList())

    /**
     * 직전에 반환한 것보다 새로운 결과가 있을 때만 돌려준다.
     * 없으면 null — 호출부에서 "이번 주기는 새 데이터 없음"으로 처리한다.
     */
    fun freshResults(): List<ScanResult>? {
        val results = currentResults()
        if (results.isEmpty()) return null
        val newest = results.maxOf { it.timestamp }
        if (newest <= lastSeenTimestamp) return null
        lastSeenTimestamp = newest
        return results
    }

    fun registerScanReceiver(onResults: () -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = onResults()
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(r, filter)
        }
        receiver = r
    }

    fun unregisterScanReceiver() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        /** ScanResult 목록을 BSSID -> RSSI 맵으로 정규화 */
        fun toRssiMap(results: List<ScanResult>): Map<String, Int> =
            results.associate { it.BSSID.lowercase() to it.level }
    }
}

/**
 * 여러 라운드의 스캔을 누적해 서명 하나를 만든다.
 *
 * 한 번의 스캔은 노이즈가 크다. 사람 몸이 신호를 막고, AP 송신 타이밍도 흔들린다.
 * 지점당 여러 라운드를 돌려 평균과 관측 빈도를 함께 남겨야 쓸 만한 서명이 된다.
 */
class SignatureBuilder {

    private val samples = LinkedHashMap<String, MutableList<Int>>()
    private val ssids = HashMap<String, String>()
    private val band5 = HashSet<String>()

    var rounds: Int = 0
        private set

    val apCount: Int get() = samples.size

    fun addRound(results: List<ScanResult>) {
        if (results.isEmpty()) return
        rounds++
        results.forEach { r ->
            val bssid = r.BSSID?.lowercase() ?: return@forEach
            samples.getOrPut(bssid) { mutableListOf() }.add(r.level)
            if (!r.SSID.isNullOrBlank()) ssids[bssid] = r.SSID
            // 5GHz는 감쇠가 빨라 2.4GHz보다 공간 해상도가 좋다.
            if (r.frequency > 4000) band5.add(bssid)
        }
    }

    fun build(): List<ApStat> {
        if (rounds == 0) return emptyList()
        return samples.map { (bssid, levels) ->
            val mean = levels.average()
            // 표본이 2개 미만이면 표준편차를 못 구한다. 기본값으로 둔다.
            val std = if (levels.size < 2) ApStat.DEFAULT_STD else {
                val variance = levels.sumOf { (it - mean) * (it - mean) } / (levels.size - 1)
                kotlin.math.sqrt(variance)
            }
            ApStat(
                bssid = bssid,
                ssid = ssids[bssid] ?: "",
                meanRssi = mean,
                hitRatio = levels.size.toDouble() / rounds,
                stdRssi = std,
                is5Ghz = bssid in band5
            )
        }.sortedByDescending { it.meanRssi }
    }

    fun reset() {
        samples.clear()
        ssids.clear()
        band5.clear()
        rounds = 0
    }
}
