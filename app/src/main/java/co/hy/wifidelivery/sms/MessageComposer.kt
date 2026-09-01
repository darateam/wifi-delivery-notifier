package co.hy.wifidelivery.sms

import android.content.Context
import co.hy.wifidelivery.data.Settings
import co.hy.wifidelivery.model.Member
import java.util.Calendar

/**
 * 문자 본문 작성.
 *
 * 광고를 붙이는 순간 규제가 전부 따라온다. 정보통신망법 제50조 기준으로
 * **정보성 문자에 광고를 섞으면 전체가 영리목적 광고성 정보**가 된다.
 * 즉 배송 완료 문자에 상품 안내 한 줄을 붙이는 순간, 그 문자도 광고 문자다.
 *
 * 그래서 광고를 붙일지 말지를 호출부 판단에 맡기지 않고 여기서 강제한다.
 * 조건을 하나라도 못 채우면 광고 블록이 자동으로 빠지고 정보성 문자만 나간다.
 *
 *  1) 사전 명시적 수신동의 — 배송 서비스 가입 동의와 별개다
 *  2) 동의 유효기간 — 2년마다 재확인해야 한다
 *  3) 본문 맨 앞 "(광고)" 표기
 *  4) 무료 수신거부 방법 명시
 *  5) 야간(21시~익일 8시) 전송 금지 — 별도 사전 동의가 없으면 불가
 *
 * 5번이 이 서비스에서 특히 중요하다. 새벽 배송이 돌면 8시 이전 발송이
 * 일상적으로 발생하는데, 그 시간대에는 광고가 붙으면 안 된다.
 * 이 경우 광고만 떼고 정보성 문자는 정상 발송한다.
 */
object MessageComposer {

    /** 광고 수신동의 유효기간 (2년) */
    private const val CONSENT_TTL_MS = 2L * 365 * 24 * 3600 * 1000

    /** 광고 전송 가능 시간대 (이 시각 이상 ~ 미만) */
    const val AD_ALLOWED_FROM_HOUR = 8
    const val AD_ALLOWED_UNTIL_HOUR = 21

    data class Composed(
        val body: String,
        /** 광고성 문자로 분류되는가 — 발송 이력에 남겨야 한다 */
        val isAd: Boolean,
        /** 광고를 붙이지 못한 이유 (디버깅·감사용) */
        val adSkipReason: String? = null
    )

    enum class Kind { DELIVERY_COMPLETE, ADVANCE_NOTICE }

    fun compose(context: Context, member: Member, kind: Kind, now: Long = System.currentTimeMillis()): Composed {
        val settings = Settings(context)
        val core = when (kind) {
            Kind.DELIVERY_COMPLETE -> settings.smsTemplate
            Kind.ADVANCE_NOTICE -> settings.advanceNoticeTemplate
        }.replace("{name}", member.name)

        val gate = adGate(context, member, now)
        if (gate != null) return Composed(core, isAd = false, adSkipReason = gate)

        val ad = settings.adTemplate.trim()
        if (ad.isEmpty()) return Composed(core, isAd = false, adSkipReason = "광고 문구 미설정")

        // 광고가 붙으면 전체가 광고성 문자다. 표기와 수신거부를 반드시 함께 넣는다.
        val body = buildString {
            append("(광고) ")
            append(core)
            append("\n\n")
            append(ad)
            append("\n무료수신거부 ")
            append(settings.optOutNumber)
        }
        return Composed(body, isAd = true)
    }

    /** 광고를 붙이면 안 되는 이유를 돌려준다. null이면 붙여도 된다. */
    private fun adGate(context: Context, member: Member, now: Long): String? {
        val settings = Settings(context)
        if (!settings.adEnabled) return "광고 기능 꺼짐"
        if (settings.optOutNumber.isBlank()) return "수신거부 번호 미설정"
        if (member.adConsentAt <= 0L) return "광고 수신 미동의"
        if (now - member.adConsentAt > CONSENT_TTL_MS) return "수신동의 2년 경과 (재확인 필요)"

        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        if (hour < AD_ALLOWED_FROM_HOUR || hour >= AD_ALLOWED_UNTIL_HOUR) {
            return "야간 시간대 (${AD_ALLOWED_FROM_HOUR}시~${AD_ALLOWED_UNTIL_HOUR}시만 가능)"
        }
        return null
    }

    /**
     * 광고를 붙였을 때 본문이 얼마나 길어지는지 미리 본다.
     * 90바이트를 넘으면 SMS가 아니라 LMS로 나가 건당 단가가 오른다.
     * 매일 배송이면 이 차이가 그대로 월 비용이 된다.
     */
    fun byteLength(body: String): Int = body.toByteArray(Charsets.UTF_8).size

    fun isLms(body: String): Boolean = byteLength(body) > 90
}
