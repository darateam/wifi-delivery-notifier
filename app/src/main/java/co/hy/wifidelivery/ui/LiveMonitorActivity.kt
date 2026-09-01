package co.hy.wifidelivery.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import co.hy.wifidelivery.data.LabelStore
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.model.SignatureTarget
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.databinding.ActivityLiveMonitorBinding
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.wifi.ApIndex
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.RouteScope
import co.hy.wifidelivery.wifi.SignatureMatcher
import co.hy.wifidelivery.wifi.WifiScanner

/**
 * 라이브 매칭 랭킹.
 *
 * 전체 회원 서명에 대해 지금 점수가 몇 점씩 나오는지 순위로 보여준다.
 * 인접 세대 오탐을 잡으려면 1등 점수가 아니라 **1등과 2등의 간격**을 봐야 한다.
 * 같은 라인 세대를 등록해 두고 이 화면을 켠 채 복도를 걸어보면
 * 어디서 순위가 뒤집히고 어디서 붙는지가 그대로 보인다.
 */
class LiveMonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveMonitorBinding
    private lateinit var scanner: WifiScanner
    private lateinit var store: MemberStore
    private lateinit var pressure: PressureTracker
    private lateinit var route: RouteScope
    private var matchParams = MatchParams()
    private val handler = Handler(Looper.getMainLooper())

    private var scanCount = 0

    private val tick = object : Runnable {
        override fun run() {
            scanner.requestScan()
            val results = scanner.currentResults()
            if (results.isNotEmpty()) {
                scanCount++
                render(WifiScanner.toRssiMap(results))
            }
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scanner = WifiScanner(this)
        store = MemberStore.get(this)
        matchParams = MatchParams.load(this)
        pressure = PressureTracker(this)
        route = RouteScope(this)
        title = "라이브 매칭"
        binding.btnLabel.setOnClickListener {
            startActivity(Intent(this, LabelActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        matchParams = MatchParams.load(this)
        pressure.start()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        pressure.stop()
        super.onPause()
    }

    private fun render(live: Map<String, Int>) {
        // 자동 확정 대상(아파트·주택) + 사무실 구역
        val homes = store.autoDetectable()
        val zoneList = ZoneStore.get(this).withSignature()
        val all: List<SignatureTarget> = homes + zoneList
        val members: List<SignatureTarget> = route.scope(homes) + zoneList
        if (members.isEmpty()) {
            binding.txtVerdict.text = "등록된 서명이 없습니다"
            return
        }

        val index = ApIndex.build(all)
        val pi = pressure.currentIndex()
        val ranked = SignatureMatcher.rank(members, live, matchParams, index, pi)
        val decision = SignatureMatcher.decide(ranked, matchParams)

        binding.txtVerdict.text = buildString {
            append(
                when {
                    decision.confident -> "✔ 확정"
                    decision.ambiguous -> "? 구분 필요"
                    else -> "✖ 보류"
                }
            )
            append("  —  ${decision.reason}\n")
            val margin = (decision.best?.score ?: 0.0) - (decision.runnerUp?.score ?: 0.0)
            append("마진 %.3f (기준 %.2f) · 관측 AP %d개 · %d회 스캔".format(
                margin, matchParams.marginThreshold, live.size, scanCount
            ))
        }

        binding.txtHeader.text = "%-10s %6s %5s %5s %s".format("회원", "점수", "커버", "RSSI", "앵커")
        binding.txtRanking.text = ranked.take(12).joinToString("\n") { s ->
            val mark = when {
                s.score >= matchParams.scoreThreshold -> "▶"
                else -> " "
            }
            "%s%-10s %6.3f %5.2f %5.2f  %s".format(
                mark,
                s.target.displayName.take(10),
                s.score,
                s.coverage,
                s.rssiScore,
                (if (s.anchorHit) "O" else "X") + (if (s.floorMismatch) " ↕층" else "")
            )
        }

        binding.txtQueue.text =
            "%s\n%s\n전송 대기 %d건 · 누적 라벨 %d건".format(
                pressure.describe(),
                route.describe(store.all()),
                EventQueue.get(this).pendingCount(),
                LabelStore.get(this).count()
            )
    }
}
