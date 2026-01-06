package com.example.twinme.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.logging.RemoteLogger

object CallAcceptEngine {

    private const val TAG = "CallAcceptEngine"
    private const val TIMEOUT_MS = 10000L // 10초

    private var currentState: CallAcceptState = CallAcceptState.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var isRunning = false

    // 상태 시작 시간 (소요시간 계산용)
    private var stateStartTime: Long = 0
    private var callStartTime: Long = 0

    // 상태 변경 리스너
    var onStateChanged: ((CallAcceptState) -> Unit)? = null

    fun getCurrentState(): CallAcceptState = currentState

    fun isRunning(): Boolean = isRunning

    fun start() {
        if (isRunning) return
        Log.d(TAG, "매크로 시작")
        isRunning = true
        changeState(CallAcceptState.WAITING_FOR_CALL, "매크로 시작됨")
    }

    fun stop() {
        if (!isRunning) return
        Log.d(TAG, "매크로 정지")
        isRunning = false
        resetTimeout()
        changeState(CallAcceptState.IDLE, "매크로 정지됨")
    }

    fun changeState(newState: CallAcceptState, reason: String = "") {
        if (currentState == newState) return

        val previousState = currentState
        val elapsedMs = if (stateStartTime > 0) System.currentTimeMillis() - stateStartTime else 0

        Log.d(TAG, "상태 변경: $currentState -> $newState (이유: $reason)")
        currentState = newState
        stateStartTime = System.currentTimeMillis()

        // 콜 감지 시작 시간 기록
        if (newState == CallAcceptState.DETECTED_CALL) {
            callStartTime = System.currentTimeMillis()
        }

        // 원격 로깅
        RemoteLogger.logStateChange(
            fromState = previousState,
            toState = newState,
            reason = reason,
            elapsedMs = elapsedMs
        )

        // 최종 결과 로깅
        if (newState == CallAcceptState.CALL_ACCEPTED) {
            val totalElapsed = if (callStartTime > 0) System.currentTimeMillis() - callStartTime else 0
            RemoteLogger.logCallResult(
                success = true,
                finalState = newState,
                totalElapsedMs = totalElapsed
            )
            callStartTime = 0
        } else if (newState == CallAcceptState.ERROR_TIMEOUT ||
                   newState == CallAcceptState.ERROR_UNKNOWN ||
                   newState == CallAcceptState.ERROR_ASSIGNED) {
            val totalElapsed = if (callStartTime > 0) System.currentTimeMillis() - callStartTime else 0
            RemoteLogger.logCallResult(
                success = false,
                finalState = newState,
                totalElapsedMs = totalElapsed,
                errorReason = reason
            )
            callStartTime = 0
        }

        // UI 스레드에서 리스너 호출 보장
        handler.post { onStateChanged?.invoke(currentState) }

        // 타임아웃 설정
        resetTimeout()
        if (newState != CallAcceptState.IDLE && newState != CallAcceptState.CALL_ACCEPTED && newState != CallAcceptState.ERROR_ASSIGNED) {
            startTimeout()
        }
    }

    private fun startTimeout() {
        timeoutRunnable = Runnable {
            changeState(CallAcceptState.ERROR_TIMEOUT, "상태 변경 타임아웃")
        }
        handler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)
    }

    private fun resetTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun processNode(rootNode: AccessibilityNodeInfo) {
        if (!isRunning) return

        when (currentState) {
            CallAcceptState.WAITING_FOR_CALL -> {
                // 1. 콜 상세 화면 감지
                val acceptButton = findNodeByViewId(rootNode, "com.kakao.taxi.driver:id/btn_call_accept")
                if (acceptButton != null && acceptButton.isClickable) {
                    changeState(CallAcceptState.DETECTED_CALL, "콜 상세 화면 감지")
                    clickAcceptButton(acceptButton)
                }
            }
            CallAcceptState.WAITING_FOR_CONFIRM -> {
                // 2. 수락 확인 다이얼로그 감지
                val confirmButton = findNodeByViewId(rootNode, "com.kakao.taxi.driver:id/btn_positive")
                if (confirmButton != null && confirmButton.isClickable) {
                    // changeState(CallAcceptState.CONFIRMING_CALL, "수락 확인 다이얼로그 감지")
                    clickConfirmButton(confirmButton)
                }
            }
            else -> {
                // 다른 상태에서는 처리 안함
            }
        }
    }

    private fun clickAcceptButton(node: AccessibilityNodeInfo) {
        Log.d(TAG, "[1단계] 콜 수락 버튼 클릭 시도")
        val clickStartTime = System.currentTimeMillis()
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 노드 클릭 로깅
        RemoteLogger.logNodeClick(
            nodeId = "com.kakao.taxi.driver:id/btn_call_accept",
            success = success,
            currentState = currentState,
            elapsedMs = elapsedMs
        )

        if (success) {
            changeState(CallAcceptState.WAITING_FOR_CONFIRM, "콜 수락 버튼 클릭 성공")
        } else {
            changeState(CallAcceptState.ERROR_UNKNOWN, "콜 수락 버튼 클릭 실패")
        }
    }

    private fun clickConfirmButton(node: AccessibilityNodeInfo) {
        Log.d(TAG, "[2단계] 수락하기 버튼 클릭 시도")
        val clickStartTime = System.currentTimeMillis()
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 노드 클릭 로깅
        RemoteLogger.logNodeClick(
            nodeId = "com.kakao.taxi.driver:id/btn_positive",
            success = success,
            currentState = currentState,
            elapsedMs = elapsedMs
        )

        if (success) {
            changeState(CallAcceptState.CALL_ACCEPTED, "수락하기 버튼 클릭 성공")
        } else {
            changeState(CallAcceptState.ERROR_UNKNOWN, "수락하기 버튼 클릭 실패")
        }
    }

    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) nodes[0] else null
    }
}
