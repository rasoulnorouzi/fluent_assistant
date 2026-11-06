package app.tonefixer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Accessibility service: tracks the focused text field, shows the bubble, reads and replaces text. (Filled in at step 1.9.) */
class ToneService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
}
