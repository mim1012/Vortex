package com.example.twinme.domain.state

import com.example.twinme.data.CallAcceptState

/**
 * 상태 핸들러 처리 결과
 */
sealed class StateResult {
    /**
     * 다른 상태로 전환
     * @param nextState 전환할 다음 상태
     * @param reason 전환 이유
     */
    data class Transition(val nextState: CallAcceptState, val reason: String) : StateResult()

    /**
     * 상태 변경 없음
     */
    object NoChange : StateResult()

    /**
     * 에러 발생
     * @param errorState 전환할 에러 상태
     * @param reason 에러 이유
     */
    data class Error(val errorState: CallAcceptState, val reason: String) : StateResult()

    /**
     * 엔진 일시정지 후 상태 전환 (원본 APK SUCCESS 상태용)
     * @param nextState 전환할 다음 상태 (보통 IDLE)
     * @param reason 전환 이유
     */
    data class PauseAndTransition(val nextState: CallAcceptState, val reason: String) : StateResult()
}
