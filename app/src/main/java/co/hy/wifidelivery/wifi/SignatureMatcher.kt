package co.hy.wifidelivery.wifi

import co.hy.wifidelivery.model.ApStat
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.SignatureTarget
import kotlin.math.abs
import kotlin.math.min

data class MatchScore(
    val target: SignatureTarget,
    val score: Double,
    val coverage: Double,
    val rssiScore: Double,
    val anchorHit: Boolean,
    val commonAps: Int,
    val floorMismatch: Boolean = false
)

data class MatchDecision(
    val best: MatchScore?,
    val runnerUp: MatchScore?,
    /** 임계값과 마진을 모두 통과했는가 */
    val confident: Boolean,
    val reason: String,
    /**
     * 점수는 충분한데 후보끼리 구분이 안 되는 상태.
     *
     * 이 경우 조용히 보류하는 것보다 사람에게 물어보는 쪽이 낫다.
     * 한 번 탭이면 끝이고, 잘못 나간 문자보다 훨씬 싸다.
     */
    val ambiguous: Boolean = false,
    val tied: List<MatchScore> = emptyList()
) {
    val margin: Double
        get() = (best?.score ?: 0.0) - (runnerUp?.score ?: 0.0)
}

/**
 * 라이브 스캔 하나를 저장된 서명들과 비교한다.
 *
 * 이 문제의 본질은 좌표 추정이 아니라 재식별이다.
 * "여기가 지난번 그 집 문앞이 맞나"만 답하면 되므로 격자 서베이가 필요 없다.
 *
 * 아파트 인접 세대가 최대 위험이라, 방어 장치를 여섯 겹으로 둔다.
 *
 *  1) 신호 세기 가중 — 가까운 AP일수록 세대 간 RSSI 차이가 크므로 크게 반영
 *  2) AP 희소성 가중 — 그 집에서만 보이는 AP를 무겁게 (ApIndex)
 *  3) 편차 정규화 — 원래 흔들리는 AP의 5dB와 안정적인 AP의 5dB를 다르게 취급
 *  4) 앵커 검사 — 서명에서 가장 강한 AP 3개 중 2개는 반드시 보여야 함
 *  5) 층 판정 — 기압으로 위아래층을 걸러냄 (PressureTracker)
 *  6) 마진 검사 — 2등 후보와 점수 차가 충분해야 확정, 아니면 사람에게 질문
 *
 * 여기에 배송 순서로 후보 자체를 좁히면(RouteScope) 마진이 크게 벌어진다.
 *
 * 모든 계수는 MatchParams로 뺐다. ParamTuner가 정답 라벨을 근거로
 * 이 값들을 다시 찾는다. 어떤 장치가 이 현장에서 해롭다면
 * 해당 가중치가 스스로 0으로 수렴한다.
 */
object SignatureMatcher {

    /** 개별 AP 편차 상한 — 이상치 하나가 점수를 통째로 깎는 걸 막는다 */
    private const val MAX_DEVIATION = 40.0

    fun score(
        target: SignatureTarget,
        live: Map<String, Int>,
        params: MatchParams,
        index: ApIndex = ApIndex.EMPTY,
        currentPressureIndex: Double? = null
    ): MatchScore {
        val stable = target.stableAps()
        if (stable.size < SignatureTarget.MIN_SIGNATURE_APS || live.isEmpty()) {
            return MatchScore(target, 0.0, 0.0, 0.0, false, 0)
        }

        val common = stable.filter { live.containsKey(it.bssid) }
        val coverage = common.size.toDouble() / stable.size

        if (coverage < params.minCoverage || common.isEmpty()) {
            return MatchScore(target, 0.0, coverage, 0.0, false, common.size)
        }

        var weightSum = 0.0
        var deviationSum = 0.0
        common.forEach { ap ->
            val w = weightOf(ap, index, params.idfWeight)
            val raw = abs(live.getValue(ap.bssid).toDouble() - ap.meanRssi)
            // 표준편차로 나누면 "그 AP 기준으로 몇 시그마 어긋났나"가 된다.
            // stdWeight로 원본 dB와 섞어, 라운드가 적어 std 추정이 부실할 때를 대비한다.
            val normalized = raw / ap.effectiveStd * ApStat.DEFAULT_STD
            val d = min((1.0 - params.stdWeight) * raw + params.stdWeight * normalized, MAX_DEVIATION)
            weightSum += w
            deviationSum += w * d
        }
        val avgDeviation = if (weightSum > 0) deviationSum / weightSum else MAX_DEVIATION
        val rssiScore = (1.0 - avgDeviation / params.rssiTolerance).coerceIn(0.0, 1.0)

        val anchors = stable.sortedByDescending { it.meanRssi }.take(3)
        val anchorHit = anchors.count { live.containsKey(it.bssid) } >= 2

        var score = params.coverageWeight * coverage + params.rssiWeight * rssiScore
        if (!anchorHit) score *= params.anchorPenalty

        val mismatch = PressureTracker.floorMismatch(
            target.pressureIndex, currentPressureIndex, params.floorToleranceHpa
        )
        if (mismatch) score *= params.floorPenalty

        return MatchScore(target, score, coverage, rssiScore, anchorHit, common.size, mismatch)
    }

