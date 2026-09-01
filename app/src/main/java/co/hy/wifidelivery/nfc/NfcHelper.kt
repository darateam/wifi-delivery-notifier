package co.hy.wifidelivery.nfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.Member
import co.hy.wifidelivery.wifi.ApIndex
import co.hy.wifidelivery.wifi.SignatureMatcher

/**
 * NFC 태그 읽기.
 *
 * 태그마다 공장에서 구워진 고유 UID가 있다. 변조가 안 되고 전 세계에서
 * 유일하므로, 이걸 회원과 매핑해두면 별도 채번 없이 태그 개수만큼 구별된다.
 *
 * 리더 모드를 쓴다. 시스템 기본 NFC 처리(다른 앱이 가로채거나 알림음이 나는 것)를
 * 끄고 이 액티비티가 태그를 직접 받는다. 배송 현장에서 한 손으로 대야 하므로
 * 중간에 선택 다이얼로그가 뜨면 안 된다.
 */
object NfcHelper {

    fun adapter(context: Context): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(context.applicationContext)

    fun isAvailable(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** 태그 UID를 대문자 hex 문자열로. 예: 04A23F1B2C5D80 */
    fun uidOf(tag: Tag): String =
        tag.id.joinToString("") { "%02X".format(it) }

    fun enableReader(activity: Activity, onTag: (Tag) -> Unit) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter(activity)?.enableReaderMode(activity, { tag -> onTag(tag) }, flags, null)
    }

    fun disableReader(activity: Activity) {
        adapter(activity)?.disableReaderMode(activity)
    }

    /** 태그를 대서 앱이 실행된 경우 인텐트에서 태그를 꺼낸다 */
    @Suppress("DEPRECATION")
    fun tagFrom(intent: Intent?): Tag? {
        if (intent == null) return null
        val action = intent.action
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    fun statusText(context: Context): String = when {
        !isAvailable(context) -> "이 기기는 NFC를 지원하지 않습니다"
        !isEnabled(context) -> "NFC가 꺼져 있습니다. 설정에서 켜주세요."
        else -> "태그를 바구니에 대주세요"
    }
}

/**
 * 태그와 Wi-Fi 위치의 교차 검증.
 *
 * NFC만 쓰면 바구니가 옮겨졌을 때 알 방법이 없다. 고객이 안으로 들였다가
 * 옆집 앞에 잘못 내놓거나, 관리실에서 치우며 섞이면 엉뚱한 집에 문자가 간다.
 * Wi-Fi는 물건이 아니라 장소를 보므로 이 어긋남을 잡아낼 수 있다.
 *
 * 반대 방향의 이득도 크다. 태그를 찍은 순간의 Wi-Fi 스캔은
 * **사람 손을 거치지 않은 확실한 정답 라벨**이다.
 * 배송할 때마다 라벨이 공짜로 쌓인다.
 */
object NfcCrossCheck {

    sealed interface Verdict {
        /** 검증할 근거가 없음 — 서명 미수집이거나 스캔 결과 없음 */
        data object Unavailable : Verdict

        /** 위치도 일치 */
        data class Agrees(val score: Double) : Verdict

        /** 태그와 위치가 어긋남 — 바구니가 옮겨졌을 가능성 */
        data class Conflicts(val score: Double, val betterMatch: String?) : Verdict
    }

    /** 이 점수 밑이면 "여기가 그 집이 아니다"로 본다 */
    private const val AGREE_FLOOR = 0.45

    /** 다른 집이 이만큼 더 높으면 확실히 어긋난 것 */
    private const val RIVAL_MARGIN = 0.15

    fun check(
        member: Member,
        live: Map<String, Int>,
        candidates: List<Member>,
        params: MatchParams,
        pressureIndex: Double?
    ): Verdict {
        if (!member.hasSignature || live.isEmpty()) return Verdict.Unavailable

        val index = ApIndex.build(candidates)
        val mine = SignatureMatcher.score(member, live, params, index, pressureIndex)

        val rival = candidates
            .filter { it.id != member.id && it.hasSignature }
            .map { SignatureMatcher.score(it, live, params, index, pressureIndex) }
            .maxByOrNull { it.score }

        val rivalWins = rival != null && rival.score - mine.score > RIVAL_MARGIN

        return if (mine.score < AGREE_FLOOR || rivalWins) {
            Verdict.Conflicts(mine.score, rival?.target?.displayName?.takeIf { rivalWins })
        } else {
            Verdict.Agrees(mine.score)
        }
    }
}
