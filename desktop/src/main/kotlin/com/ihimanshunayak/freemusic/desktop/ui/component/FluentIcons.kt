// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - the Fluent icon registry.
//
// NAME
//     FluentIcons.kt - one named glyph per concept the app draws.
//
// DESCRIPTION
//     Compose Fluent ships its glyphs as extension properties on `Icons.Regular`
//     - 1863 of them across the core and extended artifacts - each declared in
//     its own file. Importing the raw names into every screen would mean a
//     screen that wants a different glyph has to edit its import block, and the
//     same concept would drift to a different glyph on each screen.
//
//     This registry is the app's own vocabulary. A screen asks for `FluentGlyphs
//     .Queue` and gets whatever Windows' own shell uses for that concept today;
//     changing the glyph is a one-line edit here rather than a sweep.
//
//     The names are deliberately *concept* names, not glyph names: `FluentGlyphs
//     .Like` is a heart today and could be a thumbs-up tomorrow without any
//     screen knowing.
//
// RESPONSIBILITIES
//     - Name every glyph the UI draws exactly once.
//     - Keep the glyph choice for a concept in one place.
//
// DEPENDENCIES
//     - `fluent-icons-core` and `fluent-icons-extended` from Compose Fluent.
//
// INTEGRATION NOTES
//     - Declared as `get()` properties, not initialised `val`s. Fluent builds
//       each icon lazily and caches it on first access; holding a strong
//       reference from a static object would build all 190 of them at class
//       load and delay the first frame for no reason.
//     - Every name below was verified to exist in the artifacts this project
//       resolves. Fluent's icon set is not alphabetical by intent and several
//       obvious names are absent (`MusicNote`, `StopCircle`, `Menu`, `Sort`,
//       `Palette`, `Wifi`), so do not guess a name - check the jar first.

package com.ihimanshunayak.freemusic.desktop.ui.component

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.composefluent.icons.Icons

