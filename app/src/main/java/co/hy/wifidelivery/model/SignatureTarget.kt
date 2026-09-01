package co.hy.wifidelivery.model

/**
 * Wi-Fi 서명을 가진 대상.
 *
 * 처음에는 회원(=집 문앞)만 있었는데 사무실 배송이 들어오면서 갈라졌다.
 *
 *  - 아파트/주택: 문앞 한 지점이 곧 한 고객. 서명 대상 = 회원
 *  - 사무실: 책상 단위는 Wi-Fi로 원리상 못 가른다(간격 1.5m, 측위 오차 3~5m).
 *            구역까지만 잡고 그 안에서는 사람이 고른다. 서명 대상 = 구역
 *
 * 매칭 엔진 입장에서는 둘 다 "서명을 가진 장소"라 같은 코드로 다룬다.
 */
interface SignatureTarget {
    val targetId: String
    val displayName: String
    val signature: List<ApStat>
    val pressureIndex: Double?

    val hasSignature: Boolean
        get() = signature.size >= MIN_SIGNATURE_APS

    fun stableAps(): List<ApStat> = signature.filter { it.hitRatio >= STABLE_HIT_RATIO }

    companion object {
        const val MIN_SIGNATURE_APS = 3
        const val STABLE_HIT_RATIO = 0.5
    }
}

enum class DeliveryType {
    /** 아파트·주택 — 문앞 서명으로 자동 확정 */
    HOME,

    /** 사무실 — 구역까지만 자동, 자리는 사람이 선택 */
    OFFICE,

    /** 자동 감지 제외 — 수동 처리만 */
    MANUAL
}

/**
 * 사무실 구역.
 *
 * 책상이 아니라 면(面)이다. 한 지점만 찍으면 안 되고
 * 구역 안 서너 곳을 돌며 모아야 서명이 구역을 대표한다.
 *
 * 벽으로 막힌 공간(회의실, 임원실)은 오히려 아파트보다 잘 갈린다.
 * 벽 하나가 5~15dB를 깎아 경계가 뚜렷해지기 때문이다.
 * 반대로 뻥 뚫린 오픈플로어는 신호가 매끄럽게 변해 자연 경계가 없으므로,
 * 도면상 벽을 기준으로 구역을 나누는 게 정확도에 결정적이다.
 */
data class Zone(
    val id: String,
    var name: String,
    /** 건물·층 등 사람이 알아볼 설명 */
    var description: String = "",
    override var signature: List<ApStat> = emptyList(),
    var scanRounds: Int = 0,
    var updatedAt: Long = 0L,
    override var pressureIndex: Double? = null
) : SignatureTarget {
    override val targetId: String get() = id
    override val displayName: String get() = name
}
