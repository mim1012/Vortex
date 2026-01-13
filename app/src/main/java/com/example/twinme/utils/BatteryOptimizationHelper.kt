package com.example.twinme.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 배터리 최적화 관리 유틸리티
 *
 * 접근성 서비스가 백그라운드에서 강제 종료되는 것을 방지하기 위해
 * 배터리 최적화 제외를 요청합니다.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimization"

    /**
     * 배터리 최적화가 비활성화되어 있는지 확인
     *
     * @param context Android Context
     * @return true: 최적화 제외됨 (앱이 보호됨), false: 최적화 적용 중 (백그라운드에서 죽을 수 있음)
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0 미만에서는 배터리 최적화 없음
            return true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        Log.d(TAG, "배터리 최적화 제외 여부: $isIgnoring")
        return isIgnoring
    }

    /**
     * 배터리 최적화 제외 설정 화면으로 이동하는 Intent 생성
     *
     * @param context Android Context
     * @return 설정 화면 Intent
     */
    fun createBatteryOptimizationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            // Android 6.0 미만에서는 일반 설정 화면
            Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * 배터리 최적화 전체 설정 화면으로 이동하는 Intent
     * (사용자가 직접 앱을 찾아서 제외할 수 있도록)
     *
     * @return 설정 화면 Intent
     */
    fun createIgnoreBatteryOptimizationListIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * 제조사별 배터리 최적화 설정 화면 Intent
     * (삼성, 샤오미, 화웨이 등 제조사별 맞춤 설정)
     *
     * @param context Android Context
     * @return 제조사별 설정 화면 Intent 또는 null
     */
    fun createManufacturerBatterySettingsIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val packageName = context.packageName

        return try {
            when {
                // 삼성 (One UI)
                manufacturer.contains("samsung") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }

                // 샤오미 (MIUI/HyperOS)
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }

                // 화웨이 (EMUI)
                manufacturer.contains("huawei") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }

                // OPPO/OnePlus (ColorOS)
                manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }

                // Vivo (FuntouchOS)
                manufacturer.contains("vivo") -> {
                    Intent().apply {
                        component = android.content.ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "제조사별 설정 화면 열기 실패: ${e.message}")
            null
        }
    }

    /**
     * 배터리 최적화 상태 요약
     *
     * @param context Android Context
     * @return 상태 메시지
     */
    fun getBatteryOptimizationStatusMessage(context: Context): String {
        return if (isIgnoringBatteryOptimizations(context)) {
            "✅ 배터리 최적화 제외됨 (앱 보호 활성)"
        } else {
            "⚠️ 배터리 최적화 적용 중 (백그라운드에서 종료될 수 있음)"
        }
    }
}