// Icons.Regular is the default weight. Fluent also offers Filled, which Windows
// itself reserves for "selected" and "active" states - a selected navigation
// entry, a liked heart, a playing queue row. Importing both weights of the same
// name is a conflict, so the filled variants are aliased at their import site.
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.AddCircle
import io.github.composefluent.icons.regular.Album
import io.github.composefluent.icons.regular.AlbumAdd
import io.github.composefluent.icons.regular.AppsListDetail
import io.github.composefluent.icons.regular.ArrowClockwise
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.ArrowExportLtr
import io.github.composefluent.icons.regular.ArrowImport
import io.github.composefluent.icons.regular.ArrowMaximize
import io.github.composefluent.icons.regular.ArrowMinimize
import io.github.composefluent.icons.regular.ArrowRedo
import io.github.composefluent.icons.regular.ArrowRepeatAll
import io.github.composefluent.icons.regular.ArrowRepeatAllOff
import io.github.composefluent.icons.regular.ArrowShuffle
import io.github.composefluent.icons.regular.ArrowShuffleOff
import io.github.composefluent.icons.regular.ArrowSort
import io.github.composefluent.icons.regular.ArrowSortDown
import io.github.composefluent.icons.regular.ArrowSortUp
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.ArrowTrending
import io.github.composefluent.icons.regular.ArrowUndo
import io.github.composefluent.icons.regular.ArrowUpload
import io.github.composefluent.icons.regular.Beaker
import io.github.composefluent.icons.regular.Book
import io.github.composefluent.icons.regular.BookOpen
import io.github.composefluent.icons.regular.Bookmark
import io.github.composefluent.icons.regular.BookmarkMultiple
import io.github.composefluent.icons.regular.BoxMultiple
import io.github.composefluent.icons.regular.Broom
import io.github.composefluent.icons.regular.Bug
import io.github.composefluent.icons.regular.CalendarClock
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.Camera
import io.github.composefluent.icons.regular.Certificate
import io.github.composefluent.icons.regular.ChartMultiple
import io.github.composefluent.icons.regular.Chat
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.CheckmarkCircle
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.ChevronUp
import io.github.composefluent.icons.regular.Clipboard
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.icons.regular.CloudArrowDown
import io.github.composefluent.icons.regular.CloudArrowUp
import io.github.composefluent.icons.regular.Code
import io.github.composefluent.icons.regular.Color
import io.github.composefluent.icons.regular.Comment
import io.github.composefluent.icons.regular.CompassNorthwest
import io.github.composefluent.icons.regular.Copy
import io.github.composefluent.icons.regular.Crop
import io.github.composefluent.icons.regular.Cut
import io.github.composefluent.icons.regular.DataBarVertical
import io.github.composefluent.icons.regular.DataPie
import io.github.composefluent.icons.regular.DataTrending
import io.github.composefluent.icons.regular.Database
import io.github.composefluent.icons.regular.DarkTheme
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Desktop
import io.github.composefluent.icons.regular.DeviceEq
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.DismissCircle
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.DualScreen
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.Eraser
import io.github.composefluent.icons.regular.ErrorCircle
import io.github.composefluent.icons.regular.Eye
import io.github.composefluent.icons.regular.EyeOff
import io.github.composefluent.icons.regular.FastForward
import io.github.composefluent.icons.regular.Filter
import io.github.composefluent.icons.regular.FilterDismiss
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.FolderArrowRight
import io.github.composefluent.icons.regular.FolderArrowUp
import io.github.composefluent.icons.regular.FolderOpen
import io.github.composefluent.icons.regular.FullScreenMaximize
import io.github.composefluent.icons.regular.FullScreenMinimize
import io.github.composefluent.icons.regular.Gauge
import io.github.composefluent.icons.regular.Gift
import io.github.composefluent.icons.regular.Globe
import io.github.composefluent.icons.regular.Grid
import io.github.composefluent.icons.regular.Headphones
import io.github.composefluent.icons.regular.HeadphonesSoundWave
import io.github.composefluent.icons.regular.Heart
import io.github.composefluent.icons.regular.History
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Hourglass
import io.github.composefluent.icons.regular.Image
import io.github.composefluent.icons.regular.Info
import io.github.composefluent.icons.regular.Key
import io.github.composefluent.icons.regular.Layer
import io.github.composefluent.icons.regular.Library
import io.github.composefluent.icons.regular.Link
import io.github.composefluent.icons.regular.List
import io.github.composefluent.icons.regular.LockClosed
import io.github.composefluent.icons.regular.LockOpen
import io.github.composefluent.icons.regular.Mic
import io.github.composefluent.icons.regular.MicOff
import io.github.composefluent.icons.regular.MoreHorizontal
import io.github.composefluent.icons.regular.MoreVertical
import io.github.composefluent.icons.regular.MusicNote1
import io.github.composefluent.icons.regular.MusicNote2
import io.github.composefluent.icons.regular.MusicNoteOff1
import io.github.composefluent.icons.regular.MusicNoteOff2
import io.github.composefluent.icons.regular.Next
import io.github.composefluent.icons.regular.NoteAdd
import io.github.composefluent.icons.regular.Notebook
import io.github.composefluent.icons.regular.Open
import io.github.composefluent.icons.regular.Options
import io.github.composefluent.icons.regular.PaintBrush
import io.github.composefluent.icons.regular.PanelLeft
import io.github.composefluent.icons.regular.PanelRight
import io.github.composefluent.icons.regular.Pause
import io.github.composefluent.icons.regular.PauseCircle
import io.github.composefluent.icons.regular.Pen
import io.github.composefluent.icons.regular.People
import io.github.composefluent.icons.regular.PeopleTeam
import io.github.composefluent.icons.regular.Person
import io.github.composefluent.icons.regular.PersonAccounts
import io.github.composefluent.icons.regular.Play
import io.github.composefluent.icons.regular.PlayCircle
import io.github.composefluent.icons.regular.PlugConnected
import io.github.composefluent.icons.regular.PlugDisconnected
import io.github.composefluent.icons.regular.Previous
import io.github.composefluent.icons.regular.PuzzlePiece
import io.github.composefluent.icons.regular.QuestionCircle
import io.github.composefluent.icons.regular.ReadingList
import io.github.composefluent.icons.regular.Rewind
import io.github.composefluent.icons.regular.Rocket
import io.github.composefluent.icons.regular.Save
import io.github.composefluent.icons.regular.Search
import io.github.composefluent.icons.regular.Server
import io.github.composefluent.icons.regular.Settings
import io.github.composefluent.icons.regular.Share
import io.github.composefluent.icons.regular.Shield
import io.github.composefluent.icons.regular.Signature
import io.github.composefluent.icons.regular.Sleep
import io.github.composefluent.icons.regular.SlideText
import io.github.composefluent.icons.regular.Sparkle
import io.github.composefluent.icons.regular.Speaker0
import io.github.composefluent.icons.regular.Speaker1
import io.github.composefluent.icons.regular.Speaker2
import io.github.composefluent.icons.regular.SpeakerMute
import io.github.composefluent.icons.regular.SpeakerOff
import io.github.composefluent.icons.regular.SplitHorizontal
import io.github.composefluent.icons.regular.SplitVertical
import io.github.composefluent.icons.regular.Stack
import io.github.composefluent.icons.regular.Star
import io.github.composefluent.icons.regular.StarAdd
import io.github.composefluent.icons.regular.Stop
import io.github.composefluent.icons.regular.SubtractCircle
import io.github.composefluent.icons.regular.Tab
import io.github.composefluent.icons.regular.Table
import io.github.composefluent.icons.regular.Tag
import io.github.composefluent.icons.regular.TagMultiple
import io.github.composefluent.icons.regular.TextAlignCenter
import io.github.composefluent.icons.regular.TextAlignLeft
import io.github.composefluent.icons.regular.TextBulletListSquare
import io.github.composefluent.icons.regular.TextFont
import io.github.composefluent.icons.regular.TextQuote
import io.github.composefluent.icons.regular.Timer
import io.github.composefluent.icons.regular.Translate
import io.github.composefluent.icons.regular.Trophy
import io.github.composefluent.icons.regular.Warning
import io.github.composefluent.icons.regular.WeatherMoon
import io.github.composefluent.icons.regular.WeatherSunny
import io.github.composefluent.icons.regular.Wrench
import io.github.composefluent.icons.regular.ZoomIn
import io.github.composefluent.icons.regular.ZoomOut

