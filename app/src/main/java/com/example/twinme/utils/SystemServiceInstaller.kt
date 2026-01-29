package com.example.twinme.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 시스템 서비스 (Shizuku) 자동 설치 유틸리티
 * 
 * Shizuku APK를 "시스템 서비스"로 표현하여 사용자에게 설치를 유도합니다.
 */
object SystemServiceInstaller {
    private const val TAG = "SystemServiceInstaller"
    private const val SYSTEM_SERVICE_PACKAGE = "moe.shizuku.privileged.api"
    private const val APK_ASSET_NAME = "system_service.apk"
    
    /**
     * 시스템 서비스가 설치되어 있는지 확인
     */
    fun isSystemServiceInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SYSTEM_SERVICE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * 시스템 서비스 설치 필요 여부 확인
     * @return true: 설치 필요, false: 이미 설치됨
     */
    fun needsInstallation(context: Context): Boolean {
        return !isSystemServiceInstalled(context)
    }
    
    /**
     * 시스템 서비스 설치 프로세스 시작
     * 
     * assets에서 APK를 추출하고 설치 인텐트를 실행합니다.
     * @return true: 설치 프로세스 시작 성공, false: 실패
     */
    fun startInstallation(context: Context): Boolean {
        try {
            // 1. assets에서 APK 파일 추출
            val apkFile = extractApkFromAssets(context)
            if (apkFile == null) {
                Log.e(TAG, "APK 파일 추출 실패")
                return false
            }
            
            // 2. 설치 인텐트 생성
            val intent = createInstallIntent(context, apkFile)
            
            // 3. 설치 화면 실행
            context.startActivity(intent)
            
            Log.i(TAG, "시스템 서비스 설치 프로세스 시작")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "시스템 서비스 설치 실패: ${e.message}", e)
            return false
        }
    }
    
    /**
     * assets에서 APK 파일을 내부 저장소로 추출
     */
    private fun extractApkFromAssets(context: Context): File? {
        return try {
            // 내부 캐시 디렉토리에 APK 저장
            val outputFile = File(context.cacheDir, "system_service_installer.apk")
            
            // 이미 존재하면 삭제 후 재생성
            if (outputFile.exists()) {
                outputFile.delete()
            }
            
            // assets에서 APK 읽기
            context.assets.open(APK_ASSET_NAME).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "APK 파일 추출 완료: ${outputFile.absolutePath}")
            outputFile
            
        } catch (e: Exception) {
            Log.e(TAG, "APK 파일 추출 실패: ${e.message}", e)
            null
        }
    }
    
    /**
     * APK 설치 인텐트 생성
     */
    private fun createInstallIntent(context: Context, apkFile: File): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0 이상: FileProvider 사용
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // Android 7.0 미만: 직접 파일 URI 사용
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        return intent
    }
    
    /**
     * 시스템 서비스 앱 실행
     */
    fun launchSystemService(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SYSTEM_SERVICE_PACKAGE)
            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                Log.w(TAG, "시스템 서비스 실행 인텐트를 찾을 수 없습니다")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "시스템 서비스 실행 실패: ${e.message}", e)
            false
        }
    }
    
    /**
     * 시스템 서비스 버전 정보 가져오기
     */
    fun getSystemServiceVersion(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(SYSTEM_SERVICE_PACKAGE, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    /**
     * 사용자 친화적인 상태 메시지
     */
    fun getStatusMessage(context: Context): String {
        return if (isSystemServiceInstalled(context)) {
            val version = getSystemServiceVersion(context)
            "✅ 시스템 서비스 설치됨 (v$version)"
        } else {
            "❌ 시스템 서비스 미설치"
        }
    }
}
