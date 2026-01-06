package com.example.twinme.domain.interfaces

import com.example.twinme.data.CallAcceptState

interface ILogger {
    // 기존 즉시 전송 로그
    fun logStateChange(from: CallAcceptState, to: CallAcceptState, reason: String, elapsedMs: Long)
    fun logNodeClick(nodeId: String, success: Boolean, state: CallAcceptState, elapsedMs: Long)
    fun logCallResult(success: Boolean, finalState: CallAcceptState, totalMs: Long, error: String?, callKey: String = "")
    fun logConfigChange(configType: String, beforeValue: Any?, afterValue: Any?)
    fun logAuth(success: Boolean, identifier: String, userType: String?, message: String?)
    fun logAppStart()
    fun logAppStop()
    fun logError(type: String, message: String, stackTrace: String?)

    // 배치 로깅 (버퍼에 추가, 즉시 전송 안 함)
    fun logCallListDetected(screenDetected: Boolean, containerType: String, itemCount: Int, parsedCount: Int)
    fun logCallParsed(
        index: Int,
        source: String,
        destination: String,
        price: Int,
        callType: String,
        reservationTime: String,
        eligible: Boolean,
        rejectReason: String?,
        confidence: String = "UNKNOWN",  // Phase 1: 파싱 신뢰도 ("HIGH", "LOW", "UNKNOWN")
        debugInfo: Map<String, Any> = emptyMap(),  // Phase 1: 디버깅 정보
        callKey: String = "",  // Phase 2: 콜 식별자 추적
        collectedText: String = ""  // Phase 2: 원본 텍스트
    )
    fun logAcceptStep(step: Int, stepName: String, targetId: String, buttonFound: Boolean, clickSuccess: Boolean, elapsedMs: Long, callKey: String = "")
    fun logParsingFailed(index: Int, missingFields: List<String>, collectedText: String, reason: String)
    fun logButtonSearchFailed(currentState: CallAcceptState, targetViewId: String, searchDepth: Int, nodeDescription: String)

    // 버퍼 관리
    fun flush()
    fun flushLogsAsync()
    fun startNewSession()
    fun shutdown()

    // Phase 2: 화면 전환 검증 로그
    /**
     * 화면 상태 스냅샷 로그
     * - 버튼 검색 전에 호출
     * - 화면 전환 성공/실패 구분
     */
    fun logScreenCheck(
        state: CallAcceptState,
        targetButtonVisible: Boolean,
        screenTextSummary: String,
        callKey: String = ""
    )

    // Phase 2: 타임아웃 컨텍스트 로그
    /**
     * 타임아웃 컨텍스트 로그
     * - 타임아웃 발생 시 마지막 상태 스냅샷
     */
    fun logTimeoutContext(
        state: CallAcceptState,
        lastAction: String,
        retryCount: Int,
        elapsedMs: Long,
        callKey: String = ""
    )
}
