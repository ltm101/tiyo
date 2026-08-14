package com.koyo.screenwarden

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/** Rebinds built-in-role labels to the active companion at the UI edge. */
object CompanionUiText {
    fun replace(context: Context, value: CharSequence?): CharSequence? {
        val text = value?.toString() ?: return null
        val name = CompanionProfileStore.activeName(context)
        val builtInName = CompanionProfileRules.DEFAULT_COMPANION_NAME
        val resolved = if (builtInName == name) text else text.replace(builtInName, name)
        return if (resolved == text) value else resolved
    }

    fun applyRecursively(context: Context, root: View) {
        root.contentDescription = replace(context, root.contentDescription)
        if (root is TextView) {
            if (root !is EditText) root.text = replace(context, root.text)
            root.hint = replace(context, root.hint)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyRecursively(context, root.getChildAt(index))
            }
        }
    }
}
