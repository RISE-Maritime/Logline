package se.rise.logline.monitor

// In its own file on purpose: the card-kind helpers in MonitorCards.kt compile into that file's facade
// class, which `CardKind` initialises through — so a list of `CardKind`s living there too was built
// while the enum was still half-constructed, and every entry came out null.

/**
 * What a fresh install shows: enough to see a vessel is alive without configuring anything.
 * Fixed ids rather than random ones, so the default set is the same document on every phone.
 */
val DEFAULT_MONITOR_CARDS: List<MonitorCard> = listOf(
    MonitorCard("d-sog", CardKind.Value),
    MonitorCard("d-plot", CardKind.Plot, mapOf("subject" to "heading_true_north_deg")),
    MonitorCard("d-hdg", CardKind.Heading),
    MonitorCard("d-imu", CardKind.Imu),
)
