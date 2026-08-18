package se.rise.logline.keelson

/**
 * A 3×3 row-major ENU position covariance with only the diagonal populated, in m².
 *
 * `foxglove.LocationFix.position_covariance` is East-North-Up, row-major, length 9. Android reports
 * its accuracies as **68% confidence (1σ) radii in metres**, so the variance is simply the square —
 * a 5 m horizontal accuracy is 25 m², not 5.
 *
 * East and north get the same value because the phone reports a single horizontal radius, not an
 * error ellipse. That isotropic assumption is exactly why the fix is tagged `APPROXIMATED` rather
 * than `DIAGONAL_KNOWN`.
 *
 * Mirrors `enclose_from_location_fix` in the fleet's mavlink connector, so a consumer sees the same
 * shape from this phone as from any other source.
 */
fun diagonalEnuCovariance(horizontalMetres: Double, verticalMetres: Double): List<Double> {
    val east = horizontalMetres * horizontalMetres
    val north = east
    val up = verticalMetres * verticalMetres
    return listOf(
        east, 0.0, 0.0,
        0.0, north, 0.0,
        0.0, 0.0, up,
    )
}
