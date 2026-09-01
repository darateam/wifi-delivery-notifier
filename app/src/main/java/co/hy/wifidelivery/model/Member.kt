package co.hy.wifidelivery.model

/**
 * 서명을 구성하는 AP 한 개의 통계치.
 *
 * @param meanRssi 수집 라운드 전체의 평균 RSSI(dBm)
 * @param stdRssi  라운드 간 표준편차(dB).
 *                 "이 AP는 원래 ±2dB인데 지금 5dB 어긋났다"와
 *                 "저 AP는 원래 ±10dB라 5dB는 의미 없다"를 구분하는 데 쓴다.
 * @param hitRatio 전체 스캔 라운드 중 이 AP가 잡힌 비율(0.0~1.0).
 */
data class ApStat(
    val bssid: String,
    val ssid: String,
    val meanRssi: Double,
    val hitRatio: Double,
    val stdRssi: Double = DEFAULT_STD,
    /** 5GHz 여부. 감쇠가 빨라 공간 해상도가 2.4GHz보다 좋다. */
    val is5Ghz: Boolean = false
) {
    /** 표준편차 하한 — 12라운드 추정이라 0에 가까우면 과신하게 된다 */
    val effectiveStd: Double get() = stdRssi.coerceAtLeast(MIN_STD)

    companion object {
        const val DEFAULT_STD = 4.0
        const val MIN_STD = 2.0
    }
}

data class Member(
    val id: String,
    var name: String,
    var phone: String,
    var address: String = "",
    override var signature: List<ApStat> = emptyList(),
    var scanRounds: Int = 0,
    var updatedAt: Long = 0L,
    var lastNotifiedAt: Long = 0L,
    /**
     * 배송 경로상의 순번. 0이면 미지정.
     * 후보를 200개에서 서너 개로 줄여주는, 가장 값싼 정확도 개선책.
     */
    var routeOrder: Int = 0,
    /** 수집 시점 기압 지표 = (기준 기압 - 측정 기압), hPa. 클수록 높은 층. */
    override var pressureIndex: Double? = null,
    /** 배송지 유형 — 자동 확정 방식이 갈린다 */
    var deliveryType: DeliveryType = DeliveryType.HOME,
    /** OFFICE인 경우 소속 구역 id */
    var zoneId: String? = null,
    /**
     * 고객별 자동 문자 수신 여부.
     *
     * 문자를 원치 않는 고객이 반드시 있다. 끄면 감지는 그대로 되지만
     * 문자만 나가지 않고 배송 완료 처리와 경로 전진은 정상 동작한다.
     */
    var notifyEnabled: Boolean = true,
    /**
     * 등록된 NFC 태그 UID(hex). null이면 태그 없음.
     *
     * 태그마다 공장에서 구워진 고유값이라 변조가 안 되고, 태그 개수만큼
     * 자동으로 구별된다. 별도 채번이 필요 없다.
     */
    var nfcTagUid: String? = null,
    /**
     * Wi-Fi 문앞 서명으로 자동 확정할지 여부.
     *
     * NFC와 배타적이지 않다. 둘 다 켜는 게 가장 정확하다 —
     * NFC가 "이 물건이 뭔가"를, Wi-Fi가 "지금 어디인가"를 답해서
     * 서로를 검증하기 때문이다. 조합 네 가지가 모두 정상 상태다.
     */
    var wifiAutoDetect: Boolean = true,
    /**
     * 앞 순번 배송 완료 시 "곧 방문 예정" 문자를 받을지.
     * 배송 완료 알림과 별개 동의 항목이다 — 받는 문자 수가 두 배가 된다.
     */
    var advanceNoticeEnabled: Boolean = false,
    /** 마지막 방문예정 문자 발송 시각 — 하루 중복 발송 방지 */
    var lastAdvanceNoticeAt: Long = 0L,
    /**
     * 광고 수신동의 시각. 0이면 미동의.
     *
     * 배송 알림 동의와 반드시 분리해야 한다. 정보통신망법상 영리목적
     * 광고성 정보는 별도의 명시적 사전 동의를 요구하고, 2년마다 재확인해야 한다.
     */
    var adConsentAt: Long = 0L
) : SignatureTarget {

    override val targetId: String get() = id
    override val displayName: String get() = name

    /** Wi-Fi 문앞 서명으로 자동 확정할 대상인가 */
    val autoDetectable: Boolean
        get() = deliveryType == DeliveryType.HOME && wifiAutoDetect && hasSignature

    val hasTag: Boolean get() = !nfcTagUid.isNullOrBlank()

    /** 서명은 있는데 자동 확정에서 뺀 경우 — 교차 검증에는 여전히 쓴다 */
    val usableForCrossCheck: Boolean get() = hasSignature

    companion object {
        const val MIN_SIGNATURE_APS = SignatureTarget.MIN_SIGNATURE_APS
        const val STABLE_HIT_RATIO = SignatureTarget.STABLE_HIT_RATIO
    }
}
