package co.hy.wifidelivery.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.model.DeliveryType

/**
 * 구역 관리.
 *
 * 구역을 어떻게 나누느냐가 정확도를 거의 결정한다.
 * 도면상 벽을 기준으로 나눠야 한다 — 벽 하나가 5~15dB를 깎아 경계가 뚜렷해진다.
 * 뻥 뚫린 오픈플로어를 눈대중으로 반 갈라놓으면 경계에서 계속 흔들린다.
 */
class ZoneListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "사무실 구역"
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        setContentView(ScrollView(this).apply { addView(container) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val zones = ZoneStore.get(this).all()
        val members = MemberStore.get(this).all()

        container.addView(TextView(this).apply {
            text = "구역은 벽을 기준으로 나누세요.\n" +
                "회의실·임원실처럼 막힌 공간은 잘 갈리고,\n" +
                "뚫린 공간을 눈대중으로 나누면 경계에서 흔들립니다.\n\n" +
                "수집은 구역 안 서너 곳을 돌면서 하세요."
            textSize = 13f
            setPadding(0, 0, 0, 28)
        })

        container.addView(Button(this).apply {
            text = "구역 추가"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
            setOnClickListener { addZoneDialog() }
        })

        zones.forEach { z ->
            val assigned = members.count {
                it.deliveryType == DeliveryType.OFFICE && it.zoneId == z.id
            }
            container.addView(TextView(this).apply {
                text = buildString {
                    append(z.name)
                    if (z.description.isNotBlank()) append("  ·  ${z.description}")
                    append("\n")
                    append(
                        if (z.hasSignature)
                            "AP %d개 · %d라운드 · 고객 %d명".format(z.signature.size, z.scanRounds, assigned)
                        else
                            "서명 미수집 · 고객 %d명".format(assigned)
                    )
                }
                textSize = 15f
                setPadding(0, 16, 0, 8)
            })

            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }

                addView(Button(context).apply {
                    text = if (z.hasSignature) "추가 수집" else "신호 수집"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        startActivity(
                            Intent(this@ZoneListActivity, CollectActivity::class.java)
                                .putExtra(CollectActivity.EXTRA_ZONE_ID, z.id)
                        )
                    }
                })
                addView(Button(context).apply {
                    text = "배송"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        startActivity(
                            Intent(this@ZoneListActivity, ZoneDeliveryActivity::class.java)
                                .putExtra(ZoneDeliveryActivity.EXTRA_ZONE_ID, z.id)
                        )
                    }
                })
                addView(Button(context).apply {
                    text = "삭제"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { confirmDelete(z.id, z.name, assigned) }
                })
            })
        }
    }

    private fun addZoneDialog() {
        val name = EditText(this).apply { hint = "구역명 (예: 3층 동편 회의실)" }
        val desc = EditText(this).apply {
            hint = "설명 (건물·층)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(name); addView(desc)
        }
        AlertDialog.Builder(this)
            .setTitle("구역 추가")
            .setView(box)
            .setPositiveButton("추가") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    ZoneStore.get(this).add(n, desc.text.toString().trim())
                    render()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(id: String, name: String, assigned: Int) {
        AlertDialog.Builder(this)
            .setTitle("$name 삭제")
            .setMessage(
                if (assigned > 0) "이 구역에 배정된 고객 ${assigned}명의 구역 지정이 해제됩니다."
                else "구역과 서명이 삭제됩니다."
            )
            .setPositiveButton("삭제") { _, _ ->
                val store = MemberStore.get(this)
                store.all().filter { it.zoneId == id }.forEach {
                    it.zoneId = null
                    store.upsert(it)
                }
                ZoneStore.get(this).delete(id)
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
