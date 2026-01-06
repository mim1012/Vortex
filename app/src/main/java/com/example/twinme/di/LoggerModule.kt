package com.example.twinme.di

import android.content.Context
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.interfaces.ILogger
import com.example.twinme.logging.RemoteLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Logger 의존성 제공 모듈
 * RemoteLogger를 ILogger 인터페이스로 래핑하여 제공
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggerModule {

    @Provides
    @Singleton
    fun provideLogger(
        @ApplicationContext context: Context
    ): ILogger {
        // RemoteLogger 초기화
        RemoteLogger.init(context)
        return RemoteLoggerAdapter
    }
}

/**
 * RemoteLogger를 ILogger 인터페이스에 맞춰 어댑터 패턴으로 래핑
 */
object RemoteLoggerAdapter : ILogger {

    override fun logStateChange(
        from: CallAcceptState,
        to: CallAcceptState,
        reason: String,
        elapsedMs: Long
    ) {
        RemoteLogger.logStateChange(from, to, reason, elapsedMs)
    }

    override fun logNodeClick(
        nodeId: String,
        success: Boolean,
        state: CallAcceptState,
        elapsedMs: Long
    ) {
        RemoteLogger.logNodeClick(nodeId, success, state, elapsedMs)
    }

    override fun logCallResult(
        success: Boolean,
        finalState: CallAcceptState,
        totalMs: Long,
        error: String?
    ) {
        RemoteLogger.logCallResult(success, finalState, totalMs, error)
    }

    override fun logConfigChange(
        configType: String,
        beforeValue: Any?,
        afterValue: Any?
    ) {
        RemoteLogger.logConfigChange(configType, beforeValue, afterValue)
    }

    override fun logAuth(
        success: Boolean,
        identifier: String,
        userType: String?,
        message: String?
    ) {
        RemoteLogger.logAuth(success, identifier, userType, message)
    }

    override fun logAppStart() {
        RemoteLogger.logAppStart()
    }

    override fun logAppStop() {
        RemoteLogger.logAppStop()
    }

    override fun logError(type: String, message: String, stackTrace: String?) {
        RemoteLogger.logError(type, message, stackTrace)
    }

    // ============ 배치 로깅 메서드 ============

    override fun logCallListDetected(
        screenDetected: Boolean,
        containerType: String,
        itemCount: Int,
        parsedCount: Int
    ) {
        RemoteLogger.logCallListDetected(screenDetected, containerType, itemCount, parsedCount)
    }

    override fun logCallParsed(
        index: Int,
        source: String,
        destination: String,
        price: Int,
        callType: String,
        reservationTime: String,
        eligible: Boolean,
        rejectReason: String?
    ) {
        RemoteLogger.logCallParsed(index, source, destination, price, callType, reservationTime, eligible, rejectReason)
    }

    override fun logAcceptStep(
        step: Int,
        stepName: String,
        targetId: String,
        buttonFound: Boolean,
        clickSuccess: Boolean,
        elapsedMs: Long
    ) {
        RemoteLogger.logAcceptStep(step, stepName, targetId, buttonFound, clickSuccess, elapsedMs)
    }

    override fun logParsingFailed(
        index: Int,
        missingFields: List<String>,
        collectedText: String,
        reason: String
    ) {
        RemoteLogger.logParsingFailed(index, missingFields, collectedText, reason)
    }

    override fun logButtonSearchFailed(
        currentState: CallAcceptState,
        targetViewId: String,
        searchDepth: Int,
        nodeDescription: String
    ) {
        RemoteLogger.logButtonSearchFailed(currentState, targetViewId, searchDepth, nodeDescription)
    }

    override fun flush() {
        RemoteLogger.flushLogs()
    }

    override fun flushLogsAsync() {
        RemoteLogger.flushLogsAsync()
    }

    override fun startNewSession() {
        RemoteLogger.startNewSession()
    }

    override fun shutdown() {
        RemoteLogger.shutdown()
    }
}
