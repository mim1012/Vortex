package com.example.twinme.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.twinme.auth.AuthManager
import com.example.twinme.domain.interfaces.ICallEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * CallAcceptAccessibilityService - 단순화됨
 *
 * 역할:
 * 1. 인증 확인
 * 2. rootNode를 엔진에 전달 (엔진이 메인 루프에서 사용)
 *
 * 제거됨:
 * - 자동 새로고침 타이머 (엔진의 메인 루프가 처리)
 * - observeEngineState() (불필요)
 * - startAutoRefresh() (불필요)
 * - stopAutoRefresh() (불필요)
 * - performRefresh() (엔진이 처리)
 */
@AndroidEntryPoint
class CallAcceptAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAcceptService"

        /**
         * Singleton instance for TimeoutRecoveryHandler to access performGlobalAction
         */
        var instance: CallAcceptAccessibilityService? = null
            private set
    }

    @Inject
    lateinit var engine: ICallEngine

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this  // Singleton instance 설정
        Log.d(TAG, "서비스 연결됨")

        // 인증 상태 확인
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            Log.w(TAG, "인증되지 않은 접근 - 서비스 비활성화")
            Toast.makeText(applicationContext, "인증되지 않은 접근입니다.", Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        Log.d(TAG, "서비스 초기화 완료 - 엔진이 메인 루프 제어")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 인증 상태 재확인 (캐시 만료 대비)
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            Log.w(TAG, "인증 캐시 만료 - 서비스 비활성화")
            disableSelf()
            return
        }

        // 포그라운드 앱 패키지 체크
        val packageName = event?.packageName?.toString()
        if (packageName != "com.kakao.taxi.driver") {
            Log.v(TAG, "다른 앱 이벤트 무시: $packageName")
            return
        }

        // 화면 변경 시 rootNode를 엔진에 전달
        // 엔진의 메인 루프에서 이 rootNode를 사용하여 새로고침 및 상태 처리
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            rootInActiveWindow?.let { rootNode ->
                // 엔진에 rootNode 전달 (캐시에 저장됨)
                engine.processNode(rootNode)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "서비스 중단")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null  // Singleton instance 해제
        Log.d(TAG, "서비스 종료")
    }

    /**
     * 제스처 기반 클릭 실행 (원본 APK 방식)
     * AccessibilityNodeInfo.performAction() 대신 dispatchGesture() 사용
     *
     * 원본 APK: MacroAccessibilityService.smali 라인 1865-1897
     * - Path 생성 및 moveTo(x, y)
     * - StrokeDescription(path, startTime=0, duration=100ms)
     * - GestureDescription 빌드
     * - dispatchGesture() 호출
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 제스처 전송 성공 여부
     */
    fun performGestureClick(x: Float, y: Float): Boolean {
        return try {
            // 1. Path 생성 - 짧은 스와이프로 변경 (터치 → 약간 이동 → 터치 종료)
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)  // 1픽셀 이동 (탭보다 확실한 터치 인식)
            }

            // 2. StrokeDescription 생성 - duration 50ms (빠른 탭)
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,    // startTime
                50L    // duration: 50ms (짧은 탭)
            )

            // 3. GestureDescription 빌드
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            // 4. dispatchGesture() 호출 with 콜백
            var gestureCompleted = false
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✅ 제스처 완료 콜백: ($x, $y)")
                    gestureCompleted = true
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 제스처 취소됨: ($x, $y)")
                    gestureCompleted = false
                }
            }

            val success = dispatchGesture(gesture, callback, null)

            // 제스처 완료 대기 (최대 200ms)
            if (success) {
                Thread.sleep(200)
            }

            Log.d(TAG, "제스처 클릭: ($x, $y) - dispatch=${success}, completed=$gestureCompleted")
            success

        } catch (e: Exception) {
            Log.e(TAG, "제스처 클릭 실패: ${e.message}", e)
            false
        }
    }

    /**
     * Shell 명령어로 input tap 실행 (ADB와 동일한 방식)
     * dispatchGesture가 작동하지 않는 경우 대안
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 명령 실행 성공 여부
     */
    fun performShellTap(x: Float, y: Float): Boolean {
        return try {
            val command = "input tap ${x.toInt()} ${y.toInt()}"
            Log.d(TAG, "🔧 Shell 명령 실행: $command")

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()

            Log.d(TAG, "🔧 Shell 결과: exitCode=$exitCode")
            exitCode == 0

        } catch (e: Exception) {
            Log.e(TAG, "🔧 Shell 명령 실패: ${e.message}", e)
            false
        }
    }
}
