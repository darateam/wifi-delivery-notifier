package co.hy.wifidelivery.telemetry

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.hy.wifidelivery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 수집 엔드포인트로 배치 전송.
 *
 * 앱이 BigQuery에 직접 쓰지 않는다. 그러려면 서비스 계정 키를 APK에 넣어야 하는데,
 * APK는 언제든 뜯긴다. 키 하나 유출되면 웨어하우스 쓰기 권한이 통째로 나간다.
 * 단말은 HTTPS 엔드포인트로만 보내고, 적재는 서버가 한다.
 */
object Uploader {

    private const val TAG = "Uploader"

    suspend fun flush(context: Context): Boolean = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.INGEST_ENDPOINT
        if (endpoint.isBlank()) return@withContext true

        val queue = EventQueue.get(context)
        var guard = 0
        while (guard++ < 20) {
            val batch = queue.peekBatch()
            if (batch.isEmpty()) return@withContext true
            if (!post(endpoint, batch)) return@withContext false
            queue.commit(batch.size)
        }
        true
    }

    private fun post(endpoint: String, lines: List<String>): Boolean = runCatching {
        val body = """{"events":[${lines.joinToString(",")}]}"""
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (BuildConfig.INGEST_API_KEY.isNotBlank()) {
                setRequestProperty("X-Api-Key", BuildConfig.INGEST_API_KEY)
            }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        // 4xx는 재시도해도 안 되는 요청이라 큐를 비워야 무한 적체를 막는다.
        code in 200..299 || code in 400..499
    }.onFailure { Log.w(TAG, "전송 실패", it) }.getOrDefault(false)
}

class TelemetryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (Uploader.flush(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "telemetry_flush"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TelemetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
