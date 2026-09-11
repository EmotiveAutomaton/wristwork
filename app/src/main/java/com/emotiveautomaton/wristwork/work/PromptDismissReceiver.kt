package com.emotiveautomaton.wristwork.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.emotiveautomaton.wristwork.complication.StateComplicationService
import com.emotiveautomaton.wristwork.data.CurrentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A swipe-away is the wearer's answer that this question will not be labelled. Retire the exact
 * pending prompt so the complication cannot remain on NEW after its only visible question is gone.
 */
class PromptDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID) ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (CurrentState.clearPromptIfMatches(appContext, promptId)) {
                    StateComplicationService.requestUpdate(appContext)
                    android.util.Log.i("wristwork-prompt", "dismissed unanswered prompt $promptId")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PROMPT_ID = "dismissed_prompt_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
