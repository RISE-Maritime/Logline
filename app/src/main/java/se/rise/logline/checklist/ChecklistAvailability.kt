package se.rise.logline.checklist

/**
 * Whether the checklist feature can be used at all on this build.
 *
 * **False. Two separate JNI faults, and removing the first only exposed the second.** Turning
 * checklists on used to
 * abort the process three times out of three against the live bus: the session opened, the presence
 * heartbeat went out, the bootstrap `get` was answered by the router storage, and the reply callback
 * killed the app in `finalize_pending_query` with
 *
 * ```
 * JNI DETECTED ERROR IN APPLICATION: JNI NewByteArray called with pending exception
 * java.lang.ClassNotFoundException: Didn't find class "io.zenoh.jni.pubsub.EntityGlobalId"
 * ```
 *
 * That is the **same binding bug as `Zenoh.scout`** — a class built in native code with `FindClass`, on
 * one of Zenoh's own threads, where JNI resolves against the system class loader and cannot see app
 * classes — and it still blocks WHEP. Nothing about it has been repaired.
 *
 * What changed is that `ChecklistSync` no longer issues a Zenoh **query**, which was the only thing in
 * the checklist path that could reach the faulty reply handler. Run snapshots arrive by subscription
 * instead, which works because crowsnest republishes them periodically, and the item text comes from
 * this phone's own store. See `ChecklistRunsScreen` for what that costs.
 *
 * **Removing the query was not enough, and the second measurement is the important one.** With the
 * bootstrap gone and run snapshots arriving by subscription instead, the app still died — on a
 * *different* class, from a *different* code path:
 *
 * ```
 * JNI NewStringUTF called with pending exception
 * java.lang.ClassNotFoundException: Didn't find class "io.zenoh.jni.time.Timestamp"
 * ```
 *
 * in a **subscriber callback**, not a query reply. The cause is the same — `FindClass` on one of
 * Zenoh's own threads cannot see app classes — but the trigger is any sample carrying a Zenoh
 * timestamp, and on this bus every checklist key carries one: measured with a Python subscriber,
 * `checklist_state` and `checklist_presence` both arrive `timestamped=True`, because the router
 * timestamps what its storages keep.
 *
 * **`checklist_presence` was already subscribed before any of this**, which means the feature could
 * never have survived a second station being present. It crashed on the bootstrap query first, so the
 * subscription half was never reached — one bug hiding behind another.
 *
 * So the gate is not about queries. **Nothing that subscribes to a timestamped key can work on this
 * binding**, and catching does not help: it is a native abort, not an exception. The query-free sync
 * and the simplified screen are kept because they are correct and tested, and because they shorten the
 * work to whatever comes after the binding is fixed — not because they made the feature usable.
 */
const val CHECKLISTS_AVAILABLE = false

/**
 * Kept for the moment a query is unavoidable again — an interface this app must serve, say — so the
 * switch has words ready rather than being silently disabled by whoever needs to gate it next.
 */
const val CHECKLISTS_UNAVAILABLE_REASON =
    "Unavailable on this build: the Zenoh binding crashes on the first reply from the router."
