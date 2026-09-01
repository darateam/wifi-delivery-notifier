package co.hy.wifidelivery.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.databinding.ActivityCollectBinding
import co.hy.wifidelivery.BuildConfig
import co.hy.wifidelivery.model.ApStat
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.SignatureBuilder
import co.hy.wifidelivery.wifi.WifiScanner

/**
 * 수집 모드.
 *
 * 문앞에 선 상태로 여러 라운드를 돌려 서명 하나를 만든다.
 * 스로틀링이 켜져 있으면 라운드 사이가 30초까지 벌어져 12라운드에 6분이 걸린다.
 * 개발자 옵션에서 스캔 제한을 꺼야 현장에서 쓸 수 있는 속도가 나온다.
 */
class CollectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectBinding
    private lateinit var scanner: WifiScanner
    private lateinit var store: MemberStore
    private val builder = SignatureBuilder()
    private lateinit var pressure: PressureTracker
    private val handler = Handler(Looper.getMainLooper())

    private var memberId: String = ""
    private var zoneId: String = ""
    private val isZone: Boolean get() = zoneId.isNotEmpty()
    private var collecting = false
    private var built: List<ApStat> = emptyList()

    private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!collecting) return
            scanner.requestScan()
            scanner.freshResults()?.let { builder.addRound(it) }
            render()
            when {
                builder.rounds >= targetRounds() -> finishCollecting()
                System.currentTimeMillis() - startedAt > (if (isZone) MAX_COLLECT_MS * 3 else MAX_COLLECT_MS) -> {
                    // 스로틀링이 켜져 있으면 새 결과가 30초에 한 번씩만 들어온다.
                    // 무한정 돌리지 말고 모인 만큼으로 마감하고 원인을 알려준다.
                    Toast.makeText(
                        this@CollectActivity,
                        "스캔이 느립니다. 개발자 옵션 > 네트워킹 > Wi-Fi 스캔 제한을 꺼주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    finishCollecting()
                }
                else -> handler.postDelayed(this, ROUND_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = WifiScanner(this)
        pressure = PressureTracker(this)
        store = MemberStore.get(this)
        memberId = intent.getStringExtra(EXTRA_MEMBER_ID).orEmpty()
        zoneId = intent.getStringExtra(EXTRA_ZONE_ID).orEmpty()

        if (isZone) {
            val zone = ZoneStore.get(this).get(zoneId)
            if (zone == null) { finish(); return }
            binding.txtMember.text = "[구역] ${zone.name}"
            binding.txtHint.text =
                "구역 안 서너 곳을 천천히 돌면서 수집하세요.\n" +
                    "한 자리에만 서 있으면 그 지점 서명이 되지 버립니다.\n" +
                    "책상 단위 구분은 되지 않습니다 — 구역까지만 잡습니다."
        } else {
            val member = store.get(memberId)
            if (member == null) {
                Toast.makeText(this, "회원을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            binding.txtMember.text = "${member.name} (${member.address})"
        }

        binding.btnCollect.setOnClickListener {
            if (collecting) finishCollecting() else startCollecting()
        }
        binding.btnSave.setOnClickListener { save() }
        render()
    }

    override fun onResume() {
        super.onResume()
        pressure.start()
    }

    override fun onPause() {
        pressure.stop()
        super.onPause()
    }

    override fun onDestroy() {
        collecting = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startCollecting() {
        if (!scanner.isWifiEnabled) {
            Toast.makeText(this, "Wi-Fi를 켜주세요", Toast.LENGTH_SHORT).show()
            return
        }
        builder.reset()
        built = emptyList()
        collecting = true
        startedAt = System.currentTimeMillis()
        binding.btnCollect.text = "수집 중지"
        binding.btnSave.isEnabled = false
        handler.post(tick)
    }

    private fun finishCollecting() {
        collecting = false
        handler.removeCallbacksAndMessages(null)
        binding.btnCollect.text = "다시 수집"
        built = builder.build()
        binding.btnSave.isEnabled = built.size >= 3
        if (built.size < 3) {
            Toast.makeText(this, "관측된 AP가 너무 적습니다. 다시 시도하세요.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun render() {
        binding.txtProgress.text = "${builder.rounds} / ${targetRounds()} 라운드"
        binding.txtApCount.text = "관측 AP ${builder.apCount}개 · ${pressure.describe()}"
        val preview = (if (built.isNotEmpty()) built else builder.build())
            .sortedByDescending { it.meanRssi }
            .take(12)
            .joinToString("\n") {
                "%-18s %6.1f dBm  %3.0f%%".format(
                    it.ssid.take(18).ifBlank { it.bssid.takeLast(8) },
                    it.meanRssi,
                    it.hitRatio * 100
                )
            }
        binding.txtPreview.text = preview
    }

    private fun save() {
        if (built.size < 3) return
        if (isZone) {
            ZoneStore.get(this).mergeSignature(zoneId, built, builder.rounds, pressure.currentIndex())
        } else {
            store.mergeSignature(memberId, built, builder.rounds, pressure.currentIndex())
        }
        EventQueue.get(this).enqueue(
            Events.signatureCollected(
                this, BuildConfig.BSSID_SALT,
                if (isZone) "zone:$zoneId" else memberId,
                builder.rounds, built
            )
        )
        Toast.makeText(this, "서명 저장 완료", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 구역은 면이라 지점을 여러 곳 돌아야 해서 라운드를 더 잡는다 */
    private fun targetRounds(): Int = if (isZone) ZONE_ROUNDS else TARGET_ROUNDS

    companion object {
        const val EXTRA_MEMBER_ID = "member_id"
        const val EXTRA_ZONE_ID = "zone_id"
        private const val ZONE_ROUNDS = 30
        private const val TARGET_ROUNDS = 12
        private const val ROUND_INTERVAL_MS = 3000L
        private const val MAX_COLLECT_MS = 120_000L
    }
}
