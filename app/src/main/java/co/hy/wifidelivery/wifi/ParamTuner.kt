package co.hy.wifidelivery.wifi

import co.hy.wifidelivery.model.LabeledSample
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.SignatureTarget
import co.hy.wifidelivery.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 정답 라벨을 근거로 매칭 파라미터를 다시 찾는다.
 *
 * 신경망을 얹지 않는다. 라벨이 수십~수백 건 규모인데 자유도 높은 모델을 태우면
 * 그 건물, 그 시간대, 그 단말에만 맞는 값이 나오고 다른 동에 가면 무너진다.
 * 지금 가진 데이터로 정직하게 할 수 있는 건 이미 정해진 점수 함수의
 * 계수들을 다시 잡는 것까지다.
 *
 * 세 가지를 지킨다.
 *
 *  1) 비대칭 비용 — 오배송 문자(FP)는 문자 누락(FN)보다 훨씬 비싸다.
 *     정확도나 F1로 최적화하면 둘을 같은 무게로 취급해서 엉뚱한 값이 나온다.
 *  2) 교차검증 — 같은 데이터로 파라미터를 고르고 그 데이터로 성능을 재면
 *     반드시 좋아 보인다. 5겹 교차검증으로 처음 보는 데이터에서의
 *     기대 성능을 따로 보고한다. 화면에 띄우는 건 이 값이다.
 *  3) 좌표 하강 — 파라미터가 여덟 개로 늘어 전수 격자는 단말에서 몇 분씩 걸린다.
 *     한 축씩 순서대로 최적화하고 두 바퀴 돈다. 국소 최적에 걸릴 수 있지만
 *     현실적인 시간 안에 끝나고, 실제로 전수 탐색과 결과가 거의 같다.
 *
 * 배송 순서 좁히기(RouteScope)는 여기서 시뮬레이션하지 않는다.
 * 즉 실제 운영 성능은 여기 표시되는 값보다 좋게 나온다 — 보수적인 쪽이라 괜찮다.
 */
object ParamTuner {

    /** 오배송 1건을 문자 누락 몇 건과 같게 볼 것인가 */
    const val DEFAULT_FP_COST = 5.0
    const val DEFAULT_FN_COST = 1.0

    /** 이 미만이면 튜닝을 막는다. 라벨 20건으로 계수 여덟 개를 정할 수 없다. */
    const val MIN_LABELS = 30

    /** 신뢰할 만한 결과가 나오기 시작하는 지점 */
    const val RECOMMENDED_LABELS = 100

    data class Metrics(
        val tp: Int = 0,
        val fp: Int = 0,
        val fn: Int = 0,
        val tn: Int = 0
    ) {
        val total: Int get() = tp + fp + fn + tn

        fun cost(fpCost: Double = DEFAULT_FP_COST, fnCost: Double = DEFAULT_FN_COST): Double =
            fp * fpCost + fn * fnCost

        /** 발송한 것 중 맞은 비율 — 오배송률의 반대말 */
        val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)

