package com.example.twinme.di

import android.content.Context
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.interfaces.IFilterSettings
import com.example.twinme.domain.interfaces.ITimeSettings
import com.example.twinme.domain.interfaces.IUiSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Settings 의존성 제공 모듈
 * SettingsManager를 각 인터페이스로 제공
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * SettingsManager 싱글톤 제공
     */
    @Provides
    @Singleton
    fun provideSettingsManager(
        @ApplicationContext context: Context
    ): SettingsManager {
        return SettingsManager.getInstance(context)
    }

    /**
     * IFilterSettings 인터페이스 제공
     * SettingsManager가 이미 IFilterSettings를 구현하므로 그대로 반환
     */
    @Provides
    @Singleton
    fun provideFilterSettings(
        settingsManager: SettingsManager
    ): IFilterSettings = settingsManager

    /**
     * ITimeSettings 인터페이스 제공
     * SettingsManager가 이미 ITimeSettings를 구현하므로 그대로 반환
     */
    @Provides
    @Singleton
    fun provideTimeSettings(
        settingsManager: SettingsManager
    ): ITimeSettings = settingsManager

    /**
     * IUiSettings 인터페이스 제공
     * SettingsManager가 이미 IUiSettings를 구현하므로 그대로 반환
     */
    @Provides
    @Singleton
    fun provideUiSettings(
        settingsManager: SettingsManager
    ): IUiSettings = settingsManager
}
