package co.hy.wifidelivery.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.Settings
import co.hy.wifidelivery.databinding.ActivityMessageSettingsBinding
import co.hy.wifidelivery.model.Member
import co.hy.wifidelivery.sms.MessageComposer

/**
 * 문자 문구 설정.
 *
 * 광고 문구를 넣는 순간 규제가 따라오므로, 저장 전에 실제 나갈 본문을
 * 그대로 미리 보여준다. "(광고)" 표기와 수신거부 안내가 자동으로 붙는 것,
 * 그리고 그 때문에 LMS로 넘어가 단가가 오르는 것까지 눈으로 확인해야 한다.
 */
class MessageSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageSettingsBinding
    private lateinit var settings: Settings

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = preview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "문자 문구"

        settings = Settings(this)
        binding.inputDelivery.setText(settings.smsTemplate)
        binding.inputAdvance.setText(settings.advanceNoticeTemplate)
        binding.inputAd.setText(settings.adTemplate)
        binding.inputOptOut.setText(settings.optOutNumber)
        binding.switchAd.isChecked = settings.adEnabled

        listOf(
            binding.inputDelivery, binding.inputAdvance,
            binding.inputAd, binding.inputOptOut
        ).forEach { it.addTextChangedListener(watcher) }
        binding.switchAd.setOnCheckedChangeListener { _, _ -> save(); preview() }

        binding.btnSave.setOnClickListener {
            save()
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        }
        preview()
    }

    private fun save() {
        settings.smsTemplate = binding.inputDelivery.text.toString()
        settings.advanceNoticeTemplate = binding.inputAdvance.text.toString()
        settings.adTemplate = binding.inputAd.text.toString()
        settings.optOutNumber = binding.inputOptOut.text.toString().trim()
        settings.adEnabled = binding.switchAd.isChecked
    }

    private fun preview() {
        save()

        // 광고 동의가 살아 있는 가상 고객으로 미리보기.
        // 실제 발송에서는 고객별 동의 상태에 따라 광고가 빠질 수 있다.
        val sample = Member(
            id = "preview", name = "홍길동", phone = "",
            adConsentAt = System.currentTimeMillis()
        )

        val delivery = MessageComposer.compose(this, sample, MessageComposer.Kind.DELIVERY_COMPLETE)
        val advance = MessageComposer.compose(this, sample, MessageComposer.Kind.ADVANCE_NOTICE)

        binding.txtPreview.text = buildString {
            append("── 배송 완료 ──\n")
            append(delivery.body)
            append("\n\n%d바이트 · %s".format(
                MessageComposer.byteLength(delivery.body),
                if (MessageComposer.isLms(delivery.body)) "LMS" else "SMS"
            ))
            append("\n\n── 방문 예정 ──\n")
            append(advance.body)
            append("\n\n%d바이트 · %s".format(
                MessageComposer.byteLength(advance.body),
                if (MessageComposer.isLms(advance.body)) "LMS" else "SMS"
            ))
        }

        binding.txtStatus.text = buildString {
            val reason = delivery.adSkipReason
            if (delivery.isAd) {
                append("현재 설정이면 광고성 문자로 나갑니다.\n")
                append("• 본문 앞 (광고) 표기 — 자동 삽입됨\n")
                append("• 무료 수신거부 안내 — 자동 삽입됨\n")
                append("• 발송 가능 시간 %d시~%d시 (그 외에는 광고만 자동 제외)\n".format(
                    MessageComposer.AD_ALLOWED_FROM_HOUR, MessageComposer.AD_ALLOWED_UNTIL_HOUR
                ))
                append("• 고객별 광고 수신동의가 있어야 실제로 붙습니다\n")
                append("• 동의는 2년마다 재확인이 필요합니다")
            } else {
                append("정보성 문자로 나갑니다 (광고 미포함)\n")
                if (reason != null) append("광고 제외 사유: $reason")
            }

            val consented = MemberStore.get(this@MessageSettingsActivity)
                .all().count { it.adConsentAt > 0 }
            append("\n\n광고 수신동의 고객 ${consented}명")

            if (MessageComposer.isLms(delivery.body)) {
                append("\n\n90바이트를 넘어 LMS로 발송됩니다. 건당 단가가 SMS보다 높으니 " +
                    "매일 발송 물량으로 월 비용을 미리 계산해 보세요.")
            }
        }
    }
}
