package com.example.twinme

import android.app.Application
import com.example.twinme.logging.StateLoggingObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VortexApplication : Application() {

    @Inject
    lateinit var stateLoggingObserver: StateLoggingObserver

    override fun onCreate() {
        super.onCreate()
        // stateLoggingObserver는 주입 시 init 블록에서 자동으로 관찰 시작
    }
}
