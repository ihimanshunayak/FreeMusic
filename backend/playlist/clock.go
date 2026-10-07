package playlist

import "github.com/ihimanshunayak/FreeMusic/backend/clock"

// nowMs is this package's single source of time.
//
// Routed through clock.NowMs rather than time.Now so that playlist expiry and
// revision timestamps advance monotonically on the same clock that Listen
// Together already syncs playback against. A wall-clock source here would let an
// NTP step move an invitation's expiry backwards, which is the class of bug the
// clock package exists to prevent.
func nowMs() int64 { return clock.NowMs() }