    /**
     * 가까운 AP일수록, 안정적으로 잡히는 AP일수록, 그리고 희소한 AP일수록 크게 반영.
     * -55dBm 근방이면 1.0, -95dBm 근방이면 0.15까지 떨어진다.
     */
    private fun weightOf(ap: ApStat, index: ApIndex, idfWeight: Double): Double {
        val strength = ((ap.meanRssi + 95.0) / 40.0).coerceIn(0.15, 1.0)
        return ap.hitRatio * strength * index.factor(ap.bssid, idfWeight)
    }

    /** 점수 순위만 뽑는다. 튜너가 임계값을 바꿔가며 재사용할 수 있게 판정과 분리했다. */
    fun rank(
        targets: List<SignatureTarget>,
        live: Map<String, Int>,
        params: MatchParams,
        index: ApIndex = ApIndex.EMPTY,
        currentPressureIndex: Double? = null
    ): List<MatchScore> =
        targets.filter { it.hasSignature }
            .map { score(it, live, params, index, currentPressureIndex) }
            .sortedByDescending { it.score }

    fun decide(ranked: List<MatchScore>, params: MatchParams): MatchDecision {
        val best = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)

        if (best == null || best.score <= 0.0) {
            return MatchDecision(best, runnerUp, false, "일치 후보 없음")
        }
        if (best.score < params.scoreThreshold) {
            return MatchDecision(best, runnerUp, false, "점수 미달 %.2f".format(best.score))
        }
        val margin = best.score - (runnerUp?.score ?: 0.0)
        if (runnerUp != null && margin < params.marginThreshold) {
            // 둘 다 임계를 넘겼는데 서로 못 가르는 경우 — 여기서 사람에게 묻는다.
            val tied = ranked.filter {
                it.score >= params.scoreThreshold &&
                    best.score - it.score < params.marginThreshold
            }.take(4)
            return MatchDecision(
                best, runnerUp, false,
                "인접 후보와 구분 불가 (%s %.2f / %s %.2f)".format(
                    best.target.displayName, best.score, runnerUp.target.displayName, runnerUp.score
                ),
                ambiguous = tied.size >= 2,
                tied = tied
            )
        }
        return MatchDecision(best, runnerUp, true, "일치 %.2f".format(best.score))
    }

    fun evaluate(
        targets: List<SignatureTarget>,
        live: Map<String, Int>,
        params: MatchParams,
        index: ApIndex = ApIndex.EMPTY,
        currentPressureIndex: Double? = null
    ): MatchDecision = decide(rank(targets, live, params, index, currentPressureIndex), params)
}

/**
 * 체류 판정.
 *
 * 한 번 일치했다고 바로 쏘면 지나가기만 해도 문자가 나간다.
 * 같은 대상이 연속 N회 잡히고, 그 구간이 설정한 체류 시간을 넘겨야 발동한다.
 *
 * 확정 후보뿐 아니라 "애매한 후보 묶음"에 대해서도 체류를 센다.
 * 안 그러면 질문 알림이 지나가는 길에 계속 튀어나온다.
 */
class DwellTracker(
    private val minConsecutiveHits: Int = 3
) {
    private var candidateKey: String? = null
    private var since: Long = 0L
    private var hits: Int = 0

    var lastReason: String = "대기"
        private set

    sealed interface Outcome {
        data class Confirmed(val targetId: String) : Outcome
        data class Ambiguous(val candidates: List<String>) : Outcome
        data object None : Outcome
    }

    fun update(
        decision: MatchDecision,
        dwellMillis: Long,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        val key: String? = when {
            decision.confident && decision.best != null -> "T:${decision.best.target.targetId}"
            decision.ambiguous -> "A:" + decision.tied.map { it.target.targetId }.sorted().joinToString(",")
            else -> null
        }

        if (key == null) {
            lastReason = decision.reason
            reset()
            return Outcome.None
        }

        if (key != candidateKey) {
            candidateKey = key
            since = now
            hits = 1
        } else {
            hits++
        }

        val elapsed = now - since
        val label = if (decision.confident) {
            decision.best?.target?.displayName ?: "?"
        } else {
            "구분필요 " + decision.tied.joinToString("/") { it.target.displayName }
        }
        lastReason = "%s 체류 %d초 (%d회)".format(label, elapsed / 1000, hits)

        if (hits < minConsecutiveHits || elapsed < dwellMillis) return Outcome.None

        return if (decision.confident && decision.best != null) {
            Outcome.Confirmed(decision.best.target.targetId)
        } else {
            Outcome.Ambiguous(decision.tied.map { it.target.targetId })
        }
    }

    fun reset() {
        candidateKey = null
        since = 0L
        hits = 0
    }
}
