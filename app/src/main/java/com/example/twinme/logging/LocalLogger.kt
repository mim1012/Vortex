package com.example.twinme.logging

import android.util.Log
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.interfaces.ILogger

/**
 * 개발 모드 전용 로거 (adb logcat 출력)
 * - RemoteLogger 대체용
 * - 네트워크 전송 없음, 즉시 로그 출력
 * - 워크플로우 6단계 검증 지원
 *
 * 검증 단계:
 * 1️⃣ 시작 버튼 → 설정 로드 확인
 * 2️⃣ 새로고침 주기적 동작 확인
 * 3️⃣ 콜 리스트 파싱 + 조건 매칭 확인
 * 4️⃣ 조건 충족 콜 아이템 클릭 확인
 * 5️⃣ 콜 수락 버튼 감지 + 클릭 확인
 * 6️⃣ 수락하기 확인 버튼 감지 + 클릭 확인
 */
class LocalLogger : ILogger {

    companion object {
        private const val TAG = "🔧LocalLogger"
        private const val DIVIDER = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    }

    // ============================================
    // 1️⃣ 시작 버튼 → 설정 로드 확인
    // ============================================

    override fun logAppStart() {
        Log.i(TAG, DIVIDER)
        Log.i(TAG, "1️⃣ [앱 시작] Vortex 자동 수락 엔진 초기화")
        Log.i(TAG, DIVIDER)
    }

    override fun logAuth(success: Boolean, identifier: String, userType: String?, message: String?) {
        val icon = if (success) "✅" else "❌"
        Log.i(TAG, "1️⃣ $icon [인증] success=$success, user=$userType, msg=$message")
    }

    override fun logConfigChange(configType: String, beforeValue: Any?, afterValue: Any?) {
        Log.i(TAG, "1️⃣ [설정 변경] $configType: $beforeValue → $afterValue")
    }

    // ============================================
    // 상태 전환 (모든 단계)
    // ============================================

    override fun logStateChange(from: CallAcceptState, to: CallAcceptState, reason: String, elapsedMs: Long) {
        val step = getStepForState(to)
        Log.d(TAG, "$step 🔄 [상태 전환] $from → $to (${elapsedMs}ms)")
        Log.d(TAG, "$step    이유: $reason")
    }

    // ============================================
    // 2️⃣ 새로고침 주기적 동작 확인
    // ============================================

    override fun logNodeClick(nodeId: String, success: Boolean, state: CallAcceptState, elapsedMs: Long) {
        val icon = if (success) "✅" else "❌"

        // 새로고침 버튼 클릭 감지
        if (nodeId.contains("refresh", ignoreCase = true)) {
            Log.d(TAG, "2️⃣ $icon [새로고침 버튼 클릭] $nodeId (${elapsedMs}ms)")
        } else {
            val step = getStepForState(state)
            Log.d(TAG, "$step $icon [버튼 클릭] $nodeId @ $state (${elapsedMs}ms)")
        }
    }

    // ============================================
    // 3️⃣ 콜 리스트 파싱 + 조건 매칭 확인
    // ============================================

    override fun logCallListDetected(screenDetected: Boolean, containerType: String, itemCount: Int, parsedCount: Int) {
        val icon = if (screenDetected) "✅" else "❌"
        Log.d(TAG, DIVIDER)
        Log.d(TAG, "3️⃣ $icon [콜 리스트 화면 감지]")
        Log.d(TAG, "3️⃣    컨테이너: $containerType")
        Log.d(TAG, "3️⃣    총 아이템 수: $itemCount")
        Log.d(TAG, "3️⃣    파싱 성공: ${parsedCount}개")
        Log.d(TAG, DIVIDER)
    }

