package com.ihimanshunayak.freemusic.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon family the New Experience switch swaps in: the same glyphs the app
 * already draws, redrawn solid.
 *
 * [FreeMusicIcons] is a stroke set and says so — a 2.2px pen, round joins, one
 * family because every glyph is the same weight of line. These are not that.
 * A silhouette has no weight to share, so the family here is carried by
 * *mass* instead: each glyph is drawn to fill the same optical box, and the
 * ones that stay line-work are drawn at 2.6 rather than 2.2 so a stroked glyph
 * still stands as heavy as a filled one beside it.
 *
 * Where a glyph is a silhouette it is filled; where it is a line it is stroked.
 * That is not a compromise between two styles, it is what the shape is: two
 * filled arrows crossing at 24dp have no counter left to read against, and a
 * filled arc is a blob. [Shuffle], [Repeat], [Equaliser], [Infinity], [Check]
 * and [Plus] are drawn entirely in line, and [Search], [Mic], [Clock], [Timer],
 * [Explore], [Home], [Albums], [Library] and [Performance] are filled shapes
 * with a line detail laid over them — which is the honest description of a
 * magnifier or a clock face, not a mixture chosen for its own sake.
 *
 * The counters in the filled ring shapes — [Library]'s frame, [Search]'s lens,
 * [Clock]'s dial, [Timer]'s dial, [Explore]'s bezel, [Albums]' back card — are
 * punched out with [PathFillType.EvenOdd] in a single path rather than painted
 * over with a second shape in the background colour. A painted-over counter
 * takes whatever is behind the icon with it, which turns a transparent icon
 * into a black tile.
 *
 * Tint comes from [androidx.compose.material3.Icon] like every other icon in
 * the app; the black here is the brush that gets tinted.
 */
object NewExperienceIcons {

    /**
     * The pen the line-work members are drawn with.
     *
     * Heavier than `FreeMusicIcons`' 2.2 because these sit beside filled
     * shapes: at equal weight a stroke reads as the lighter of the two, and a
     * bar of tabs where two glyphs look thin and two look heavy is a bar that
     * looks like it was assembled rather than drawn.
     */
    private const val LINE = 2.6f