/**
 * The wordmark's glyph.
 *
 * Separately named so the logo can diverge from the transport controls' play
 * button - a logo is a brand asset and a play button is a control, and one
 * should not drag the other when it changes.
 */
object BrandIcons {
    val Play: ImageVector get() = Icons.Regular.Play
}

/**
 * The app's glyph vocabulary.
 *
 * Grouped by the part of the UI that uses them rather than alphabetically, so a
 * reader can see at a glance which glyphs a given area owns.
 */
object FluentGlyphs {

    // -- ## SUBSECTION: Navigation -----------------------------------------

    val Home: ImageVector get() = Icons.Regular.Home
    val Search: ImageVector get() = Icons.Regular.Search
    val Explore: ImageVector get() = Icons.Regular.CompassNorthwest
    val Library: ImageVector get() = Icons.Regular.Library
    val LocalFiles: ImageVector get() = Icons.Regular.Folder
    val History: ImageVector get() = Icons.Regular.History
    val Queue: ImageVector get() = Icons.Regular.AppsListDetail
    val Downloads: ImageVector get() = Icons.Regular.ArrowDownload
    val Statistics: ImageVector get() = Icons.Regular.DataBarVertical
    val ListenTogether: ImageVector get() = Icons.Regular.PeopleTeam
    val Sources: ImageVector get() = Icons.Regular.PuzzlePiece
    val Account: ImageVector get() = Icons.Regular.Person
    val Settings: ImageVector get() = Icons.Regular.Settings
    val Diagnostics: ImageVector get() = Icons.Regular.Bug
    val Equalizer: ImageVector get() = Icons.Regular.Options

    // -- ## SUBSECTION: Transport ------------------------------------------

