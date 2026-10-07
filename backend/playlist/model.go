package playlist

import (
	"errors"
	"sort"
	"strings"

	"github.com/ihimanshunayak/FreeMusic/backend/clock"
)

// Wire error codes this package returns. Strings rather than integers because
// every other error in this service is a string, and a client switching on an
// integer would be the only one that does.
const (
	ErrNotFound      = "no_such_playlist"
	ErrForbidden     = "forbidden"
	ErrBadRequest    = "bad_request"
	ErrConflict      = "revision_conflict"
	ErrInviteExpired = "invite_expired"
	ErrInviteRevoked = "invite_revoked"
	ErrInviteUsed    = "invite_used"
	ErrInviteLimit   = "invite_limit"
	ErrFull          = "playlist_full"
	ErrTrackLimit    = "track_limit"
	ErrPlaylistLimit = "playlist_limit"
	ErrServerFull    = "server_full"
)

// Error is a failure a handler can turn into a response without inspecting it.
// Deliberately the same shape as party.PartyError: one failure form for one
// service, so the existing jsonError helper keeps working unchanged.
type Error struct {
	Status  int
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Message }

func newError(status int, code, message string) *Error {
	return &Error{Status: status, Code: code, Message: message}
}

func errNotFound() *Error { return newError(404, ErrNotFound, "No playlist with that id.") }

func errForbidden() *Error {
	return newError(403, ErrForbidden, "You do not have access to this playlist.")
}

func errBadRequest(message string) *Error { return newError(400, ErrBadRequest, message) }

// AsError converts anything returned by this package into an *Error, so a
// handler in main.go has exactly one type to switch on. A non-*Error becomes a
// 500 without leaking its text.
func AsError(err error) *Error {
	var e *Error
	if errors.As(err, &e) {
		return e
	}
	return newError(500, "internal", "Something went wrong on the playlist server.")
}

// PlaylistType distinguishes the three kinds of playlist this service knows.
// The distinction is load-bearing: a Blend is generated and members only read
// it, a Collaborative playlist is hand-edited, and Personal playlists never
// reach the server.
type PlaylistType string

const (
	TypePersonal      PlaylistType = "PERSONAL"
	TypeCollaborative PlaylistType = "COLLABORATIVE"
	TypeBlend         PlaylistType = "BLEND"
)

// Valid reports whether the type is one this service will store.
func (t PlaylistType) Valid() bool {
	switch t {
	case TypePersonal, TypeCollaborative, TypeBlend:
		return true
	}
	return false
}

// Role decides what a member may do: exactly one owner, any number of
// collaborators — the brief's two-role model.
type Role string

const (
	RoleOwner        Role = "OWNER"
	RoleCollaborator Role = "COLLABORATOR"
)

// Member is one participant in a playlist.
//
// UserId is the pseudonym the client derives from its account id (see
// ListenTogether.identity on Android). Stable, and what the UI shows. It is
// *not* an authorisation principal — any client can claim any value — so
// nothing is ever gated on it alone. Authorisation is by TokenHash.
type Member struct {
	UserId      string  `json:"userId"`
	DisplayName string  `json:"displayName"`
	AvatarUrl   *string `json:"avatarUrl,omitempty"`
	Role        Role    `json:"role"`
	JoinedAtMs  int64   `json:"joinedAtMs"`
	// LastActiveMs is best-effort, written at most once a minute so a read does
	// not become a write.
	LastActiveMs int64 `json:"lastActiveMs"`

	// TokenHash is the SHA-256 of this member's membership token. The token
	// itself is returned once at join time and never stored.
	TokenHash string `json:"-"`
}

