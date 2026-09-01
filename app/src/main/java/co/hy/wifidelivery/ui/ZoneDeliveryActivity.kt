package co.hy.wifidelivery.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.DeliveryCompleter
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.model.DeliveryType

/**
 * 사무실 구역 배송 화면.
 *
 * 책상 단위는 Wi-Fi로 못 가른다. 인접 책상 간격이 1.5m인데 측위 오차가 3~5m라
 * 원리상 불가능하고, 억지로 하면 옆자리에 완료 문자가 간다.
 *
 * 그래서 자동화는 구역 진입까지만 하고, 마지막 한 걸음은 사람이 누른다.
 * 어차피 자리마다 서서 물건을 놓는 동작이 있으니 그 김에 한 번 탭하는 게
 * 오탐 걱정하며 임계값 튜닝하는 것보다 빠르고 정확하다.
 *
 * 이 화면의 값어치는 "후보를 3~4명으로 줄여준 것"에 있다.
 * 200명 목록에서 찾는 것과 4명 중에 고르는 것은 완전히 다른 일이다.
 */
class ZoneDeliveryActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var zoneId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        zoneId = intent.getStringExtra(EXTRA_ZONE_ID).orEmpty()

        val zone = ZoneStore.get(this).get(zoneId)
        if (zone == null) {
            finish()
            return
        }
        title = zone.name

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        setContentView(ScrollView(this).apply { addView(container) })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (zoneId.isNotEmpty()) render()
    }

    private fun render() {
        container.removeAllViews()
        val store = MemberStore.get(this)
        val zone = ZoneStore.get(this).get(zoneId) ?: return

        val members = store.all()
            .filter { it.deliveryType == DeliveryType.OFFICE && it.zoneId == zoneId }
            .sortedBy { if (it.routeOrder > 0) it.routeOrder else Int.MAX_VALUE }

        container.addView(TextView(this).apply {
            text = buildString {
                append(zone.name)
                if (zone.description.isNotBlank()) append("\n${zone.description}")
                append("\n\n배송한 자리를 눌러주세요.")
            }
            textSize = 15f
            setPadding(0, 0, 0, 32)
        })

        if (members.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "이 구역에 등록된 고객이 없습니다."
                textSize = 14f
            })
            return
        }

        val today = System.currentTimeMillis()
        members.forEach { m ->
            // 오늘 이미 처리한 자리는 흐리게 — 중복 탭을 줄인다.
            val doneToday = today - m.lastNotifiedAt < 8 * 3600_000L
            container.addView(Button(this).apply {
                text = buildString {
                    if (doneToday) append("✓ ")
                    append(m.name)
                    if (m.address.isNotBlank()) append("  ${m.address}")
                    if (!m.notifyEnabled) append("  (문자 미수신)")
                }
                textSize = 16f
                alpha = if (doneToday) 0.45f else 1f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 165
                ).apply { bottomMargin = 18 }
                setOnClickListener {
                    val result = DeliveryCompleter.complete(this@ZoneDeliveryActivity, m, "zone_pick")
                    Toast.makeText(
                        this@ZoneDeliveryActivity,
                        DeliveryCompleter.message(m, result),
                        Toast.LENGTH_SHORT
                    ).show()
                    render()
                }
            })
        }

        container.addView(Button(this).apply {
            text = "닫기"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            setOnClickListener { finish() }
        })
    }

    companion object {
        const val EXTRA_ZONE_ID = "zone_id"
    }
}
