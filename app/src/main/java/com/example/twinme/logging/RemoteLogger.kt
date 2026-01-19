package com.example.twinme.logging

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.twinme.auth.AuthManager
import com.example.twinme.data.CallAcceptState
import com.example.twinme.data.SettingsManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TwinMe 원격 로거
 * - 배치 로깅: 수락 과정 중에는 버퍼에 누적, 완료/실패 후 한 번에 전송
 * - 인증, 설정 변경, 콜 수락 상태 로깅
 */
object RemoteLogger {

    private const val TAG = "RemoteLogger"
    private const val BASE_URL = "https://mediaenhanced-v10-production-011.up.railway.app"
    private const val LOG_ENDPOINT = "/api/twinme/logs"
    private const val MAX_BUFFER_SIZE = 100

    // SharedPreferences for last state tracking
    private const val PREF_NAME = "last_state"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_LAST_DETAILS = "details"

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var context: Context? = null
    private var isEnabled = false  // ⚠️ DEBUG: Remote logging disabled
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 배치 로깅용 버퍼 ============

    private val logBuffer = mutableListOf<LogEntry>()
    private val bufferLock = Any()
    private var sessionId: String = UUID.randomUUID().toString()

    data class LogEntry(
        val eventType: String,
        val detail: Map<String, Any>,
        val timestamp: String
    )

    /**
     * 초기화
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * 로깅 활성화/비활성화
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    // ============ 로그 이벤트 타입 ============

    enum class EventType {
        AUTH,               // 인증 시도/성공/실패
        CONFIG_CHANGE,      // 설정 변경
        STATE_CHANGE,       // 상태 전환
        NODE_CLICK,         // 버튼 클릭
        CALL_RESULT,        // 최종 결과
        APP_START,          // 앱 시작
        APP_STOP,           // 앱 종료
        ERROR,              // 에러
        // 배치 로깅용 새 이벤트
        CALL_LIST_DETECTED, // 콜 리스트 화면 감지
        CALL_PARSED,        // 개별 콜 파싱 결과
        ACCEPT_STEP,        // 수락 단계 진행
        REFRESH_ATTEMPT,    // 새로고침 시도 (추가)
        BATCH_LOG           // 배치 로그 전송
    }

    // ============ 인증 로깅 ============

    fun logAuth(
        success: Boolean,
        identifier: String,
        userType: String? = null,
        message: String? = null
    ) {
        sendLog(
            eventType = EventType.AUTH,
            detail = mapOf(
                "success" to success,
                "identifier_masked" to maskIdentifier(identifier),
                "user_type" to (userType ?: ""),
                "message" to (message ?: "")
            )
        )
    }

    // ============ 설정 변경 로깅 ============

    fun logConfigChange(
        configType: String,
        beforeValue: Any?,
        afterValue: Any?
    ) {
        val ctx = context ?: return
        val settings = SettingsManager.getInstance(ctx)

        sendLog(
            eventType = EventType.CONFIG_CHANGE,
            detail = mapOf(
                "config_type" to configType,
                "before" to beforeValue.toString(),
                "after" to afterValue.toString()
            ),
            contextInfo = buildConfigContext(settings)
        )
    }

    // ============ 상태 변경 로깅 ============

    fun logStateChange(
        fromState: CallAcceptState,
        toState: CallAcceptState,
        reason: String,
        elapsedMs: Long? = null
    ) {
        val ctx = context ?: return
        val settings = SettingsManager.getInstance(ctx)

        sendLog(
            eventType = EventType.STATE_CHANGE,
            detail = mapOf(
                "from_state" to fromState.name,
                "to_state" to toState.name,
                "reason" to reason,
                "elapsed_ms" to (elapsedMs ?: 0)
            ),
            contextInfo = buildConfigContext(settings)
        )
    }

    // ============ 노드 클릭 로깅 ============

    fun logNodeClick(
        nodeId: String,
        success: Boolean,
        currentState: CallAcceptState,
        elapsedMs: Long? = null
    ) {
        val ctx = context ?: return
        val settings = SettingsManager.getInstance(ctx)

        sendLog(
            eventType = EventType.NODE_CLICK,
            detail = mapOf(
                "node_id" to nodeId,
                "success" to success,
                "current_state" to currentState.name,
                "elapsed_ms" to (elapsedMs ?: 0)
            ),
            contextInfo = buildConfigContext(settings)
        )
    }

    // ============ 콜 결과 로깅 ============

    fun logCallResult(
        success: Boolean,
        finalState: CallAcceptState,
        totalElapsedMs: Long,
        errorReason: String? = null
    ) {
        val ctx = context ?: return
        val settings = SettingsManager.getInstance(ctx)

        sendLog(
            eventType = EventType.CALL_RESULT,
            detail = mapOf(
                "success" to success,
                "final_state" to finalState.name,
                "total_elapsed_ms" to totalElapsedMs,
                "error_reason" to (errorReason ?: "")
            ),
            contextInfo = buildConfigContext(settings)
        )
    }

    // ============ 앱 라이프사이클 로깅 ============

    fun logAppStart() {
        sendLog(
            eventType = EventType.APP_START,
            detail = mapOf(
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    fun logAppStop() {
        sendLog(
            eventType = EventType.APP_STOP,
            detail = mapOf(
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    // ============ 에러 로깅 ============

    fun logError(
        errorType: String,
        message: String,
        stackTrace: String? = null
    ) {
        sendLog(
            eventType = EventType.ERROR,
            detail = mapOf(
                "error_type" to errorType,
                "message" to message,
                "stack_trace" to (stackTrace ?: "")
            )
        )
    }

    // ============ 배치 로깅 (버퍼에 추가, 즉시 전송 안 함) ============

    /**
     * 콜 리스트 화면 감지 로그 (버퍼에 추가)
     */
    fun logCallListDetected(
        screenDetected: Boolean,
        containerType: String,
        itemCount: Int,
        parsedCount: Int
    ) {
        addToBuffer(
            EventType.CALL_LIST_DETECTED,
            mapOf(
                "screen_detected" to screenDetected,
                "container_type" to containerType,
                "item_count" to itemCount,
                "parsed_count" to parsedCount
            )
        )
    }