    val Play: ImageVector get() = Icons.Regular.Play
    val PlayCircle: ImageVector get() = Icons.Regular.PlayCircle
    val Pause: ImageVector get() = Icons.Regular.Pause
    val PauseCircle: ImageVector get() = Icons.Regular.PauseCircle
    val Stop: ImageVector get() = Icons.Regular.Stop
    val Next: ImageVector get() = Icons.Regular.Next
    val Previous: ImageVector get() = Icons.Regular.Previous
    val FastForward: ImageVector get() = Icons.Regular.FastForward
    val Rewind: ImageVector get() = Icons.Regular.Rewind
    val Shuffle: ImageVector get() = Icons.Regular.ArrowShuffle
    val ShuffleOff: ImageVector get() = Icons.Regular.ArrowShuffleOff
    val Repeat: ImageVector get() = Icons.Regular.ArrowRepeatAll
    val RepeatOff: ImageVector get() = Icons.Regular.ArrowRepeatAllOff
    val VolumeHigh: ImageVector get() = Icons.Regular.Speaker2
    val VolumeLow: ImageVector get() = Icons.Regular.Speaker1
    val VolumeOff: ImageVector get() = Icons.Regular.Speaker0
    val VolumeMute: ImageVector get() = Icons.Regular.SpeakerMute
    val VolumeUnavailable: ImageVector get() = Icons.Regular.SpeakerOff
    val FullScreen: ImageVector get() = Icons.Regular.FullScreenMaximize
    val ExitFullScreen: ImageVector get() = Icons.Regular.FullScreenMinimize

    // -- ## SUBSECTION: Library and content --------------------------------

    val Music: ImageVector get() = Icons.Regular.MusicNote1
    val MusicAlt: ImageVector get() = Icons.Regular.MusicNote2
    val MusicOff: ImageVector get() = Icons.Regular.MusicNoteOff1
    val Album: ImageVector get() = Icons.Regular.Album
    val AlbumAdd: ImageVector get() = Icons.Regular.AlbumAdd
    val Bookmark: ImageVector get() = Icons.Regular.Bookmark
    val Bookmarks: ImageVector get() = Icons.Regular.BookmarkMultiple
    val Like: ImageVector get() = Icons.Regular.Heart
    val Liked: ImageVector get() = Icons.Regular.Star
    val LikeAdd: ImageVector get() = Icons.Regular.StarAdd
    val Lyrics: ImageVector get() = Icons.Regular.TextBulletListSquare
    val QueueAdd: ImageVector get() = Icons.Regular.NoteAdd
    val Playlist: ImageVector get() = Icons.Regular.ReadingList
    val Artists: ImageVector get() = Icons.Regular.PersonAccounts
    val ArtistsGroup: ImageVector get() = Icons.Regular.People
    val Folder: ImageVector get() = Icons.Regular.Folder
    val FolderOpen: ImageVector get() = Icons.Regular.FolderOpen
    val FolderToQueue: ImageVector get() = Icons.Regular.FolderArrowRight
    val FolderUpload: ImageVector get() = Icons.Regular.FolderArrowUp
    val File: ImageVector get() = Icons.Regular.Document
    val Notebook: ImageVector get() = Icons.Regular.Notebook
    val Tag: ImageVector get() = Icons.Regular.Tag
    val Tags: ImageVector get() = Icons.Regular.TagMultiple
    val Grid: ImageVector get() = Icons.Regular.Grid
    val List: ImageVector get() = Icons.Regular.List
    val Sort: ImageVector get() = Icons.Regular.ArrowSort
    val SortUp: ImageVector get() = Icons.Regular.ArrowSortUp
    val SortDown: ImageVector get() = Icons.Regular.ArrowSortDown
    val Filter: ImageVector get() = Icons.Regular.Filter

    // -- ## SUBSECTION: Lyrics, canvas and appearance ----------------------

