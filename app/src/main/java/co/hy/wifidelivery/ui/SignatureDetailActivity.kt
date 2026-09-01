package co.hy.wifidelivery.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.databinding.ActivitySignatureDetailBinding
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.Member
import co.hy.wifidelivery.wifi.ApIndex
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.SignatureMatcher
import co.hy.wifidelivery.wifi.WifiScanner
import kotlin.math.abs

/**
 * 저장된 서명을 실시간 스캔과 나란히 놓고 보는 화면.
 *
 * 임계값 튜닝은 결국 현장에서 점수가 몇 점 나오는지를 눈으로 봐야 잡힌다.
 * 대상 세대 문앞에서 몇 점, 옆집 앞에서 몇 점이 나오는지 직접 찍어보고
 * SCORE_THRESHOLD와 MARGIN_THRESHOLD를 정하는 게 순서다.
 */
class SignatureDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignatureDetailBinding
    private lateinit var scanner: WifiScanner
    private lateinit var member: Member

    private val handler = Handler(Looper.getMainLooper())
    private var live: Map<String, Int> = emptyMap()
    private lateinit var params: MatchParams
    private lateinit var pressure: PressureTracker

    private val tick = object : Runnable {
        override fun run() {
            scanner.requestScan()
            scanner.currentResults().takeIf { it.isNotEmpty() }?.let {
                live = WifiScanner.toRssiMap(it)
            }
            render()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignatureDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = WifiScanner(this)
        params = MatchParams.load(this)
        pressure = PressureTracker(this)
        val id = intent.getStringExtra(EXTRA_MEMBER_ID).orEmpty()
        val found = MemberStore.get(this).get(id)
        if (found == null) {
            finish()
            return
        }
        member = found
        title = "${member.name} 서명"
    }

    override fun onResume() {
        super.onResume()
        pressure.start()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        pressure.stop()
        super.onPause()
    }

    private fun render() {
        val stable = member.stableAps()
        val index = ApIndex.build(MemberStore.get(this).withSignature())
        val result = SignatureMatcher.score(member, live, params, index, pressure.currentIndex())

        binding.txtSummary.text = buildString {
            append("수집 %d라운드 · AP %d개 (안정 %d개)\n".format(
                member.scanRounds, member.signature.size, stable.size
            ))
            append("갱신 ${MainActivity.formatTime(member.updatedAt)}\n\n")
            append("현재 점수  %.3f   %s\n".format(
                result.score,
                if (result.score >= params.scoreThreshold) "임계 통과" else "임계 미달"
            ))
            append("커버리지   %.2f  (%d/%d)\n".format(
                result.coverage, result.commonAps, stable.size
            ))
            append("RSSI 점수  %.2f\n".format(result.rssiScore))
            append("앵커       ${if (result.anchorHit) "충족" else "미충족 (감점)"}\n")
            append("층 판정    ${if (result.floorMismatch) "불일치 (감점)" else "일치 또는 판정불가"}\n")
            append(pressure.describe())
        }

        // 서명 AP를 현재 관측치와 대조. Δ가 크게 벌어진 AP가 오탐의 원인이다.
        val rows = member.signature
            .sortedByDescending { it.meanRssi }
            .joinToString("\n") { ap ->
                val now = live[ap.bssid]
                val label = ap.ssid.ifBlank { ap.bssid.takeLast(8) }.take(16)
                val stableMark = if (ap.hitRatio >= Member.STABLE_HIT_RATIO) " " else "·"
                if (now == null) {
                    "%s%-16s %6.1f    --   미관측 %3.0f%%".format(
                        stableMark, label, ap.meanRssi, ap.hitRatio * 100
                    )
                } else {
                    val d = now - ap.meanRssi
                    "%s%-16s %6.1f %5d  Δ%+5.1f %3.0f%%".format(
                        stableMark, label, ap.meanRssi, now, d, ap.hitRatio * 100
                    )
                }
            }

        binding.txtHeader.text = " %-16s %6s %5s  %6s %4s".format("AP", "저장", "현재", "Δ", "빈도")
        binding.txtRows.text = rows

        val extra = live.keys.count { it !in member.signature.map { s -> s.bssid }.toSet() }
        binding.txtFooter.text =
            "· = 불안정 AP(빈도 50% 미만, 매칭 제외)\n서명에 없는데 지금 보이는 AP ${extra}개"
    }

    companion object {
        const val EXTRA_MEMBER_ID = "member_id"
    }
}