    override fun logCallParsed(
        index: Int,
        source: String,
        destination: String,
        price: Int,
        callType: String,
        reservationTime: String,
        eligible: Boolean,
        rejectReason: String?,
        confidence: String,
        debugInfo: Map<String, Any>,
        callKey: String,
        collectedText: String
    ) {
        val icon = if (eligible) "✅" else "❌"

        // 기본 정보
        Log.d(TAG, "3️⃣ $icon [콜 #$index] $source → $destination")
        Log.d(TAG, "3️⃣    💰 금액: ${String.format("%,d", price)}원 | 📅 타입: $callType")

        // 예약시간
        if (reservationTime.isNotEmpty()) {
            Log.d(TAG, "3️⃣    ⏰ 예약시간: $reservationTime")
        }

        // 파싱 신뢰도 (Phase 2: 신규)
        Log.d(TAG, "3️⃣    🔍 신뢰도: $confidence")

        // 원본 텍스트 (Phase 2: 신규)
        if (collectedText.isNotEmpty()) {
            Log.v(TAG, "3️⃣    🧾 원본 텍스트: \"$collectedText\"")
        }

        // 조건 충족 여부
        Log.d(TAG, "3️⃣    🎯 조건충족: $eligible")

        // 거부 사유
        if (!eligible && rejectReason != null) {
            Log.w(TAG, "3️⃣    ⚠️ 거부사유: $rejectReason")
        }

        // 수락 표시
        if (eligible) {
            Log.i(TAG, "3️⃣    ⭐ 이 콜을 수락하려고 시도합니다!")
        }

        // 콜 식별자 (Phase 2: 신규)
        if (callKey.isNotEmpty()) {
            Log.d(TAG, "3️⃣    🔑 콜 식별자: $callKey")
        }

        // 디버그 정보
        if (debugInfo.isNotEmpty()) {
            Log.v(TAG, "3️⃣    🐛 디버그 정보: $debugInfo")
        }
    }

    override fun logParsingFailed(index: Int, missingFields: List<String>, collectedText: String, reason: String) {
        Log.w(TAG, "3️⃣ ❌ [파싱 실패 #$index] $reason")
        Log.w(TAG, "3️⃣    누락 필드: ${missingFields.joinToString(", ")}")
        Log.w(TAG, "3️⃣    수집된 텍스트: $collectedText")
    }

    // ============================================
    // 4️⃣~6️⃣ 콜 아이템 클릭 및 버튼 클릭 확인
    // ============================================

    override fun logAcceptStep(
        step: Int,
        stepName: String,
        targetId: String,
        buttonFound: Boolean,
        clickSuccess: Boolean,
        elapsedMs: Long,
        callKey: String
    ) {
        val icon = if (clickSuccess) "✅" else "❌"

        // 단계별 분류
        when (stepName) {
            "CLICKING_ITEM" -> {
                Log.d(TAG, DIVIDER)
                Log.d(TAG, "4️⃣ $icon [콜 아이템 클릭 시도]")
                Log.d(TAG, "4️⃣    버튼 발견: $buttonFound")
                Log.d(TAG, "4️⃣    클릭 성공: $clickSuccess")
                Log.d(TAG, "4️⃣    소요 시간: ${elapsedMs}ms")
                if (callKey.isNotEmpty()) {
                    Log.d(TAG, "4️⃣    🔑 콜: $callKey")
                }
                Log.d(TAG, DIVIDER)
            }
            "DETECTED_CALL" -> {
                Log.d(TAG, "5️⃣ $icon [콜 수락 버튼 클릭 시도]")
                Log.d(TAG, "5️⃣    대상 ID: $targetId")
                Log.d(TAG, "5️⃣    버튼 발견: $buttonFound")
                Log.d(TAG, "5️⃣    클릭 성공: $clickSuccess")
                Log.d(TAG, "5️⃣    소요 시간: ${elapsedMs}ms")
                if (callKey.isNotEmpty()) {
                    Log.d(TAG, "5️⃣    🔑 콜: $callKey")
                }
            }
            "WAITING_FOR_CONFIRM" -> {
                Log.d(TAG, "6️⃣ $icon [수락하기 확인 버튼 클릭 시도]")
                Log.d(TAG, "6️⃣    대상 ID: $targetId")
                Log.d(TAG, "6️⃣    버튼 발견: $buttonFound")
                Log.d(TAG, "6️⃣    클릭 성공: $clickSuccess")
                Log.d(TAG, "6️⃣    소요 시간: ${elapsedMs}ms")
                if (callKey.isNotEmpty()) {
                    Log.d(TAG, "6️⃣    🔑 콜: $callKey")
                }
            }
            else -> {
                Log.d(TAG, "⚙️ [단계 $step] $stepName - $targetId (found=$buttonFound, success=$clickSuccess, ${elapsedMs}ms)")
            }
        }
    }

    override fun logButtonSearchFailed(
        currentState: CallAcceptState,
        targetViewId: String,
        searchDepth: Int,
        nodeDescription: String
    ) {
        val step = getStepForState(currentState)
        Log.w(TAG, "$step 🔍 [버튼 검색 실패] $targetViewId @ $currentState")
        Log.w(TAG, "$step    검색 깊이: $searchDepth")
        Log.w(TAG, "$step    노드 정보: $nodeDescription")
    }

    // ============================================
    // Phase 2: 화면 전환 검증 로그
    // ============================================

