package co.hy.wifidelivery.wifi

import android.content.Context
import co.hy.wifidelivery.model.Member

/**
 * 배송 순서로 후보를 좁힌다.
 *
 * 정확도 개선책 중 가장 값싸다. 하드웨어도, 추가 수집도, 알고리즘 변경도 없다.
 * FM 배송은 경로가 정해져 있으니 지금 위치가 200세대 어디든 될 수 있는 게 아니라
 * "직전에 끝낸 집 다음 서너 곳" 중 하나다.
 *
 * 후보가 200개에서 4개로 줄면 2등 점수가 통째로 낮아져 마진이 저절로 벌어진다.
 * 점수 함수를 아무리 다듬어도 이만큼 벌기는 어렵다.
 *
 * 커서는 배송이 확정될 때마다 전진하고, 순서를 건너뛰거나 되돌아가는
 * 실제 상황을 감안해 앞뒤로 여유를 둔다.
 */
class RouteScope(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 기능 사용 여부. 경로가 지정되지 않았으면 켜도 자동으로 무시된다. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** 커서 앞쪽으로 몇 곳까지 후보에 넣을지 */
    var lookAhead: Int
        get() = prefs.getInt("lookAhead", 4)
        set(v) = prefs.edit().putInt("lookAhead", v).apply()

    /** 커서 뒤쪽으로 몇 곳까지 (되돌아가는 경우 대비) */
    var lookBehind: Int
        get() = prefs.getInt("lookBehind", 1)
        set(v) = prefs.edit().putInt("lookBehind", v).apply()

    /** 현재 진행 위치(routeOrder 기준). 0이면 아직 시작 안 함. */
    var cursor: Int
        get() = prefs.getInt("cursor", 0)
        set(v) = prefs.edit().putInt("cursor", v).apply()

    fun reset() {
        prefs.edit().putInt("cursor", 0).apply()
    }

    /** 배송 완료 시 호출 — 커서를 그 집 순번으로 옮긴다 */
    fun advanceTo(member: Member) {
        if (member.routeOrder > 0) cursor = member.routeOrder
    }

    /**
     * 후보 목록을 좁힌다.
     *
     * 순번이 하나도 지정되지 않았으면 전체를 그대로 돌려준다.
     * 순번이 없는 세대는 항상 후보에 남긴다 — 경로 등록이 덜 된 상태에서
     * 조용히 누락되는 게 제일 나쁘다.
     */
    fun scope(members: List<Member>): List<Member> {
        if (!enabled) return members
        val ordered = members.filter { it.routeOrder > 0 }
        if (ordered.isEmpty()) return members

        val lo = cursor - lookBehind
        val hi = cursor + lookAhead

        val scoped = members.filter {
            it.routeOrder == 0 || (it.routeOrder in lo..hi)
        }
        // 좁힌 결과가 비면 안전하게 전체로 되돌린다.
        return scoped.ifEmpty { members }
    }

    fun describe(members: List<Member>): String {
        val ordered = members.count { it.routeOrder > 0 }
        return when {
            !enabled -> "경로 좁히기 꺼짐"
            ordered == 0 -> "순번 미지정 — 전체 비교"
            else -> "순번 %d/%d · 후보 %d곳".format(
                cursor, ordered, scope(members).size
            )
        }
    }

    companion object {
        private const val PREFS = "route_scope"
    }
}