    /**
     * 개별 콜 파싱 결과 로그 (버퍼에 추가)
     * Phase 1: confidence, debugInfo 추가
     */
    fun logCallParsed(
        index: Int,
        source: String,
        destination: String,
        price: Int,
        callType: String,
        reservationTime: String,
        eligible: Boolean,
        rejectReason: String?,
        confidence: String = "UNKNOWN",
        debugInfo: Map<String, Any> = emptyMap()
    ) {
        // debugInfo를 JSON 변환 가능한 맵으로 필터링
        val filteredDebugInfo = debugInfo.mapValues { entry ->
            when (val value = entry.value) {
                is String, is Number, is Boolean -> value
                is List<*> -> value.joinToString(", ")
                else -> value.toString()
            }
        }

        addToBuffer(
            EventType.CALL_PARSED,
            mapOf(
                "index" to index,
                "source" to source,
                "destination" to destination,
                "price" to price,
                "callType" to callType,
                "reservationTime" to reservationTime,
                "eligible" to eligible,
                "reject_reason" to (rejectReason ?: ""),
                "confidence" to confidence,  // Phase 1: 파싱 신뢰도
                "debug_info" to filteredDebugInfo  // Phase 1: 디버깅 정보
            )
        )
    }

    /**
     * 수락 단계 진행 로그 (버퍼에 추가)
     */
    fun logAcceptStep(
        step: Int,
        stepName: String,
        targetId: String,
        buttonFound: Boolean,
        clickSuccess: Boolean,
        elapsedMs: Long
    ) {
        addToBuffer(
            EventType.ACCEPT_STEP,
            mapOf(
                "step" to step,
                "step_name" to stepName,
                "target_id" to targetId,
                "button_found" to buttonFound,
                "click_success" to clickSuccess,
                "elapsed_ms" to elapsedMs
            )
        )
    }

