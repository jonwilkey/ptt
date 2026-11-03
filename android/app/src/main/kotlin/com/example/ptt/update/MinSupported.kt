package com.example.ptt.update

import com.example.ptt.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Whether this app is older than the server's `min_supported_version_code` (docs/protocol.md section 14.5). The
 * last reported minimum is persisted, so an app that cannot reach the server stays blocked.
 */
class MinSupported(private val settings: SettingsRepository, private val installedCode: Long) {
    val required: Flow<Boolean> = settings.minSupportedCode.map { installedCode < it }

    suspend fun isRequired(): Boolean = required.first()
}
