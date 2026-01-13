package com.example.twinme.monitoring

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.example.twinme.logging.RemoteLogger
import com.example.twinme.service.CallAcceptAccessibilityService
import com.example.twinme.utils.ShizukuLifecycleTracker
import java.text.SimpleDateFormat
import java.util.*

/**
 * 접근성 서비스 비활성화 원인 추적 시스템
 *
 * 다음을 추적합니다:
 * 1. Settings 변경 (ContentObserver)
 * 2. 메모리 압박 (ComponentCallbacks2)
 * 3. 배터리 상태 (BroadcastReceiver)
 * 4. 시스템 이벤트 (BroadcastReceiver)
 * 5. 크래시 이력 (ActivityManager)
 */
object AccessibilityDeathTracker {

    private const val TAG = "AccessibilityDeathTracker"

    // 추적 상태
    private var isTracking = false
    private var lastKnownAccessibilityList: String = ""
    private var lastHeartbeatTime: Long = 0
    private var memoryWarningCount = 0
    private var lastMemoryWarning: String = ""

    // 시스템 정보 캐싱
    private data class SystemSnapshot(
        val timestamp: Long,
        val batteryLevel: Int,
        val batteryStatus: String,
        val memoryAvailableMB: Long,
        val memoryUsagePercent: Int,
        val isLowMemory: Boolean,
        val isDozeMode: Boolean,
        val shizukuAlive: Boolean,
        val accessibilityList: String
    )

    private val systemSnapshots = mutableListOf<SystemSnapshot>()
    private const val MAX_SNAPSHOTS = 20

    // Observers and Receivers
    private var settingsObserver: AccessibilitySettingsObserver? = null
    private var systemEventReceiver: SystemEventReceiver? = null

    /**
     * 추적 시작
     */
    fun startTracking(context: Context) {
        if (isTracking) {
            Log.w(TAG, "이미 추적 중입니다")
            return
        }

        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "🔍 접근성 서비스 비활성화 원인 추적 시작")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        isTracking = true
        lastHeartbeatTime = System.currentTimeMillis()

        // 1. Settings ContentObserver 등록
        registerSettingsObserver(context)

        // 2. System Event BroadcastReceiver 등록
        registerSystemEventReceiver(context)

        // 3. ComponentCallbacks2 등록 (메모리 압박 감지)
        registerMemoryCallback(context)

        // 4. 초기 스냅샷 저장
        captureSystemSnapshot(context, "TRACKING_STARTED")

