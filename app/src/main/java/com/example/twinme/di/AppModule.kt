package com.example.twinme.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application 레벨의 의존성 제공 모듈
 * - Application Context
 * - CoroutineScope (앱 전역 스코프)
 * - ApplicationScope (Hilt 제공)
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 앱 전역 CoroutineScope 제공
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * Application 레벨 CoroutineScope을 구분하기 위한 Qualifier
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
