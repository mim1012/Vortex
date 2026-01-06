package com.example.twinme.di

import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.handlers.AnalyzingHandler
import com.example.twinme.domain.state.handlers.CallAcceptedHandler
import com.example.twinme.domain.state.handlers.ClickingItemHandler
import com.example.twinme.domain.state.handlers.DetectedCallHandler
import com.example.twinme.domain.state.handlers.ErrorUnknownHandler
import com.example.twinme.domain.state.handlers.IdleHandler
import com.example.twinme.domain.state.handlers.ListDetectedHandler
import com.example.twinme.domain.state.handlers.RefreshingHandler
import com.example.twinme.domain.state.handlers.TimeoutRecoveryHandler
import com.example.twinme.domain.state.handlers.WaitingForCallHandler
import com.example.twinme.domain.state.handlers.WaitingForConfirmHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * State Pattern 핸들러를 Hilt에 등록하는 모듈 (원본 APK 방식)
 *
 * 상태 흐름:
 * IDLE → WAITING_FOR_CALL → LIST_DETECTED → REFRESHING → ANALYZING → CLICKING_ITEM
 * → DETECTED_CALL → WAITING_FOR_CONFIRM → CALL_ACCEPTED
 *
 * 핸들러 역할:
 * - IdleHandler: IDLE 상태 (엔진 정지)
 * - WaitingForCallHandler: WAITING_FOR_CALL 상태 (즉시 LIST_DETECTED로 전환)
 * - ListDetectedHandler: LIST_DETECTED 상태 (화면 감지 + 시간 체크)
 * - RefreshingHandler: REFRESHING 상태 (새로고침 버튼 클릭)
 * - AnalyzingHandler: ANALYZING 상태 (콜 파싱 & 필터링)
 * - ClickingItemHandler: CLICKING_ITEM 상태 (콜 아이템 클릭)
 * - DetectedCallHandler: DETECTED_CALL 상태 (콜 수락 버튼 클릭)
 * - WaitingForConfirmHandler: WAITING_FOR_CONFIRM 상태 (확인 버튼 클릭)
 * - CallAcceptedHandler: CALL_ACCEPTED 상태 (수락 완료 후 자동 리셋)
 * - TimeoutRecoveryHandler: TIMEOUT_RECOVERY 상태 (타임아웃 후 자동 복구)
 * - ErrorUnknownHandler: ERROR_UNKNOWN 상태 (일시적 에러 복구)
 */
@Module
@InstallIn(SingletonComponent::class)
object StateModule {
    @Provides
    @IntoSet
    fun provideIdleHandler(): StateHandler = IdleHandler()

    @Provides
    @IntoSet
    fun provideWaitingForCallHandler(): StateHandler = WaitingForCallHandler()

    @Provides
    @IntoSet
    @Singleton
    fun provideListDetectedHandler(settingsManager: SettingsManager): StateHandler =
        ListDetectedHandler(settingsManager)

    @Provides
    @IntoSet
    fun provideRefreshingHandler(): StateHandler = RefreshingHandler()

    @Provides
    @IntoSet
    fun provideAnalyzingHandler(): StateHandler = AnalyzingHandler()

    @Provides
    @IntoSet
    @Singleton
    fun provideClickingItemHandler(): StateHandler = ClickingItemHandler()

    @Provides
    @IntoSet
    @Singleton
    fun provideDetectedCallHandler(): StateHandler = DetectedCallHandler()

    @Provides
    @IntoSet
    fun provideWaitingForConfirmHandler(): StateHandler = WaitingForConfirmHandler()

    @Provides
    @IntoSet
    fun provideCallAcceptedHandler(): StateHandler = CallAcceptedHandler()

    @Provides
    @IntoSet
    fun provideTimeoutRecoveryHandler(): StateHandler = TimeoutRecoveryHandler()

    @Provides
    @IntoSet
    fun provideErrorUnknownHandler(): StateHandler = ErrorUnknownHandler()
}
