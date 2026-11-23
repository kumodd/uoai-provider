package com.uoai.integration.accessibility

import com.uoai.integration.AutomationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FR-11: Opt-in, transparent Accessibility capability boundary.
 * Core inference and API endpoints never depend on an AccessibilityService.
 */
class OptInAccessibilityCapability : AutomationCapability {
    override val id: String = "android.accessibility"
    private val _userEnabled = MutableStateFlow(false)
    val userEnabled: StateFlow<Boolean> = _userEnabled.asStateFlow()

    fun setUserOptIn(enabled: Boolean) { _userEnabled.value = enabled }
}