    val LyricsQuote: ImageVector get() = Icons.Regular.TextQuote
    val Translate: ImageVector get() = Icons.Regular.Translate
    val TextFont: ImageVector get() = Icons.Regular.TextFont
    val AlignLeft: ImageVector get() = Icons.Regular.TextAlignLeft
    val AlignCenter: ImageVector get() = Icons.Regular.TextAlignCenter
    val Image: ImageVector get() = Icons.Regular.Image
    val Canvas: ImageVector get() = Icons.Regular.SlideText
    val Palette: ImageVector get() = Icons.Regular.PaintBrush
    val ColorSwatch: ImageVector get() = Icons.Regular.Color
    val ThemeLight: ImageVector get() = Icons.Regular.WeatherSunny
    val ThemeDark: ImageVector get() = Icons.Regular.WeatherMoon
    val ThemeSystem: ImageVector get() = Icons.Regular.DarkTheme
    val Sparkle: ImageVector get() = Icons.Regular.Sparkle
    val ZoomIn: ImageVector get() = Icons.Regular.ZoomIn
    val ZoomOut: ImageVector get() = Icons.Regular.ZoomOut
    val Crop: ImageVector get() = Icons.Regular.Crop

    // -- ## SUBSECTION: Downloads and transfer -----------------------------

    val Download: ImageVector get() = Icons.Regular.ArrowDownload
    val DownloadCloud: ImageVector get() = Icons.Regular.CloudArrowDown
    val UploadCloud: ImageVector get() = Icons.Regular.CloudArrowUp
    val Import: ImageVector get() = Icons.Regular.ArrowImport
    val Export: ImageVector get() = Icons.Regular.ArrowExportLtr
    val Sync: ImageVector get() = Icons.Regular.ArrowSync
    val Refresh: ImageVector get() = Icons.Regular.ArrowClockwise
    val Retry: ImageVector get() = Icons.Regular.ArrowClockwise
    val Cancel: ImageVector get() = Icons.Regular.DismissCircle
    val DismissSmall: ImageVector get() = Icons.Regular.Dismiss
    val FilterOff: ImageVector get() = Icons.Regular.FilterDismiss
    val Complete: ImageVector get() = Icons.Regular.CheckmarkCircle
    val Checkmark: ImageVector get() = Icons.Regular.Checkmark
    val Hourglass: ImageVector get() = Icons.Regular.Hourglass

    // -- ## SUBSECTION: Remote and network ---------------------------------

    val Server: ImageVector get() = Icons.Regular.Server
    val Database: ImageVector get() = Icons.Regular.Database
    val Cloud: ImageVector get() = Icons.Regular.Cloud
    val Globe: ImageVector get() = Icons.Regular.Globe
    val Link: ImageVector get() = Icons.Regular.Link
    val Plugged: ImageVector get() = Icons.Regular.PlugConnected
    val Unplugged: ImageVector get() = Icons.Regular.PlugDisconnected
    val Shield: ImageVector get() = Icons.Regular.Shield
    val Key: ImageVector get() = Icons.Regular.Key
    val Locked: ImageVector get() = Icons.Regular.LockClosed
    val Unlocked: ImageVector get() = Icons.Regular.LockOpen
    val Visible: ImageVector get() = Icons.Regular.Eye
    val Hidden: ImageVector get() = Icons.Regular.EyeOff

    // -- ## SUBSECTION: Statistics and insight -----------------------------

    val Charts: ImageVector get() = Icons.Regular.ChartMultiple
    val BarChart: ImageVector get() = Icons.Regular.DataBarVertical
    val PieChart: ImageVector get() = Icons.Regular.DataPie
    val Trending: ImageVector get() = Icons.Regular.DataTrending
    val ArrowTrending: ImageVector get() = Icons.Regular.ArrowTrending
    val Calendar: ImageVector get() = Icons.Regular.CalendarLtr
    val CalendarClock: ImageVector get() = Icons.Regular.CalendarClock
    val Clock: ImageVector get() = Icons.Regular.Clock
    val Timer: ImageVector get() = Icons.Regular.Timer
    val Sleep: ImageVector get() = Icons.Regular.Sleep
    val Trophy: ImageVector get() = Icons.Regular.Trophy
    val Certificate: ImageVector get() = Icons.Regular.Certificate
    val Gift: ImageVector get() = Icons.Regular.Gift
    val Table: ImageVector get() = Icons.Regular.Table

    // -- ## SUBSECTION: Audio devices and quality --------------------------

