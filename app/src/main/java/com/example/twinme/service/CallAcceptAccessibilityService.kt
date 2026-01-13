package com.example.twinme.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.twinme.auth.AuthManager
import com.example.twinme.domain.interfaces.ICallEngine
import dagger.hilt.android.AndroidEntryPoint
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Random
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * CallAcceptAccessibilityService - MediaEnhanced 방식 완전 이식
 *
 * 핵심 변경:
 * 1. View ID 우선 검색 (btn_call_accept)
 * 2. 클릭 불가능 시 getParent() 사용
 * 3. performAction + dispatchGesture 하이브리드 클릭
 * 4. rootInActiveWindow는 recycle() 하지 않음 (시스템 관리)
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

        // ⭐ MediaEnhanced 방식: View ID 우선 (1순위)
        private const val VIEW_ID_BTN_CALL_ACCEPT = "com.kakao.taxi.driver:id/btn_call_accept"
        private const val VIEW_ID_BTN_ACCEPT = "com.kakao.taxi.driver:id/btn_accept"  // MediaEnhanced Config.java:23
        private const val VIEW_ID_BTN_POSITIVE = "com.kakao.taxi.driver:id/btn_positive"

        // ⭐ 부모 노드 View IDs (버튼 클릭 안 될 때 대안)
        private const val VIEW_ID_FL_CALL_ACCEPT = "com.kakao.taxi.driver:id/fl_call_accept"  // FrameLayout (버튼의 부모)
        private const val VIEW_ID_LL_CALL_ACCEPT = "com.kakao.taxi.driver:id/ll_call_accept"  // LinearLayout (더 상위 부모)

        // 텍스트 기반 검색 (2순위 - 백업)
        private val ACCEPT_BUTTON_TEXTS = listOf("수락", "직접결제 수락", "자동결제 수락", "콜 수락")
        private val CONFIRM_BUTTON_TEXTS = listOf("수락하기", "확인")

        // 랜덤 오프셋 범위 (픽셀) - 더 크게 (봇 탐지 회피)
        private const val CLICK_OFFSET_MAX = 40

        // ⭐⭐⭐ D3 최적화 터치 설정
        private const val TOUCH_DURATION_MIN = 80L   // 최소 duration
        private const val TOUCH_DURATION_MAX = 130L  // 최대 duration
        private const val THROTTLE_MIN_MS = 1100L    // 재시도 최소 간격 (KakaoT 1초 쓰로틀링 회피)
        private const val THROTTLE_MAX_MS = 1400L    // 재시도 최대 간격

        // ⭐⭐⭐ 실험 모드: X좌표 스윕 테스트
        // true로 설정하면 X좌표를 0.1 → 0.9로 스윕하면서 히트박스 찾기
        private const val EXPERIMENT_MODE = false  // 실험 모드 OFF → 원본 APK 방식 사용
        private val X_RATIOS = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)  // 중앙 근처 집중
        private val Y_RATIOS = listOf(0.4f, 0.5f, 0.6f)  // Y도 스윕 (40%, 50%, 60%)
        private const val EXPERIMENT_INTERVAL_MS = 1200L  // 1.2초 간격
    }

    @Inject
    lateinit var engine: ICallEngine

    // MediaEnhanced 방식: ThreadPool 사용
    private val threadPool = Executors.newCachedThreadPool()

    // ⭐ 클릭 디바운스: KakaoT Driver의 1초 쓰로틀링 고려
    private var lastClickTime: Long = 0
    private val CLICK_DEBOUNCE_MS = 1100L  // 1.1초 (KakaoT 1초 쓰로틀링 + 여유)

    // ⭐ 터치 시각화 오버레이
    private var windowManager: WindowManager? = null
    private var touchIndicator: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ⭐ 실험 모드 상태 변수
    private var experimentRunning = false
    private var currentXRatioIndex = 0
    private var currentYRatioIndex = 0  // Y도 스윕
    private var experimentButtonBounds: android.graphics.Rect? = null

    // ⭐⭐⭐ Media_enhanced 방식: 버튼 노드 캐시!
    // rootNode가 아닌 버튼 노드 자체를 저장해야 stale 문제 없음
    private var cachedAcceptButton: AccessibilityNodeInfo? = null
    private var cachedConfirmButton: AccessibilityNodeInfo? = null

    /**
     * ⭐⭐⭐ D3 최적화: 안전영역 데이터 클래스
     * 버튼 가장자리를 회피하여 확실한 터치 영역 확보
     */
    data class SafeArea(
        val cx: Float,    // 중앙 X
        val cy: Float,    // 중앙 Y
        val minX: Float,  // 안전영역 최소 X
        val maxX: Float,  // 안전영역 최대 X
        val minY: Float,  // 안전영역 최소 Y
        val maxY: Float   // 안전영역 최대 Y
    )

    /**
     * ⭐ 안전영역 계산 (가장자리 회피)
     * 최소 6px 또는 15% 마진
     */
    private fun computeSafeArea(bounds: android.graphics.Rect): SafeArea {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        // 가장자리 회피: 최소 6px 또는 15%
        val mx = maxOf(6f, w * 0.15f)
        val my = maxOf(6f, h * 0.15f)

        val minX = bounds.left + mx
        val maxX = bounds.right - mx
        val minY = bounds.top + my
        val maxY = bounds.bottom - my

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f

        return SafeArea(cx, cy, minX, maxX, minY, maxY)
    }

    /**
     * ⭐ 흔들림(jitter) 생성 - 안전영역 내부에서 ±1~3px 랜덤 이동
     * 직선 금지, 작은 이동으로 인간적 터치 흉내
     */
    private fun jitter(base: Float, min: Float, max: Float, deltaMin: Float = 1f, deltaMax: Float = 3f): Float {
        val delta = deltaMin + (Math.random() * (deltaMax - deltaMin)).toFloat()
        val sign = if (Math.random() < 0.5) -1 else 1
        val value = base + sign * delta
        return value.coerceIn(min, max)
    }

    /**
     * ⭐⭐⭐ D3 최적화 터치 duration (80~130ms)
     */
    private fun randomTouchDuration(): Long {
        return TOUCH_DURATION_MIN + Random().nextInt((TOUCH_DURATION_MAX - TOUCH_DURATION_MIN).toInt() + 1)
    }

    /**
     * ⭐⭐⭐ D3 최적화 쓰로틀 대기 시간 (1100~1400ms)
     */
    private fun randomThrottleDelay(): Long {
        return THROTTLE_MIN_MS + Random().nextInt((THROTTLE_MAX_MS - THROTTLE_MIN_MS).toInt() + 1)
    }

    /**
     * ⭐⭐⭐ D3 최적화 통과형 dispatchGesture
     *
     * 핵심 설계:
     * - DOWN: 안전영역 중앙
     * - MOVE: 2~3회, ±1~3px, 안전영역 내부만
     * - UP: 반드시 중앙으로 회귀 (가장 중요!)
     * - Duration: 80~130ms
     *
     * 이 버튼은 UP 시점에만 판정한다. MOVE는 인간성 보강용.
     */
    private fun dispatchHumanLikeTap(
        bounds: android.graphics.Rect,
        onDone: (() -> Unit)? = null
    ) {
        val safe = computeSafeArea(bounds)
        val durationMs = randomTouchDuration()

        Log.d(TAG, "🎯 [D3 터치] bounds=$bounds, 중앙=(${safe.cx}, ${safe.cy}), duration=${durationMs}ms")

        val path = Path().apply {
            // DOWN - 안전영역 중앙
            moveTo(safe.cx, safe.cy)

            // MOVE #1 - jitter
            lineTo(
                jitter(safe.cx, safe.minX, safe.maxX),
                jitter(safe.cy, safe.minY, safe.maxY)
            )

            // MOVE #2 - jitter
            lineTo(
                jitter(safe.cx, safe.minX, safe.maxX),
                jitter(safe.cy, safe.minY, safe.maxY)
            )

            // MOVE #3 - 가끔 (40% 확률)
            if (Math.random() < 0.4) {
                lineTo(
                    jitter(safe.cx, safe.minX, safe.maxX),
                    jitter(safe.cy, safe.minY, safe.maxY)
                )
            }

            // ⭐ UP - 반드시 중앙 회귀 (핵심!)
            lineTo(safe.cx, safe.cy)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✅ [D3 터치] 완료: 중앙=(${safe.cx}, ${safe.cy})")
                    onDone?.invoke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ [D3 터치] 취소됨")
                }
            },
            null
        )
    }

    /**
     * 다이얼로그 확인 및 로그 출력 (실험용)
     * @return 다이얼로그 발견 시 true
     */
    private fun checkDialogAndLog(experimentName: String): Boolean {
        val dialogFound = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(VIEW_ID_BTN_POSITIVE)?.isNotEmpty() == true
        if (dialogFound) {
            Log.i(TAG, "🎉🎉🎉 [$experimentName] 다이얼로그 발견! btn_positive 있음!")
            checkAndClickDialog()  // 바로 클릭
            return true
        } else {
            Log.w(TAG, "❌ [$experimentName 후] 다이얼로그 없음")
            return false
        }
    }

    /**
     * 다이얼로그 확인 후 클릭 (실험용 헬퍼)
     */
    private fun checkAndClickDialog() {
        rootInActiveWindow?.let { root ->
            val dialogButton = root.findAccessibilityNodeInfosByViewId(
                VIEW_ID_BTN_POSITIVE
            ).firstOrNull()

            if (dialogButton != null) {
                Log.d(TAG, "✅✅✅ [실험 성공] 다이얼로그 표시! btn_positive 발견!")
                val btnRect = android.graphics.Rect()
                dialogButton.getBoundsInScreen(btnRect)
                dispatchHumanLikeTap(btnRect)
                Log.d(TAG, "🖱️ [확인버튼] D3 터치 전송")
            } else {
                Log.w(TAG, "⏳ [실험 후] 다이얼로그 미표시 - btn_positive 없음")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this  // Singleton instance 설정

        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "========================================")
        Log.d(TAG, "접근성 서비스 연결됨 (시각: $timestamp)")
        Log.d(TAG, "========================================")

        // 생명주기 이벤트 로깅 (문자열로 전송)
        com.example.twinme.logging.RemoteLogger.logConfigChange(
            configType = "LIFECYCLE",
            beforeValue = "SYSTEM",
            afterValue = "ACCESSIBILITY_SERVICE_CONNECTED"
        )

        // 인증 상태 확인
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            Log.w(TAG, "인증되지 않은 접근 - 서비스 비활성화")
            Toast.makeText(applicationContext, "인증되지 않은 접근입니다.", Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        // ⭐ WindowManager 초기화 (터치 시각화용)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        Log.d(TAG, "서비스 초기화 완료 - MediaEnhanced 방식 적용")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ⭐⭐⭐ v1.4 방식 복원: 이벤트마다 executeImmediate() 즉시 실행
        // 화면 변경 시 실시간 반응으로 안정성 향상

        // 인증 상태 재확인 (캐시 만료 시 엔진 정지)
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            // ⭐ 서비스 비활성화 대신 엔진만 정지
            if (engine.isRunning.value) {
                Log.e(TAG, "❌ 인증 만료 - 자동화 중단 (재인증 필요)")
                engine.stop()
                // 알림 또는 토스트로 사용자에게 알림 (옵션)
                android.widget.Toast.makeText(
                    applicationContext,
                    "인증 만료: 재인증이 필요합니다",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // 포그라운드 앱 패키지 체크
        val packageName = event?.packageName?.toString()
        if (packageName != "com.kakao.taxi.driver") {
            return
        }

        // ⭐⭐⭐ v1.4 방식 복원: 화면 변경 이벤트 시 즉시 실행
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                Log.d(TAG, "✅ [v1.4 복원] executeImmediate() 호출 - 이벤트: ${event.eventType}")
                engine.executeImmediate(rootNode)  // ✅ v1.4 방식 복원!
            }
        }
    }

    /**
     * ⭐⭐ 수락 버튼 검색 (부모 노드 강제 시도)
     *
     * 1순위: btn_call_accept View ID
     * 2순위: btn_accept View ID (MediaEnhanced Config.java:23)
     * 3순위: 부모 노드 강제 시도 (isClickable=true여도)
     * 4순위: 텍스트 검색
     *
     * @return 클릭 가능한 버튼 노드 (또는 부모 노드)
     */
    private fun findAcceptButtonWithForceParent(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1순위: btn_call_accept
        var node = tryFindByViewId(rootNode, VIEW_ID_BTN_CALL_ACCEPT)

        // 2순위: btn_accept (MediaEnhanced Config.java:23)
        if (node == null) {
            node = tryFindByViewId(rootNode, VIEW_ID_BTN_ACCEPT)
        }

        // 3순위: 텍스트 검색
        if (node == null) {
            for (text in ACCEPT_BUTTON_TEXTS) {
                val textNodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (textNodes.isNotEmpty()) {
                    node = textNodes[0]
                    Log.d(TAG, "🔍 [Text] '$text' 발견: clickable=${node.isClickable}")
                    break
                }
            }
        }

        if (node == null) {
            return null
        }

        // ⭐⭐ 원래 버튼 반환 (부모 노드는 performAction=false 반환)
        // 로그 분석 결과: 부모 노드(FrameLayout)는 클릭 불가
        // 원래 버튼(Button)을 그대로 사용
        val parent = node.parent
        if (parent != null) {
            Log.d(TAG, "🔍 [부모 노드 참고] class=${parent.className}, clickable=${parent.isClickable}")
        }

        Log.d(TAG, "🎯 [원래 버튼 사용] ${node.className}, clickable=${node.isClickable}")
        return if (node.isClickable) node else null
    }

    /**
     * View ID로 노드 검색
     */
    private fun tryFindByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) {
            val node = nodes[0]
            Log.d(TAG, "🔍 [ViewID] '$viewId' 발견: clickable=${node.isClickable}, class=${node.className}")
            node
        } else {
            null
        }
    }

    /**
     * ⭐ MediaEnhanced 정확한 방식:
     * 1순위: View ID로 버튼 검색
     * 2순위: 클릭 불가 시 즉시 부모 노드로 승격
     * 3순위: View ID 실패 시 텍스트 검색 (백업)
     */
    private fun findButtonByViewIdWithParentPromotion(
        rootNode: AccessibilityNodeInfo,
        viewId: String,
        fallbackTexts: List<String>
    ): AccessibilityNodeInfo? {
        // ⭐ 1순위: View ID로 검색
        val viewIdNodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        if (viewIdNodes.isNotEmpty()) {
            var node = viewIdNodes[0]
            Log.d(TAG, "🔍 [ViewID] '$viewId' 발견: clickable=${node.isClickable}, class=${node.className}")

            // ⭐ 2순위: 클릭 불가 시 즉시 부모 노드로 승격 (MediaEnhanced 핵심!)
            if (!node.isClickable) {
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    Log.d(TAG, "🔄 [부모승격] ${node.className} → ${parent.className}")
                    try { node.recycle() } catch (e: Exception) {}
                    node = parent
                }
            }

            // ⭐⭐ 부모 노드 강제 시도 (isClickable=true여도!)
            if (node.isClickable) {
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    Log.d(TAG, "🔼 [부모 노드 강제] ${node.className} → ${parent.className}")
                    return parent
                }
            }

            if (node.isClickable) {
                return node
            }
        }

        // ⭐ 3순위: View ID 실패 시 텍스트 검색 (백업)
        Log.d(TAG, "⚠️ [ViewID 실패] '$viewId' → 텍스트 검색 시도")
        for (text in fallbackTexts) {
            val textNodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (textNodes.isNotEmpty()) {
                var node = textNodes[0]
                Log.d(TAG, "🔍 [Text] '$text' 발견: clickable=${node.isClickable}")

                // 텍스트로 찾은 경우도 부모 승격 체크
                if (!node.isClickable) {
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        Log.d(TAG, "🔄 [부모승격] ${node.className} → ${parent.className}")
                        try { node.recycle() } catch (e: Exception) {}
                        node = parent
                    }
                }

                // 부모 노드 강제 시도
                if (node.isClickable) {
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        Log.d(TAG, "🔼 [부모 노드 강제] ${node.className} → ${parent.className}")
                        return parent
                    }
                }

                if (node.isClickable) {
                    return node
                }
            }
        }

        return null
    }

    /**
     * ⭐ 하이브리드 클릭: dispatchGesture 우선 + performAction 보조
     *
     * KakaoT Driver 앱이 performAction을 차단할 수 있으므로,
     * dispatchGesture를 우선 사용하고 performAction은 보조로 실행
     *
     * ⭐ 랜덤 오프셋 적용: 정확히 중앙이 아닌 약간의 오차를 줘서 봇 탐지 회피
     *
     * @param node 클릭할 버튼 노드
     */
    private fun hybridButtonClick(node: AccessibilityNodeInfo) {
        // 버튼의 bounds 가져오기
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        // ⭐ 랜덤 오프셋 적용 (-40 ~ +40 픽셀)
        val random = Random()
        val offsetX = random.nextInt(CLICK_OFFSET_MAX * 2 + 1) - CLICK_OFFSET_MAX
        val offsetY = random.nextInt(CLICK_OFFSET_MAX * 2 + 1) - CLICK_OFFSET_MAX
        val centerX = rect.centerX().toFloat() + offsetX
        val centerY = rect.centerY().toFloat() + offsetY

        Log.d(TAG, "🎯 [하이브리드] 버튼 좌표: ($centerX, $centerY), offset=($offsetX, $offsetY), bounds=$rect, clickable=${node.isClickable}")

        // 인간적인 랜덤 지연 (50-150ms)
        val delay = Random().nextInt(100) + 50

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // ⭐ 1차: dispatchGesture 우선 (performAction보다 신뢰성 높음)
                val gestureResult = performGestureClickInternal(centerX, centerY)
                Log.d(TAG, "🖱️ [1차] dispatchGesture 결과: $gestureResult")

                // 2차: performAction 보조 (isClickable 무시)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val performResult = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "🖱️ [2차] performAction 결과: $performResult")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ performAction 예외: ${e.message}")
                    }
                }, 100L)

            } catch (e: Exception) {
                Log.e(TAG, "❌ 클릭 실패: ${e.message}")
            }
        }, delay.toLong())
    }

    /**
     * 내부용 제스처 클릭 (dispatchGesture 사용)
     * ⭐ 인간형 터치 패턴: 미세 이동 + 60~120ms duration
     */
    private fun performGestureClickInternal(x: Float, y: Float): Boolean {
        return try {
            // ⭐ 인간형 터치 패턴 (미세 이동으로 봇 탐지 회피)
            val path = Path().apply {
                moveTo(x, y)                         // ACTION_DOWN
                lineTo(x + 1.2f, y + 0.6f)          // ACTION_MOVE #1
                lineTo(x + 0.4f, y + 1.1f)          // ACTION_MOVE #2 → ACTION_UP
            }

            // ⭐ Duration 60~120ms (인간 최소 터치 시간)
            val touchDuration = 60L + Random().nextInt(61)
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,     // startTime
                touchDuration
            )

            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            Log.d(TAG, "🖱️ [인간형터치] ($x, $y), duration=${touchDuration}ms")
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✅ [dispatchGesture] 완료: ($x, $y)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ [dispatchGesture] 취소: ($x, $y)")
                }
            }

            dispatchGesture(gesture, callback, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ dispatchGesture 실패: ${e.message}")
            false
        }
    }

    /**
     * MediaEnhanced 방식: 버튼 클릭 (Helper.delegateButtonClick 완전 이식)
     * - Handler + postDelayed
     * - 랜덤 딜레이 50-150ms
     * - performAction(ACTION_CLICK)
     * - recycle() 호출
     *
     * @deprecated hybridButtonClick 사용 권장
     */
    private fun delegateButtonClick(node: AccessibilityNodeInfo) {
        // 인간적인 랜덤 지연 (50-150ms)
        val delay = Random().nextInt(100) + 50

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "🖱️ [MediaEnhanced] performAction 결과: $success")

                // MediaEnhanced 방식: 사용 후 즉시 recycle
                try {
                    node.recycle()
                } catch (e: Exception) {
                    // 이미 recycled
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 클릭 실패: ${e.message}")
            }
        }, delay.toLong())
    }

    /**
     * ⭐⭐⭐ X좌표 스윕 실험
     * Y는 고정(65%), X를 0.1 → 0.9까지 1200ms 간격으로 스윕
     * 다이얼로그(btn_positive) 등장 여부로 히트박스 영역 확인
     */
    private fun startXSweepExperiment() {
        val bounds = experimentButtonBounds
        if (bounds == null) {
            Log.e(TAG, "🧪 [실험 오류] bounds가 null")
            experimentRunning = false
            return
        }

        // 화면 크기 가져오기
        val realDisplayMetrics = android.util.DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(realDisplayMetrics)
        val screenWidth = realDisplayMetrics.widthPixels
        val screenHeight = realDisplayMetrics.heightPixels

        // bounds가 이상하면 현재 rootNode에서 다시 찾기
        val boundsInvalid = bounds.left > screenWidth || bounds.right > screenWidth * 2
        val effectiveBounds = if (boundsInvalid) {
            // 다시 버튼 찾아서 유효한 bounds 사용
            val freshBounds = findValidButtonBounds()
            if (freshBounds != null && freshBounds.left <= screenWidth && freshBounds.right <= screenWidth * 2) {
                Log.d(TAG, "🧪 [실험] 유효 bounds 재발견: $freshBounds")
                freshBounds
            } else {
                // 그래도 없으면 화면 기준 하드코딩 (실측값 기반)
                // 유효 bounds 예시: Rect(28, 1974 - 1052, 2177)
                Log.w(TAG, "🧪 [실험] 유효 bounds 못 찾음 - 하드코딩 사용")
                android.graphics.Rect(28, 1974, 1052, 2177)
            }
        } else {
            bounds
        }

        Log.d(TAG, "🧪 [실험] 유효 bounds: $effectiveBounds (원본: $bounds, 보정: $boundsInvalid)")

        // 버튼 크기 계산
        val buttonWidth = effectiveBounds.right - effectiveBounds.left
        val buttonHeight = effectiveBounds.bottom - effectiveBounds.top

        Log.d(TAG, "🧪 [실험] buttonWidth=$buttonWidth, buttonHeight=$buttonHeight")
        Log.d(TAG, "🧪 [실험] X 비율: $X_RATIOS")
        Log.d(TAG, "🧪 [실험] Y 비율: $Y_RATIOS")
        Log.d(TAG, "🧪 [실험] 총 ${X_RATIOS.size * Y_RATIOS.size}개 조합 테스트")

        // 재귀적으로 다음 X,Y 비율 터치
        executeNextXYRatio(effectiveBounds, buttonWidth, buttonHeight)
    }

    /**
     * 실험: 다음 X,Y 비율에서 터치 실행
     */
    private fun executeNextXYRatio(bounds: android.graphics.Rect, buttonWidth: Int, buttonHeight: Int) {
        val totalCombinations = X_RATIOS.size * Y_RATIOS.size
        val currentIndex = currentYRatioIndex * X_RATIOS.size + currentXRatioIndex

        if (currentIndex >= totalCombinations) {
            Log.d(TAG, "🧪🧪🧪 [실험 완료] 모든 X,Y 조합 테스트 완료!")
            experimentRunning = false
            currentXRatioIndex = 0
            currentYRatioIndex = 0
            return
        }

        val xRatio = X_RATIOS[currentXRatioIndex]
        val yRatio = Y_RATIOS[currentYRatioIndex]
        val touchX = bounds.left + buttonWidth * xRatio
        val touchY = bounds.top + buttonHeight * yRatio

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🧪 [실험 ${currentIndex + 1}/${totalCombinations}] X=${xRatio}, Y=${yRatio}")
        Log.d(TAG, "🧪 터치 좌표: ($touchX, $touchY)")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // 빨간 점 표시
        showTouchIndicator(touchX, touchY)

        // ⭐ 인간형 터치 패턴으로 dispatchGesture 실행
        val path = Path().apply {
            moveTo(touchX, touchY)                     // ACTION_DOWN
            lineTo(touchX + 1.2f, touchY + 0.6f)      // ACTION_MOVE #1
            lineTo(touchX + 0.4f, touchY + 1.1f)      // ACTION_MOVE #2 → ACTION_UP
        }
        val duration = 60L + Random().nextInt(61)  // 60~120ms

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "🧪 [터치 완료] X=$xRatio, Y=$yRatio, 좌표=($touchX, $touchY)")

                // 500ms 후 다이얼로그 확인
                mainHandler.postDelayed({
                    checkDialogAndContinueXY(xRatio, yRatio, touchX, touchY, bounds, buttonWidth, buttonHeight)
                }, 500L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "🧪 [터치 취소] X=$xRatio, Y=$yRatio")
                // 다음 비율로 진행
                scheduleNextXYRatio(bounds, buttonWidth, buttonHeight)
            }
        }

        val result = dispatchGesture(gesture, callback, null)
        Log.d(TAG, "🧪 [dispatchGesture] 전송: $result")
    }

    /**
     * 다이얼로그 확인 후 다음 진행 (X,Y 스윕 버전)
     */
    private fun checkDialogAndContinueXY(
        xRatio: Float, yRatio: Float, touchX: Float, touchY: Float,
        bounds: android.graphics.Rect, buttonWidth: Int, buttonHeight: Int
    ) {
        rootInActiveWindow?.let { root ->
            val dialogButton = root.findAccessibilityNodeInfosByViewId(VIEW_ID_BTN_POSITIVE).firstOrNull()

            if (dialogButton != null) {
                Log.d(TAG, "✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅")
                Log.d(TAG, "✅ [실험 성공!] X=$xRatio, Y=$yRatio 에서 다이얼로그 표시됨!")
                Log.d(TAG, "✅ 성공 좌표: ($touchX, $touchY)")
                Log.d(TAG, "✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅")

                // 실험 종료 (성공)
                experimentRunning = false
                currentXRatioIndex = 0
                currentYRatioIndex = 0
            } else {
                Log.w(TAG, "❌ [실험 실패] X=$xRatio, Y=$yRatio - 다이얼로그 미표시")
                // 다음 비율로 진행
                scheduleNextXYRatio(bounds, buttonWidth, buttonHeight)
            }
        } ?: run {
            Log.e(TAG, "🧪 [오류] rootInActiveWindow null")
            scheduleNextXYRatio(bounds, buttonWidth, buttonHeight)
        }
    }

    /**
     * 다음 X,Y 비율 스케줄링
     */
    private fun scheduleNextXYRatio(bounds: android.graphics.Rect, buttonWidth: Int, buttonHeight: Int) {
        currentXRatioIndex++
        if (currentXRatioIndex >= X_RATIOS.size) {
            currentXRatioIndex = 0
            currentYRatioIndex++
        }

        val totalCombinations = X_RATIOS.size * Y_RATIOS.size
        val currentIndex = currentYRatioIndex * X_RATIOS.size + currentXRatioIndex

        if (currentIndex < totalCombinations) {
            Log.d(TAG, "🧪 ${EXPERIMENT_INTERVAL_MS}ms 후 다음 비율 테스트...")
            mainHandler.postDelayed({
                executeNextXYRatio(bounds, buttonWidth, buttonHeight)
            }, EXPERIMENT_INTERVAL_MS)
        } else {
            Log.d(TAG, "🧪🧪🧪 [실험 완료] 모든 X,Y 조합 테스트 완료 - 성공한 비율 없음")
            experimentRunning = false
            currentXRatioIndex = 0
            currentYRatioIndex = 0
        }
    }

    /**
     * 유효한 버튼 bounds 찾기
     * rootInActiveWindow에서 btn_call_accept를 찾아 화면 내 유효한 bounds 반환
     */
    private fun findValidButtonBounds(): android.graphics.Rect? {
        val realDisplayMetrics = android.util.DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(realDisplayMetrics)
        val screenWidth = realDisplayMetrics.widthPixels

        return rootInActiveWindow?.let { root ->
            val btnNodes = root.findAccessibilityNodeInfosByViewId(VIEW_ID_BTN_CALL_ACCEPT)
            for (node in btnNodes) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                // 화면 내에 있는 bounds인지 확인
                if (rect.left >= 0 && rect.left <= screenWidth && rect.right <= screenWidth * 2) {
                    Log.d(TAG, "🔍 유효 bounds 발견: $rect")
                    return@let rect
                }
            }
            null
        }
    }

    override fun onInterrupt() {
        val timestamp = System.currentTimeMillis()
        Log.w(TAG, "========================================")
        Log.w(TAG, "접근성 서비스 중단됨 (시각: $timestamp)")
        Log.w(TAG, "========================================")

        // 생명주기 이벤트 로깅
        com.example.twinme.logging.RemoteLogger.logError(
            errorType = "ACCESSIBILITY_SERVICE_INTERRUPTED",
            message = "onInterrupt 호출 - 시스템에 의한 서비스 중단",
            stackTrace = "timestamp: $timestamp"
        )
    }

    override fun onDestroy() {
        val timestamp = System.currentTimeMillis()
        Log.w(TAG, "========================================")
        Log.w(TAG, "접근성 서비스 파괴됨 (시각: $timestamp)")
        Log.w(TAG, "========================================")

        // 1. 마지막 상태를 SharedPreferences에 기록 (동기식 - 무조건 성공)
        com.example.twinme.logging.RemoteLogger.recordLastState(
            event = "ACCESSIBILITY_SERVICE_DESTROYED",
            details = """
                timestamp: $timestamp
                Shizuku 상태: ${if (com.example.twinme.utils.ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
                Shizuku 종료 후 경과: ${com.example.twinme.utils.ShizukuLifecycleTracker.getTimeSinceShizukuDeath()}ms
                인스턴스: ${if (instance != null) "살아있음" else "null"}
            """.trimIndent()
        )

        // 2. 동기식 에러 로깅 (프로세스 종료 전 완료 보장)
        try {
            com.example.twinme.logging.RemoteLogger.logErrorSync(
                errorType = "ACCESSIBILITY_SERVICE_DESTROYED",
                message = "onDestroy 호출 - 서비스 완전 종료",
                stackTrace = """
                    timestamp: $timestamp
                    Shizuku 상태: ${if (com.example.twinme.utils.ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
                    Shizuku 종료 후 경과: ${com.example.twinme.utils.ShizukuLifecycleTracker.getTimeSinceShizukuDeath()}ms
                    인스턴스: ${if (instance != null) "살아있음" else "null"}
                """.trimIndent()
            )
        } catch (e: Exception) {
            Log.e(TAG, "동기식 로깅 실패: ${e.message}")
        }

        // ⭐ 엔진 정지 및 리소스 정리
        engine.stop()
        engine.cleanup()  // 추가: 엔진 내부 리소스 정리

        super.onDestroy()
        instance = null  // Singleton instance 해제
        threadPool.shutdown()  // ThreadPool 정리
        mainHandler.removeCallbacksAndMessages(null)  // ⭐ 추가: 모든 pending runnable 제거
        removeTouchIndicator()  // 오버레이 제거
        Log.d(TAG, "서비스 종료 완료 - 모든 리소스 정리됨")
    }

    /**
     * ⭐ 터치 위치에 빨간 레이저 점 표시
     * 실제 터치가 어디에서 일어나는지 시각적으로 확인 가능
     *
     * @param x 터치 X 좌표
     * @param y 터치 Y 좌표
     */
    private fun showTouchIndicator(x: Float, y: Float) {
        mainHandler.post {
            try {
                // 기존 인디케이터 제거
                removeTouchIndicator()

                // 빨간 원형 뷰 생성 (30dp 크기)
                val indicatorSize = (30 * resources.displayMetrics.density).toInt()

                touchIndicator = View(this).apply {
                    setBackgroundColor(Color.RED)
                    alpha = 0.8f
                }

                val params = WindowManager.LayoutParams(
                    indicatorSize,
                    indicatorSize,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 좌표에서 인디케이터 크기의 절반을 빼서 중심이 터치 위치에 오도록
                    this.x = (x - indicatorSize / 2).toInt()
                    this.y = (y - indicatorSize / 2).toInt()
                }

                windowManager?.addView(touchIndicator, params)
                Log.d(TAG, "🔴 [레이저 점] 표시: ($x, $y)")

                // 1초 후 자동 제거
                mainHandler.postDelayed({
                    removeTouchIndicator()
                }, 1000L)

            } catch (e: Exception) {
                Log.e(TAG, "❌ [레이저 점] 표시 실패: ${e.message}")
            }
        }
    }

    /**
     * 터치 인디케이터 제거
     */
    private fun removeTouchIndicator() {
        try {
            touchIndicator?.let {
                windowManager?.removeView(it)
                touchIndicator = null
            }
        } catch (e: Exception) {
            // 이미 제거됨
        }
    }

    /**
     * 상태바 높이 가져오기
     */
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    /**
     * 네비게이션바 높이 가져오기
     */
    private fun getNavigationBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    /**
     * 제스처 기반 클릭 실행 (원본 APK 방식)
     * AccessibilityNodeInfo.performAction() 대신 dispatchGesture() 사용
     *
     * ⭐ 인간형 터치 패턴: 미세 이동 + 60~120ms duration
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 제스처 전송 성공 여부
     */
    fun performGestureClick(x: Float, y: Float): Boolean {
        return try {
            // 1. Path 생성 - 인간형 터치 패턴 (미세 이동)
            val path = Path().apply {
                moveTo(x, y)                         // ACTION_DOWN
                lineTo(x + 1.2f, y + 0.6f)          // ACTION_MOVE #1
                lineTo(x + 0.4f, y + 1.1f)          // ACTION_MOVE #2 → ACTION_UP
            }

            // 2. StrokeDescription 생성 - duration 60~120ms (인간 최소 터치 시간)
            val touchDuration = 60L + Random().nextInt(61)
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,     // startTime
                touchDuration
            )

            // 3. GestureDescription 빌드
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            // 4. dispatchGesture() 호출 with 콜백
            var gestureCompleted = false
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "✅ 제스처 완료 콜백: ($x, $y) duration=150ms")
                    gestureCompleted = true
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ 제스처 취소됨: ($x, $y)")
                    gestureCompleted = false
                }
            }

            val success = dispatchGesture(gesture, callback, null)

            // 제스처 완료 대기 (최대 300ms)
            if (success) {
                Thread.sleep(300)
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

    // ============================================
    // ⭐⭐⭐ SHIZUKU API 통합 (시스템 레벨 input tap)
    // ============================================

    /**
     * Shizuku 권한 요청 코드
     */
    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    /**
     * Shizuku 바인더 상태 체크
     * @return Shizuku 사용 가능 여부
     */
    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ [Shizuku] pingBinder 실패: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 권한 체크
     * @return 권한 있음 여부
     */
    private fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) {
                Log.w(TAG, "⚠️ [Shizuku] 바인더 연결 안됨")
                return false
            }

            if (Shizuku.isPreV11()) {
                // Shizuku v11 이전 버전
                Log.w(TAG, "⚠️ [Shizuku] v11 이전 버전 - 권한 체크 불가")
                return false
            }

            val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "🔑 [Shizuku] 권한 상태: $granted")
            granted

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Shizuku] 권한 체크 실패: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 권한 요청
     */
    private fun requestShizukuPermission() {
        try {
            if (!isShizukuAvailable()) {
                Log.e(TAG, "❌ [Shizuku] 바인더 연결 안됨 - 권한 요청 불가")
                return
            }

            if (Shizuku.isPreV11()) {
                Log.w(TAG, "⚠️ [Shizuku] v11 이전 버전 - 권한 요청 불가")
                return
            }

            if (!hasShizukuPermission()) {
                Log.d(TAG, "🔑 [Shizuku] 권한 요청 중...")
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Shizuku] 권한 요청 실패: ${e.message}")
        }
    }

    /**
     * ⭐⭐⭐ Shizuku를 통한 input tap 실행 (Reflection 사용)
     * Shizuku.newProcess가 private이므로 Reflection으로 접근
     * ADB 권한으로 실행되어 봇 탐지 우회 가능
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 명령 실행 성공 여부
     */
    fun shizukuInputTap(x: Int, y: Int): Boolean {
        return try {
            // 1. Shizuku 사용 가능 여부 확인
            if (!isShizukuAvailable()) {
                Log.e(TAG, "❌ [Shizuku] 바인더 연결 안됨")
                return false
            }

            // 2. 권한 확인
            if (!hasShizukuPermission()) {
                Log.w(TAG, "⚠️ [Shizuku] 권한 없음 - 요청 시도")
                requestShizukuPermission()
                return false
            }

            // 3. Reflection으로 Shizuku.newProcess 호출
            // (newProcess가 private으로 변경됨)
            val command = arrayOf("sh", "-c", "input tap $x $y")
            Log.d(TAG, "🚀 [Shizuku] Reflection으로 명령 실행: input tap $x $y")

            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val remoteProcess = method.invoke(null, command, null, null) as ShizukuRemoteProcess

            // 4. 출력 읽기
            val output = remoteProcess.inputStream.bufferedReader().use { it.readText() }
            val error = remoteProcess.errorStream.bufferedReader().use { it.readText() }
            val exitCode = remoteProcess.waitFor()
            remoteProcess.destroy()

            // 5. 결과 확인
            if (exitCode == 0) {
                Log.i(TAG, "✅ [Shizuku] input tap 성공! ($x, $y)")
                true
            } else {
                Log.e(TAG, "❌ [Shizuku] input tap 실패: exitCode=$exitCode, error=$error")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Shizuku] input tap 예외: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ Shizuku input tap + 인간적 랜덤 지연
     * 봇 탐지 회피를 위한 랜덤 딜레이 포함
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 명령 실행 성공 여부
     */
    fun shizukuInputTapWithDelay(x: Int, y: Int): Boolean {
        return try {
            // 인간적 랜덤 지연 (50-150ms)
            val delay = 50L + Random().nextInt(100)
            Thread.sleep(delay)
            Log.d(TAG, "🎲 [Shizuku] 랜덤 지연: ${delay}ms")

            shizukuInputTap(x, y)

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Shizuku] 지연 tap 실패: ${e.message}")
            false
        }
    }

    /**
     * ⭐⭐⭐ 메인 클릭 함수 - Shizuku 우선, dispatchGesture fallback
     * btn_call_accept 클릭 시 이 함수 사용
     *
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 성공 여부
     */
    fun smartClick(x: Int, y: Int): Boolean {
        Log.d(TAG, "🎯 [SmartClick] 좌표: ($x, $y)")

        // 1차 시도: Shizuku input tap (봇 탐지 우회)
        if (isShizukuAvailable() && hasShizukuPermission()) {
            Log.d(TAG, "🚀 [SmartClick] Shizuku 사용")
            val result = shizukuInputTapWithDelay(x, y)
            if (result) {
                Log.i(TAG, "✅ [SmartClick] Shizuku 성공!")
                return true
            }
            Log.w(TAG, "⚠️ [SmartClick] Shizuku 실패 → dispatchGesture fallback")
        } else {
            Log.w(TAG, "⚠️ [SmartClick] Shizuku 불가 → dispatchGesture 사용")
        }

        // 2차 시도: dispatchGesture (fallback)
        val gestureResult = performGestureClick(x.toFloat(), y.toFloat())
        Log.d(TAG, "🖱️ [SmartClick] dispatchGesture 결과: $gestureResult")
        return gestureResult
    }
}