    /**
     * 새로고침 시도 로그 (즉시 전송)
     */
    fun logRefreshAttempt(
        buttonFound: Boolean,
        clickSuccess: Boolean,
        elapsedSinceLastRefresh: Long,
        targetDelay: Long
    ) {
        sendLog(
            eventType = EventType.REFRESH_ATTEMPT,
            detail = mapOf(
                "button_found" to buttonFound,
                "click_success" to clickSuccess,
                "elapsed_since_last_ms" to elapsedSinceLastRefresh,
                "target_delay_ms" to targetDelay,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * 파싱 실패 로그 (버퍼에 추가)
     * - 필수 필드 누락으로 콜 파싱이 실패한 경우
     */
    fun logParsingFailed(
        index: Int,
        missingFields: List<String>,
        collectedText: String,
        reason: String
    ) {
        addToBuffer(
            EventType.CALL_PARSED,
            mapOf(
                "index" to index,
                "parsing_success" to false,
                "missing_fields" to missingFields.joinToString(", "),
                "collected_text" to collectedText,
                "failure_reason" to reason
            )
        )
    }

    /**
     * 버튼 검색 실패 로그 (버퍼에 추가)
     * - 특정 viewId를 가진 버튼을 찾지 못한 경우
     */
    fun logButtonSearchFailed(
        currentState: CallAcceptState,
        targetViewId: String,
        searchDepth: Int,
        nodeDescription: String
    ) {
        addToBuffer(
            EventType.ACCEPT_STEP,
            mapOf(
                "current_state" to currentState.name,
                "button_found" to false,
                "target_view_id" to targetViewId,
                "search_depth" to searchDepth,
                "node_description" to nodeDescription,
                "failure_reason" to "Button not found in accessibility tree"
            )
        )
    }

    /**
     * 버퍼에 로그 추가 (전송 안 함)
     */
    private fun addToBuffer(eventType: EventType, detail: Map<String, Any>) {
        if (!isEnabled) return

        synchronized(bufferLock) {
            logBuffer.add(
                LogEntry(
                    eventType = eventType.name,
                    detail = detail,
                    timestamp = dateFormat.format(Date())
                )
            )
            Log.d(TAG, "버퍼에 추가: ${eventType.name} (버퍼 크기: ${logBuffer.size})")

            // 버퍼 초과 시 자동 flush
            if (logBuffer.size >= MAX_BUFFER_SIZE) {
                Log.d(TAG, "버퍼 초과, 자동 flush")
                flushLogsInternal()
            }
        }
    }

    /**
     * 버퍼의 모든 로그를 한 번에 전송
     */
    fun flushLogs() {
        synchronized(bufferLock) {
            flushLogsInternal()
        }
    }

    /**
     * 버퍼의 모든 로그를 비동기로 전송
     */
    fun flushLogsAsync() {
        if (!isEnabled) return

        logScope.launch {
            try {
                withTimeout(5000) {  // 5초 타임아웃
                    synchronized(bufferLock) {
                        flushLogsInternal()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "로그 flush 타임아웃")
            } catch (e: Exception) {
                Log.e(TAG, "로그 flush 실패: ${e.message}")
            }
        }
    }

    /**
     * 새 세션 시작 (버퍼 초기화 + 새 세션 ID)
     */
    fun startNewSession() {
        synchronized(bufferLock) {
            logBuffer.clear()
            sessionId = UUID.randomUUID().toString()
            Log.d(TAG, "새 세션 시작: $sessionId")
        }
    }

    /**
     * 동기식 에러 로깅 (프로세스 종료 직전에 사용)
     * 비동기가 아닌 동기식으로 즉시 전송하여 프로세스 종료 전에 완료 보장
     */
    fun logErrorSync(errorType: String, message: String, stackTrace: String?) {
        if (!isEnabled) return

        val logData = createLogDataSync(
            eventType = EventType.ERROR,
            detail = mapOf(
                "error_type" to errorType,
                "message" to message,
                "stack_trace" to (stackTrace ?: "")
            )
        )

        sendLogSynchronously(logData)
    }

    /**
     * 마지막 상태를 SharedPreferences에 기록 (동기식)
     * 프로세스가 갑자기 종료되어도 다음 시작 시 복구 가능
     */
    fun recordLastState(event: String, details: String) {
        try {
            val prefs = context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
            prefs.edit()
                .putString(KEY_LAST_EVENT, event)
                .putString(KEY_LAST_TIMESTAMP, System.currentTimeMillis().toString())
                .putString(KEY_LAST_DETAILS, details)
                .commit()  // apply() 아님 - 즉시 디스크에 저장
            Log.d(TAG, "마지막 상태 기록: $event")
        } catch (e: Exception) {
            Log.e(TAG, "상태 저장 실패", e)
        }
    }

    /**
     * 앱 시작 시 이전 세션의 마지막 상태 전송
     * 이전 세션이 비정상 종료된 경우 원인 추적에 유용
     */
    fun sendPendingStateOnStartup() {
        try {
            val prefs = context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
            val lastEvent = prefs.getString(KEY_LAST_EVENT, null) ?: return
            val lastTimestamp = prefs.getString(KEY_LAST_TIMESTAMP, null) ?: return
            val details = prefs.getString(KEY_LAST_DETAILS, "") ?: ""

            Log.d(TAG, "이전 세션 마지막 상태 발견: $lastEvent")

            // 이전 세션의 마지막 상태 전송
            logError(
                errorType = "RECOVERED_LAST_STATE",
                message = "이전 세션 마지막 상태: $lastEvent (timestamp: $lastTimestamp)",
                stackTrace = details
            )

            // 전송 후 삭제
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "이전 상태 전송 실패", e)
        }
    }

    /**
     * 동기식으로 로그 전송 (프로세스 종료 직전 사용)
     */
    private fun sendLogSynchronously(logData: Map<String, Any>) {
        try {
            val json = gson.toJson(logData)
            Log.d(TAG, "동기식 로그 전송: $json")

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL$LOG_ENDPOINT")
                .post(body)
                .build()

            // execute()는 동기식 - 응답을 기다림
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "동기식 로그 전송 성공")
                } else {
                    Log.w(TAG, "동기식 로그 전송 실패: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "동기식 전송 오류: ${e.message}")
        }
    }

    /**
     * 동기식 로그 데이터 생성 (sendLogSynchronously용)
     */
    private fun createLogDataSync(eventType: EventType, detail: Map<String, Any>): Map<String, Any> {
        val ctx = context ?: return emptyMap()

        try {
            val authManager = AuthManager.getInstance(ctx)
            val phoneNumber = authManager.getPhoneNumber() ?: ""
            val deviceId = authManager.getDeviceId()

            val detailWithTimestamp = detail.toMutableMap()
            detailWithTimestamp["event_timestamp"] = dateFormat.format(Date())

            return mutableMapOf(
                "app_name" to "Vortex",
                "app_version" to getAppVersion(ctx),
                "phone_number" to maskIdentifier(phoneNumber),
                "device_id" to deviceId,
                "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android_version" to Build.VERSION.SDK_INT.toString(),
                "event_type" to eventType.name,
                "event_detail" to detailWithTimestamp,
                "context_info" to emptyMap<String, Any>()
            )
        } catch (e: Exception) {
            Log.e(TAG, "로그 데이터 생성 실패", e)
            return emptyMap()
        }
    }

    /**
     * 코루틴 스코프 취소 (메모리 누수 방지)
     */
    fun shutdown() {
        try {
            logScope.coroutineContext[Job]?.cancel()
            Log.d(TAG, "LogScope 취소됨")
        } catch (e: Exception) {
            Log.e(TAG, "LogScope 취소 실패: ${e.message}")
        }
    }

    private fun flushLogsInternal() {
        if (logBuffer.isEmpty()) {
            Log.d(TAG, "버퍼가 비어있음, flush 스킵")
            return
        }

        val logsToSend = logBuffer.toList()
        val currentSessionId = sessionId
        logBuffer.clear()

        Log.d(TAG, "배치 전송 시작: ${logsToSend.size}개 로그")

        // 배치로 전송
        sendBatchLog(logsToSend, currentSessionId)
    }

    private fun sendBatchLog(logs: List<LogEntry>, batchSessionId: String) {
        val ctx = context ?: return

        executor.execute {
            try {
                val authManager = AuthManager.getInstance(ctx)
                val phoneNumber = authManager.getPhoneNumber() ?: ""
                val deviceId = authManager.getDeviceId()

                val payload = mutableMapOf<String, Any>(
                    "app_name" to "Vortex",
                    "app_version" to getAppVersion(ctx),
                    "phone_number" to maskIdentifier(phoneNumber),
                    "device_id" to deviceId,
                    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_version" to Build.VERSION.SDK_INT.toString(),
                    "event_type" to EventType.BATCH_LOG.name,
                    "event_detail" to mapOf(
                        "session_id" to batchSessionId,
                        "log_count" to logs.size,
                        "batch_timestamp" to dateFormat.format(Date()),
                        "logs" to logs.map { entry ->
                            mapOf(
                                "event_type" to entry.eventType,
                                "detail" to entry.detail,
                                "event_timestamp" to entry.timestamp
                            )
                        }
                    )
                )

                val json = gson.toJson(payload)
                Log.d(TAG, "배치 로그 전송: ${logs.size}개")

                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL$LOG_ENDPOINT")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "배치 로그 전송 성공: ${logs.size}개")
                    } else {
                        Log.w(TAG, "배치 로그 전송 실패: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "배치 로그 전송 오류: ${e.message}")
            }
        }
    }

    // ============ 내부 메서드 ============

    private fun sendLog(
        eventType: EventType,
        detail: Map<String, Any>,
        contextInfo: Map<String, Any>? = null
    ) {
        if (!isEnabled) return
        val ctx = context ?: return

        executor.execute {
            try {
                val authManager = AuthManager.getInstance(ctx)
                val phoneNumber = authManager.getPhoneNumber() ?: ""
                val deviceId = authManager.getDeviceId()

                // event_detail에 timestamp 추가
                val detailWithTimestamp = detail.toMutableMap()
                detailWithTimestamp["event_timestamp"] = dateFormat.format(Date())

                val payload = mutableMapOf<String, Any>(
                    "app_name" to "Vortex",
                    "app_version" to getAppVersion(ctx),
                    "phone_number" to maskIdentifier(phoneNumber),
                    "device_id" to deviceId,
                    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_version" to Build.VERSION.SDK_INT.toString(),
                    "event_type" to eventType.name,
                    "event_detail" to detailWithTimestamp
                )

                if (contextInfo != null) {
                    payload["context_info"] = contextInfo
                }

                val json = gson.toJson(payload)
                Log.d(TAG, "로그 전송: $json")

                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL$LOG_ENDPOINT")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "로그 전송 성공: ${eventType.name}")
                    } else {
                        Log.w(TAG, "로그 전송 실패: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "로그 전송 오류: ${e.message}")
            }
        }
    }

    private fun buildConfigContext(settings: SettingsManager): Map<String, Any> {
        return mapOf(
            "condition_mode" to settings.conditionMode.name,
            "min_amount" to settings.minAmount,
            "keyword_min_amount" to settings.keywordMinAmount,  // ⭐ 추가
            "airport_min_amount" to settings.airportMinAmount,  // ⭐ 추가
            "keywords_count" to settings.keywords.size,
            "time_ranges_count" to settings.timeRanges.size,
            "refresh_delay" to settings.refreshDelay,
            "click_effect_enabled" to settings.isClickEffectEnabled
        )
    }

    private fun maskIdentifier(identifier: String): String {
        if (identifier.isEmpty()) return ""
        if (identifier.length <= 4) return "****"
        return identifier.substring(0, 3) + "****" + identifier.takeLast(4)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
