package co.hy.wifidelivery.wifi

import co.hy.wifidelivery.model.SignatureTarget
import kotlin.math.ln

/**
 * AP 희소성 지수.
 *
 * 지금까지 가중치는 신호 세기와 관측 빈도만 봤다. 그런데 인접 세대를
 * 가르는 데 진짜 값어치 있는 AP는 **그 집에서만 보이는 AP**다.
 * 20세대 서명에 전부 등장하는 동 공용 AP는 변별력이 사실상 0이고,
 * 한두 집에만 나오는 AP가 결정적이다. 문서 검색의 IDF와 같은 논리다.
 *
 * 추가 수집이 전혀 필요 없다. 이미 저장된 서명들만으로 계산된다.
 * 아파트처럼 서명이 서로 겹치는 환경일수록 효과가 크다.
 */
class ApIndex private constructor(
    private val docFreq: Map<String, Int>,
    private val memberCount: Int
) {

    private val maxIdf = ln((memberCount + 1.0))

    /**
     * 0.0(모든 세대에 등장) ~ 1.0(한 세대에만 등장) 로 정규화된 희소성.
     */
    fun rarity(bssid: String): Double {
        if (memberCount <= 1 || maxIdf <= 0.0) return 0.5
        val df = docFreq[bssid] ?: 0
        val idf = ln((memberCount + 1.0) / (df + 1.0))
        return (idf / maxIdf).coerceIn(0.0, 1.0)
    }

    /**
     * 가중치 배율. weight가 0이면 1.0(기능 꺼짐), 1이면 0.25~2.0 범위로 벌어진다.
     * 튜너가 weight를 학습하므로 이 기능이 해로운 환경이면 스스로 0으로 수렴한다.
     */
    fun factor(bssid: String, weight: Double): Double {
        if (weight <= 0.0) return 1.0
        val spread = 0.25 + 1.75 * rarity(bssid)
        return (1.0 - weight) + weight * spread
    }

    companion object {
        val EMPTY = ApIndex(emptyMap(), 0)

        fun build(targets: List<SignatureTarget>): ApIndex {
            val withSig = targets.filter { it.hasSignature }
            if (withSig.isEmpty()) return EMPTY
            val df = HashMap<String, Int>()
            withSig.forEach { m ->
                // 같은 세대 안에서 중복 계산하지 않도록 집합으로 센다.
                m.stableAps().map { it.bssid }.toSet().forEach { b ->
                    df[b] = (df[b] ?: 0) + 1
                }
            }
            return ApIndex(df, withSig.size)
        }
    }
}
