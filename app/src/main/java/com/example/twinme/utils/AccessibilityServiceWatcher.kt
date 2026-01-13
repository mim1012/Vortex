package com.example.twinme.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.example.twinme.service.CallAcceptAccessibilityService

/**
 * 접근성 서비스 상태 모니터링 유틸리티
 *
 * 앱이 크래시 후 재시작되거나 시스템에 의해 강제 종료될 때
 * 접근성 서비스가 비활성화되는 문제를 감지합니다.
 */
object AccessibilityServiceWatcher {
    private const val TAG = "AccessibilityWatcher"

    /**
     * 접근성 서비스가 현재 활성화되어 있는지 확인
     *
     * @param context Android Context
     * @return true: 활성화됨, false: 비활성화됨
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${CallAcceptAccessibilityService::class.java.name}"

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledServices.isNullOrEmpty()) {
            Log.w(TAG, "접근성 서비스가 하나도 활성화되지 않음")
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val serviceName = colonSplitter.next()
            if (serviceName.equals(expectedServiceName, ignoreCase = true)) {
                Log.d(TAG, "접근성 서비스 활성화 확인됨: $serviceName")
                return true
            }
        }

        Log.w(TAG, "접근성 서비스 비활성화됨")
        Log.d(TAG, "기대한 서비스: $expectedServiceName")
        Log.d(TAG, "활성화된 서비스들: $enabledServices")
        return false
    }

    /**
     * 접근성 서비스 설정 화면으로 이동하는 Intent 생성
     *
     * @return 설정 화면 Intent
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * 접근성 서비스 인스턴스가 살아있는지 확인
     * (onServiceConnected 호출 후 onDestroy 호출 전)
     *
     * @return true: 인스턴스 살아있음, false: null
     */
    fun isServiceInstanceAlive(): Boolean {
        val alive = CallAcceptAccessibilityService.instance != null
        Log.d(TAG, "접근성 서비스 인스턴스 상태: ${if (alive) "살아있음" else "null"}")
        return alive
    }

    /**
     * 접근성 서비스 전체 상태 체크
     * (설정 활성화 여부 + 인스턴스 생존 여부)
     *
     * @param context Android Context
     * @return true: 정상 작동 중, false: 문제 있음
     */
    fun isFullyOperational(context: Context): Boolean {
        val settingEnabled = isAccessibilityServiceEnabled(context)
        val instanceAlive = isServiceInstanceAlive()

        val operational = settingEnabled && instanceAlive
        Log.d(TAG, "접근성 서비스 전체 상태: ${if (operational) "정상" else "비정상"} (설정=$settingEnabled, 인스턴스=$instanceAlive)")

        return operational
    }
}
