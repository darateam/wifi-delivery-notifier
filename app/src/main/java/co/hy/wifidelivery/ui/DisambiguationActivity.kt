package co.hy.wifidelivery.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.DeliveryCompleter
import co.hy.wifidelivery.data.MemberStore

/**
 * 구분 불가 상황에서 사람에게 묻는 화면.
 *
 * 아무리 다듬어도 진짜로 분리 안 되는 세대 쌍은 남는다.
 * 그때 조용히 보류하면 배송 완료 문자가 그냥 누락되고,
 * 억지로 확정하면 오배송 문자가 나간다. 둘 다 나쁘다.
 *
 * 한 번 탭으로 끝나는 질문이 두 실패보다 싸다.
 */
class DisambiguationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "어느 집인가요?"

        val ids = intent.getStringArrayExtra(EXTRA_MEMBER_IDS)?.toList() ?: emptyList()
        val store = MemberStore.get(this)
        val members = ids.mapNotNull { store.get(it) }

        if (members.isEmpty()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "신호로는 구분이 안 됩니다.\n실제 배송지를 눌러주세요."
            textSize = 16f
            setPadding(0, 0, 0, 32)
        })

        members.forEach { m ->
            root.addView(Button(this).apply {
                text = buildString {
                    append(m.name)
                    if (m.address.isNotBlank()) append("  ${m.address}")
                    if (!m.notifyEnabled) append("  (문자 미수신)")
                }
                textSize = 17f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 170
                ).apply { bottomMargin = 20 }
                setOnClickListener {
                    val result = DeliveryCompleter.complete(
                        this@DisambiguationActivity, m, "disambiguated"
                    )
                    Toast.makeText(
                        this@DisambiguationActivity,
                        DeliveryCompleter.message(m, result),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            })
        }

        root.addView(Button(this).apply {
            text = "해당 없음 — 보내지 않음"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    companion object {
        const val EXTRA_MEMBER_IDS = "member_ids"
    }
}
