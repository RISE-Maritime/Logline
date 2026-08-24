package se.rise.logline.checklist

/**
 * Whether the checklist feature can be used at all on this build.
 *
 * **False, because turning it on crashes the app.** Verified against the live `router.example.com` bus on a
 * Pixel 6, three attempts out of three: the session opens, the presence heartbeat goes out, the
 * bootstrap `get` on the wildcard `checklist_procedure` and `checklist_state` keys is answered by the
 * router storage,
 * and the reply callback aborts the process in `finalize_pending_query` with
 *
 * ```
 * JNI DETECTED ERROR IN APPLICATION: JNI NewByteArray called with pending exception
 * java.lang.ClassNotFoundException: Didn't find class "io.zenoh.jni.pubsub.EntityGlobalId"
 * ```
 *
 * It is the **same binding bug as `Zenoh.scout`** — the binding builds a class in native code with
 * `FindClass`, on one of Zenoh's own threads, where JNI resolves against the system class loader and
 * cannot see app classes — and `keelson/Scout.kt` exists because of it. The same fault already blocks
 * WHEP; that TODO item asked whether `KeelsonSession.query()` was affected when a storage actually
 * answers, and said it mattered because the checklist bootstrap uses it. It is, and it does.
 *
 * **Nothing in this app's own code is wrong**, which is why this is a gate rather than a fix. The one
 * message that escapes before the abort is well-formed: captured off the bus and decoded with keelson's
 * *own* Python bindings, the presence lands on
 * `crowsnest/@v0/checklist/pubsub/checklist_presence/{roc_site}/{operator_id}` carrying `username`,
 * `roc_site` and a timestamp. There is no repair from this side — the binding has to change upstream,
 * or the bootstrap has to stop being a Zenoh query.
 *
 * What the gate is *for* is the part that is this app's business: a switch a person can reach must not
 * reliably kill the app. Until then a crowsnest station sees this phone announce itself and vanish,
 * over and over.
 *
 * **Re-enabling is this one boolean.** Flip it to true, run the checklist screens against a bus whose
 * router has the checklist storages, and confirm `adb logcat -b crash | grep EntityGlobalId` stays
 * empty. Everything else — the sync, the store, the reducer, the screens — is written and untouched.
 */
const val CHECKLISTS_AVAILABLE = false

/**
 * Why not, in the words the Settings row shows.
 *
 * A disabled control has to say what disabled it: that is the app's rule that *state* — including why a
 * control cannot be used — stays on the page rather than moving behind the ⓘ.
 */
const val CHECKLISTS_UNAVAILABLE_REASON =
    "Unavailable on this build: the Zenoh binding crashes on the first reply from the router."
