package com.example.twinme.domain.interfaces

import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import kotlinx.coroutines.flow.StateFlow

interface ICallEngine {
    val currentState: StateFlow<CallAcceptState>
    val isRunning: StateFlow<Boolean>
    val isPaused: StateFlow<Boolean>

    // 자동 새로고침 활성화 여부
    val isAutoRefreshEnabled: StateFlow<Boolean>

    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun processNode(node: AccessibilityNodeInfo)

    // 자동 새로고침 설정
    fun setAutoRefreshEnabled(enabled: Boolean)
}
