package com.example.twinme.engine

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.interfaces.ICallEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CallAcceptViewModel @Inject constructor(
    private val engine: ICallEngine
) : ViewModel() {

    val currentState: LiveData<CallAcceptState> = engine.currentState.asLiveData()
    val isRunning: LiveData<Boolean> = engine.isRunning.asLiveData()

    fun start() = engine.start()
    fun stop() = engine.stop()
}