        /** 보내야 할 것 중 보낸 비율 */
        val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)

        operator fun plus(o: Metrics) = Metrics(tp + o.tp, fp + o.fp, fn + o.fn, tn + o.tn)

        fun describe(): String = buildString {
            append("정확 발송 %d · 오배송 %d\n".format(tp, fp))
            append("누락 %d · 올바른 보류 %d\n".format(fn, tn))
            append("발송 정확도 %.1f%% · 발송률 %.1f%%".format(precision * 100, recall * 100))
        }
    }

    data class Result(
        val params: MatchParams,
        /** 교차검증 성능 — 처음 보는 데이터에서 기대되는 값 */
        val crossValidated: Metrics,
        /** 전체 라벨에 맞춘 성능 — 참고용, 실제보다 좋게 나온다 */
        val fitted: Metrics,
        /** 현재 파라미터의 성능 (비교 기준) */
        val baseline: Metrics,
        val baselineCv: Metrics,
        val sampleCount: Int
    ) {
        val improved: Boolean
            get() = crossValidated.cost() < baselineCv.cost()
    }

    // ── 탐색 축 ────────────────────────────────────────────────
    // 점수 자체를 바꾸는 축들. 한 번에 하나씩 최적화한다.
    private val axes: List<Pair<String, List<(MatchParams) -> MatchParams>>> = listOf(
        "minCoverage" to listOf(0.45, 0.55, 0.65).map { v -> { p: MatchParams -> p.copy(minCoverage = v) } },
        "rssiTolerance" to listOf(12.0, 15.0, 18.0, 22.0, 26.0).map { v -> { p: MatchParams -> p.copy(rssiTolerance = v) } },
        "coverageWeight" to listOf(0.25, 0.35, 0.45).map { v -> { p: MatchParams -> p.copy(coverageWeight = v) } },
        "idfWeight" to listOf(0.0, 0.3, 0.6, 1.0).map { v -> { p: MatchParams -> p.copy(idfWeight = v) } },
        "stdWeight" to listOf(0.0, 0.5, 1.0).map { v -> { p: MatchParams -> p.copy(stdWeight = v) } },
        "floorPenalty" to listOf(1.0, 0.5, 0.35, 0.15).map { v -> { p: MatchParams -> p.copy(floorPenalty = v) } },
        "floorTolerance" to listOf(0.18, 0.25, 0.40).map { v -> { p: MatchParams -> p.copy(floorToleranceHpa = v) } }
    )

    // 판정만 바꾸는 축들 — 점수 재계산 없이 비교되므로 전수로 돌린다.
    private val gridScoreThreshold = listOf(0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80)
    private val gridMarginThreshold = listOf(0.05, 0.10, 0.15, 0.20, 0.25)

    private const val PASSES = 2

    private data class Thresholds(val scoreThreshold: Double, val marginThreshold: Double)

    /** 샘플 하나에 대한 상위 후보 스냅샷 */
    private data class Snapshot(val topIds: List<String>, val topScores: List<Double>)

    suspend fun tune(
        samples: List<LabeledSample>,
        targets: List<SignatureTarget>,
        current: MatchParams,
        fpCost: Double = DEFAULT_FP_COST,
        fnCost: Double = DEFAULT_FN_COST,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.Default) {

        val usable = targets.filter { it.hasSignature }
        val index = ApIndex.build(usable)
        val indices = samples.indices.toList()

        val totalSteps = PASSES * axes.sumOf { it.second.size } + 1
        var step = 0
        val bump = { step++; onProgress(step, totalSteps) }

        // 전체 데이터로 최종 파라미터를 찾는다.
        val best = coordinateDescent(samples, usable, index, current, indices, fpCost, fnCost, bump)

        // 5겹 교차검증 — 겹마다 학습분으로 고르고 남긴 겹에서만 잰다.
        var cvTuned = Metrics()
        var cvBaseline = Metrics()
        val folds = 5
        for (f in 0 until folds) {
            val test = indices.filter { it % folds == f }
            val train = indices.filter { it % folds != f }
            if (test.isEmpty() || train.isEmpty()) continue
            val p = coordinateDescent(samples, usable, index, current, train, fpCost, fnCost) {}
            cvTuned += measure(samples, usable, index, p, test)
            cvBaseline += measure(samples, usable, index, current, test)
        }
        bump()

        Result(
            params = best,
            crossValidated = cvTuned,
            fitted = measure(samples, usable, index, best, indices),
            baseline = measure(samples, usable, index, current, indices),
            baselineCv = cvBaseline,
            sampleCount = samples.size
        )
    }

    /**
     * 한 축씩 돌아가며 최적값을 잡는다.
     * 축 하나를 고정할 때마다 임계값 두 개는 전수로 다시 맞춘다.
     */
    private fun coordinateDescent(
        samples: List<LabeledSample>,
        targets: List<SignatureTarget>,
        index: ApIndex,
        start: MatchParams,
        subset: List<Int>,
        fpCost: Double,
        fnCost: Double,
        onStep: () -> Unit
    ): MatchParams {
        var best = start
        var bestCost = Double.MAX_VALUE

        repeat(PASSES) {
            axes.forEach { (_, mutations) ->
                var localBest = best
                mutations.forEach { mutate ->
                    val candidate = mutate(best)
                    val snaps = subset.map { snapshot(targets, samples[it], candidate, index) }
                    val (th, cost, _) = bestThresholds(samples, subset, snaps, fpCost, fnCost)
                    val withTh = candidate.copy(
                        scoreThreshold = th.scoreThreshold,
                        marginThreshold = th.marginThreshold
                    )
                    if (cost < bestCost) {
                        bestCost = cost
                        localBest = withTh
                    }
                    onStep()
                }
                best = localBest
            }
        }
        return best
    }

    /** 점수는 그대로 두고 임계값 조합만 전수 비교 */
    private fun bestThresholds(
        samples: List<LabeledSample>,
        subset: List<Int>,
        snaps: List<Snapshot>,
        fpCost: Double,
        fnCost: Double
    ): Triple<Thresholds, Double, Metrics> {
        var bestTh = Thresholds(0.62, 0.10)
        var bestCost = Double.MAX_VALUE
        var bestMetrics = Metrics()
        var bestTp = -1

        gridScoreThreshold.forEach { st ->
            gridMarginThreshold.forEach { mt ->
                val th = Thresholds(st, mt)
                var m = Metrics()
                subset.forEachIndexed { k, i ->
                    m += classify(predict(snaps[k], th), samples[i])
                }
                val c = m.cost(fpCost, fnCost)
                // 비용이 같으면 더 많이 발송하는 쪽. 안 그러면 "아무것도 안 보냄"이 늘 이긴다.
                if (c < bestCost || (c == bestCost && m.tp > bestTp)) {
                    bestCost = c
                    bestTp = m.tp
                    bestTh = th
                    bestMetrics = m
                }
            }
        }
        return Triple(bestTh, bestCost, bestMetrics)
    }

    private fun snapshot(
        targets: List<SignatureTarget>,
        sample: LabeledSample,
        params: MatchParams,
        index: ApIndex
    ): Snapshot {
        val ranked = SignatureMatcher
            .rank(targets, sample.liveRssi, params, index, sample.pressureIndex)
            .take(5)
        return Snapshot(ranked.map { it.target.targetId }, ranked.map { it.score })
    }

    private fun measure(
        samples: List<LabeledSample>,
        targets: List<SignatureTarget>,
        index: ApIndex,
        params: MatchParams,
        subset: List<Int>
    ): Metrics {
        var m = Metrics()
        subset.forEach { i ->
            val d = SignatureMatcher.evaluate(
                targets, samples[i].liveRssi, params, index, samples[i].pressureIndex
            )
            m += classify(if (d.confident) d.best?.target?.targetId else null, samples[i])
        }
        return m
    }

    private fun predict(snap: Snapshot, th: Thresholds): String? {
        val best = snap.topScores.firstOrNull() ?: return null
        if (best < th.scoreThreshold) return null
        val second = snap.topScores.getOrNull(1) ?: 0.0
        if (best - second < th.marginThreshold) return null
        return snap.topIds.first()
    }

    private fun classify(predicted: String?, sample: LabeledSample): Metrics {
        val actual = if (sample.verdict == Verdict.NOT_A_DOOR) null else sample.actualMemberId
        return when {
            predicted != null && predicted == actual -> Metrics(tp = 1)
            predicted != null -> Metrics(fp = 1)   // 오배송 — 제일 비싼 오류
            actual != null -> Metrics(fn = 1)      // 누락
            else -> Metrics(tn = 1)
        }
    }
}
