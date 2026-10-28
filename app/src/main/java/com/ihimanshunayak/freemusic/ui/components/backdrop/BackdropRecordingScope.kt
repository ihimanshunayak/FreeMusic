// Copyright (c) A|iens. All rights reserved.
// AliensStyle code-header — full structured block below.
//
// Name        : BackdropRecordingScope.kt
// Version     : 1.0.0
// Author      : A'nil Cyborg
// Author URI  : https://github.com/ihimanshunayak
// Description : Records whether the app's shared backdrop layer is being
//               captured at this moment, so that a glass surface can tell
//               whether it is being drawn inside the layer it is about to
//               sample.
// Requires    : FreeMusic backdrop package.
// Responsibilities:
//   - Hold the one boolean that says "the backdrop layer is being recorded".
//   - Provide a scoped runner that sets the flag and always clears it, even
//     if the recording throws.
//   - Be the single source of truth that the guard in `Modifier.liquidGlass`
//     reads before it samples the backdrop.
// Dependencies:
//   - BackdropRecordingScope (this file) — read by ui.components.LiquidGlass
//   - LayerBackdropModifier (ui.components.backdrop.backdrops) — writes it
// Integration Notes:
//   - The flag is a plain boolean on the main thread. The recording and the
//     sampling both happen on the render thread inside the same frame, and
//     the guard only has to be right for the duration of that frame.
//   - It deliberately does not use a CompositionLocal: the check has to
//     work inside a draw modifier, which is not a composable scope.

package com.ihimanshunayak.freemusic.ui.components.backdrop

/**
 * Whether the app's shared backdrop layer is being captured right now.
 *
 * The app records the whole page into one layer so the glass bars can sample
 * it. A surface that samples that layer *from inside the layer being
 * recorded* makes the render tree reference itself, and Android's render-node
 * tree walks that cycle until the thread runs out of stack — the app dies
 * with a SIGSEGV in the render thread. There is no exception to catch and no
 * log line that names the cause.
 *
 * It is a real crash and not a theory. Opening the Explore tab with liquid
 * glass on produced a 2,868-frame tombstone that was nothing but
 * `RenderNode::prepareTreeImpl` calling itself, over and over, with no app
 * frames in it at all; the Account page did the same. Both had a glass
 * surface inside the recorded page, and both stopped crashing the moment that
 * surface was taken out.
 *
 * So the rule is structural, not advisory: a surface may sample the backdrop
 * only from *outside* the recorded layer. The chrome that draws the bars sits
 * outside it and may sample freely; anything the page itself draws may not.
 */
object BackdropRecordingScope {

    /** True while the app's backdrop layer is being recorded. */
    var isRecording: Boolean = false
        private set

    /**
     * Runs [block] with the recording flag set, and always clears it after.
     *
     * The `finally` matters: the recorded block draws the entire page, and a
     * single exception thrown by any child would otherwise leave the flag
     * set for the rest of the process's life — which would silently turn
     * every glass surface in the app into a flat one.
     *
     * Not `inline`: an inline function cannot touch the private setter of a
     * public property, and the setter is what makes the flag write-private.
     * The call is one per recorded frame, so the lambda allocation it costs
     * is noise next to the recording it wraps.
     */
    fun <T> recording(block: () -> T): T {
        isRecording = true
        try {
            return block()
        } finally {
            isRecording = false
        }
    }
}