    override fun logScreenCheck(
        state: CallAcceptState,
        targetButtonVisible: Boolean,
        screenTextSummary: String,
        callKey: String
    ) {
        val step = getStepForState(state)
        val icon = if (targetButtonVisible) "✅" else "❌"
        Log.d(TAG, "$step 🔍 [화면 검증] $state")
        Log.d(TAG, "$step    $icon 목표 버튼: ${if (targetButtonVisible) "VISIBLE" else "NOT_VISIBLE"}")
        Log.d(TAG, "$step    📄 화면 텍스트: \"$screenTextSummary\"")
        if (callKey.isNotEmpty()) {
            Log.d(TAG, "$step    🔑 콜: $callKey")
        }
    }

    // ============================================
    // Phase 2: 타임아웃 컨텍스트 로그
    // ============================================

    override fun logTimeoutContext(
        state: CallAcceptState,
        lastAction: String,
        retryCount: Int,
        elapsedMs: Long,
        callKey: String
    ) {
        Log.e(TAG, "💥 [타임아웃] $state (${elapsedMs}ms)")
        Log.e(TAG, "💥    마지막 액션: $lastAction")
        Log.e(TAG, "💥    재시도 횟수: ${retryCount}회")
        if (callKey.isNotEmpty()) {
            Log.e(TAG, "💥    🔑 콜: $callKey")
        }
    }

    // ============================================
    // 최종 결과
    // ============================================

    override fun logCallResult(success: Boolean, finalState: CallAcceptState, totalMs: Long, error: String?, callKey: String) {
        Log.i(TAG, DIVIDER)
        if (success) {
            Log.i(TAG, "🎉 [콜 수락 완료] 최종 상태: $finalState")
            Log.i(TAG, "🎉    총 소요 시간: ${totalMs}ms (${totalMs / 1000.0}초)")
            if (callKey.isNotEmpty()) {
                Log.i(TAG, "🎉    🔑 콜: $callKey")
            }
        } else {
            Log.e(TAG, "💥 [콜 수락 실패] 최종 상태: $finalState")
            Log.e(TAG, "💥    에러: ${error ?: "알 수 없는 오류"}")
            Log.e(TAG, "💥    총 소요 시간: ${totalMs}ms")
            if (callKey.isNotEmpty()) {
                Log.e(TAG, "💥    🔑 콜: $callKey")
            }
        }
        Log.i(TAG, DIVIDER)
    }

    override fun logError(type: String, message: String, stackTrace: String?) {
        Log.e(TAG, "💥 [에러] $type: $message")
        if (stackTrace != null) {
            Log.e(TAG, "💥    스택: $stackTrace")
        }
    }

    override fun logAppStop() {
        Log.i(TAG, DIVIDER)
        Log.i(TAG, "🛑 [앱 종료] Vortex 자동 수락 엔진 종료")
        Log.i(TAG, DIVIDER)
    }

    // ============================================
    // 배치 로깅 (LocalLogger는 즉시 출력이므로 no-op)
    // ============================================

    override fun flush() {
        // LocalLogger는 즉시 출력하므로 버퍼 관리 불필요
    }

    override fun flushLogsAsync() {
        // LocalLogger는 즉시 출력하므로 버퍼 관리 불필요
    }

    override fun startNewSession() {
        Log.d(TAG, "🔄 [새 세션 시작]")
    }

    override fun shutdown() {
        Log.d(TAG, "🛑 [로거 종료]")
    }

    // ============================================
    // 유틸리티
    // ============================================

    /**
     * 상태별 검증 단계 이모지 반환
     */
    private fun getStepForState(state: CallAcceptState): String {
        return when (state) {
            CallAcceptState.IDLE -> "⚪"
            CallAcceptState.WAITING_FOR_CALL -> "2️⃣"
            CallAcceptState.LIST_DETECTED -> "3️⃣"
            CallAcceptState.REFRESHING -> "2️⃣"
            CallAcceptState.ANALYZING -> "3️⃣"
            CallAcceptState.CLICKING_ITEM -> "4️⃣"
            CallAcceptState.DETECTED_CALL -> "5️⃣"
            CallAcceptState.WAITING_FOR_CONFIRM -> "6️⃣"
            CallAcceptState.CALL_ACCEPTED -> "🎉"
            CallAcceptState.TIMEOUT_RECOVERY -> "⚠️"
            CallAcceptState.ERROR_ASSIGNED,
            CallAcceptState.ERROR_TIMEOUT,
            CallAcceptState.ERROR_UNKNOWN -> "💥"
        }
    }
}