    private val ink = SolidColor(Color.Black)

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /**
     * A rectangle with square-ish rounded corners, as four lines and four
     * quarters.
     *
     * `quadTo` rather than `arcTo`: a quadratic with its control point on the
     * corner is the standard rounded rectangle, it is what a design tool emits,
     * and it needs one call per corner instead of an arc's five arguments and a
     * sweep flag that has to be reasoned about in a y-down coordinate system.
     */
    private fun PathBuilder.roundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
    ) {
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        quadTo(right, top, right, top + radius)
        lineTo(right, bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        lineTo(left + radius, bottom)
        quadTo(left, bottom, left, bottom - radius)
        lineTo(left, top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }

    /**
     * A full circle as two half arcs.
     *
     * Two, not one: a single `arcTo` cannot express a closed circle because its
     * start and end points are the same, and an arc whose endpoints coincide is
     * undefined. Two halves each sweep exactly 180 degrees, which is also the
     * only sweep that is unambiguously "the short way" or "the long way" — see
     * the `isMoreThanHalf` argument below, false both times.
     */
    private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
        moveTo(centerX - radius, centerY)
        arcToRelative(radius, radius, 0f, false, true, radius * 2f, 0f)
        arcToRelative(radius, radius, 0f, false, true, -radius * 2f, 0f)
        close()
    }

    /**
     * The bottom navigation bar's library tab.
     *
     * A rounded frame with a quaver in it, which is the shape the reference
     * sheet leads with and the shape this tab has to keep: `FreeMusicIcons`
     * draws the same frame, so a listener who leaves the switch off and one who
     * turns it on are looking at the same idea drawn two ways rather than at
     * two different buttons.
     *
     * The frame's counter is the inner rectangle, so its corners are inset by
     * the same 1.8 as its sides — a ring drawn with a different corner radius
     * on the inside than the outside reads as a shape that was scaled rather
     * than offset.
     */
    val Library: ImageVector by lazy {
        icon("nx_library") {
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                roundedRect(3f, 3f, 21f, 21f, 4.4f)
                roundedRect(4.8f, 4.8f, 19.2f, 19.2f, 2.8f)
            }
            path(fill = ink) {
                roundedRect(11.0f, 7.0f, 12.4f, 15.4f, 0.7f)
                circle(9.9f, 15.0f, 1.9f)
                moveTo(12.3f, 7.0f)
                quadTo(15.0f, 7.9f, 14.1f, 11.0f)
                quadTo(13.8f, 8.9f, 12.3f, 8.6f)
                close()
            }
        }
    }

    /**
     * Notifications, on the header bell and the tab that opens the page.
     *
     * Filled dome, flared skirt, separate clapper. The clapper stays a separate
     * subpath even though it almost touches the skirt: an unbroken bell reads
     * as a bell, but the segment of sky between the two is what makes it read
     * as *ringing* rather than as a jar.
     */
    val Bell: ImageVector by lazy {
        icon("nx_bell") {
            path(fill = ink) {
                moveTo(12f, 2.6f)
                quadTo(6.5f, 2.6f, 6.5f, 8.8f)
                lineTo(6.5f, 13.3f)
                quadTo(6.5f, 15.4f, 4.6f, 17.2f)
                lineTo(19.4f, 17.2f)
                quadTo(17.5f, 15.4f, 17.5f, 13.3f)
                lineTo(17.5f, 8.8f)
                quadTo(17.5f, 2.6f, 12f, 2.6f)
                close()
                circle(12f, 19.5f, 1.7f)
            }
        }
    }

    /** Download, on a track's saved-to-device state and a completed batch. */
    val Download: ImageVector by lazy {
        icon("nx_download") {
            path(fill = ink) {
                moveTo(10.3f, 2.8f)
                lineTo(13.7f, 2.8f)
                lineTo(13.7f, 11.8f)
                lineTo(17.6f, 11.8f)
                lineTo(12f, 17.4f)
                lineTo(6.4f, 11.8f)
                lineTo(10.3f, 11.8f)
                close()
                roundedRect(3f, 19.2f, 21f, 21.4f, 1.1f)
            }
        }
    }

    /**
     * Play, on the player's transport and every play affordance over a cover.
     *
     * Four control points rather than three corners so the two long edges bow
     * very slightly outward. A straight-edged triangle at this size reads as a
     * triangle; the reference sheet's plays as a *button*, which is a shape
     * with no straight line in it longer than the eye can hold.
     */
    val Play: ImageVector by lazy {
        icon("nx_play") {
            path(fill = ink) {
                moveTo(7.4f, 4.3f)
                quadTo(7.4f, 3.2f, 8.4f, 3.7f)
                lineTo(19.1f, 11.4f)
                quadTo(19.9f, 12f, 19.1f, 12.6f)
                lineTo(8.4f, 20.3f)
                quadTo(7.4f, 20.8f, 7.4f, 19.7f)
                close()
            }
        }
    }

    /** Shuffle, on the player's transport. Two railed arrows crossing. */
    val Shuffle: ImageVector by lazy {
        icon("nx_shuffle") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.4f, 7.2f)
                lineTo(7.0f, 7.2f)
                quadTo(9.2f, 7.2f, 10.5f, 8.7f)
                lineTo(13.5f, 15.3f)
                quadTo(14.8f, 16.8f, 17.0f, 16.8f)
                lineTo(20.6f, 16.8f)
                moveTo(3.4f, 16.8f)
                lineTo(7.0f, 16.8f)
                quadTo(9.2f, 16.8f, 10.5f, 15.3f)
                lineTo(13.5f, 8.7f)
                quadTo(14.8f, 7.2f, 17.0f, 7.2f)
                lineTo(20.6f, 7.2f)
            }
            path(fill = ink) {
                moveTo(18.0f, 4.2f)
                lineTo(21.6f, 7.2f)
                lineTo(18.0f, 10.2f)
                close()
                moveTo(18.0f, 13.8f)
                lineTo(21.6f, 16.8f)
                lineTo(18.0f, 19.8f)
                close()
            }
        }
    }

    /** Search, on the nav bar's circular button and the Search tab. */
    val Search: ImageVector by lazy {
        icon("nx_search") {
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                circle(10.5f, 10.5f, 7.3f)
                circle(10.5f, 10.5f, 5.1f)
            }
            path(
                stroke = ink,
                strokeLineWidth = 2.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(15.9f, 15.9f)
                lineTo(20.6f, 20.6f)
            }
        }
    }

    /** Home, on the Home tab. Filled and stroked with the same path. */
    val Home: ImageVector by lazy {
        icon("nx_home") {
            path(
                fill = ink,
                stroke = ink,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 2.8f)
                lineTo(21.4f, 10.4f)
                quadTo(22.0f, 10.9f, 21.2f, 11.3f)
                lineTo(20.2f, 11.8f)
                lineTo(20.2f, 19.2f)
                quadTo(20.2f, 21.0f, 18.4f, 21.0f)
                lineTo(14.4f, 21.0f)
                lineTo(14.4f, 15.6f)
                quadTo(14.4f, 14.6f, 13.4f, 14.6f)
                lineTo(10.6f, 14.6f)
                quadTo(9.6f, 14.6f, 9.6f, 15.6f)
                lineTo(9.6f, 21.0f)
                lineTo(5.6f, 21.0f)
                quadTo(3.8f, 21.0f, 3.8f, 19.2f)
                lineTo(3.8f, 11.8f)
                lineTo(2.8f, 11.3f)
                quadTo(2.0f, 10.9f, 2.6f, 10.4f)
                close()
            }
        }
    }

    /**
     * Lyrics, on the player's lyrics affordance.
     *
     * The cradle is a half arc rather than a squared bracket: it is the one part
     * of the glyph that says the capsule is being held, and a bracket says it is
     * being clamped.
     *
     * The arc runs from 6.2,12.6 to the other side the short way — the lower
     * half — so the cradle opens upward around the capsule's foot. Going the
     * other way draws the same curve as a hood over the top of the mic, which
     * reads as a microphone with a handle.
     */
    val Mic: ImageVector by lazy {
        icon("nx_mic") {
            path(fill = ink) {
                roundedRect(9.3f, 2.8f, 14.7f, 14.0f, 2.7f)
            }
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.2f, 11.6f)
                lineTo(6.2f, 12.6f)
                arcToRelative(5.8f, 5.8f, 0f, false, false, 11.6f, 0f)
                lineTo(17.8f, 11.6f)
                moveTo(12f, 18.4f)
                lineTo(12f, 21.2f)
            }
        }
    }

    /** History, on the History tab. A dial, not a wall clock. */
    val Clock: ImageVector by lazy {
        icon("nx_clock") {
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9f)
                circle(12f, 12f, 6.8f)
            }
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 7.4f)
                lineTo(12f, 12.4f)
                lineTo(16.2f, 14.8f)
            }
        }
    }

    /**
     * The equaliser, on the settings row and the player's tone controls.
     *
     * Knobs drawn a little wider than the rail is thick, so the rail visibly
     * enters and leaves each one. A knob exactly as wide as the rail reads as a
     * bead threaded on a wire rather than as a fader.
     */
    val Equaliser: ImageVector by lazy {
        icon("nx_equaliser") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
            ) {
                for (y in listOf(6.8f, 12f, 17.2f)) {
                    moveTo(3.6f, y)
                    lineTo(20.4f, y)
                }
            }
            path(fill = ink) {
                circle(9.2f, 6.8f, 2.7f)
                circle(15.0f, 12f, 2.7f)
                circle(7.8f, 17.2f, 2.7f)
            }
        }
    }

    /** Explore, on the Explore tab. Bezel plus a pointing needle. */
    val Explore: ImageVector by lazy {
        icon("nx_explore") {
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9f)
                circle(12f, 12f, 7f)
            }
            path(fill = ink) {
                moveTo(15.6f, 8.4f)
                lineTo(12.9f, 12.9f)
                lineTo(8.4f, 15.6f)
                lineTo(11.1f, 11.1f)
                close()
            }
        }
    }

    /**
     * Albums, on a collection's card and the library's album filter.
     *
     * The card behind is a bezel rather than a filled shape so the card in
     * front can sit over it without the two merging into one silhouette. A
     * stack of two solid cards is one card with a lump on it; the gap is the
     * entire glyph.
     */
    val Albums: ImageVector by lazy {
        icon("nx_albums") {
            path(fill = ink) {
                roundedRect(3f, 8.6f, 14.8f, 21f, 2.8f)
            }
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                roundedRect(9.2f, 3f, 21f, 15.4f, 2.8f)
                roundedRect(11.0f, 4.8f, 19.2f, 13.6f, 1.4f)
            }
        }
    }

    /** Repeat, on the player's transport. Two rails, two arrowheads. */
    val Repeat: ImageVector by lazy {
        icon("nx_repeat") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(8.4f, 6.6f)
                lineTo(16.2f, 6.6f)
                quadTo(20.2f, 6.6f, 20.2f, 10.6f)
                lineTo(20.2f, 11.8f)
                moveTo(15.6f, 17.4f)
                lineTo(7.8f, 17.4f)
                quadTo(3.8f, 17.4f, 3.8f, 13.4f)
                lineTo(3.8f, 12.2f)
            }
            path(fill = ink) {
                moveTo(8.6f, 3.2f)
                lineTo(5.0f, 6.6f)
                lineTo(8.6f, 10.0f)
                close()
                moveTo(15.4f, 14.0f)
                lineTo(19.0f, 17.4f)
                lineTo(15.4f, 20.8f)
                close()
            }
        }
    }

    /** A listener, on the account header and the shared-playlist member row. */
    val Person: ImageVector by lazy {
        icon("nx_person") {
            path(fill = ink) {
                circle(12f, 7.9f, 4.1f)
                moveTo(4.3f, 20.6f)
                quadTo(4.3f, 14.1f, 12f, 14.1f)
                quadTo(19.7f, 14.1f, 19.7f, 20.6f)
                close()
            }
        }
    }

    /**
     * Celebration, on a shared playlist's leading card.
     *
     * A four-point burst rather than a five-point star: a star has an internal
     * angle between two of its arms that closes to a point, and at 24dp that
     * point fills in with the arms either side of it and the shape becomes a
     * splat. Four arms meeting at 90 degrees keep a counter between every pair.
     */
    val Party: ImageVector by lazy {
        icon("nx_party") {
            path(fill = ink) {
                moveTo(12f, 3.2f)
                quadTo(13.4f, 9.6f, 19.8f, 11.2f)
                quadTo(13.4f, 12.8f, 12f, 19.2f)
                quadTo(10.6f, 12.8f, 4.2f, 11.2f)
                quadTo(10.6f, 9.6f, 12f, 3.2f)
                close()
                circle(19.4f, 4.6f, 1.7f)
                circle(19.8f, 18.4f, 1.5f)
                circle(5.0f, 18.8f, 1.7f)
            }
        }
    }

    /**
     * New, in the library's leading tile and the nav bar's add button.
     *
     * Stroked at 4.4 — the heaviest line in the app — because a plus is two
     * crossings and nothing else. Drawn as a filled polygon its eight corners
     * are the whole of the shape, and every one of them is a place the eye
     * stops; the round cap is what makes it read as a mark somebody made.
     */
    val Plus: ImageVector by lazy {
        icon("nx_plus") {
            path(
                stroke = ink,
                strokeLineWidth = 4.4f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 4.6f)
                lineTo(12f, 19.4f)
                moveTo(4.6f, 12f)
                lineTo(19.4f, 12f)
            }
        }
    }

    /** A track's rating, unset. */
    val Heart: ImageVector by lazy {
        icon("nx_heart") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 20.0f)
                curveTo(10.2f, 18.4f, 3.8f, 14.2f, 3.8f, 9.4f)
                curveTo(3.8f, 6.2f, 6.1f, 4.2f, 8.6f, 4.2f)
                curveTo(10.2f, 4.2f, 11.4f, 5.0f, 12f, 5.9f)
                curveTo(12.6f, 5.0f, 13.8f, 4.2f, 15.4f, 4.2f)
                curveTo(17.9f, 4.2f, 20.2f, 6.2f, 20.2f, 9.4f)
                curveTo(20.2f, 14.2f, 13.8f, 18.4f, 12f, 20.0f)
                close()
            }
        }
    }

    /** A track's rating, set. */
    val HeartFilled: ImageVector by lazy {
        icon("nx_heart_filled") {
            path(fill = ink) {
                moveTo(12f, 20.4f)
                curveTo(10.2f, 18.8f, 3.4f, 14.4f, 3.4f, 9.4f)
                curveTo(3.4f, 6.0f, 5.9f, 3.9f, 8.6f, 3.9f)
                curveTo(10.3f, 3.9f, 11.5f, 4.8f, 12f, 5.7f)
                curveTo(12.5f, 4.8f, 13.7f, 3.9f, 15.4f, 3.9f)
                curveTo(18.1f, 3.9f, 20.6f, 6.0f, 20.6f, 9.4f)
                curveTo(20.6f, 14.4f, 13.8f, 18.8f, 12f, 20.4f)
                close()
            }
        }
    }

    /** A batch that finished, or an option that is on. */
    val Check: ImageVector by lazy {
        icon("nx_check") {
            path(
                stroke = ink,
                strokeLineWidth = 3.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.2f, 12.6f)
                lineTo(9.6f, 18.0f)
                lineTo(19.8f, 6.2f)
            }
        }
    }

    /** A pinned playlist. */
    val Bookmark: ImageVector by lazy {
        icon("nx_bookmark") {
            path(fill = ink) {
                moveTo(6.0f, 3.4f)
                lineTo(18.0f, 3.4f)
                quadTo(19.8f, 3.4f, 19.8f, 5.2f)
                lineTo(19.8f, 20.8f)
                lineTo(12f, 15.4f)
                lineTo(4.2f, 20.8f)
                lineTo(4.2f, 5.2f)
                quadTo(4.2f, 3.4f, 6.0f, 3.4f)
                close()
            }
        }
    }

    /** The library's grid view. */
    val Grid: ImageVector by lazy {
        icon("nx_grid") {
            path(fill = ink) {
                roundedRect(3.2f, 3.2f, 10.8f, 10.8f, 2.3f)
                roundedRect(13.2f, 3.2f, 20.8f, 10.8f, 2.3f)
                roundedRect(3.2f, 13.2f, 10.8f, 20.8f, 2.3f)
                roundedRect(13.2f, 13.2f, 20.8f, 20.8f, 2.3f)
            }
        }
    }

    /**
     * Performance, on the settings row that opens the frame-rate page.
     *
     * A half dial rather than a whole one, and the needle starts *above* the
     * dial's centre line rather than on it: a gauge whose needle lies along the
     * baseline reads as a speedometer at rest, which is the one reading a
     * performance control should not have.
     */
    val Performance: ImageVector by lazy {
        icon("nx_performance") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3.4f, 16.6f)
                arcToRelative(8.6f, 8.6f, 0f, false, true, 17.2f, 0f)
                moveTo(12f, 16.6f)
                lineTo(17.2f, 11.4f)
            }
            path(fill = ink) {
                circle(12f, 16.6f, 2.0f)
            }
        }
    }

    /** An endless queue. */
    val Infinity: ImageVector by lazy {
        icon("nx_infinity") {
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.0f, 7.8f, 6.6f, 7.1f, 5.2f, 8.7f)
                curveTo(3.7f, 10.5f, 4.5f, 14.0f, 6.6f, 14.8f)
                curveTo(8.7f, 15.6f, 10.3f, 13.6f, 12f, 12f)
                curveTo(13.7f, 10.4f, 15.3f, 8.4f, 17.4f, 9.2f)
                curveTo(19.5f, 10.0f, 20.3f, 13.5f, 18.8f, 15.3f)
                curveTo(17.4f, 16.9f, 14.0f, 16.2f, 12f, 12f)
                close()
            }
        }
    }

    /**
     * A track, in place of an album's artwork.
     *
     * One quaver: head, stem, flag. The head is a circle rather than the ellipse
     * a real note has, because at 24dp an ellipse 3.6 wide and 2.8 tall reads as
     * a circle that was drawn slightly wrong rather than as a note head.
     */
    val MusicNote: ImageVector by lazy {
        icon("nx_music_note") {
            path(fill = ink) {
                circle(8.6f, 16.8f, 3.3f)
                roundedRect(10.6f, 5.4f, 12.0f, 16.8f, 0.7f)
                moveTo(11.9f, 5.4f)
                quadTo(17.6f, 6.6f, 18.6f, 12.4f)
                quadTo(16.8f, 7.9f, 11.9f, 8.3f)
                close()
            }
        }
    }

    /**
     * A sleep timer.
     *
     * Told from [Clock] by its crown and by hands at ten past ten rather than
     * at four o'clock. Two dials a few rows apart in the same settings list is
     * the one place the app cannot afford them to be the same glyph twice.
     */
    val Timer: ImageVector by lazy {
        icon("nx_timer") {
            path(fill = ink) {
                roundedRect(9.4f, 1.8f, 14.6f, 4.0f, 1.1f)
            }
            path(fill = ink, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 13.0f, 8.6f)
                circle(12f, 13.0f, 6.6f)
            }
            path(
                stroke = ink,
                strokeLineWidth = LINE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 13.0f)
                lineTo(12f, 8.6f)
                moveTo(12f, 13.0f)
                lineTo(15.0f, 14.6f)
            }
        }
    }
}
