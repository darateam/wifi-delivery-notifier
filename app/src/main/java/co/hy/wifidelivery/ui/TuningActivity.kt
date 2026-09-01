package co.hy.wifidelivery.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.hy.wifidelivery.data.LabelStore
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.databinding.ActivityTuningBinding
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.Verdict
import co.hy.wifidelivery.wifi.ParamTuner
import kotlinx.coroutines.launch

/**
 * 알고리즘 업데이트 화면.
 *
 * 라벨을 정답으로 놓고 파라미터를 다시 찾는다. 결과를 곧바로 적용하지 않고
 * 현재값과 나란히 보여준 뒤 사용자가 명시적으로 적용하게 한다.
 * 되돌리기도 항상 열어둔다 — 튜닝이 늘 개선인 건 아니다.
 */
class TuningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTuningBinding
    private lateinit var labels: LabelStore
    private lateinit var store: MemberStore

    private var candidate: MatchParams? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTuningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "알고리즘 업데이트"

        labels = LabelStore.get(this)
        store = MemberStore.get(this)

        binding.btnTune.setOnClickListener { runTuning() }
        binding.btnApply.setOnClickListener { apply() }
        binding.btnRollback.setOnClickListener { rollback() }
        binding.btnExport.setOnClickListener { exportLabels() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val n = labels.count()
        val correct = labels.countBy(Verdict.CORRECT)
        val wrong = labels.countBy(Verdict.WRONG_MEMBER)
        val notDoor = labels.countBy(Verdict.NOT_A_DOOR)

        binding.txtLabelStatus.text = buildString {
            append("라벨 %d건  (맞음 %d · 다른집 %d · 배송지아님 %d)\n\n".format(n, correct, wrong, notDoor))
            when {
                n < ParamTuner.MIN_LABELS ->
                    append("최소 ${ParamTuner.MIN_LABELS}건은 있어야 합니다. ${ParamTuner.MIN_LABELS - n}건 더 필요.")
                n < ParamTuner.RECOMMENDED_LABELS ->
                    append("돌릴 수는 있지만 ${ParamTuner.RECOMMENDED_LABELS}건 넘어야 결과가 안정됩니다.")
                else ->
                    append("충분합니다.")
            }
            if (wrong + notDoor == 0 && n > 0) {
                append("\n\n오답 라벨이 하나도 없습니다. 맞은 것만 모으면 " +
                    "임계값을 낮추는 쪽으로만 답이 나옵니다. 옆집·복도에서도 찍어주세요.")
            }
        }

        binding.txtCurrent.text = "현재 파라미터\n" + MatchParams.load(this).describe()
        binding.btnTune.isEnabled = labels.count() >= ParamTuner.MIN_LABELS
        binding.btnApply.isEnabled = candidate != null
        binding.btnRollback.isEnabled = MatchParams.hasRollback(this)
    }

    private fun runTuning() {
        val samples = labels.all()
        val members = store.withSignature()
        if (members.isEmpty()) {
            Toast.makeText(this, "서명이 등록된 회원이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTune.isEnabled = false
        binding.txtResult.text = "계산 중..."

        lifecycleScope.launch {
            val result = ParamTuner.tune(
                samples = samples,
                members = members,
                current = MatchParams.load(this@TuningActivity),
                onProgress = { done, total ->
                    runOnUiThread { binding.txtResult.text = "계산 중... $done / $total" }
                }
            )

            candidate = result.params
            binding.txtResult.text = buildString {
                append("교차검증 성능 (처음 보는 데이터 기준)\n")
                append("─────────────────────\n")
                append("현재\n${result.baselineCv.describe()}\n\n")
                append("튜닝 후\n${result.crossValidated.describe()}\n\n")

                val d = result.baselineCv.cost() - result.crossValidated.cost()
                append(
                    if (result.improved) "→ 비용 %.1f 개선".format(d)
                    else "→ 개선 없음 (%.1f). 적용하지 않는 게 낫습니다.".format(d)
                )

                append("\n\n제안 파라미터\n")
                append(result.params.describe())

                append("\n\n─────────────────────\n")
                append("전체 라벨에 맞춘 성능(참고)\n")
                append(result.fitted.describe())
                append("\n\n이 값은 답을 보고 맞춘 것이라 항상 좋게 나옵니다. ")
                append("판단은 위쪽 교차검증 수치로 하세요.")

                append("\n\n라벨 ${result.sampleCount}건 기준. ")
                append("한 동에서만 모은 라벨이면 다른 동에서는 다르게 나올 수 있습니다.")
            }
            renderStatus()
        }
    }

    private fun apply() {
        val p = candidate ?: return
        AlertDialog.Builder(this)
            .setTitle("파라미터 적용")
            .setMessage("${p.describe()}\n\n되돌리기는 언제든 가능합니다.")
            .setPositiveButton("적용") { _, _ ->
                MatchParams.save(this, p)
                candidate = null
                Toast.makeText(this, "적용됨", Toast.LENGTH_SHORT).show()
                renderStatus()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun rollback() {
        val restored = MatchParams.rollback(this)
        Toast.makeText(
            this,
            if (restored == null) "되돌릴 값이 없습니다" else "이전 값으로 되돌림",
            Toast.LENGTH_SHORT
        ).show()
        renderStatus()
    }

    /** 라벨은 현장 왕복의 결과물이라 단말에만 두면 위험하다. */
    private fun exportLabels() {
        val json = labels.exportJson()
        val file = java.io.File(getExternalFilesDir(null), "labels_export.json")
        runCatching { file.writeText(json) }
            .onSuccess {
                Toast.makeText(this, "저장: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
            .onFailure {
                Toast.makeText(this, "내보내기 실패", Toast.LENGTH_SHORT).show()
            }
    }
}
