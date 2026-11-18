package com.ashish.scanphone.recover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CarveStatus(
    val running: Boolean = false,
    val bytesScanned: Long = 0,
    val filesCarved: Int = 0,
    val finishedAt: Long = 0,
    val message: String? = null,
)

object CarveController {

    private val _status = MutableStateFlow(CarveStatus())
    val status: StateFlow<CarveStatus> = _status.asStateFlow()

    fun onStart() {
        _status.value = CarveStatus(running = true)
    }

    fun onProgress(bytesScanned: Long, filesCarved: Int) {
        _status.value = _status.value.copy(bytesScanned = bytesScanned, filesCarved = filesCarved)
    }

    fun onFinished(filesCarved: Int, message: String?) {
        _status.value = _status.value.copy(
            running = false,
            filesCarved = filesCarved,
            finishedAt = System.currentTimeMillis(),
            message = message,
        )
    }
}
