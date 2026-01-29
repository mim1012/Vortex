package com.example.twinme

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.twinme.utils.ShizukuStatusChecker
import com.example.twinme.utils.SystemServiceInstaller
import rikka.shizuku.Shizuku

/**
 * 시스템 서비스 설정 가이드 액티비티
 * 
 * 사용자에게 시스템 서비스(Shizuku) 설치, 활성화, 권한 부여 과정을 안내합니다.
 * "Shizuku" 용어 대신 "시스템 서비스"로 표현하여 정체를 숨깁니다.
 */
class ShizukuGuideActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShizukuGuideActivity"
        private const val REQUEST_CODE_PERMISSION = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        // 사용자에게는 "시스템 서비스"로 표현
        private const val SERVICE_NAME = "시스템 서비스"
    }

    // UI 요소
    private lateinit var tvShizukuInstalled: TextView
    private lateinit var tvShizukuRunning: TextView
    private lateinit var tvShizukuPermission: TextView
    private lateinit var btnRefreshStatus: Button
    private lateinit var btnInstallService: Button
    private lateinit var btnOpenShizuku: Button
    private lateinit var btnRequestPermission: Button
    private lateinit var btnComplete: Button

    // Shizuku 권한 요청 리스너
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_PERMISSION) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ $SERVICE_NAME 권한이 부여되었습니다", Toast.LENGTH_SHORT).show()
                    updateStatus()
                } else {
                    Toast.makeText(this, "❌ $SERVICE_NAME 권한이 거부되었습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizuku_guide)

        // UI 초기화
        initViews()
        
        // 버튼 리스너 설정
        setupListeners()
        
        // Shizuku 권한 리스너 등록
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        
        // 초기 상태 업데이트
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shizuku 권한 리스너 해제
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    override fun onResume() {
        super.onResume()
        // 화면 복귀 시 상태 업데이트
        updateStatus()
    }

    /**
     * UI 요소 초기화
     */
    private fun initViews() {
        tvShizukuInstalled = findViewById(R.id.tv_shizuku_installed)
        tvShizukuRunning = findViewById(R.id.tv_shizuku_running)
        tvShizukuPermission = findViewById(R.id.tv_shizuku_permission)
        btnRefreshStatus = findViewById(R.id.btn_refresh_status)
        btnInstallService = findViewById(R.id.btn_download_shizuku)
        btnOpenShizuku = findViewById(R.id.btn_open_shizuku)
        btnRequestPermission = findViewById(R.id.btn_request_permission)
        btnComplete = findViewById(R.id.btn_complete)
    }

    /**
     * 버튼 리스너 설정
     */
    private fun setupListeners() {
        // 상태 새로고침
        btnRefreshStatus.setOnClickListener {
            updateStatus()
            Toast.makeText(this, "상태를 업데이트했습니다", Toast.LENGTH_SHORT).show()
        }

        // 시스템 서비스 설치
        btnInstallService.setOnClickListener {
            installSystemService()
        }

        // 시스템 서비스 앱 열기
        btnOpenShizuku.setOnClickListener {
            openSystemServiceApp()
        }

        // 권한 요청
        btnRequestPermission.setOnClickListener {
            requestShizukuPermission()
        }

        // 설정 완료
        btnComplete.setOnClickListener {
            finish()
        }
    }

    /**
     * 시스템 서비스 상태 업데이트
     */
    private fun updateStatus() {
        // 1. 시스템 서비스 설치 여부
        val isInstalled = SystemServiceInstaller.isSystemServiceInstalled(this)
        tvShizukuInstalled.text = if (isInstalled) "✅ 설치됨" else "❌ 미설치"
        tvShizukuInstalled.setTextColor(
            getColor(if (isInstalled) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        // 2. 시스템 서비스 실행 여부
        val isRunning = ShizukuStatusChecker.isShizukuAvailable()
        tvShizukuRunning.text = if (isRunning) "✅ 실행 중" else "❌ 정지됨"
        tvShizukuRunning.setTextColor(
            getColor(if (isRunning) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        // 3. 권한 부여 여부
        val hasPermission = ShizukuStatusChecker.hasShizukuPermission()
        tvShizukuPermission.text = if (hasPermission) "✅ 부여됨" else "❌ 미부여"
        tvShizukuPermission.setTextColor(
            getColor(if (hasPermission) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        // 버튼 활성화 상태 업데이트
        btnInstallService.isEnabled = !isInstalled
        btnOpenShizuku.isEnabled = isInstalled
        btnRequestPermission.isEnabled = isRunning && !hasPermission
        btnComplete.isEnabled = isInstalled && isRunning && hasPermission
    }

    /**
     * 시스템 서비스 설치
     */
    private fun installSystemService() {
        val success = SystemServiceInstaller.startInstallation(this)
        if (success) {
            Toast.makeText(
                this,
                "$SERVICE_NAME 설치를 시작합니다\n설치 후 활성화해주세요",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "$SERVICE_NAME 설치 실패\n다시 시도해주세요",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 시스템 서비스 앱 열기
     */
    private fun openSystemServiceApp() {
        val success = SystemServiceInstaller.launchSystemService(this)
        if (!success) {
            Toast.makeText(this, "$SERVICE_NAME 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 시스템 서비스 권한 요청
     */
    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "$SERVICE_NAME 버전이 너무 낮습니다. 업데이트해주세요.", Toast.LENGTH_LONG).show()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "이미 권한이 부여되어 있습니다", Toast.LENGTH_SHORT).show()
                updateStatus()
                return
            }

            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(
                    this,
                    "$SERVICE_NAME 앱에서 직접 권한을 부여해주세요",
                    Toast.LENGTH_LONG
                ).show()
                openSystemServiceApp()
                return
            }

            // 권한 요청
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Exception) {
            Toast.makeText(this, "권한 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
