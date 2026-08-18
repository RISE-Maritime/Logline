package se.rise.logline.calibrate

import android.content.Context
import se.rise.logline.record.saveToDownloads

/**
 * Write the calibration to `Downloads/Logline` as a platform-geometry file.
 *
 * The **strict** variant: no provenance block, so the file validates against
 * `keelson/connectors/platform/config-schema.json` and can be handed straight to
 * `platform-geometry2keelson.py --config`. That is the point of exporting at all — the phone surveys
 * the rig once, and a connector on the vessel publishes the result from then on.
 *
 * Returns the file name it wrote, for the screen to show. Throws whatever the MediaStore write throws;
 * the caller reports it rather than this pretending it succeeded.
 */
fun exportCalibration(context: Context, calibration: RigCalibration): String {
    val name = "${defaultEntityId(calibration.name)}-platform-geometry.json"
    saveToDownloads(context, name, "application/json") { out ->
        out.write(calibration.toPlatformGeometryJson().toByteArray(Charsets.UTF_8))
    }
    return name
}
