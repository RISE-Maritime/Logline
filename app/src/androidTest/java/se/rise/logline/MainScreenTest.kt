package se.rise.logline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.ConnectionState
import se.rise.logline.publish.LiveLatest
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.record.RecordingStatus
import se.rise.logline.ui.MainScreen
import se.rise.logline.ui.theme.LoglineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four states of the status card, on a real device.
 *
 * These are the first instrumented tests in the app, and they are deliberately narrow: `MainScreen`
 * takes data and lambdas and nothing else — no repository, no `Context` — which is exactly what makes
 * it testable without a running publisher. What they catch is the class of regression that otherwise
 * only shows up on a phone: a status card that says the wrong thing about a run, where every JVM test
 * still passes because the arithmetic underneath was right.
 *
 * The distinction between "Publishing" and "Publishing to nothing" is the one worth guarding hardest.
 * A lost router is not a failed run — the service must not stop for it — so the difference lives only
 * in this card, and a phone that has quietly stopped reaching the bus looks identical to a healthy one
 * if this text is wrong.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
    )

    private fun show(status: PublisherStatus, recording: RecordingStatus = RecordingStatus()) {
        compose.setContent {
            LoglineTheme {
                MainScreen(
                    settings = settings,
                    status = status,
                    recording = recording,
                    live = LiveLatest(),
                    locationGranted = true,
                    freeBytes = 80L * 1024 * 1024 * 1024,
                    unavailableSubjects = emptySet(),
                    disabledSubjects = emptySet(),
                    onStart = {},
                    onStop = {},
                    onGrantLocation = {},
                    onOpenSubjectQos = {},
                    onToggleSubject = { _, _ -> },
                    onToggleSubjects = { _, _ -> },
                )
            }
        }
    }

    /** A subject that published a moment ago, so it reads as live rather than stalled. */
    private fun healthy(samples: Long = 1_000): SubjectStatus {
        val now = System.currentTimeMillis()
        return SubjectStatus(
            samplesPublished = samples,
            firstPublishEpochMillis = now - 60_000,
            lastPublishEpochMillis = now,
        )
    }

    @Test
    fun idle_says_what_to_do_rather_than_reporting_a_run() {
        show(PublisherStatus(running = false))

        compose.onNodeWithText("Not publishing").assertIsDisplayed()
        compose.onNodeWithText("Start to put this phone's sensors on the bus.").assertIsDisplayed()
        // The chip is `clearAndSetSemantics`, so what a screen reader gets is the whole chip's
        // description rather than its text — which is the thing worth asserting.
        compose.onNodeWithContentDescription("Router Idle").assertIsDisplayed()
    }

    /**
     * After a run the card keeps reporting it. That is deliberate and was once not the case: the
     * summary used to vanish at Stop, taking the sample count off screen at the moment somebody wanted
     * to read it.
     */
    @Test
    fun a_finished_run_is_still_reported() {
        show(
            PublisherStatus(
                running = false,
                subjects = mapOf(PublishedSubject.LOCATION_FIX to healthy(samples = 12_345)),
            )
        )

        compose.onNodeWithText("Not publishing").assertIsDisplayed()
        // A narrow no-break space groups the digits, not an ordinary one — `formatCount` uses U+202F
        // so a count never wraps mid-number. Spelling it out here rather than pasting an invisible
        // character into the expectation.
        val nnbsp = '\u202f'
        compose.onNodeWithText("Last run published 12${nnbsp}345 samples over 00:01:00.").assertIsDisplayed()
    }

    @Test
    fun publishing_names_the_bus_it_is_publishing_to() {
        show(
            PublisherStatus(
                running = true,
                connection = ConnectionState.Connected,
                subjects = mapOf(PublishedSubject.LOCATION_FIX to healthy()),
            )
        )

        compose.onNodeWithText("Publishing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Router Connected").assertIsDisplayed()
        compose.onNodeWithText("Not publishing").assertIsNotDisplayed()
    }

    /**
     * The state this file exists for. A run whose router has gone is still *running* — the service
     * keeps going and Zenoh reconnects on its own — so nothing else on the phone changes. If this card
     * said "Publishing" the operator would have no way to know the data was going nowhere.
     */
    @Test
    fun a_lost_router_is_said_out_loud() {
        show(
            PublisherStatus(
                running = true,
                connection = ConnectionState.Disconnected,
                subjects = mapOf(PublishedSubject.LOCATION_FIX to healthy()),
            )
        )

        compose.onNodeWithText("Publishing to nothing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Router No router").assertIsDisplayed()
    }

    /** A subject that published and then went quiet is called out in the detail line. */
    @Test
    fun a_stalled_subject_is_counted_in_the_detail() {
        val now = System.currentTimeMillis()
        show(
            PublisherStatus(
                running = true,
                connection = ConnectionState.Connected,
                subjects = mapOf(
                    PublishedSubject.LOCATION_FIX to SubjectStatus(
                        samplesPublished = 600,
                        firstPublishEpochMillis = now - 600_000,
                        // Ten minutes since the last sample from a subject that was managing 1 Hz.
                        lastPublishEpochMillis = now - 600_000,
                    ),
                ),
            )
        )

        compose.onNodeWithText("Publishing").assertIsDisplayed()
        // Two nodes say it — the card's detail line and the group heading that contains the subject —
        // and that they agree is the point, so this asserts both rather than picking one.
        compose.onAllNodes(androidx.compose.ui.test.hasText("stalled", substring = true), useUnmergedTree = false)
            .assertCountEquals(2)
    }
}
