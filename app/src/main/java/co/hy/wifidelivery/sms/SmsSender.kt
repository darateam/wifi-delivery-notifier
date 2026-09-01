package co.hy.wifidelivery.sms

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import co.hy.wifidelivery.data.DeliveryCompleter
import co.hy.wifidelivery.data.MemberStore
import co.hy.wifidelivery.telemetry.EventQueue
import co.hy.wifidelivery.telemetry.Events

object SmsSender {

    private const val TAG = "SmsSender"

    fun canSend(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 단말 SIM으로 직접 발송한다.
     *
     * 사내 배포(APK 직접 설치 / MDM) 전제다. SEND_SMS 권한을 쓰는 앱은
     * Play 스토어 등록 제한이 크고, 발신번호가 담당자 개인 번호로 찍힌다.
     * 대표번호로 나가야 한다면 이 함수를 사내 문자발송 플랫폼 API 호출로
     * 갈아끼우는 게 맞다 — 호출부는 그대로 두면 된다.
     */
    fun send(context: Context, phone: String, body: String): Boolean {
        if (!canSend(context)) {
            Log.w(TAG, "SEND_SMS 권한 없음")
            return false
        }
        return runCatching {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(body)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                manager.sendTextMessage(phone, null, body, null, null)
            }
            true
        }.onFailure { Log.e(TAG, "발송 실패", it) }.getOrDefault(false)
    }
}

/**
 * 확인 모드에서 알림을 탭했을 때 실제 발송을 수행한다.
 * 자동 발송을 기본값으로 두지 않는 이유는 단순하다 — 나간 문자는 못 되돌린다.
 */
class SendSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val memberId = intent.getStringExtra(EXTRA_MEMBER_ID) ?: return
        val store = MemberStore.get(context)
        val member = store.get(memberId) ?: return

        val result = DeliveryCompleter.complete(context, member, "confirm")

        context.getSystemService(NotificationManager::class.java)
            ?.cancel(memberId.hashCode())

        Toast.makeText(context, DeliveryCompleter.message(member, result), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MEMBER_ID = "member_id"
    }
}
