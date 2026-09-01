package co.hy.wifidelivery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import co.hy.wifidelivery.data.DeliveryCompleter
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.data.ZoneStore
import co.hy.wifidelivery.data.Settings
import co.hy.wifidelivery.model.MatchParams
import co.hy.wifidelivery.model.SignatureTarget
import co.hy.wifidelivery.sms.SendSmsReceiver
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events
import co.hy.wifidelivery.telemetry.TelemetryWorker
import co.hy.wifidelivery.sms.SmsSender
import co.hy.wifidelivery.ui.DisambiguationActivity
import co.hy.wifidelivery.ui.LabelActivity
import co.hy.wifidelivery.ui.ZoneDeliveryActivity
import co.hy.wifidelivery.ui.MainActivity
import co.hy.wifidelivery.wifi.ApIndex
import co.hy.wifidelivery.wifi.DwellTracker
import co.hy.wifidelivery.wifi.PressureTracker
import co.hy.wifidelivery.wifi.RouteScope
import co.hy.wifidelivery.wifi.SignatureMatcher
import co.hy.wifidelivery.wifi.WifiScanner

/**
 * 운영 모드.
 *
 * 반드시 포그라운드 서비스여야 한다. 백그라운드 앱은 전체 합산 30분에 1회로
 * 스캔이 제한되기 때문에, 일반 백그라운드 작업으로 만들면 체류 감지가 원리상 불가능하다.
 * 상단 알림이 상주하는 건 회피할 방법이 없고, 회피하려 해서도 안 된다 —
 * 근무 중 위치가 계속 수집되고 있다는 사실이 당사자에게 보여야 한다.
 */
class DetectionService : Service() {

    private lateinit var scanner: WifiScanner
    private lateinit var store: MemberStore
    private lateinit var zones: ZoneStore
    private lateinit var settings: Settings
    private val tracker = DwellTracker()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var params: MatchParams
    private lateinit var pressure: PressureTracker
    private lateinit var route: RouteScope
    private var apIndex: ApIndex = ApIndex.EMPTY
    private var indexStamp: Long = 0L
    private var running = false
    private var statusText = "탐지 시작 중"

    private val tick = object : Runnable {
        override fun run() {
            evaluateOnce()
            if (running) {
                handler.postDelayed(this, settings.scanIntervalSeconds * 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scanner = WifiScanner(this)
        store = MemberStore.get(this)
        zones = ZoneStore.get(this)
        settings = Settings(this)
        params = MatchParams.load(this)
        pressure = PressureTracker(this)
        route = RouteScope(this)
        createChannels()
        TelemetryWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            params = MatchParams.load(this)
            pressure.start()
            startInForeground()
            scanner.registerScanReceiver { evaluateOnce() }
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scanner.unregisterScanReceiver()
        pressure.stop()
        super.onDestroy()
    }

    // ---- 핵심 루프 ----

    private fun evaluateOnce() {
        if (!running) return
        scanner.requestScan()

        val results = scanner.freshResults()
        if (results == null) {
            // 스로틀링에 걸렸거나 아직 새 결과가 없다. 후보 상태는 유지한다.
            updateStatus(statusText)
            return
        }

        val live = WifiScanner.toRssiMap(results)

        // 자동 확정 대상은 아파트·주택 고객뿐이다.
        // 사무실 고객은 책상 단위라 여기 넣으면 옆자리 오배송만 만든다.
        val homes = store.autoDetectable()
        val zoneList = zones.withSignature()
        if (homes.isEmpty() && zoneList.isEmpty()) {
            updateStatus("등록된 서명 없음")
            return
        }

        // 배송 순서로 회원 후보를 좁힌다. 200곳 → 서너 곳이면 마진이 저절로 벌어진다.
        // 구역은 순서와 무관하므로 항상 전부 후보에 남긴다.
        val targets: List<SignatureTarget> = route.scope(homes) + zoneList

        // IDF 지수는 서명이 바뀔 때만 다시 만든다.
        val newest = maxOf(
            homes.maxOfOrNull { it.updatedAt } ?: 0L,
            zoneList.maxOfOrNull { it.updatedAt } ?: 0L
        )
        if (newest != indexStamp) {
            apIndex = ApIndex.build(homes + zoneList)
            indexStamp = newest
        }

        val decision = SignatureMatcher.evaluate(
            targets, live, params, apIndex, pressure.currentIndex()
        )
        val dwellMillis = settings.dwellSeconds * 1000L
        val outcome = tracker.update(decision, dwellMillis)

        // 확정 여부와 무관하게 매 평가를 남긴다. 보류된 건이 오히려
        // 임계값을 어디에 둘지 판단하는 데 제일 중요한 데이터다.
        EventQueue.get(this).enqueue(
            Events.matchEvaluated(this, decision, live.size, outcome !is DwellTracker.Outcome.None)
        )

        updateStatus(tracker.lastReason)

        // 구분 불가 상태로 체류하면 조용히 넘기지 말고 사람에게 묻는다.
        if (outcome is DwellTracker.Outcome.Ambiguous) {
            if (settings.testMode) {
                notifyLabel("구분 필요")
                updateStatus("[테스트] 구분 불가 — 정답 기록 대기")
            } else {
                notifyDisambiguation(outcome.candidates)
                updateStatus("구분 불가 — 사용자 확인 대기")
            }
            tracker.reset()
            return
        }

        val firedId = (outcome as? DwellTracker.Outcome.Confirmed)?.targetId ?: return

        // 구역이 잡힌 경우 — 자리는 사람이 고른다.
        val zone = zones.get(firedId)
        if (zone != null) {
            if (settings.testMode) {
                notifyLabel(zone.name)
                updateStatus("[테스트] ${zone.name} 감지")
            } else {
                notifyZone(zone.id, zone.name)
                updateStatus("${zone.name} 진입 — 자리 선택 대기")
            }
            tracker.reset()
            return
        }

        val member = store.get(firedId) ?: return

        // 테스트 모드에서는 문자를 절대 보내지 않는다.
        if (settings.testMode) {
            notifyLabel(member.name)
            updateStatus("[테스트] ${member.name} 감지 — 정답 기록 대기")
            tracker.reset()
            return
        }

        val cooldown = settings.cooldownHours * 3600_000L
        if (System.currentTimeMillis() - member.lastNotifiedAt < cooldown) {
            updateStatus("${member.name} 발송 이력 있음 (쿨다운)")
            tracker.reset()
            return
        }

        if (settings.autoSend) {
            val result = DeliveryCompleter.complete(this, member, "auto")
            updateStatus(DeliveryCompleter.message(member, result))
        } else {
            notifyConfirm(firedId, member.name, member.notifyEnabled)
            updateStatus("${member.name} 확인 대기")
        }
        tracker.reset()
    }

    // ---- 알림 ----

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "탐지 상태", NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONFIRM, "발송 확인", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun buildStatusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setContentTitle(if (settings.testMode) "테스트 모드 (문자 발송 안 함)" else "배송 알림 탐지 중")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "중지", stop).build()
            )
            .build()
    }