// Track is one entry in a playlist.
//
// The field set matches party.Track minus the parts that belong to a live queue
// (FromAutoplay, and the stream URL a resolver produces). A playlist stores what
// identifies a song, never a URL that expires.
type Track struct {
	// EntryId identifies *this occurrence*, not the song. The same videoId may
	// legitimately appear twice, added by two people, and moving or removing one
	// must not disturb the other. Assigned server-side.
	EntryId      string  `json:"entryId"`
	VideoId      string  `json:"videoId"`
	Title        string  `json:"title"`
	Artist       string  `json:"artist"`
	Album        *string `json:"album,omitempty"`
	ThumbnailUrl *string `json:"thumbnailUrl,omitempty"`
	DurationMs   *int64  `json:"durationMs,omitempty"`

	// Attribution, kept even after the member leaves, so the playlist does not
	// silently forget who added what.
	AddedByUserId string `json:"addedByUserId"`
	AddedByName   string `json:"addedByName"`
	AddedAtMs     int64  `json:"addedAtMs"`

	// Position is authoritative and dense: renumbered on every structural
	// change, so a client never reconciles gaps or ties.
	Position int `json:"position"`
}

// Invite is a pending invitation. Only the token hash is stored, so a leak of
// the store cannot be replayed; the token never contains a playlist or user id.
type Invite struct {
	TokenHash   string `json:"tokenHash"`
	CreatedBy   string `json:"createdBy"`
	CreatedAtMs int64  `json:"createdAtMs"`
	ExpiresAtMs int64  `json:"expiresAtMs"`
	// RevokedAtMs is set rather than deleting, so "withdrawn" stays
	// distinguishable from "never existed".
	RevokedAtMs *int64 `json:"revokedAtMs,omitempty"`
	Uses        int    `json:"uses"`
	MaxUses     int    `json:"maxUses"`
}

func (i *Invite) Revoked() bool { return i.RevokedAtMs != nil }

func (i *Invite) Expired(nowMs int64) bool { return nowMs >= i.ExpiresAtMs }

func (i *Invite) Exhausted() bool { return i.MaxUses > 0 && i.Uses >= i.MaxUses }

// Usable is the single question a join asks.
func (i *Invite) Usable(nowMs int64) error {
	if i.Revoked() {
		return newError(410, ErrInviteRevoked, "That invitation was withdrawn.")
	}
	if i.Expired(nowMs) {
		return newError(410, ErrInviteExpired, "That invitation has expired.")
	}
	if i.Exhausted() {
		return newError(410, ErrInviteUsed, "That invitation has already been used.")
	}
	return nil
}

// Revision is one applied change, retained so a client reconnecting behind the
// head can be caught up with a delta instead of the whole playlist.
type Revision struct {
	Revision int64  `json:"revision"`
	Kind     string `json:"kind"`
	AtMs     int64  `json:"atMs"`
	ByUserId string `json:"byUserId"`
	// Payload is the wire form of what changed. Bounded: history is capped at
	// HistoryLimit and the oldest entries are dropped.
	Payload map[string]interface{} `json:"payload,omitempty"`
}

// Revision kinds — the same words the brief asked for.
const (
	KindTrackAdded       = "playlistTrackAdded"
	KindTrackRemoved     = "playlistTrackRemoved"
	KindTrackMoved       = "playlistTrackMoved"
	KindTracksCleared    = "playlistTrackCleared"
	KindMetadataUpdated  = "playlistMetadataUpdated"
	KindMemberJoined     = "playlistMemberJoined"
	KindMemberLeft       = "playlistMemberLeft"
	KindMemberRemoved    = "playlistMemberRemoved"
	KindBlendRegenerated = "playlistBlendRegenerated"
)

// BlendMeta carries what only a Blend knows. Kept on the playlist rather than in
// a parallel table because it is one-to-one and never queried alone.
type BlendMeta struct {
	GeneratedAtMs int64  `json:"generatedAtMs"`
	Reason        string `json:"generationReason"`
	// Compatibility is per member pair, keyed "userIdA|userIdB" with the ids
	// sorted, so a pair has one value regardless of which of them asks.
	Compatibility map[string]float64 `json:"compatibility,omitempty"`
	MemberCount   int                `json:"memberCount"`
	TrackCount    int                `json:"trackCount"`
}

