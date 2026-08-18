package se.rise.logline

import android.app.Application
import se.rise.logline.checklist.ChecklistRepository
import se.rise.logline.checklist.ChecklistSync
import se.rise.logline.config.SettingsRepository
import se.rise.logline.publish.SensorPublisher

/**
 * Process-scoped owner of the publisher and the settings repository.
 *
 * Publishing outlives any Activity — [publish.PublisherService] keeps it running with the screen off —
 * so neither of these may be tied to a Compose scope.
 */
class LoglineApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val publisher: SensorPublisher by lazy { SensorPublisher(this) }

    val checklistRepository: ChecklistRepository by lazy { ChecklistRepository(this) }

    /**
     * The checklist peer. Here for the same reason [publisher] is — it owns a Zenoh session, and a
     * session must not be tied to a Compose scope — even though its lifetime is shorter: it is opened
     * when a checklist screen asks for it and closed when the last one leaves.
     */
    val checklist: ChecklistSync by lazy { ChecklistSync(this, checklistRepository) }
}