    private fun startInForeground() {
        val n = buildStatusNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    private fun updateStatus(text: String) {
        statusText = text
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_STATUS, buildStatusNotification(text))
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
        )
    }

    private fun notifyZone(zoneId: String, name: String) {
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(this, ZoneDeliveryActivity::class.java)
                .putExtra(ZoneDeliveryActivity.EXTRA_ZONE_ID, zoneId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_CONFIRM)
            .setContentTitle("$name 진입")
            .setContentText("탭해서 배송한 자리를 선택하세요")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ZONE, n)
    }

    private fun notifyDisambiguation(ids: List<String>) {
        val names = ids.mapNotNull { store.get(it)?.name ?: zones.get(it)?.name }
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, DisambiguationActivity::class.java)
                .putExtra(DisambiguationActivity.EXTRA_MEMBER_IDS, ids.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_CONFIRM)
            .setContentTitle("어느 집인가요?")
            .setContentText(names.joinToString(" / ") + " 구분 안 됨 — 탭해서 선택")
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_AMBIGUOUS, n)
    }

    /** 테스트 모드 전용 — 탭하면 라벨링 화면이 열린다 */
    private fun notifyLabel(name: String) {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, LabelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_CONFIRM)
            .setContentTitle("[테스트] $name 감지")
            .setContentText("맞는지 눌러서 정답을 기록하세요")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_LABEL, n)
    }

    private fun notifyConfirm(memberId: String, name: String, notifyEnabled: Boolean) {
        val send = PendingIntent.getBroadcast(
            this, memberId.hashCode(),
            Intent(this, SendSmsReceiver::class.java)
                .setPackage(packageName)
                .putExtra(SendSmsReceiver.EXTRA_MEMBER_ID, memberId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_CONFIRM)
            .setContentTitle("$name 배송 완료?")
            .setContentText(
                if (notifyEnabled) "탭하면 배송 완료 문자를 발송합니다"
                else "문자 미수신 고객 — 탭하면 완료 처리만 합니다"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(send)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(memberId.hashCode(), n)
    }

    companion object {
        private const val CHANNEL_STATUS = "detection_status"
        private const val CHANNEL_CONFIRM = "send_confirm"
        private const val NOTIF_STATUS = 1001
        private const val NOTIF_LABEL = 1002
        private const val NOTIF_AMBIGUOUS = 1003
        private const val NOTIF_ZONE = 1004

        const val ACTION_STOP = "co.hy.wifidelivery.STOP"
        const val ACTION_STATUS = "co.hy.wifidelivery.STATUS"
        const val EXTRA_STATUS = "status"

        fun start(context: Context) {
            val i = Intent(context, DetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DetectionService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
