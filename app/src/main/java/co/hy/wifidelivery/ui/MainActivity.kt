package co.hy.wifidelivery.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.Settings
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.model.DeliveryType
import co.hy.wifidelivery.nfc.NfcHelper
import co.hy.wifidelivery.databinding.ActivityMainBinding
import co.hy.wifidelivery.service.DetectionService
import co.hy.wifidelivery.telemetry.TelemetryWorker
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.RouteScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: MemberStore
    private lateinit var settings: Settings
    private lateinit var adapter: MemberAdapter
    private lateinit var pressure: PressureTracker
    private lateinit var route: RouteScope

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "권한이 없으면 동작하지 않습니다: ${denied.joinToString()}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            binding.txtStatus.text = i?.getStringExtra(DetectionService.EXTRA_STATUS) ?: return
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = MemberStore.get(this)
        settings = Settings(this)
        pressure = PressureTracker(this)
        route = RouteScope(this)

        adapter = MemberAdapter(
            onClick = { member -> showMemberActions(member.id, member.name) },
            onLongClick = { member -> confirmDelete(member.id, member.name) }
        )
        binding.listMembers.layoutManager = LinearLayoutManager(this)
        binding.listMembers.adapter = adapter

        binding.inputDwell.setText(settings.dwellSeconds.toString())
        binding.inputInterval.setText(settings.scanIntervalSeconds.toString())
        binding.switchAutoSend.isChecked = settings.autoSend
        binding.switchTestMode.isChecked = settings.testMode

        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnAddMember.setOnClickListener { showAddMemberDialog() }
        binding.btnLiveMonitor.setOnClickListener {
            startActivity(Intent(this, LiveMonitorActivity::class.java))
        }

        binding.switchAutoSend.setOnCheckedChangeListener { _, checked ->
            if (checked) confirmAutoSend() else settings.autoSend = false
        }

        binding.switchTestMode.setOnCheckedChangeListener { view, checked ->
            settings.testMode = checked
            if (!view.isPressed) return@setOnCheckedChangeListener
            // 테스트 모드는 문자를 아예 막는다. 자동 발송과 동시에 켜질 수 없다.
            if (checked && binding.switchAutoSend.isChecked) {
                binding.switchAutoSend.isChecked = false
                settings.autoSend = false
            }
            Toast.makeText(
                this,
                if (checked) "테스트 모드 — 문자를 보내지 않습니다" else "테스트 모드 해제",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRouteStart.setOnClickListener {
            route.reset()
            Toast.makeText(this, "배송 순서를 처음으로 되돌렸습니다", Toast.LENGTH_SHORT).show()
            renderContext()
        }

        binding.btnSetBaseline.setOnClickListener { setBaseline() }

        binding.btnNfc.setOnClickListener {
            if (!NfcHelper.isAvailable(this)) {
                Toast.makeText(this, "이 기기는 NFC를 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, NfcActivity::class.java))
            }
        }

        binding.btnMessages.setOnClickListener {
            startActivity(Intent(this, MessageSettingsActivity::class.java))
        }

        binding.btnZones.setOnClickListener {
            startActivity(Intent(this, ZoneListActivity::class.java))
        }

        binding.btnTuning.setOnClickListener {
            startActivity(Intent(this, TuningActivity::class.java))
        }

        binding.switchDetection.setOnCheckedChangeListener { view, checked ->
            if (!view.isPressed) return@setOnCheckedChangeListener
            if (checked) {
                if (store.withSignature().isEmpty()) {
                    Toast.makeText(this, "먼저 회원 서명을 수집하세요", Toast.LENGTH_SHORT).show()
                    view.isChecked = false
                    return@setOnCheckedChangeListener
                }
                DetectionService.start(this)
            } else {
                DetectionService.stop(this)
                binding.txtStatus.text = "중지됨"
            }
        }

        requestPermissions()
        TelemetryWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        pressure.start()
        adapter.submit(store.all())
        renderContext()
        val filter = IntentFilter(DetectionService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        pressure.stop()
        runCatching { unregisterReceiver(statusReceiver) }
        super.onPause()
    }

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun saveSettings() {
        settings.dwellSeconds =
            binding.inputDwell.text.toString().toIntOrNull()?.coerceIn(10, 600) ?: 90
        settings.scanIntervalSeconds =
            binding.inputInterval.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 30
        binding.inputDwell.setText(settings.dwellSeconds.toString())
        binding.inputInterval.setText(settings.scanIntervalSeconds.toString())
        Toast.makeText(this, "설정 저장", Toast.LENGTH_SHORT).show()
    }

    private fun confirmAutoSend() {
        AlertDialog.Builder(this)
            .setTitle("자동 발송 켜기")
            .setMessage(
                "확인 절차 없이 문자가 즉시 나갑니다.\n" +
                    "인접 세대 오탐이 그대로 오배송 문자가 되므로, " +
                    "충분한 기간 확인 모드로 운영해 정확도를 검증한 뒤 켜세요."
            )
            .setPositiveButton("켜기") { _, _ -> settings.autoSend = true }
            .setNegativeButton("취소") { _, _ ->
                binding.switchAutoSend.isChecked = false
                settings.autoSend = false
            }
            .setCancelable(false)
            .show()
    }

    private fun renderContext() {
        binding.txtContext.text = "${route.describe(store.all())}\n${pressure.describe()}"
    }

    /**
     * 배송 시작 지점(보통 1층 로비)에서 기압 기준을 잡는다.
     *
     * 절대 기압은 날씨로 하루 10hPa씩 움직여서 층 판정에 못 쓴다.
     * 반드시 같은 세션의 기준 대비 상대값으로만 비교해야 하고,
     * 그래서 동에 들어갈 때마다 새로 잡아주는 게 정확하다.
     */
    private fun setBaseline() {
        if (!pressure.isAvailable) {
            Toast.makeText(this, "이 기기에는 기압계가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (pressure.currentHpa == null) {
            Toast.makeText(this, "센서 값을 기다리는 중입니다. 잠시 후 다시.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("기압 기준 설정")
            .setMessage("지금 위치를 기준층으로 잡습니다.\n동 입구나 1층에서 눌러주세요.\n\n6시간 지나면 자동으로 만료됩니다.")
            .setPositiveButton("설정") { _, _ ->
                pressure.setBaseline()
                renderContext()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMemberActions(id: String, name: String) {
        val member = store.get(id) ?: return
        val notifyLabel = if (member.notifyEnabled) "자동 문자 끄기" else "자동 문자 켜기"
        val typeLabel = "배송지 유형 변경 (현재: ${typeName(member.deliveryType)})"

        val advanceLabel = if (member.advanceNoticeEnabled) "방문예정 알림 끄기" else "방문예정 알림 켜기"
        val adLabel = if (member.adConsentAt > 0) "광고 수신동의 철회" else "광고 수신동의 기록"
        val wifiLabel = if (member.wifiAutoDetect) "Wi-Fi 자동 감지 끄기" else "Wi-Fi 자동 감지 켜기"
        val tagLabel = if (member.hasTag) "NFC 태그 해제" else "NFC 태그 등록"

        val actions = buildList {
            if (member.deliveryType == DeliveryType.HOME) {
                if (member.hasSignature) add("서명 확인 / 실시간 대조")
                add(if (member.hasSignature) "다시 수집 (기존 서명에 병합)" else "신호 수집")
                add(wifiLabel)
            }
            if (member.deliveryType == DeliveryType.OFFICE) add("소속 구역 지정")
            add(tagLabel)
            add(notifyLabel)
            add(advanceLabel)
            add(adLabel)
            add(typeLabel)
            add("삭제")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(buildString {
                append(name)
                append("  [${typeName(member.deliveryType)}]")
                if (member.hasTag) append("  NFC")
                if (member.advanceNoticeEnabled) append("  예고")
                if (member.adConsentAt > 0) append("  광고동의")
                if (!member.notifyEnabled) append("  문자 끔")
            })
            .setItems(actions) { _, which ->
                when (val a = actions[which]) {
                    wifiLabel -> toggleWifi(id)
                    advanceLabel -> toggleAdvance(id)
                    adLabel -> toggleAdConsent(id)
                    tagLabel -> if (member.hasTag) unbindTag(id) else registerTag(id)
                    "서명 확인 / 실시간 대조" -> startActivity(
                        Intent(this, SignatureDetailActivity::class.java)
                            .putExtra(SignatureDetailActivity.EXTRA_MEMBER_ID, id)
                    )
                    "소속 구역 지정" -> pickZone(id)
                    notifyLabel -> toggleNotify(id)
                    typeLabel -> pickType(id)
                    "삭제" -> confirmDelete(id, name)
                    else -> if (a.contains("수집")) startActivity(
                        Intent(this, CollectActivity::class.java)
                            .putExtra(CollectActivity.EXTRA_MEMBER_ID, id)
                    )
                }
            }
            .show()
    }

    private fun typeName(t: DeliveryType) = when (t) {
        DeliveryType.HOME -> "아파트·주택"
        DeliveryType.OFFICE -> "사무실"
        DeliveryType.MANUAL -> "수동"
    }

    /**
     * 고객별 자동 문자 on/off.
     * 끄면 감지와 배송 완료 처리는 그대로 되고 문자만 나가지 않는다.
     */
    private fun toggleNotify(id: String) {
        val m = store.get(id) ?: return
        m.notifyEnabled = !m.notifyEnabled
        store.upsert(m)
        Toast.makeText(
            this,
            if (m.notifyEnabled) "${m.name} 자동 문자 켬"
            else "${m.name} 자동 문자 끔 — 배송 완료 처리만 됩니다",
            Toast.LENGTH_SHORT
        ).show()
        adapter.submit(store.all())
    }

    /**
     * NFC와 Wi-Fi는 배타적이지 않다. 둘 다 켜는 조합이 가장 정확하다 —
     * 태그가 "이 물건이 뭔가"를, Wi-Fi가 "지금 어디인가"를 답해 서로를 검증한다.
     */
    private fun toggleWifi(id: String) {
        val m = store.get(id) ?: return
        m.wifiAutoDetect = !m.wifiAutoDetect
        store.upsert(m)
        Toast.makeText(
            this,
            if (m.wifiAutoDetect) "${m.name} Wi-Fi 자동 감지 켬"
            else "${m.name} Wi-Fi 자동 감지 끔 — 서명은 교차 검증에 계속 쓰입니다",
            Toast.LENGTH_SHORT
        ).show()
        adapter.submit(store.all())
    }

    private fun toggleAdvance(id: String) {
        val m = store.get(id) ?: return
        m.advanceNoticeEnabled = !m.advanceNoticeEnabled
        store.upsert(m)
        Toast.makeText(
            this,
            if (m.advanceNoticeEnabled)
                "${m.name} 방문예정 알림 켬 — 받는 문자가 하루 두 통이 됩니다"
            else "${m.name} 방문예정 알림 끔",
            Toast.LENGTH_SHORT
        ).show()
        adapter.submit(store.all())
    }

    /**
     * 광고 수신동의는 배송 알림 동의와 별개다.
     * 앱에서 체크박스 한 번 누르는 것으로 갈음되지 않는다 —
     * 실제 동의 근거(가입서, 통화 녹취 등)를 회사가 보관해야 하고,
     * 여기 기록은 그 사실을 앱이 참조하기 위한 것이다.
     */
    private fun toggleAdConsent(id: String) {
        val m = store.get(id) ?: return
        if (m.adConsentAt > 0) {
            m.adConsentAt = 0L
            store.upsert(m)
            Toast.makeText(this, "${m.name} 광고 수신동의 철회", Toast.LENGTH_SHORT).show()
            adapter.submit(store.all())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("광고 수신동의 기록")
            .setMessage(
                "고객이 광고성 정보 수신에 별도로 동의했음을 기록합니다.\n\n" +
                    "이 체크가 동의를 대신하지 않습니다. 가입서·녹취 등 실제 동의 근거는 " +
                    "회사가 따로 보관해야 하고, 2년마다 재확인해야 합니다.\n\n" +
                    "동의를 받으셨습니까?"
            )
            .setPositiveButton("동의 받음") { _, _ ->
                m.adConsentAt = System.currentTimeMillis()
                store.upsert(m)
                adapter.submit(store.all())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun registerTag(id: String) {
        if (!NfcHelper.isAvailable(this)) {
            Toast.makeText(this, "이 기기는 NFC를 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, NfcActivity::class.java)
                .putExtra(NfcActivity.EXTRA_REGISTER_MEMBER_ID, id)
        )
    }

    private fun unbindTag(id: String) {
        val m = store.get(id) ?: return
        AlertDialog.Builder(this)
            .setTitle("${m.name} 태그 해제")
            .setMessage("태그 연결이 끊깁니다. 스티커는 재등록해서 다시 쓸 수 있습니다.")
            .setPositiveButton("해제") { _, _ ->
                m.nfcTagUid = null
                store.upsert(m)
                adapter.submit(store.all())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun pickType(id: String) {
        val m = store.get(id) ?: return
        val types = DeliveryType.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("배송지 유형")
            .setMessage(
                "아파트·주택: 문앞 서명으로 자동 확정\n" +
                    "사무실: 구역까지만 자동, 자리는 목록에서 선택\n" +
                    "수동: 자동 감지 제외"
            )
            .setItems(types.map { typeName(it) }.toTypedArray()) { _, which ->
                m.deliveryType = types[which]
                if (m.deliveryType != DeliveryType.OFFICE) m.zoneId = null
                store.upsert(m)
                adapter.submit(store.all())
                if (m.deliveryType == DeliveryType.OFFICE) pickZone(id)
            }
            .show()
    }

    private fun pickZone(id: String) {
        val m = store.get(id) ?: return
        val zones = ZoneStore.get(this).all()
        if (zones.isEmpty()) {
            Toast.makeText(this, "먼저 사무실 구역을 등록하세요", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("소속 구역")
            .setItems(zones.map { it.name }.toTypedArray()) { _, which ->
                m.zoneId = zones[which].id
                store.upsert(m)
                adapter.submit(store.all())
            }
            .show()
    }

    private fun showAddMemberDialog() {
        val name = EditText(this).apply { hint = "회원명" }
        val phone = EditText(this).apply {
            hint = "휴대폰 번호"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val address = EditText(this).apply { hint = "주소 (동/호수)" }
        val order = EditText(this).apply {
            hint = "배송 순번 (비우면 자동)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.nextRouteOrder().toString())
        }
        val notify = android.widget.CheckBox(this).apply {
            text = "자동 문자 발송"
            isChecked = true
        }
        val office = android.widget.CheckBox(this).apply {
            text = "사무실 배송 (구역 방식)"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(name); addView(phone); addView(address); addView(order)
            addView(notify); addView(office)
        }

        AlertDialog.Builder(this)
            .setTitle("회원 추가")
            .setView(container)
            .setPositiveButton("추가") { _, _ ->
                val n = name.text.toString().trim()
                val p = phone.text.toString().trim()
                if (n.isEmpty() || p.isEmpty()) {
                    Toast.makeText(this, "이름과 번호는 필수입니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val created = store.addMember(
                    n, p, address.text.toString().trim(),
                    order.text.toString().toIntOrNull() ?: store.nextRouteOrder(),
                    if (office.isChecked) DeliveryType.OFFICE else DeliveryType.HOME,
                    null,
                    notify.isChecked
                )
                adapter.submit(store.all())
                if (office.isChecked) pickZone(created.id)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("$name 삭제")
            .setMessage("서명까지 함께 삭제됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                store.delete(id)
                adapter.submit(store.all())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        val DATE_FMT = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        fun formatTime(ms: Long): String = if (ms <= 0) "-" else DATE_FMT.format(Date(ms))
    }
}