    val Headphones: ImageVector get() = Icons.Regular.Headphones
    val HeadphonesActive: ImageVector get() = Icons.Regular.HeadphonesSoundWave
    val AudioDevice: ImageVector get() = Icons.Regular.DeviceEq
    val Mic: ImageVector get() = Icons.Regular.Mic
    val MicOff: ImageVector get() = Icons.Regular.MicOff
    val Gauge: ImageVector get() = Icons.Regular.Gauge
    val Beaker: ImageVector get() = Icons.Regular.Beaker

    // -- ## SUBSECTION: Editing and file actions ---------------------------

    val Add: ImageVector get() = Icons.Regular.Add
    val AddCircle: ImageVector get() = Icons.Regular.AddCircle
    val Remove: ImageVector get() = Icons.Regular.SubtractCircle
    val Edit: ImageVector get() = Icons.Regular.Edit
    val Save: ImageVector get() = Icons.Regular.Save
    val Delete: ImageVector get() = Icons.Regular.Delete
    val Copy: ImageVector get() = Icons.Regular.Copy
    val Share: ImageVector get() = Icons.Regular.Share
    val Open: ImageVector get() = Icons.Regular.Open
    val Redo: ImageVector get() = Icons.Regular.ArrowRedo
    val Undo: ImageVector get() = Icons.Regular.ArrowUndo
    val Select: ImageVector get() = Icons.Regular.BoxMultiple
    val Pen: ImageVector get() = Icons.Regular.Pen
    val Eraser: ImageVector get() = Icons.Regular.Eraser
    val Signature: ImageVector get() = Icons.Regular.Signature
    val Clipboard: ImageVector get() = Icons.Regular.Clipboard
    val Cut: ImageVector get() = Icons.Regular.Cut

    // -- ## SUBSECTION: Shell and chrome -----------------------------------

    val More: ImageVector get() = Icons.Regular.MoreHorizontal
    val MoreVertical: ImageVector get() = Icons.Regular.MoreVertical
    val Close: ImageVector get() = Icons.Regular.Dismiss
    val Back: ImageVector get() = Icons.Regular.ChevronLeft
    val Forward: ImageVector get() = Icons.Regular.ChevronRight
    val Up: ImageVector get() = Icons.Regular.ChevronUp
    val Down: ImageVector get() = Icons.Regular.ChevronDown
    val Expand: ImageVector get() = Icons.Regular.ArrowMaximize
    val Collapse: ImageVector get() = Icons.Regular.ArrowMinimize
    val SidebarLeft: ImageVector get() = Icons.Regular.PanelLeft
    val SidebarRight: ImageVector get() = Icons.Regular.PanelRight
    val SplitHorizontal: ImageVector get() = Icons.Regular.SplitHorizontal
    val SplitVertical: ImageVector get() = Icons.Regular.SplitVertical
    val DualScreen: ImageVector get() = Icons.Regular.DualScreen
    val Desktop: ImageVector get() = Icons.Regular.Desktop
    val Tab: ImageVector get() = Icons.Regular.Tab
    val Layers: ImageVector get() = Icons.Regular.Layer
    val Stack: ImageVector get() = Icons.Regular.Stack

    // -- ## SUBSECTION: Status and messaging -------------------------------

    val Info: ImageVector get() = Icons.Regular.Info
    val Warning: ImageVector get() = Icons.Regular.Warning
    val Error: ImageVector get() = Icons.Regular.ErrorCircle
    val Question: ImageVector get() = Icons.Regular.QuestionCircle
    val Comment: ImageVector get() = Icons.Regular.Comment
    val Chat: ImageVector get() = Icons.Regular.Chat
    val Book: ImageVector get() = Icons.Regular.Book
    val BookOpen: ImageVector get() = Icons.Regular.BookOpen
    val Rocket: ImageVector get() = Icons.Regular.Rocket
    val Tools: ImageVector get() = Icons.Regular.Wrench
    val Broom: ImageVector get() = Icons.Regular.Broom
    val Code: ImageVector get() = Icons.Regular.Code
    val BeakerEmpty: ImageVector get() = Icons.Regular.Beaker
    val AudioCamera: ImageVector get() = Icons.Regular.Camera
}
