package dev.whispr.whispr_ai

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * WhisprAccessibilityService detects when the user clicks/focuses an editable text field
 * or when the soft keyboard appears across any app.
 *
 * This allows the Flying Dictation Button to:
 * 1. Only appear when the user is actually typing or focused on a place to write.
 * 2. Automatically hide when the keyboard or text field is dismissed.
 * 3. Directly insert the dictated, grammatically cleaned text into the active text field!
 */
class WhisprAccessibilityService : AccessibilityService() {

    private var lastFocusedNode: AccessibilityNodeInfo? = null

    companion object {
        @Volatile
        var instance: WhisprAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        /**
         * Inserts text directly into the currently focused or last focused editable node.
         * Falls back to paste action if ACTION_SET_TEXT is rejected.
         */
        fun insertText(text: String): Boolean {
            val inst = instance ?: return false
            val target = inst.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: inst.lastFocusedNode
                ?: return false

            return try {
                val current = target.text?.toString().orEmpty()
                val fullText = if (current.isBlank()) {
                    text
                } else if (current.endsWith(" ") || current.endsWith("\n")) {
                    current + text
                } else {
                    "$current $text"
                }

                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fullText)
                }
                val setSuccess = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setSuccess) {
                    target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } else {
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        checkAndUpdateInputState(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            checkAndUpdateInputState(event)
        }
    }

    private fun checkAndUpdateInputState(event: AccessibilityEvent?) {
        var isInputActive = false

        // 1. Check if soft keyboard (TYPE_INPUT_METHOD) window is currently visible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val windowList = windows
                for (w in windowList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        isInputActive = true
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Check if a text input field has focus
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && (focusedNode.isEditable ||
            focusedNode.className?.toString()?.contains("EditText", ignoreCase = true) == true)
        ) {
            isInputActive = true
            lastFocusedNode = focusedNode
        } else if (event?.source != null && (event.source.isEditable ||
            event.className?.toString()?.contains("EditText", ignoreCase = true) == true)
        ) {
            isInputActive = true
            lastFocusedNode = event.source
        }

        FloatingDictationService.onInputActivityChanged(isInputActive)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
}
