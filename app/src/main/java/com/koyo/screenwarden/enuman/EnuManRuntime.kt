package com.koyo.screenwarden.enuman

import android.content.Context
import android.util.Log
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.presence.PresenceEvent

/** The private inner loop. It deliberately exposes no expression method. */
object EnuManRuntime {
    private const val TAG = "EnuManRuntime"
    private val lock = Any()
    private val engine = EnuManPulseEngine()

    fun observePresence(context: Context, event: PresenceEvent) {
        val app = context.applicationContext
        val scope = CompanionScope.capture(app)
        runCatching {
            integrate(app, scope, EnuManSignalMapper.fromPresence(event))
        }.onFailure { Log.w(TAG, "presence integration failed", it) }
    }

    fun onUserInteraction(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context),
        at: Long = System.currentTimeMillis()
    ) {
        runCatching { integrate(context.applicationContext, scope, EnuManSignalMapper.userInteraction(at)) }
            .onFailure { Log.w(TAG, "user interaction integration failed", it) }
    }

    fun onCompanionExpression(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context),
        at: Long = System.currentTimeMillis()
    ) {
        runCatching { integrate(context.applicationContext, scope, EnuManSignalMapper.companionExpression(at)) }
            .onFailure { Log.w(TAG, "expression feedback integration failed", it) }
    }

    fun tick(
        context: Context,
        scope: CompanionScope,
        now: Long = System.currentTimeMillis()
    ): PreSemanticPulse? = synchronized(lock) {
        val state = EnuManStore.loadState(context, scope, now)
        val result = engine.step(state, signal = null, now = now)
        EnuManStore.saveState(context, scope, result.state)
        result.pulse?.also { pulse ->
            EnuManStore.appendPulse(context, scope, pulse)
            EnuManInterpretationWorker.enqueue(context, scope, pulse.id)
        }
    }

    private fun integrate(
        context: Context,
        scope: CompanionScope,
        signal: EnuManSignal
    ): PreSemanticPulse? = synchronized(lock) {
        val state = EnuManStore.loadState(context, scope, signal.occurredAt)
        if (signal.id in state.recentSignalIds) return@synchronized null
        val result = engine.step(state, signal, signal.occurredAt)
        EnuManStore.saveState(context, scope, result.state)
        result.pulse?.also { pulse ->
            EnuManStore.appendPulse(context, scope, pulse)
            EnuManInterpretationWorker.enqueue(context, scope, pulse.id)
        }
    }
}
