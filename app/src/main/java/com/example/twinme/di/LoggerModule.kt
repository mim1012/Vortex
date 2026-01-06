package com.example.twinme.di

import android.content.Context
// import com.example.twinme.BuildConfig  // Temporarily commented for build
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.interfaces.ILogger
import com.example.twinme.logging.LocalLogger
import com.example.twinme.logging.RemoteLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Logger 의존성 제공 모듈
 * - 개발 모드 (BuildConfig.DEBUG): LocalLogger (adb logcat 전용)
 * - 프로덕션 모드: RemoteLogger (Railway 전송)
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggerModule {

    @Provides
    @Singleton
    fun provideLogger(
        @ApplicationContext context: Context
    ): ILogger {
        RemoteLogger.init(context)

        // ⭐ 임시로 RemoteLogger 사용 (BuildConfig 에러 회피)
        // TODO: BuildConfig.DEBUG로 변경
        android.util.Log.i("LoggerModule", "🚀 RemoteLogger 활성화")
        return RemoteLoggerAdapter

        // 원래 코드 (BuildConfig 복구 후 사용):
        // return if (BuildConfig.DEBUG) {
        //     android.util.Log.i("LoggerModule", "🔧 개발 모드: LocalLogger 활성화")
        //     LocalLogger()
        // } else {
        //     android.util.Log.i("LoggerModule", "🚀 프로덕션 모드: RemoteLogger 활성화")
        //     RemoteLoggerAdapter
        // }
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
        error: String?,
        callKey: String
    ) {
        RemoteLogger.logCallResult(success, finalState, totalMs, error)
        // TODO Phase 8: callKey 파라미터를 RemoteLogger에 전달
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
        rejectReason: String?,
        confidence: String,
        debugInfo: Map<String, Any>,
        callKey: String,
        collectedText: String
    ) {
        RemoteLogger.logCallParsed(index, source, destination, price, callType, reservationTime, eligible, rejectReason, confidence, debugInfo)
        // TODO Phase 8: callKey, collectedText 파라미터를 RemoteLogger에 전달
    }

    override fun logAcceptStep(
        step: Int,
        stepName: String,
        targetId: String,
        buttonFound: Boolean,
        clickSuccess: Boolean,
        elapsedMs: Long,
        callKey: String
    ) {
        RemoteLogger.logAcceptStep(step, stepName, targetId, buttonFound, clickSuccess, elapsedMs)
        // TODO Phase 8: callKey 파라미터를 RemoteLogger에 전달
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

    // ============ Phase 2: 화면 전환 검증 로그 ============

    override fun logScreenCheck(
        state: CallAcceptState,
        targetButtonVisible: Boolean,
        screenTextSummary: String,
        callKey: String
    ) {
        // TODO Phase 8: RemoteLogger에 logScreenCheck 구현 후 연결
        // 현재는 no-op (LocalLogger에서만 작동)
    }

    // ============ Phase 2: 타임아웃 컨텍스트 로그 ============

    override fun logTimeoutContext(
        state: CallAcceptState,
        lastAction: String,
        retryCount: Int,
        elapsedMs: Long,
        callKey: String
    ) {
        // TODO Phase 8: RemoteLogger에 logTimeoutContext 구현 후 연결
        // 현재는 no-op (LocalLogger에서만 작동)
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
