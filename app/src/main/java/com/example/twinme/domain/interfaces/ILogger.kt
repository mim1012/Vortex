package com.example.twinme.domain.interfaces

import com.example.twinme.data.CallAcceptState

interface ILogger {
    // 기존 즉시 전송 로그
    fun logStateChange(from: CallAcceptState, to: CallAcceptState, reason: String, elapsedMs: Long)
    fun logNodeClick(nodeId: String, success: Boolean, state: CallAcceptState, elapsedMs: Long)
    fun logCallResult(success: Boolean, finalState: CallAcceptState, totalMs: Long, error: String?)
    fun logConfigChange(configType: String, beforeValue: Any?, afterValue: Any?)
    fun logAuth(success: Boolean, identifier: String, userType: String?, message: String?)
    fun logAppStart()
    fun logAppStop()
    fun logError(type: String, message: String, stackTrace: String?)

    // 배치 로깅 (버퍼에 추가, 즉시 전송 안 함)
    fun logCallListDetected(screenDetected: Boolean, containerType: String, itemCount: Int, parsedCount: Int)
    fun logCallParsed(index: Int, source: String, destination: String, price: Int, callType: String, reservationTime: String, eligible: Boolean, rejectReason: String?)
    fun logAcceptStep(step: Int, stepName: String, targetId: String, buttonFound: Boolean, clickSuccess: Boolean, elapsedMs: Long)
    fun logParsingFailed(index: Int, missingFields: List<String>, collectedText: String, reason: String)
    fun logButtonSearchFailed(currentState: CallAcceptState, targetViewId: String, searchDepth: Int, nodeDescription: String)

    // 버퍼 관리
    fun flush()
    fun flushLogsAsync()
    fun startNewSession()
    fun shutdown()
}