// Playlist is the aggregate.
type Playlist struct {
	Id          string       `json:"id"`
	Type        PlaylistType `json:"playlistType"`
	Name        string       `json:"name"`
	Description string       `json:"description,omitempty"`
	CoverUrl    *string      `json:"coverUrl,omitempty"`
	OwnerId     string       `json:"ownerId"`
	Visibility  Visibility   `json:"visibility"`

	CreatedAtMs int64 `json:"createdAtMs"`
	UpdatedAtMs int64 `json:"updatedAtMs"`

	// Revision increases by exactly one per applied mutation. Clients send the
	// revision they are at and stale writes are refused — the brief's central
	// requirement, and why no client clock is consulted anywhere in this package.
	Revision int64 `json:"revision"`

	Members []Member `json:"members"`
	Tracks  []Track  `json:"tracks"`

	// Internal: a snapshot never carries a token hash, and a client that wants
	// deltas asks for them explicitly.
	Invites []Invite   `json:"-"`
	History []Revision `json:"-"`

	// OwnerTokenHash is the SHA-256 of the owner credential. Stored on the
	// playlist so a restart can restore it, and marked `json:"-"` at the
	// snapshot layer — ToSnapshot copies fields explicitly, so this cannot leak
	// into a response no matter what else changes.
	OwnerTokenHash string `json:"ownerTokenHash,omitempty"`

	// DeleteAtMs is retention only; see Store.
	DeleteAtMs *int64 `json:"deleteAtMs,omitempty"`

	Blend *BlendMeta `json:"blend,omitempty"`
}

// Visibility is reserved for a future sharing model. Stored so the shape need
// not change later; nothing reads it yet.
type Visibility string

const (
	VisibilityPrivate Visibility = "PRIVATE"
	VisibilityLink    Visibility = "LINK"
)

// Member returns the member for a user id, or nil.
func (p *Playlist) Member(userId string) *Member {
	for i := range p.Members {
		if p.Members[i].UserId == userId {
			return &p.Members[i]
		}
	}
	return nil
}

// IsOwner reports whether a user id owns this playlist.
func (p *Playlist) IsOwner(userId string) bool { return p.OwnerId == userId }

// TrackCount is the number of entries, which may exceed the number of distinct
// songs.
func (p *Playlist) TrackCount() int { return len(p.Tracks) }

// FindTrack returns the entry with an id, or nil.
func (p *Playlist) FindTrack(entryId string) *Track {
	for i := range p.Tracks {
		if p.Tracks[i].EntryId == entryId {
			return &p.Tracks[i]
		}
	}
	return nil
}

// IndexOf returns the slice index of an entry, or -1.
func (p *Playlist) IndexOf(entryId string) int {
	for i := range p.Tracks {
		if p.Tracks[i].EntryId == entryId {
			return i
		}
	}
	return -1
}

// SortTracks restores the position invariant from stored positions: ordered by
// Position, with Position rewritten as a dense 0..n-1 sequence.
//
// Used on load, where Position is the authority — a document on disk is the one
// place a hand-edit or a half-applied upgrade could leave a gap. In-memory
// mutations use Renumber instead, because they have already put the slice in the
// order they want and sorting by a stale Position would undo it.
func (p *Playlist) SortTracks() {
	sort.SliceStable(p.Tracks, func(i, j int) bool {
		return p.Tracks[i].Position < p.Tracks[j].Position
	})
	p.Renumber()
}

// Renumber rewrites Position from the slice's current order.
//
// Split from SortTracks deliberately. A mutation that has finished moving
// elements around holds the order it wants but not yet the positions to match,
// so calling a sort there would re-apply the order it just changed — the bug
// that made every reorder a no-op.
func (p *Playlist) Renumber() {
	for i := range p.Tracks {
		p.Tracks[i].Position = i
	}
}

