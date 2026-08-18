package se.rise.logline.checklist

/**
 * A starter checklist library, for seeding a bus that has none.
 *
 * **This is not where procedures come from.** They come from the router: a `checklist_procedure` key
 * per procedure, held by the `storage_manager`, which any client reads with one query. That is the
 * whole point of the subject — an event names an item by id, so a client with no definition can tell
 * you something was completed but not what it said.
 *
 * The problem is the first client. A storage that nobody has published to answers a query with
 * nothing, and "no procedures on this bus" and "no storage configured" look identical from here. So
 * this exists to break the deadlock: when the library comes back empty, the app offers to publish
 * these, and from then on every client — this phone, crowsnest, anything else — reads them off the
 * router like any other.
 *
 * The contents are a verbatim transcription of crowsnest's `sampleProcedures`
 * (`../crowsnest-dev/src/jotai/checklistAtoms.js`), **ids included**. That is the load-bearing part:
 * crowsnest seeds its own copy from that constant into browser storage, so an event it publishes
 * names `item_003`, and only a library using the same ids can render it. Changing an id here does not
 * rename anything — it makes the two sides stop agreeing, silently.
 */
internal val STARTER_PROCEDURES: List<Procedure> = listOf(
    Procedure(
        procedureId = "proc_001",
        title = "Open Sea Navigation Checks",
        description = "Pre-departure verification for open water transit",
        category = "navigation",
        version = "2.4.1",
        estimatedMinutes = 15,
        items = listOf(
            ProcedureItem("item_001", 1, "Verify route plan loaded and approved", "Check ECDIS shows current passage plan with all waypoints", true),
            ProcedureItem("item_002", 2, "Check weather forecast for route", "Review current and forecasted conditions along planned route", true),
            ProcedureItem("item_003", 3, "Verify AIS transponder operational", "Confirm AIS is transmitting and receiving correctly", true),
            ProcedureItem("item_004", 4, "Test VHF radio", "Perform radio check on channel 16", false),
            ProcedureItem("item_005", 5, "Confirm radar functioning", "Check both radar units are operational with proper range settings", true),
            ProcedureItem("item_006", 6, "Review NAVTEX warnings", "Check for any navigational warnings affecting planned route", false),
        ),
    ),
    Procedure(
        procedureId = "proc_002",
        title = "Harbor Maneuvering Checks",
        description = "Pre-arrival verification for port entry and berthing",
        category = "navigation",
        version = "1.8.0",
        estimatedMinutes = 20,
        items = listOf(
            ProcedureItem("item_007", 1, "Contact port authority", "Establish communication with VTS and confirm berth assignment", true),
            ProcedureItem("item_008", 2, "Test bow thruster", "Verify bow thruster responds correctly to commands", true),
            ProcedureItem("item_009", 3, "Test stern thruster", "Verify stern thruster responds correctly to commands", true),
            ProcedureItem("item_010", 4, "Check mooring lines ready", "Confirm mooring team has lines prepared on deck", true),
            ProcedureItem("item_011", 5, "Verify anchor ready for emergency drop", "Anchor cleared and ready for immediate deployment if needed", true),
            ProcedureItem("item_012", 6, "Confirm pilot boarding arrangements", "Pilot ladder or accommodation ladder prepared as required", false),
            ProcedureItem("item_013", 7, "Test engine telegraph", "Verify engine room confirms telegraph orders", true),
            ProcedureItem("item_014", 8, "Check fenders deployed", "Confirm fenders positioned for berthing side", false),
        ),
    ),
    Procedure(
        procedureId = "proc_003",
        title = "ROC Watch Handover",
        description = "Standard procedure for remote operations center watch handover",
        category = "handover",
        version = "3.1.0",
        estimatedMinutes = 10,
        items = listOf(
            ProcedureItem("item_015", 1, "Review vessel status summary", "Outgoing watch briefs incoming on all monitored vessels", true),
            ProcedureItem("item_016", 2, "Transfer outstanding communications", "Hand over any pending messages or scheduled calls", true),
            ProcedureItem("item_017", 3, "Review active alerts", "Brief on any unresolved alerts or ongoing situations", true),
            ProcedureItem("item_018", 4, "Confirm system access", "Incoming watch verifies login and access to all systems", true),
        ),
    ),
    Procedure(
        procedureId = "proc_004",
        title = "Fire Response",
        description = "Emergency fire response procedures",
        category = "emergency",
        version = "4.0.0",
        estimatedMinutes = 5,
        items = listOf(
            ProcedureItem("item_019", 1, "Sound fire alarm", "Activate general alarm and announce fire location", true),
            ProcedureItem("item_020", 2, "Muster fire team", "Fire team proceeds to fire station and dons equipment", true),
            ProcedureItem("item_021", 3, "Isolate ventilation", "Shut ventilation to affected compartment", true),
        ),
    ),
    Procedure(
        procedureId = "proc_005",
        title = "Loss of GNSS/Gyro",
        description = "Response procedure for navigation system failure",
        category = "emergency",
        version = "2.2.0",
        estimatedMinutes = 8,
        items = listOf(
            ProcedureItem("item_022", 1, "Engage backup navigation", "Switch to secondary GNSS receiver or backup compass", true),
            ProcedureItem("item_023", 2, "Fix position visually", "Use visual bearings, radar ranges, or other means", true),
            ProcedureItem("item_024", 3, "Reduce speed if necessary", "Reduce speed to allow more time for position fixes", false),
            ProcedureItem("item_025", 4, "Notify engine room", "Alert engine room of navigation system failure", true),
            ProcedureItem("item_026", 5, "Log the incident", "Record time, position, and nature of failure in log", true),
        ),
    ),
    Procedure(
        procedureId = "proc_006",
        title = "Man Overboard Response",
        description = "Emergency MOB recovery procedures",
        category = "emergency",
        version = "5.0.0",
        estimatedMinutes = 3,
        items = listOf(
            ProcedureItem("item_027", 1, "Sound MOB alarm", "Three prolonged blasts and announce location", true),
            ProcedureItem("item_028", 2, "Mark position", "Press MOB button on GPS, deploy lifebuoy with light/smoke", true),
            ProcedureItem("item_029", 3, "Post lookout", "Assign dedicated lookout to maintain visual contact", true),
            ProcedureItem("item_030", 4, "Execute recovery maneuver", "Williamson turn or other appropriate maneuver", true),
            ProcedureItem("item_031", 5, "Prepare rescue boat", "Ready rescue boat and crew for deployment", true),
            ProcedureItem("item_032", 6, "Notify authorities", "Contact coast guard if recovery is prolonged", false),
        ),
    ),
    Procedure(
        procedureId = "proc_007",
        title = "Monthly Safety Equipment Check",
        description = "Regular inspection of life-saving and firefighting equipment",
        category = "maintenance",
        version = "1.5.0",
        estimatedMinutes = 45,
        items = listOf(
            ProcedureItem("item_033", 1, "Inspect lifeboat davits", "Check for corrosion, lubrication, and wire condition", true),
            ProcedureItem("item_034", 2, "Check liferaft hydrostatic releases", "Verify HRU expiry dates and mounting", true),
            ProcedureItem("item_035", 3, "Test EPIRB battery", "Check EPIRB battery expiry and perform test", true),
            ProcedureItem("item_036", 4, "Inspect fire extinguishers", "Check pressure gauges and inspection tags", true),
            ProcedureItem("item_037", 5, "Test fire detection system", "Activate test points and verify panel response", true),
            ProcedureItem("item_038", 6, "Check immersion suits", "Inspect for damage, check storage locations", true),
            ProcedureItem("item_039", 7, "Verify first aid kit contents", "Check contents against inventory, replace expired items", false),
            ProcedureItem("item_040", 8, "Test emergency lighting", "Activate and verify emergency lighting throughout vessel", true),
            ProcedureItem("item_041", 9, "Check breathing apparatus", "Inspect SCBA sets and verify cylinder pressure", true),
            ProcedureItem("item_042", 10, "Log inspection results", "Record all findings in safety equipment log", true),
        ),
    ),
    Procedure(
        procedureId = "proc_008",
        title = "Autonomy System Test",
        description = "Pre-departure verification of autonomous navigation systems",
        category = "test",
        version = "1.0.0",
        estimatedMinutes = 25,
        items = listOf(
            ProcedureItem("item_043", 1, "Verify sensor fusion status", "Check all sensor inputs are active and fused correctly", true),
            ProcedureItem("item_044", 2, "Test collision avoidance response", "Simulate target and verify COLAV system reaction", true),
            ProcedureItem("item_045", 3, "Check geofence boundaries", "Verify operational boundaries are loaded correctly", true),
            ProcedureItem("item_046", 4, "Test remote takeover", "Verify shore control can assume command", true),
            ProcedureItem("item_047", 5, "Verify communication links", "Test all data links including backup satellite", true),
            ProcedureItem("item_048", 6, "Check weather routing integration", "Verify weather data is updating and route optimization active", false),
            ProcedureItem("item_049", 7, "Test emergency stop function", "Verify e-stop commands are received and executed", true),
            ProcedureItem("item_050", 8, "Log system readiness", "Record test results and declare system status", true),
        ),
    ),
)
