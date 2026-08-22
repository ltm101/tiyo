package com.koyo.screenwarden

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the WebSocket command payload for private `expression_policy_v1`.
 *
 * When the native capability is available the original user text stays in
 * `text` and the private state goes into a separate `mind_context` field.
 * Older runtimes receive plain user text; raw private state is never wrapped into user content.
 */
object MindContextCodec {
    const val CAPABILITY = "expression_policy_v1"
    const val MAX_EXPRESSION_POLICY_BYTES = 1024

    fun sendMessagePayload(
        text: String,
        images: List<String>,
        expressionPolicy: JSONObject?,
        supportsExpressionPolicy: Boolean
    ): JSONObject {
        val payload = JSONObject().put("command", "send_message")
        applyCommon(payload, text, images, expressionPolicy, supportsExpressionPolicy)
        return payload
    }

    fun jumpInPayload(text: String): JSONObject {
        // A jump-in continues an already running turn whose ephemeral system policy is fixed
        // Never append policy as a queued user/internal history message
        return JSONObject()
            .put("command", "jump_in")
            .put("text", text)
    }

    private fun applyCommon(
        payload: JSONObject,
        text: String,
        images: List<String>,
        expressionPolicy: JSONObject?,
        supportsExpressionPolicy: Boolean
    ) {
        val safePolicy = expressionPolicy?.takeIf {
            it.optString("schema") == "enuman_expression_v1" &&
                it.toString().toByteArray(Charsets.UTF_8).size <= MAX_EXPRESSION_POLICY_BYTES
        }
        val native = supportsExpressionPolicy && safePolicy != null
        payload.put("text", text)
        if (images.isNotEmpty()) payload.put("images", JSONArray(images))
        if (native) payload.put("expression_policy", safePolicy)
    }
}