// Snapshot is the wire form: everything a client needs to render a playlist and
// nothing it must not see. Token hashes and invites are absent by construction,
// not by filtering.
type Snapshot struct {
	Id          string       `json:"id"`
	Type        PlaylistType `json:"playlistType"`
	Name        string       `json:"name"`
	Description string       `json:"description,omitempty"`
	CoverUrl    *string      `json:"coverUrl,omitempty"`
	OwnerId     string       `json:"ownerId"`
	Revision    int64        `json:"revision"`
	CreatedAtMs int64        `json:"createdAtMs"`
	UpdatedAtMs int64        `json:"updatedAtMs"`

	Members []Member `json:"members"`
	Tracks  []Track  `json:"tracks"`

	// ViewerRole is resolved from the caller's credential so the UI can hide
	// actions it would be refused anyway. Presentation only — the server
	// re-checks every request.
	ViewerRole Role `json:"viewerRole"`

	Blend *BlendMeta `json:"blend,omitempty"`
}

// ToSnapshot renders the playlist for a caller holding role.
func (p *Playlist) ToSnapshot(role Role) *Snapshot {
	return &Snapshot{
		Id:          p.Id,
		Type:        p.Type,
		Name:        p.Name,
		Description: p.Description,
		CoverUrl:    p.CoverUrl,
		OwnerId:     p.OwnerId,
		Revision:    p.Revision,
		CreatedAtMs: p.CreatedAtMs,
		UpdatedAtMs: p.UpdatedAtMs,
		Members:     p.Members,
		Tracks:      p.Tracks,
		ViewerRole:  role,
		Blend:       p.Blend,
	}
}

// Summary is the list-view form: enough for a card, without the tracks.
type Summary struct {
	Id          string       `json:"id"`
	Type        PlaylistType `json:"playlistType"`
	Name        string       `json:"name"`
	Description string       `json:"description,omitempty"`
	CoverUrl    *string      `json:"coverUrl,omitempty"`
	OwnerId     string       `json:"ownerId"`
	Revision    int64        `json:"revision"`
	UpdatedAtMs int64        `json:"updatedAtMs"`
	TrackCount  int          `json:"trackCount"`
	MemberCount int          `json:"memberCount"`
	ViewerRole  Role         `json:"viewerRole"`
	// CoverThumbs are up to four distinct track thumbnails so a card can compose
	// a cover without fetching the playlist.
	CoverThumbs []string `json:"coverThumbs,omitempty"`
}

// ToSummary renders the card form for a caller holding role.
func (p *Playlist) ToSummary(role Role) *Summary {
	thumbs := make([]string, 0, 4)
	seen := make(map[string]bool, 4)
	for i := range p.Tracks {
		if len(thumbs) == 4 {
			break
		}
		url := p.Tracks[i].ThumbnailUrl
		if url == nil || *url == "" || seen[*url] {
			continue
		}
		seen[*url] = true
		thumbs = append(thumbs, *url)
	}
	return &Summary{
		Id:          p.Id,
		Type:        p.Type,
		Name:        p.Name,
		Description: p.Description,
		CoverUrl:    p.CoverUrl,
		OwnerId:     p.OwnerId,
		Revision:    p.Revision,
		UpdatedAtMs: p.UpdatedAtMs,
		TrackCount:  len(p.Tracks),
		MemberCount: len(p.Members),
		ViewerRole:  role,
		CoverThumbs: thumbs,
	}
}

// ---------------------------------------------------------------------------
// Bounds
// ---------------------------------------------------------------------------

// Every bound the brief asked to be configurable rather than hard-coded. config
// reads them and passes them in; these are the fallbacks used by tests and by a
// zero-value Options.
const (
	DefaultMaxMembers   = 10
	DefaultMaxTracks    = 2000
	DefaultMaxPlaylists = 20000
	DefaultMaxInvites   = 20
	DefaultInviteTTLMs  = int64(7 * 24 * 60 * 60 * 1000)
	DefaultHistoryLimit = 500
	DefaultNameMaxLen   = 120
	DefaultDescMaxLen   = 1000
)

