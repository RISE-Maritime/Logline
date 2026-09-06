package se.rise.logline.checklist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires a reminder, and re-arms the lot after a reboot.
 *
 * Both paths need the stored reminders and the stored procedures, which live in DataStore and are
 * therefore read with a coroutine — hence [goAsync], which holds the broadcast open while that
 * happens. Without it the process can be killed the moment [onReceive] returns and the notification
 * never appears.
 */
class ChecklistReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        val repository = ChecklistRepository(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> fire(
                        appContext,
                        repository,
                        intent.getStringExtra(ChecklistReminders.EXTRA_PROCEDURE_ID).orEmpty(),
                        intent.getStringExtra(ChecklistReminders.EXTRA_ITEM_ID).orEmpty(),
                    )

                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                        ChecklistReminders.armAll(appContext, repository.stored.first().reminders)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(
        context: Context,
        repository: ChecklistRepository,
        procedureId: String,
        itemId: String,
    ) {
        if (procedureId.isEmpty() || itemId.isEmpty()) return
        val stored = repository.stored.first()
        val reminder = stored.reminders
            .firstOrNull { it.procedureId == procedureId && it.itemId == itemId } ?: return

        val procedure = stored.procedures.firstOrNull { it.procedureId == procedureId }
        // Reminders stay keyed on the *procedure* — they are phone-local and about the template, not
        // about one execution of it — so the run is resolved here, at the moment the alarm fires,
        // rather than pinned when it was set. A reminder set before a run started still finds it.
        val run = stored.progress.values
            .filter { it.procedureId == procedureId || it.runId == procedureId }
            .let { runs ->
                runs.filterNot { it.isTerminal() }.maxByOrNull { it.createdAtEpochMillis ?: 0L }
                    ?: runs.maxByOrNull { it.createdAtEpochMillis ?: 0L }
            }
        val done = run?.item(itemId)?.status == ItemStatus.Completed

        // An alarm for something already ticked is the fastest way to teach somebody to swipe alarms
        // away without reading them. It is dropped rather than repeated, whatever its interval says.
        if (!done) {
            ChecklistReminders.notify(
                context = context,
                reminder = reminder,
                procedureTitle = procedure?.title ?: procedureId,
                itemTitle = procedure?.items?.firstOrNull { it.itemId == itemId }?.title ?: itemId,
            )
        }

        val next = if (done) null else reminder.next(System.currentTimeMillis())
        val remaining = stored.reminders.filterNot { it.procedureId == procedureId && it.itemId == itemId }
        repository.saveReminders(if (next != null) remaining + next else remaining)
        if (next != null) ChecklistReminders.arm(context, next)
    }

    companion object {
        const val ACTION_FIRE = "se.rise.logline.checklist.REMIND"
    }
}
