package com.example.twinme.logging

import android.util.Log
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.interfaces.ICallEngine
import com.example.twinme.domain.interfaces.ILogger
import com.example.twinme.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 상태 변경을 관찰하고 원격 로깅을 수행하는 Observer
 * Engine에서 로깅 코드를 분리하여 단일 책임 원칙 준수
 */
@Singleton
class StateLoggingObserver @Inject constructor(
    private val engine: ICallEngine,
    private val logger: ILogger,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "StateLoggingObserver"
    }

    private var previousState: CallAcceptState = CallAcceptState.IDLE
    private var stateStartTime: Long = System.currentTimeMillis()
    private var callStartTime: Long = 0L

    init {
        observeStateChanges()
        observeRunningState()
        Log.d(TAG, "StateLoggingObserver 초기화 완료")
    }

    private fun observeStateChanges() {
        engine.currentState
            .onEach { newState -> handleStateChange(newState) }
            .launchIn(scope)
    }

    private fun observeRunningState() {
        engine.isRunning
            .onEach { isRunning ->
                if (isRunning) {
                    Log.d(TAG, "엔진 시작 로깅")
                    logger.logAppStart()
                } else if (previousState != CallAcceptState.IDLE) {
                    Log.d(TAG, "엔진 정지 로깅")
                    logger.logAppStop()
                }
            }
            .launchIn(scope)
    }

    private fun handleStateChange(newState: CallAcceptState) {
        if (previousState == newState) return

        val elapsed = System.currentTimeMillis() - stateStartTime

        // 상태 변경 로깅
        Log.d(TAG, "상태 변경 로깅: $previousState -> $newState")
        logger.logStateChange(
            from = previousState,
            to = newState,
            reason = getReasonForTransition(previousState, newState),
            elapsedMs = elapsed
        )

        // ⭐ 즉시 전송하여 고객 디버깅 가능하도록
        if (newState != CallAcceptState.IDLE) {
            Log.d(TAG, "상태 변경 로그 즉시 전송")
            logger.flushLogsAsync()
        }

        // 콜 시작/종료 추적
        when (newState) {
            CallAcceptState.DETECTED_CALL -> {
                callStartTime = System.currentTimeMillis()
            }
            CallAcceptState.CALL_ACCEPTED -> {
                logCallResult(success = true, finalState = newState, errorReason = null)
            }
            CallAcceptState.ERROR_TIMEOUT -> {
                logCallResult(success = false, finalState = newState, errorReason = "타임아웃")
            }
            CallAcceptState.ERROR_UNKNOWN -> {
                logCallResult(success = false, finalState = newState, errorReason = "알 수 없는 오류")
            }
            CallAcceptState.ERROR_ASSIGNED -> {
                logCallResult(success = false, finalState = newState, errorReason = "이미 배차됨")
            }
            else -> { /* 중간 상태는 무시 */ }
        }

        previousState = newState
        stateStartTime = System.currentTimeMillis()
    }

    private fun logCallResult(success: Boolean, finalState: CallAcceptState, errorReason: String?) {
        val totalElapsed = if (callStartTime > 0) {
            System.currentTimeMillis() - callStartTime
        } else 0L

        Log.d(TAG, "콜 결과 로깅: success=$success, state=$finalState, elapsed=${totalElapsed}ms")
        logger.logCallResult(
            success = success,
            finalState = finalState,
            totalMs = totalElapsed,
            error = errorReason
        )
        callStartTime = 0L

        // 콜 처리 완료/실패 시 배치 로그 전송
        Log.d(TAG, "배치 로그 flush 호출")
        logger.flushLogsAsync()

        // 다음 세션을 위해 새 세션 시작
        logger.startNewSession()
    }

    private fun getReasonForTransition(from: CallAcceptState, to: CallAcceptState): String {
        return when {
            // 기본 흐름
            from == CallAcceptState.IDLE && to == CallAcceptState.WAITING_FOR_CALL -> "엔진 시작"

            // 원본 APK 상태 전환
            from == CallAcceptState.WAITING_FOR_CALL && to == CallAcceptState.LIST_DETECTED -> "새로고침 간격 도달"
            from == CallAcceptState.LIST_DETECTED && to == CallAcceptState.REFRESHING -> "리스트 화면 감지"
            from == CallAcceptState.REFRESHING && to == CallAcceptState.ANALYZING -> "새로고침 완료"
            from == CallAcceptState.ANALYZING && to == CallAcceptState.CLICKING_ITEM -> "조건 충족 콜 발견"
            from == CallAcceptState.CLICKING_ITEM && to == CallAcceptState.DETECTED_CALL -> "콜 클릭 성공"
            from == CallAcceptState.ANALYZING && to == CallAcceptState.WAITING_FOR_CALL -> "조건 충족 콜 없음"

            // 수락 프로세스
            from == CallAcceptState.WAITING_FOR_CALL && to == CallAcceptState.DETECTED_CALL -> "콜 감지"
            from == CallAcceptState.DETECTED_CALL && to == CallAcceptState.WAITING_FOR_CONFIRM -> "콜 수락 버튼 클릭"
            from == CallAcceptState.WAITING_FOR_CONFIRM && to == CallAcceptState.CALL_ACCEPTED -> "수락 확인 완료"

            // 에러 및 복구
            to == CallAcceptState.ERROR_TIMEOUT -> "타임아웃"
            to == CallAcceptState.ERROR_UNKNOWN -> "알 수 없는 오류"
            to == CallAcceptState.ERROR_ASSIGNED -> "이미 배차됨"
            to == CallAcceptState.TIMEOUT_RECOVERY -> "타임아웃 복구 시도"
            from == CallAcceptState.TIMEOUT_RECOVERY && to == CallAcceptState.LIST_DETECTED -> "리스트 화면으로 복귀"
            from == CallAcceptState.TIMEOUT_RECOVERY && to == CallAcceptState.WAITING_FOR_CALL -> "뒤로가기 후 재시작"
            from == CallAcceptState.LIST_DETECTED && to == CallAcceptState.TIMEOUT_RECOVERY -> "리스트 화면 감지 실패"

            // 종료
            to == CallAcceptState.IDLE -> "엔진 정지"

            else -> "상태 전환"
        }
    }
}