// ---------------------------------------------------------------------------
// Input normalisation
// ---------------------------------------------------------------------------

// cleanName trims and bounds a name, erroring rather than silently accepting an
// empty one.
func cleanName(raw string, maxLen int) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "", errBadRequest("Give the playlist a name.")
	}
	if maxLen <= 0 {
		maxLen = DefaultNameMaxLen
	}
	if runes := []rune(name); len(runes) > maxLen {
		name = string(runes[:maxLen])
	}
	return name, nil
}

// cleanDescription bounds a description. Empty is allowed — it is optional.
func cleanDescription(raw string, maxLen int) string {
	desc := strings.TrimSpace(raw)
	if maxLen <= 0 {
		maxLen = DefaultDescMaxLen
	}
	if runes := []rune(desc); len(runes) > maxLen {
		desc = string(runes[:maxLen])
	}
	return desc
}

// cleanDisplayName bounds the name shown beside a track or in the member list.
func cleanDisplayName(raw string) string {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "Someone"
	}
	if runes := []rune(name); len(runes) > 80 {
		name = string(runes[:80])
	}
	return name
}

// cleanUserId bounds and trims the client-supplied pseudonym.
func cleanUserId(raw string) (string, error) {
	id := strings.TrimSpace(raw)
	if id == "" || len(id) > 128 {
		return "", errBadRequest("userId must be between 1 and 128 characters.")
	}
	return id, nil
}

// cleanAvatar accepts only an http(s) URL, as protocol.JoinRequest does.
func cleanAvatar(raw string) *string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || len(trimmed) > 1000 {
		return nil
	}
	if !strings.HasPrefix(trimmed, "http://") && !strings.HasPrefix(trimmed, "https://") {
		return nil
	}
	return &trimmed
}

// trackFromInput converts a client-supplied track into a stored one.
//
// The client sends only what identifies a song. EntryId and Position are
// assigned here, and AddedBy* comes from the authenticated member rather than
// the request, so attribution cannot be forged.
func trackFromInput(raw map[string]interface{}, member *Member) (*Track, error) {
	videoId := stringField(raw, "videoId", 128)
	if videoId == "" {
		return nil, errBadRequest("A track needs a videoId.")
	}
	var album *string
	if a := stringField(raw, "album", 300); a != "" {
		album = &a
	}
	var thumb *string
	if t := stringField(raw, "thumbnailUrl", 1000); t != "" {
		if strings.HasPrefix(t, "http://") || strings.HasPrefix(t, "https://") {
			thumb = &t
		}
	}
	var duration *int64
	if d := numberField(raw, "durationMs"); d > 0 {
		v := int64(d)
		duration = &v
	}
	return &Track{
		VideoId:       videoId,
		Title:         stringField(raw, "title", 300),
		Artist:        stringField(raw, "artist", 300),
		Album:         album,
		ThumbnailUrl:  thumb,
		DurationMs:    duration,
		AddedByUserId: member.UserId,
		AddedByName:   member.DisplayName,
		AddedAtMs:     clock.NowMs(),
	}, nil
}

// stringField reads a string, trims it and bounds its rune length.
func stringField(raw map[string]interface{}, key string, maxLen int) string {
	value, _ := raw[key].(string)
	value = strings.TrimSpace(value)
	if maxLen > 0 {
		if runes := []rune(value); len(runes) > maxLen {
			value = string(runes[:maxLen])
		}
	}
	return value
}

// numberField reads a JSON number, which encoding/json decodes as float64.
func numberField(raw map[string]interface{}, key string) float64 {
	value, _ := raw[key].(float64)
	return value
}

// boolField reads a JSON boolean.
func boolField(raw map[string]interface{}, key string) bool {
	value, _ := raw[key].(bool)
	return value
}
