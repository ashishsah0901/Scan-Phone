package com.ashish.scanphone.threat.source

import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatScanOptions
import kotlinx.coroutines.flow.Flow

interface ThreatSource {
    val check: ThreatCheck

    fun scan(options: ThreatScanOptions): Flow<ThreatEvent>
}
