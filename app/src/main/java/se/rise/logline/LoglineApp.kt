package se.rise.logline

import android.app.Application
import se.rise.logline.checklist.ChecklistRepository
import se.rise.logline.checklist.ChecklistSync
import se.rise.logline.platform.PlatformSync
import se.rise.logline.config.SettingsRepository
import se.rise.logline.publish.SensorPublisher
import se.rise.logline.record.RecordingTags

/**
 * Process-scoped owner of the publisher and the settings repository.
 *
 * Publishing outlives any Activity — [publish.PublisherService] keeps it running with the screen off —
 * so neither of these may be tied to a Compose scope.
 */
class LoglineApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /**
     * Tags on saved recordings. Its own store rather than a corner of the settings: these belong to
     * files, not to the phone, and in `Settings` they would ride into every exported profile.
     */
    val recordingTags: RecordingTags by lazy { RecordingTags(this) }

    val publisher: SensorPublisher by lazy { SensorPublisher(this) }

    val checklistRepository: ChecklistRepository by lazy { ChecklistRepository(this) }

    /**
     * The checklist peer. Here for the same reason [publisher] is — it owns a Zenoh session, and a
     * session must not be tied to a Compose scope — even though its lifetime is shorter: it is opened
     * when a checklist screen asks for it and closed when the last one leaves.
     */
    val checklist: ChecklistSync by lazy { ChecklistSync(this, checklistRepository) }

    /**
     * The platform peer: discovery, `get_config`, and the shared rig library.
     *
     * Here for the same reason [checklist] is, and with the same lifetime rule — its own Zenoh
     * session, opened when a rig screen asks for it and closed when the last one leaves. Rigs are
     * surveyed with logging stopped, so it deliberately shares nothing with [publisher].
     */
    val platforms: PlatformSync by lazy { PlatformSync(this) }
}