        RemoteLogger.logConfigChange(
            configType = "DEATH_TRACKER_STARTED",
            beforeValue = "INACTIVE",
            afterValue = "ACTIVE"
        )
    }

    /**
     * 추적 중단
     */
    fun stopTracking(context: Context) {
        if (!isTracking) return

        Log.i(TAG, "🛑 접근성 서비스 비활성화 원인 추적 중단")
        isTracking = false

        // Observers 해제
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }

        systemEventReceiver?.let {
            context.unregisterReceiver(it)
        }

        RemoteLogger.logConfigChange(
            configType = "DEATH_TRACKER_STOPPED",
            beforeValue = "ACTIVE",
            afterValue = "INACTIVE"
        )
    }

    /**
     * Heartbeat 업데이트 (정상 작동 확인)
     */
    fun updateHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis()
    }

    /**
     * 1. Settings ContentObserver 등록
     */
    private fun registerSettingsObserver(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        settingsObserver = AccessibilitySettingsObserver(context, handler)

        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        context.contentResolver.registerContentObserver(uri, false, settingsObserver!!)

        // 초기값 저장
        lastKnownAccessibilityList = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        Log.d(TAG, "✅ Settings ContentObserver 등록 완료")
    }

    /**
     * 2. System Event BroadcastReceiver 등록
     */
    private fun registerSystemEventReceiver(context: Context) {
        systemEventReceiver = SystemEventReceiver()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        }

        context.registerReceiver(systemEventReceiver, filter)
        Log.d(TAG, "✅ SystemEvent BroadcastReceiver 등록 완료")
    }

    /**
     * 3. ComponentCallbacks2 등록 (메모리 압박 감지)
     */
    private fun registerMemoryCallback(context: Context) {
        val callback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                handleMemoryPressure(context, level)
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                Log.w(TAG, "⚠️ onLowMemory() 호출됨!")
                handleMemoryPressure(context, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        }

        context.registerComponentCallbacks(callback)
        Log.d(TAG, "✅ Memory Callback 등록 완료")
    }

    /**
     * 시스템 스냅샷 캡처
     */
    private fun captureSystemSnapshot(context: Context, reason: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memoryInfo)

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            val snapshot = SystemSnapshot(
                timestamp = System.currentTimeMillis(),
                batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                batteryStatus = getBatteryStatus(context),
                memoryAvailableMB = memoryInfo.availMem / 1024 / 1024,
                memoryUsagePercent = calculateMemoryUsage(memoryInfo),
                isLowMemory = memoryInfo.lowMemory,
                isDozeMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pm.isDeviceIdleMode
                } else {
                    false
                },
                shizukuAlive = !ShizukuLifecycleTracker.isShizukuDead(),
                accessibilityList = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
            )

            synchronized(systemSnapshots) {
                systemSnapshots.add(snapshot)
                if (systemSnapshots.size > MAX_SNAPSHOTS) {
                    systemSnapshots.removeAt(0)
                }
            }

            Log.d(TAG, "📸 스냅샷 캡처: $reason")
            Log.d(TAG, "   배터리: ${snapshot.batteryLevel}% (${snapshot.batteryStatus})")
            Log.d(TAG, "   메모리: ${snapshot.memoryAvailableMB}MB (${snapshot.memoryUsagePercent}%)")
            Log.d(TAG, "   LowMemory: ${snapshot.isLowMemory}, Doze: ${snapshot.isDozeMode}")

        } catch (e: Exception) {
            Log.e(TAG, "스냅샷 캡처 실패: ${e.message}", e)
        }
    }

    /**
     * 접근성 서비스 비활성화 원인 분석
     */
    fun analyzeDeathCause(context: Context): String {
        val report = StringBuilder()
        report.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        report.appendLine("🔍 접근성 서비스 비활성화 원인 분석")
        report.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val now = System.currentTimeMillis()
        val timeSinceLastHeartbeat = (now - lastHeartbeatTime) / 1000

        // 1. 시간 정보
        report.appendLine()
        report.appendLine("⏱️ 시간 정보:")
        report.appendLine("   현재 시각: ${formatTime(now)}")
        report.appendLine("   마지막 Heartbeat: ${formatTime(lastHeartbeatTime)}")
        report.appendLine("   경과 시간: ${timeSinceLastHeartbeat}초")

        // 2. 현재 접근성 설정 상태
        val currentList = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        report.appendLine()
        report.appendLine("📋 접근성 서비스 설정:")
        report.appendLine("   이전: $lastKnownAccessibilityList")
        report.appendLine("   현재: $currentList")
        report.appendLine("   변경됨: ${lastKnownAccessibilityList != currentList}")

        // 3. 최근 스냅샷 분석
        synchronized(systemSnapshots) {
            if (systemSnapshots.isNotEmpty()) {
                report.appendLine()
                report.appendLine("📸 최근 시스템 상태 (최근 ${systemSnapshots.size}개):")

                val recentSnapshots = systemSnapshots.takeLast(5)
                recentSnapshots.forEachIndexed { index, snapshot ->
                    val elapsed = (now - snapshot.timestamp) / 1000
                    report.appendLine("   [${index + 1}] ${elapsed}초 전:")
                    report.appendLine("       배터리: ${snapshot.batteryLevel}% (${snapshot.batteryStatus})")
                    report.appendLine("       메모리: ${snapshot.memoryAvailableMB}MB 사용 (${snapshot.memoryUsagePercent}%)")
                    report.appendLine("       LowMemory: ${snapshot.isLowMemory}, Doze: ${snapshot.isDozeMode}")
                    report.appendLine("       Shizuku: ${if (snapshot.shizukuAlive) "살아있음" else "죽음"}")
                }
            }
        }

        // 4. 메모리 경고 이력
        if (memoryWarningCount > 0) {
            report.appendLine()
            report.appendLine("⚠️ 메모리 경고:")
            report.appendLine("   경고 횟수: ${memoryWarningCount}회")
            report.appendLine("   마지막 경고: $lastMemoryWarning")
        }

        // 5. Shizuku 상태
        report.appendLine()
        report.appendLine("🔧 Shizuku 상태:")
        val shizukuDead = ShizukuLifecycleTracker.isShizukuDead()
        report.appendLine("   상태: ${if (shizukuDead) "죽음" else "살아있음"}")
        if (shizukuDead) {
            val deathTime = ShizukuLifecycleTracker.getTimeSinceShizukuDeath()
            report.appendLine("   종료 후 경과: ${deathTime}ms")
        }

        // 6. 배터리 최적화 상태
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        report.appendLine()
        report.appendLine("🔋 배터리 최적화:")
        report.appendLine("   상태: ${if (isIgnoringBatteryOptimizations) "무시됨 (안전)" else "활성화됨 (위험)"}")

        // 7. 크래시 이력 확인
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses
        val ourProcess = processes?.find { it.processName == context.packageName }

        report.appendLine()
        report.appendLine("📱 앱 프로세스:")
        if (ourProcess != null) {
            report.appendLine("   PID: ${ourProcess.pid}")
            report.appendLine("   중요도: ${getImportanceString(ourProcess.importance)}")
        } else {
            report.appendLine("   상태: 프로세스 없음 (크래시 또는 강제 종료)")
        }

        // 8. 인스턴스 상태
        val instanceAlive = CallAcceptAccessibilityService.instance != null
        report.appendLine()
        report.appendLine("🔌 서비스 인스턴스:")
        report.appendLine("   상태: ${if (instanceAlive) "살아있음" else "null (onDestroy 호출됨)"}")

        // 9. 원인 추론
        report.appendLine()
        report.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        report.appendLine("💡 추정 원인:")
        report.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val causes = mutableListOf<String>()

        // 원인 1: Shizuku 사망
        if (shizukuDead) {
            causes.add("🔴 Shizuku 서비스가 종료되었습니다")
            causes.add("   → Shizuku 의존적인 접근성 서비스도 함께 종료됨")
        }

        // 원인 2: 메모리 압박
        val recentLowMemory = synchronized(systemSnapshots) {
            systemSnapshots.takeLast(3).any { it.isLowMemory }
        }
        if (recentLowMemory || memoryWarningCount > 0) {
            causes.add("🟡 메모리 부족 감지됨")
            causes.add("   → 시스템이 백그라운드 프로세스를 강제 종료했을 가능성")
        }

        // 원인 3: Settings 직접 변경
        if (lastKnownAccessibilityList != currentList && currentList.isNotEmpty()) {
            causes.add("🟠 Settings 값이 직접 변경되었습니다")
            causes.add("   → 사용자 또는 다른 앱이 수동으로 변경했을 가능성")
        }

        // 원인 4: 배터리 최적화
        if (!isIgnoringBatteryOptimizations) {
            causes.add("🟡 배터리 최적화가 활성화되어 있습니다")
            causes.add("   → Doze 모드에서 앱이 종료되었을 가능성")
        }

        // 원인 5: 인스턴스는 있는데 설정이 꺼진 경우
        if (instanceAlive && !currentList.contains("com.example.twinme")) {
            causes.add("🔵 인스턴스는 살아있지만 Settings에서 제거됨")
            causes.add("   → 설정 앱에서 직접 비활성화했을 가능성 높음")
        }

        // 원인 6: 인스턴스도 없고 설정도 없는 경우
        if (!instanceAlive && !currentList.contains("com.example.twinme")) {
            if (timeSinceLastHeartbeat < 60) {
                causes.add("🔴 갑작스런 종료 (Silent Kill)")
                causes.add("   → kill -9 또는 시스템 강제 종료")
            } else {
                causes.add("🟠 정상 종료 (onDestroy 호출)")
                causes.add("   → 사용자가 수동으로 종료했을 가능성")
            }
        }

        if (causes.isEmpty()) {
            causes.add("❓ 명확한 원인을 찾을 수 없습니다")
            causes.add("   → 로그가 부족하거나 알 수 없는 시스템 동작")
        }

        causes.forEach { report.appendLine(it) }

        report.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val reportString = report.toString()
        Log.i(TAG, reportString)

        return reportString
    }

    /**
     * ContentObserver for Settings changes
     */
    private class AccessibilitySettingsObserver(
        private val context: Context,
        handler: Handler
    ) : ContentObserver(handler) {

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            Log.w(TAG, "🚨 접근성 서비스 설정이 변경되었습니다!")

            val newList = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val wasVortexEnabled = lastKnownAccessibilityList.contains("com.example.twinme")
            val isVortexEnabled = newList.contains("com.example.twinme")

            Log.d(TAG, "   변경 전: $lastKnownAccessibilityList")
            Log.d(TAG, "   변경 후: $newList")
            Log.d(TAG, "   Vortex 상태: $wasVortexEnabled → $isVortexEnabled")

            RemoteLogger.logConfigChange(
                configType = "ACCESSIBILITY_SETTINGS_CHANGED_REALTIME",
                beforeValue = lastKnownAccessibilityList,
                afterValue = newList
            )

            // 스냅샷 캡처
            captureSystemSnapshot(context, "SETTINGS_CHANGED")

            // Vortex가 제거되었을 경우
            if (wasVortexEnabled && !isVortexEnabled) {
                Log.e(TAG, "❌ Vortex 접근성 서비스가 제거되었습니다!")

                val report = analyzeDeathCause(context)

                RemoteLogger.logError(
                    errorType = "ACCESSIBILITY_SERVICE_REMOVED",
                    message = "Settings에서 Vortex가 제거됨",
                    stackTrace = report
                )
            }

            lastKnownAccessibilityList = newList
        }
    }

    /**
     * BroadcastReceiver for System Events
     */
    private class SystemEventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            Log.d(TAG, "📡 시스템 이벤트: $action")

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    captureSystemSnapshot(context, "SCREEN_OFF")
                    RemoteLogger.logConfigChange("SCREEN_OFF", "ON", "OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    captureSystemSnapshot(context, "SCREEN_ON")
                    RemoteLogger.logConfigChange("SCREEN_ON", "OFF", "ON")
                }
                Intent.ACTION_BATTERY_LOW -> {
                    captureSystemSnapshot(context, "BATTERY_LOW")
                    RemoteLogger.logConfigChange("BATTERY_LOW", "NORMAL", "LOW")
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val isDoze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        pm.isDeviceIdleMode
                    } else {
                        false
                    }
                    captureSystemSnapshot(context, if (isDoze) "DOZE_ENTER" else "DOZE_EXIT")
                    RemoteLogger.logConfigChange(
                        "DOZE_MODE",
                        if (isDoze) "NORMAL" else "DOZE",
                        if (isDoze) "DOZE" else "NORMAL"
                    )
                }
            }
        }
    }

    /**
     * 메모리 압박 처리
     */
    private fun handleMemoryPressure(context: Context, level: Int) {
        val levelStr = getMemoryTrimLevelString(level)
        memoryWarningCount++
        lastMemoryWarning = "$levelStr (${formatTime(System.currentTimeMillis())})"

        Log.w(TAG, "⚠️ 메모리 압박 감지: $levelStr (총 ${memoryWarningCount}회)")

        captureSystemSnapshot(context, "MEMORY_PRESSURE_$levelStr")

        // 심각한 메모리 압박인 경우 즉시 로깅
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            RemoteLogger.logError(
                errorType = "CRITICAL_MEMORY_PRESSURE",
                message = "심각한 메모리 부족: $levelStr",
                stackTrace = "Level: $level, Count: $memoryWarningCount"
            )
        }
    }

    // ========== 헬퍼 함수 ==========

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun calculateMemoryUsage(memoryInfo: ActivityManager.MemoryInfo): Int {
        val total = memoryInfo.totalMem
        val available = memoryInfo.availMem
        return ((total - available) * 100.0 / total).toInt()
    }

    private fun getBatteryStatus(context: Context): String {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "방전 중"
            BatteryManager.BATTERY_STATUS_FULL -> "완충"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "미충전"
            else -> "알 수 없음"
        }
    }

    private fun getImportanceString(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "UNKNOWN ($importance)"
        }
    }

    private fun getMemoryTrimLevelString(level: Int): String {
        return when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (최고 위험)"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE (중간)"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL (위험)"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            else -> "UNKNOWN ($level)"
        }
    }
}
