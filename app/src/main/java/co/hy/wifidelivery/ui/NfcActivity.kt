package co.hy.wifidelivery.ui

import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import co.hy.wifidelivery.data.DeliveryCompleter
import co.hy.wifidelivery.data.LabelStore
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.Settings
import co.hy.wifidelivery.databinding.ActivityNfcBinding
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.Member
import co.hy.wifidelivery.model.Verdict
import co.hy.wifidelivery.nfc.NfcCrossCheck
import co.hy.wifidelivery.nfc.NfcHelper
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.WifiScanner

/**
 * NFC 배송 화면.
 *
 * 등록 모드와 배송 모드를 겸한다. 태그를 대면
 *
 *   등록 모드 — 지정한 회원에 UID를 묶는다
 *   배송 모드 — UID로 회원을 찾아 문자를 보낸다
 *
 * 배송 모드에서는 Wi-Fi 교차 검증을 함께 돌린다.
 * 태그는 물건에 붙어 있고 물건은 옮겨지기 때문이다.
 */
class NfcActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfcBinding
    private lateinit var store: MemberStore
    private lateinit var scanner: WifiScanner
    private lateinit var pressure: PressureTracker
    private lateinit var params: MatchParams

    private val handler = Handler(Looper.getMainLooper())
    private var live: Map<String, Int> = emptyMap()

    /** 비어 있으면 배송 모드, 값이 있으면 그 회원에 태그를 묶는 등록 모드 */
    private var registerForMemberId: String = ""
    private val isRegisterMode: Boolean get() = registerForMemberId.isNotEmpty()

    private var lastUid: String? = null
    private var lastUidAt: Long = 0L

    private val scanTick = object : Runnable {
        override fun run() {
            scanner.requestScan()
            scanner.currentResults().takeIf { it.isNotEmpty() }?.let {
                live = WifiScanner.toRssiMap(it)
            }
            renderStatus()
            handler.postDelayed(this, 4000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = MemberStore.get(this)
        scanner = WifiScanner(this)
        pressure = PressureTracker(this)
        params = MatchParams.load(this)
        registerForMemberId = intent.getStringExtra(EXTRA_REGISTER_MEMBER_ID).orEmpty()

        title = if (isRegisterMode) "NFC 태그 등록" else "NFC 배송"
        binding.btnClose.setOnClickListener { finish() }

        // 앱이 꺼져 있을 때 태그를 대서 실행된 경우
        NfcHelper.tagFrom(intent)?.let { handleTag(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NfcHelper.tagFrom(intent)?.let { handleTag(it) }
    }

    override fun onResume() {
        super.onResume()
        params = MatchParams.load(this)
        pressure.start()
        handler.post(scanTick)
        NfcHelper.enableReader(this) { tag -> runOnUiThread { handleTag(tag) } }
        renderStatus()
    }

    override fun onPause() {
        NfcHelper.disableReader(this)
        handler.removeCallbacksAndMessages(null)
        pressure.stop()
        super.onPause()
    }

    private fun renderStatus() {
        binding.txtPrompt.text = when {
            !NfcHelper.isAvailable(this) -> "이 기기는 NFC를 지원하지 않습니다"
            !NfcHelper.isEnabled(this) -> "NFC가 꺼져 있습니다.\n설정에서 켜주세요."
            isRegisterMode -> {
                val m = store.get(registerForMemberId)
                "${m?.name ?: ""} 님의 태그를\n바구니에 붙인 뒤 대주세요"
            }
            else -> "바구니의 태그에 폰을 대주세요"
        }
        binding.txtDetail.text = "관측 AP ${live.size}개 · ${pressure.describe()}"
    }

    private fun handleTag(tag: Tag) {
        val uid = NfcHelper.uidOf(tag)

        // 태그를 대고 있으면 이벤트가 연속으로 들어온다. 짧은 중복은 무시.
        val now = System.currentTimeMillis()
        if (uid == lastUid && now - lastUidAt < 3000) return
        lastUid = uid
        lastUidAt = now

        if (isRegisterMode) registerTag(uid) else deliverByTag(uid)
    }

    private fun registerTag(uid: String) {
        val member = store.get(registerForMemberId) ?: return
        val existing = store.tagOwner(uid, excludeId = member.id)

        if (existing != null) {
            AlertDialog.Builder(this)
                .setTitle("이미 등록된 태그")
                .setMessage("이 태그는 ${existing.name} 님에게 등록되어 있습니다.\n${member.name} 님으로 옮길까요?")
                .setPositiveButton("옮기기") { _, _ ->
                    existing.nfcTagUid = null
                    store.upsert(existing)
                    bind(member, uid)
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        bind(member, uid)
    }

    private fun bind(member: Member, uid: String) {
        member.nfcTagUid = uid
        store.upsert(member)
        binding.txtPrompt.text = "${member.name}\n태그 등록 완료"
        binding.txtDetail.text = uid
        handler.postDelayed({ finish() }, 900)
    }

    private fun deliverByTag(uid: String) {
        val member = store.findByTag(uid)
        if (member == null) {
            binding.txtPrompt.text = "등록되지 않은 태그입니다"
            binding.txtDetail.text = uid
            return
        }

        val verdict = NfcCrossCheck.check(
            member, live, store.crossCheckTargets(), params, pressure.currentIndex()
        )

        // 태그를 찍은 순간의 스캔은 사람 손을 거치지 않은 정답 라벨이다.
        // 위치가 일치할 때만 남긴다 — 어긋난 건을 정답으로 넣으면 학습이 망가진다.
        if (verdict is NfcCrossCheck.Verdict.Agrees && live.isNotEmpty()) {
            LabelStore.get(this).add(
                member.id, member.id, Verdict.CORRECT, live, params, pressure.currentIndex()
            )
        }

        when (verdict) {
            is NfcCrossCheck.Verdict.Conflicts -> confirmConflict(member, verdict)
            else -> send(member, verdict)
        }
    }

    /**
     * 태그와 위치가 어긋나면 그냥 보내지 않는다.
     * 바구니가 옮겨졌을 수 있고, 그대로 발송하면 오배송 문자가 된다.
     */
    private fun confirmConflict(member: Member, v: NfcCrossCheck.Verdict.Conflicts) {
        EventQueue.get(this).enqueue(
            Events.tagLocationConflict(this, member.id, v.score, v.betterMatch != null)
        )
        AlertDialog.Builder(this)
            .setTitle("위치가 맞지 않습니다")
            .setMessage(
                buildString {
                    append("태그는 ${member.name} 님인데 현재 위치의 Wi-Fi 신호가 다릅니다.")
                    v.betterMatch?.let { append("\n\n이 위치는 ${it} 님 쪽에 더 가깝습니다.") }
                    append("\n\n바구니가 옮겨졌을 수 있습니다. 그대로 보낼까요?")
                }
            )
            .setPositiveButton("${member.name}에게 발송") { _, _ -> send(member, v) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun send(member: Member, verdict: NfcCrossCheck.Verdict) {
        val result = DeliveryCompleter.complete(this, member, "nfc")
        binding.txtPrompt.text = DeliveryCompleter.message(member, result)
        binding.txtDetail.text = when (verdict) {
            is NfcCrossCheck.Verdict.Agrees -> "위치 확인됨 (%.2f)".format(verdict.score)
            is NfcCrossCheck.Verdict.Conflicts -> "위치 불일치 상태로 발송"
            NfcCrossCheck.Verdict.Unavailable -> "위치 검증 없음 (서명 미수집)"
        }
    }

    companion object {
        const val EXTRA_REGISTER_MEMBER_ID = "register_member_id"
    }
}
