package com.example.twinme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.twinme.MainActivity
import com.example.twinme.R
import com.example.twinme.auth.AuthManager
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.interfaces.ICallEngine
import com.example.twinme.monitoring.AccessibilityDeathTracker
import com.example.twinme.utils.AccessibilityServiceWatcher
import com.example.twinme.utils.BatteryOptimizationHelper
import com.example.twinme.utils.ShizukuStatusChecker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingStateService : Service() {

    @Inject
    lateinit var engine: ICallEngine

    companion object {
        private const val TAG = "FloatingStateService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "twinme_floating_service"
        private const val ACCESSIBILITY_WARNING_ID = 1002
        private const val ACCESSIBILITY_WARNING_CHANNEL_ID = "twinme_accessibility_warning"

        // 접근성 서비스 체크 주기 (30초마다)
        private const val ACCESSIBILITY_CHECK_INTERVAL_MS = 30000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var settingsManager: SettingsManager
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var btnDragHandle: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPlayPause: ImageButton

    private var isPlaying = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // 접근성 서비스 모니터링용
    private val accessibilityCheckHandler = Handler(Looper.getMainLooper())
    private var hasShownAccessibilityWarning = false
    private var hasShownBatteryWarning = false
    private var hasShownShizukuWarning = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "========================================")
        Log.d(TAG, "FloatingStateService 생성됨 (시각: $timestamp)")
        Log.d(TAG, "========================================")

        // 생명주기 이벤트 로깅 (문자열로 전송)
        com.example.twinme.logging.RemoteLogger.logConfigChange(
            configType = "LIFECYCLE",
            beforeValue = "SYSTEM",
            afterValue = "FLOATING_SERVICE_CREATED"
        )

        // Foreground Service 먼저 시작 (Android 12+ 크래시 방지)
        startForegroundService()

        // 인증 상태 확인
        val authManager = AuthManager.getInstance(this)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            // 인증되지 않은 사용자 - 서비스 종료
            Toast.makeText(this, "인증되지 않은 접근입니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        settingsManager = SettingsManager.getInstance(this)

        // 플로팅 뷰 초기화
        initFloatingView()

        // 초기 상태 체크 및 경고
        checkInitialStatus()

        // 접근성 서비스 모니터링 시작
        startAccessibilityServiceMonitoring()
    }

    /**
     * 초기 상태 체크 및 사용자 경고
     */
    private fun checkInitialStatus() {
        // 배터리 최적화 체크
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            Log.w(TAG, "⚠️ 배터리 최적화가 적용되어 있습니다 - 백그라운드에서 종료될 수 있음")
            Toast.makeText(
                this,
                "배터리 최적화를 끄시면 더 안정적으로 작동합니다",
                Toast.LENGTH_LONG
            ).show()
        }

        // Shizuku 상태 체크
        if (!ShizukuStatusChecker.isShizukuFullyOperational()) {
            Log.w(TAG, "⚠️ Shizuku가 비활성화되어 있습니다")
            Log.i(TAG, "Shizuku 없이도 기본 접근성 클릭은 작동합니다")
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)  // ⭐ 최대 우선순위
            .setCategory(NotificationCompat.CATEGORY_SERVICE)  // ⭐ 서비스 카테고리
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // ⭐ 잠금화면에도 표시
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // ⭐ 일반 알림 채널 - IMPORTANCE_DEFAULT로 변경 (메모리 부족 시 종료 방지)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT  // ⭐ LOW → DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC  // ⭐ 잠금화면 표시
            }
            notificationManager.createNotificationChannel(channel)

            // 접근성 서비스 경고 채널 (HIGH 중요도)
            val warningChannel = NotificationChannel(
                ACCESSIBILITY_WARNING_CHANNEL_ID,
                "접근성 서비스 경고",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "접근성 서비스가 비활성화되었을 때 긴급 알림"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(warningChannel)
        }
    }

    private fun initFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)

        btnDragHandle = floatingView.findViewById(R.id.btn_drag_handle)
        btnStop = floatingView.findViewById(R.id.btn_stop)
        btnPlayPause = floatingView.findViewById(R.id.btn_play_pause)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        setupButtonListeners()
        setupDragListener()
    }

    private fun setupButtonListeners() {
        // 종료 버튼
        btnStop.setOnClickListener {
            settingsManager.isFloatingUiEnabled = false
            stopSelf()
        }

        // 재생/일시정지 버튼
        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updatePlayPauseButton()

            if (isPlaying) {
                engine.start()
            } else {
                engine.stop()
            }
        }
    }

    private fun setupDragListener() {
        btnDragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnPlayPause.setImageResource(iconRes)
    }

    override fun onDestroy() {
        val timestamp = System.currentTimeMillis()
        Log.w(TAG, "========================================")
        Log.w(TAG, "FloatingStateService 파괴됨 (시각: $timestamp)")
        Log.w(TAG, "========================================")

        // 1. 마지막 상태를 SharedPreferences에 기록 (동기식 - 무조건 성공)
        com.example.twinme.logging.RemoteLogger.recordLastState(
            event = "FLOATING_SERVICE_DESTROYED",
            details = """
                timestamp: $timestamp
                Shizuku 상태: ${if (com.example.twinme.utils.ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
                Shizuku 종료 후 경과: ${com.example.twinme.utils.ShizukuLifecycleTracker.getTimeSinceShizukuDeath()}ms
                접근성 서비스 인스턴스: ${if (com.example.twinme.utils.AccessibilityServiceWatcher.isServiceInstanceAlive()) "살아있음" else "null"}
            """.trimIndent()
        )

        // 2. 동기식 에러 로깅 (프로세스 종료 전 완료 보장)
        try {
            com.example.twinme.logging.RemoteLogger.logErrorSync(
                errorType = "FLOATING_SERVICE_DESTROYED",
                message = "onDestroy 호출 - 서비스 완전 종료",
                stackTrace = """
                    timestamp: $timestamp
                    Shizuku 상태: ${if (com.example.twinme.utils.ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
                    Shizuku 종료 후 경과: ${com.example.twinme.utils.ShizukuLifecycleTracker.getTimeSinceShizukuDeath()}ms
                    접근성 서비스 인스턴스: ${if (com.example.twinme.utils.AccessibilityServiceWatcher.isServiceInstanceAlive()) "살아있음" else "null"}
                """.trimIndent()
            )
        } catch (e: Exception) {
            Log.e(TAG, "동기식 로깅 실패: ${e.message}")
        }

        super.onDestroy()

        // ⭐ 접근성 서비스 모니터링 중단
        stopAccessibilityServiceMonitoring()
        accessibilityCheckHandler.removeCallbacksAndMessages(null)  // ⭐ 추가: 모든 pending runnable 제거

        // ⭐ 플로팅 뷰 제거
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }

        // ⭐ 엔진 정지 및 리소스 정리
        engine.stop()
        engine.cleanup()  // ⭐ 추가: 엔진 내부 리소스 정리

        Log.d(TAG, "FloatingStateService 종료 완료 - 모든 리소스 정리됨")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 접근성 서비스 모니터링 시작
     * 주기적으로 접근성 서비스 활성화 상태를 체크하고,
     * 비활성화되면 사용자에게 경고 알림 발송
     */
    private fun startAccessibilityServiceMonitoring() {
        Log.d(TAG, "접근성 서비스 모니터링 시작 (체크 주기: ${ACCESSIBILITY_CHECK_INTERVAL_MS}ms)")
        checkAccessibilityServiceStatus()
    }

    /**
     * 접근성 서비스 모니터링 중단
     */
    private fun stopAccessibilityServiceMonitoring() {
        Log.d(TAG, "접근성 서비스 모니터링 중단")
        accessibilityCheckHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 접근성 서비스 상태 체크 (재귀 호출)
     */
    private fun checkAccessibilityServiceStatus() {
        // 1. 접근성 서비스 체크
        val isAccessibilityOperational = AccessibilityServiceWatcher.isFullyOperational(this)

        if (!isAccessibilityOperational) {
            Log.w(TAG, "⚠️ 접근성 서비스가 비활성화되었습니다!")

            // 경고 알림 발송 (한 번만)
            if (!hasShownAccessibilityWarning) {
                showAccessibilityWarningNotification()
                hasShownAccessibilityWarning = true

                // ⭐ 접근성 서비스 비활성화 원인 분석
                val deathReport = AccessibilityDeathTracker.analyzeDeathCause(this)

                // RemoteLogger에 원인 분석 결과 로깅
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "ACCESSIBILITY_SERVICE_DISCONNECTED",
                    message = "접근성 서비스가 비활성화됨",
                    stackTrace = deathReport
                )

                // ⭐ Shizuku로 자동 복구 시도
                tryRestoreAccessibilityService()
            }

            // 엔진 중지 (접근성 서비스 없이는 작동 불가)
            engine.stop()
        } else {
            // 정상 복구 시 경고 플래그 리셋
            if (hasShownAccessibilityWarning) {
                Log.i(TAG, "✅ 접근성 서비스 복구됨")
                dismissAccessibilityWarningNotification()
                hasShownAccessibilityWarning = false
            }

            // ⭐ Heartbeat 업데이트: AccessibilityDeathTracker에 알림
            AccessibilityDeathTracker.updateHeartbeat()

            // ⭐ Heartbeat 로깅: 30초마다 정상 작동 확인
            com.example.twinme.logging.RemoteLogger.logConfigChange(
                configType = "HEARTBEAT",
                beforeValue = "OPERATIONAL",
                afterValue = "FLOATING_SERVICE_ALIVE (접근성 정상)"
            )
        }

        // 2. Shizuku 상태 체크 (경고만, 엔진 중지는 하지 않음)
        val isShizukuOperational = ShizukuStatusChecker.isShizukuFullyOperational()
        if (!isShizukuOperational) {
            if (!hasShownShizukuWarning) {
                Log.w(TAG, "⚠️ Shizuku가 비활성화되었습니다 (접근성 클릭으로 대체)")
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "SHIZUKU_DISCONNECTED",
                    message = "Shizuku가 비활성화됨 - dispatchGesture로 fallback",
                    stackTrace = null
                )
                hasShownShizukuWarning = true
            }
        } else {
            if (hasShownShizukuWarning) {
                Log.i(TAG, "✅ Shizuku 복구됨")
                hasShownShizukuWarning = false
            }
        }

        // 3. 배터리 최적화 체크 (정보성)
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            if (!hasShownBatteryWarning) {
                Log.w(TAG, "⚠️ 배터리 최적화 적용 중 - 백그라운드에서 종료될 수 있음")
                hasShownBatteryWarning = true
            }
        }

        // 다음 체크 예약
        accessibilityCheckHandler.postDelayed(
            { checkAccessibilityServiceStatus() },
            ACCESSIBILITY_CHECK_INTERVAL_MS
        )
    }

    /**
     * 접근성 서비스 비활성화 경고 알림 발송
     */
    private fun showAccessibilityWarningNotification() {
        Log.w(TAG, "🚨 접근성 서비스 경고 알림 발송")

        val settingsIntent = AccessibilityServiceWatcher.createAccessibilitySettingsIntent()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ACCESSIBILITY_WARNING_CHANNEL_ID)
            .setContentTitle("⚠️ 자동 콜 수락이 중단되었습니다")
            .setContentText("접근성 서비스가 비활성화되었습니다. 탭하여 다시 활성화하세요.")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))  // 진동 패턴
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(ACCESSIBILITY_WARNING_ID, notification)
    }

    /**
     * 접근성 서비스 경고 알림 제거
     */
    private fun dismissAccessibilityWarningNotification() {
        Log.i(TAG, "✅ 접근성 서비스 경고 알림 제거")
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(ACCESSIBILITY_WARNING_ID)
    }

    /**
     * ⭐ Shizuku로 접근성 서비스 자동 복구 시도
     */
    private fun tryRestoreAccessibilityService() {
        Log.d(TAG, "🔧 접근성 서비스 자동 복구 시도...")

        // ⭐ 복구 시도 시작 로깅
        com.example.twinme.logging.RemoteLogger.logConfigChange(
            configType = "AUTO_RESTORE_ATTEMPT",
            beforeValue = "ACCESSIBILITY_DISABLED",
            afterValue = "TRYING_TO_RESTORE_VIA_SHIZUKU"
        )

        // 1. Shizuku 사용 가능 여부 확인
        if (!com.example.twinme.utils.ShizukuStatusChecker.isShizukuFullyOperational()) {
            Log.w(TAG, "❌ Shizuku 사용 불가 - 수동 복구 필요")
            com.example.twinme.logging.RemoteLogger.logError(
                errorType = "AUTO_RESTORE_FAILED",
                message = "Shizuku 없음 - 접근성 서비스 자동 복구 실패",
                stackTrace = null
            )
            return
        }

        try {
            // 2. Shizuku로 접근성 서비스 재활성화
            val serviceName = "com.example.twinme/.service.CallAcceptAccessibilityService"

            // ⭐ 단계 1: 현재 활성화된 접근성 서비스 리스트 가져오기
            val getCommand = arrayOf("sh", "-c", "settings get secure enabled_accessibility_services")
            Log.d(TAG, "🔍 현재 접근성 서비스 리스트 조회...")

            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val getProcess = method.invoke(null, getCommand, null, null) as rikka.shizuku.ShizukuRemoteProcess
            val currentList = getProcess.inputStream.bufferedReader().use { it.readText().trim() }
            getProcess.destroy()

            Log.d(TAG, "현재 리스트: $currentList")

            // ⭐ 현재 리스트 로깅
            com.example.twinme.logging.RemoteLogger.logConfigChange(
                configType = "CURRENT_ACCESSIBILITY_LIST",
                beforeValue = "QUERY",
                afterValue = currentList.ifEmpty { "EMPTY" }
            )

            // ⭐ 단계 2: Vortex가 리스트에 있는지 확인하고 없으면 추가
            val newList = if (currentList.isEmpty() || currentList == "null") {
                // 리스트가 비어있으면 Vortex만 추가
                serviceName
            } else if (!currentList.contains(serviceName)) {
                // Vortex가 없으면 기존 리스트에 추가
                "$currentList:$serviceName"
            } else {
                // 이미 있으면 그대로 사용
                currentList
            }

            Log.d(TAG, "새 리스트: $newList")

            // ⭐ 단계 3: 접근성 서비스 리스트 업데이트
            val putCommand = arrayOf("sh", "-c", "settings put secure enabled_accessibility_services '$newList'")
            Log.d(TAG, "🚀 Shizuku 명령 실행: settings put secure enabled_accessibility_services '$newList'")

            val putProcess = method.invoke(null, putCommand, null, null) as rikka.shizuku.ShizukuRemoteProcess
            val exitCode1 = putProcess.waitFor()
            putProcess.destroy()

            // ⭐ 단계 4: 접근성 기능 전체 활성화
            val enableCommand = arrayOf("sh", "-c", "settings put secure accessibility_enabled 1")
            val enableProcess = method.invoke(null, enableCommand, null, null) as rikka.shizuku.ShizukuRemoteProcess
            val exitCode2 = enableProcess.waitFor()
            enableProcess.destroy()

            val exitCode = if (exitCode1 == 0 && exitCode2 == 0) 0 else 1

            if (exitCode == 0) {
                Log.i(TAG, "✅ 접근성 서비스 자동 복구 성공!")
                com.example.twinme.logging.RemoteLogger.logConfigChange(
                    configType = "AUTO_RESTORE",
                    beforeValue = "ACCESSIBILITY_DISABLED",
                    afterValue = "ACCESSIBILITY_ENABLED_BY_SHIZUKU"
                )

                // 3초 후 재확인
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val isNowOperational = com.example.twinme.utils.AccessibilityServiceWatcher.isFullyOperational(this)
                    if (isNowOperational) {
                        Log.i(TAG, "✅ 접근성 서비스 복구 확인 완료!")

                        // ⭐ 복구 성공 확인 로깅
                        com.example.twinme.logging.RemoteLogger.logConfigChange(
                            configType = "AUTO_RESTORE_SUCCESS",
                            beforeValue = "ACCESSIBILITY_DISABLED",
                            afterValue = "ACCESSIBILITY_RESTORED_AND_VERIFIED"
                        )

                        hasShownAccessibilityWarning = false
                        dismissAccessibilityWarningNotification()
                        engine.start()
                    } else {
                        Log.w(TAG, "⚠️ 접근성 서비스 복구 실패 - 수동 활성화 필요")

                        // ⭐ 복구 실패 로깅
                        com.example.twinme.logging.RemoteLogger.logError(
                            errorType = "AUTO_RESTORE_VERIFICATION_FAILED",
                            message = "Shizuku 명령은 성공했으나 3초 후 재확인 시 접근성 서비스 여전히 비활성화",
                            stackTrace = null
                        )
                    }
                }, 3000L)

            } else {
                Log.e(TAG, "❌ 접근성 서비스 복구 실패: exitCode=$exitCode")
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "AUTO_RESTORE_FAILED",
                    message = "Shizuku 명령 실패 (exitCode=$exitCode)",
                    stackTrace = null
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 접근성 서비스 복구 예외: ${e.message}", e)
            com.example.twinme.logging.RemoteLogger.logError(
                errorType = "AUTO_RESTORE_EXCEPTION",
                message = "복구 시도 중 예외 발생: ${e.message}",
                stackTrace = e.stackTraceToString()
            )
        }
    }
}
