package com.example.twinme.di

import com.example.twinme.domain.interfaces.ICallEngine
import com.example.twinme.engine.CallAcceptEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    @Singleton
    abstract fun bindCallEngine(impl: CallAcceptEngineImpl): ICallEngine
}
