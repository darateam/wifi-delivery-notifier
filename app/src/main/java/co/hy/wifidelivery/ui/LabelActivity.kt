package co.hy.wifidelivery.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.LabelStore
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.databinding.ActivityLabelBinding
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events
import co.hy.wifidelivery.model.Verdict
import co.hy.wifidelivery.wifi.ApIndex
import co.hy.wifidelivery.wifi.MatchDecision
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.RouteScope
import co.hy.wifidelivery.wifi.SignatureMatcher
import co.hy.wifidelivery.wifi.WifiScanner

/**
 * 정답 라벨링 화면.
 *
 * 테스터가 문앞에 선 채로 "지금 알고리즘이 뭐라고 하는지"를 보고
 * 맞았는지 틀렸는지를 눌러 정답을 만든다.
 *
 * 중요한 건 **틀린 경우도 정답을 함께 받는다**는 점이다.
 * "아님"만 받으면 오답 하나가 늘 뿐이지만, "실제로는 3호였다"까지 받으면
 * 3호에 대한 정답 하나가 동시에 생긴다. 라벨 한 건의 값이 두 배가 된다.
 *
 * 그리고 확정된 건만 라벨링하면 안 된다. 보류된 상황도 찍어야
 * 임계값을 내렸을 때 어떻게 되는지를 계산할 수 있다.
 * 그래서 이 화면은 알고리즘이 보류 판정을 내려도 그대로 뜬다.
 */
class LabelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLabelBinding
    private lateinit var scanner: WifiScanner
    private lateinit var store: MemberStore
    private lateinit var labels: LabelStore
    private lateinit var params: MatchParams
    private lateinit var pressure: PressureTracker

    private val handler = Handler(Looper.getMainLooper())
    private var live: Map<String, Int> = emptyMap()
    private var decision: MatchDecision? = null

    private val tick = object : Runnable {
        override fun run() {
            scanner.requestScan()
            scanner.currentResults().takeIf { it.isNotEmpty() }?.let {
                live = WifiScanner.toRssiMap(it)
                refresh()
            }
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLabelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "정답 기록"

        scanner = WifiScanner(this)
        store = MemberStore.get(this)
        labels = LabelStore.get(this)
        params = MatchParams.load(this)
        pressure = PressureTracker(this)

        binding.btnCorrect.setOnClickListener { labelCorrect() }
        binding.btnWrong.setOnClickListener { pickActualMember() }
        binding.btnNotDoor.setOnClickListener { labelNotDoor() }
        binding.btnUndo.setOnClickListener { undo() }
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

    private fun refresh() {
        val members = store.autoDetectable()
        if (members.isEmpty()) {
            binding.txtPrediction.text = "등록된 서명이 없습니다"
            return
        }
        val d = SignatureMatcher.evaluate(
            members, live, params, ApIndex.build(members), pressure.currentIndex()
        )
        decision = d

        val best = d.best
        binding.txtPrediction.text = if (best == null) {
            "후보 없음"
        } else {
            buildString {
                append(best.target.displayName)
                val m = store.get(best.target.targetId)
                if (m != null && m.address.isNotBlank()) append("  ${m.address}")
                append("\n")
                append(if (d.confident) "판정: 발송" else "판정: 보류")
                append("   점수 %.3f · 마진 %.3f".format(best.score, d.margin))
            }
        }

        binding.txtRunnerUp.text = d.runnerUp?.let {
            "2등  ${it.target.displayName}  %.3f".format(it.score)
        } ?: "2등 없음"

        binding.btnCorrect.isEnabled = best != null
        binding.btnCorrect.text = best?.let { "맞음 — ${it.target.displayName}" } ?: "맞음"

        binding.txtStatus.text =
            "관측 AP ${live.size}개 · 누적 라벨 ${labels.count()}건\n" +
                pressure.describe() + "\n" +
                RouteScope(this).describe(store.all())
    }

    private fun labelCorrect() {
        val best = decision?.best ?: return
        save(best.target.targetId, best.target.targetId, Verdict.CORRECT, "${best.target.displayName} 정답 기록")
    }

    private fun labelNotDoor() {
        save(decision?.best?.target?.targetId, null, Verdict.NOT_A_DOOR, "배송지 아님 기록")
    }

    /**
     * 실제 정답 세대를 고르게 한다.
     * 서명이 없는 회원도 목록에 넣는다 — 정답이 미수집 세대인 경우도
     * "여기서는 아무것도 발송하면 안 된다"는 정보로 쓸모가 있다.
     */
    private fun pickActualMember() {
        val members = store.all()
        if (members.isEmpty()) return
        val names = members.map { m ->
            val mark = if (m.hasSignature) "" else " (서명없음)"
            "${m.name}${if (m.address.isBlank()) "" else " · ${m.address}"}$mark"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("실제 배송지는?")
            .setItems(names) { _, which ->
                val actual = members[which]
                save(
                    decision?.best?.target?.targetId,
                    actual.id,
                    Verdict.WRONG_MEMBER,
                    "${actual.name} 정답 기록"
                )
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun save(predicted: String?, actual: String?, verdict: Verdict, message: String) {
        if (live.isEmpty()) {
            Toast.makeText(this, "스캔 결과를 기다리는 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        labels.add(predicted, actual, verdict, live, params, pressure.currentIndex())
        EventQueue.get(this).enqueue(
            Events.labelRecorded(this, predicted, actual, verdict.name, params.toJson())
        )
        Toast.makeText(this, "$message (총 ${labels.count()}건)", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun undo() {
        val removed = labels.removeLast()
        Toast.makeText(
            this,
            if (removed == null) "취소할 라벨이 없습니다" else "마지막 라벨 취소 (총 ${labels.count()}건)",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }
}
