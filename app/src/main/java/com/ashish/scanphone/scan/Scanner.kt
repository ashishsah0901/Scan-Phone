package com.ashish.scanphone.scan

import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.flow.Flow

interface Scanner {
    val source: ScanSource

    fun scan(request: ScanRequest): Flow<ScanEvent>
}
