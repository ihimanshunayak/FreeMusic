package playlist

import (
	"fmt"
	"strings"
	"testing"
)

// newTestService builds a service over a memory store with small, explicit
// bounds so a test can reach a limit without creating thousands of records.
//
// MaxInvites is deliberately generous: nearly every membership test mints an
// invite per join, so a small cap here would make unrelated tests fail on a
// limit they are not exercising. The invite cap has its own test, which builds
// its own service.
func newTestService(t *testing.T) *Service {
	t.Helper()
	s, err := NewService(NewMemoryStore(), Options{
		MaxPlaylists: 10,
		MaxMembers:   4,
		MaxTracks:    10,
		MaxInvites:   64,
		InviteTTLMs:  DefaultInviteTTLMs,
		HistoryLimit: 20,
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	return s
}

// newServiceWith builds a service over a memory store with explicit bounds, for
// the tests that need to reach a limit the shared options do not.
func newServiceWith(t *testing.T, opts Options) *Service {
	t.Helper()
	s, err := NewService(NewMemoryStore(), opts)
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	return s
}

// createPlaylist is the common setup: an owner and a playlist.
func createPlaylist(t *testing.T, s *Service, name string) (*CreateResult, Credential) {
	t.Helper()
	res, err := s.Create(CreateRequest{
		Type:        TypeCollaborative,
		Name:        name,
		Description: "test playlist",
		UserId:      "owner-1",
		DisplayName: "Owner",
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	return res, Credential{UserId: "owner-1", Token: res.OwnerToken}
}

// joinAs invites and joins a second user, returning their credential.
func joinAs(t *testing.T, s *Service, playlistId string, owner Credential, userId, name string) Credential {
	t.Helper()
	inv, err := s.Invite(playlistId, owner, 0, 0)
	if err != nil {
		t.Fatalf("Invite: %v", err)
	}
	res, err := s.Join(inv.Token, JoinRequest{UserId: userId, DisplayName: name})
	if err != nil {
		t.Fatalf("Join(%s): %v", userId, err)
	}
	return Credential{UserId: userId, Token: res.MemberToken}
}

func track(videoId string) map[string]interface{} {
	return map[string]interface{}{
		"videoId": videoId,
		"title":   "Song " + videoId,
		"artist":  "Artist",
	}
}

// ---------------------------------------------------------------------------
// Creation
// ---------------------------------------------------------------------------

func TestCreateAssignsOwnerAndRevision(t *testing.T) {
	s := newTestService(t)
	res, cred := createPlaylist(t, s, "Friday Night")

	if res.Playlist.Id == "" {
		t.Fatal("expected a playlist id")
	}
	if res.Playlist.Revision != 1 {
		t.Fatalf("expected initial revision 1, got %d", res.Playlist.Revision)
	}
	if res.Playlist.OwnerId != "owner-1" {
		t.Fatalf("unexpected owner %q", res.Playlist.OwnerId)
	}
	if len(res.Playlist.Members) != 1 || res.Playlist.Members[0].Role != RoleOwner {
		t.Fatalf("expected one owner member, got %+v", res.Playlist.Members)
	}
	if res.Playlist.ViewerRole != RoleOwner {
		t.Fatalf("expected viewer role OWNER, got %q", res.Playlist.ViewerRole)
	}

	// The two tokens must differ, or the owner credential would be usable as a
	// membership credential and vice versa.
	if res.OwnerToken == res.MemberToken {
		t.Fatal("owner and member tokens must not be the same value")
	}
	if res.OwnerToken == "" || res.MemberToken == "" {
		t.Fatal("both tokens must be returned once")
	}

	if _, err := s.Get(res.Playlist.Id, cred); err != nil {
		t.Fatalf("owner should be able to read own playlist: %v", err)
	}
}

func TestCreateRejectsBlankName(t *testing.T) {
	s := newTestService(t)
	_, err := s.Create(CreateRequest{Type: TypeCollaborative, Name: "   ", UserId: "u1"})
	if err == nil {
		t.Fatal("expected an error for a blank name")
	}
	if AsError(err).Code != ErrBadRequest {
		t.Fatalf("expected %s, got %s", ErrBadRequest, AsError(err).Code)
	}
}

func TestCreateTrimsAndBoundsName(t *testing.T) {
	s := newTestService(t)
	long := strings.Repeat("x", 400)
	res, err := s.Create(CreateRequest{Type: TypeCollaborative, Name: "  Trimmed  ", UserId: "u1"})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if res.Playlist.Name != "Trimmed" {
		t.Fatalf("expected trimmed name, got %q", res.Playlist.Name)
	}

	res2, err := s.Create(CreateRequest{Type: TypeCollaborative, Name: long, UserId: "u1"})
	if err != nil {
		t.Fatalf("Create long: %v", err)
	}
	if got := len([]rune(res2.Playlist.Name)); got != DefaultNameMaxLen {
		t.Fatalf("expected name capped at %d, got %d", DefaultNameMaxLen, got)
	}
}

func TestCreateRejectsUnknownType(t *testing.T) {
	s := newTestService(t)
	_, err := s.Create(CreateRequest{Type: "WHATEVER", Name: "x", UserId: "u1"})
	if err == nil {
		t.Fatal("expected an error for an unknown playlist type")
	}
}

// The playlist cap is a service-level bound, not a per-user one.
func TestCreateEnforcesServerLimit(t *testing.T) {
	s := newTestService(t)
	for i := 0; i < 10; i++ {
		if _, err := s.Create(CreateRequest{Type: TypeCollaborative, Name: "p", UserId: "u"}); err != nil {
			t.Fatalf("Create %d: %v", i, err)
		}
	}
	_, err := s.Create(CreateRequest{Type: TypeCollaborative, Name: "p", UserId: "u"})
	if err == nil {
		t.Fatal("expected the 11th playlist to be refused")
	}
	if AsError(err).Code != ErrPlaylistLimit {
		t.Fatalf("expected %s, got %s", ErrPlaylistLimit, AsError(err).Code)
	}
}

// ---------------------------------------------------------------------------
// Authorisation
// ---------------------------------------------------------------------------

func TestGetRequiresACredential(t *testing.T) {
	s := newTestService(t)
	res, _ := createPlaylist(t, s, "Private")

	if _, err := s.Get(res.Playlist.Id, Credential{}); err == nil {
		t.Fatal("expected an anonymous read to be refused")
	}
	if _, err := s.Get(res.Playlist.Id, Credential{UserId: "owner-1", Token: "not-a-token"}); err == nil {
		t.Fatal("expected a wrong token to be refused")
	}
}

// The central security property of the audit's §15: claiming the owner's userId
// must not grant anything, because userId is self-asserted.
func TestClaimingOwnerUserIdDoesNotGrantAccess(t *testing.T) {
	s := newTestService(t)
	res, _ := createPlaylist(t, s, "Private")

	forged := Credential{UserId: "owner-1", Token: "made-up"}
	if _, err := s.Get(res.Playlist.Id, forged); err == nil {
		t.Fatal("a forged userId with a bad token must not be able to read")
	}
	if _, _, err := s.AddTracks(res.Playlist.Id, forged, 0, []map[string]interface{}{track("v1")}); err == nil {
		t.Fatal("a forged userId with a bad token must not be able to write")
	}
	if err := s.Delete(res.Playlist.Id, forged); err == nil {
		t.Fatal("a forged userId with a bad token must not be able to delete")
	}
}

func TestCollaboratorCanAddButCannotRenameOrDelete(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Shared")
	guest := joinAs(t, s, res.Playlist.Id, owner, "user-2", "Rahul")

	if _, _, err := s.AddTracks(res.Playlist.Id, guest, 0, []map[string]interface{}{track("v1")}); err != nil {
		t.Fatalf("a collaborator must be able to add tracks: %v", err)
	}

	name := "Renamed"
	if _, err := s.UpdateMetadata(res.Playlist.Id, guest, MetadataPatch{Name: &name}); err == nil {
		t.Fatal("a collaborator must not be able to rename")
	}
	if err := s.Delete(res.Playlist.Id, guest); err == nil {
		t.Fatal("a collaborator must not be able to delete")
	}
	if _, err := s.ClearTracks(res.Playlist.Id, guest, 0); err == nil {
		t.Fatal("a collaborator must not be able to clear every track")
	}
}

// Removal has to take effect immediately — there is no session to expire,
// because authorisation is resolved from the store on every call.
func TestRemovedCollaboratorLosesAccessAtOnce(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Shared")
	guest := joinAs(t, s, res.Playlist.Id, owner, "user-2", "Rahul")

	if _, _, err := s.AddTracks(res.Playlist.Id, guest, 0, []map[string]interface{}{track("v1")}); err != nil {
		t.Fatalf("guest should be able to add before removal: %v", err)
	}
	if err := s.RemoveMember(res.Playlist.Id, owner, "user-2"); err != nil {
		t.Fatalf("RemoveMember: %v", err)
	}
	if _, err := s.Get(res.Playlist.Id, guest); err == nil {
		t.Fatal("a removed collaborator must lose read access")
	}
	if _, _, err := s.AddTracks(res.Playlist.Id, guest, 0, []map[string]interface{}{track("v2")}); err == nil {
		t.Fatal("a removed collaborator must lose write access")
	}
}

func TestOwnerCannotBeRemoved(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Mine")
	if err := s.RemoveMember(res.Playlist.Id, owner, "owner-1"); err == nil {
		t.Fatal("the owner must not be removable")
	}
}

// ---------------------------------------------------------------------------
// Invitations
// ---------------------------------------------------------------------------

func TestInviteAndJoin(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Friday Night")

	inv, err := s.Invite(res.Playlist.Id, owner, 0, 0)
	if err != nil {
		t.Fatalf("Invite: %v", err)
	}
	if inv.Token == "" {
		t.Fatal("expected an invite token")
	}
	if inv.ExpiresAtMs <= nowMs() {
		t.Fatal("expected a future expiry")
	}

	// The token must not carry any identifier in a readable form.
	if strings.Contains(inv.Token, res.Playlist.Id) || strings.Contains(inv.Token, "owner-1") {
		t.Fatal("the invite token must not expose internal ids")
	}

	joined, err := s.Join(inv.Token, JoinRequest{UserId: "user-2", DisplayName: "Rahul"})
	if err != nil {
		t.Fatalf("Join: %v", err)
	}
	if joined.MemberToken == "" {
		t.Fatal("expected a membership token")
	}
	if joined.Playlist.ViewerRole != RoleCollaborator {
		t.Fatalf("expected COLLABORATOR, got %q", joined.Playlist.ViewerRole)
	}
	if joined.Playlist.MemberCountIfAny() != 2 {
		t.Fatalf("expected 2 members, got %d", joined.Playlist.MemberCountIfAny())
	}
}

func TestJoinWithUnknownTokenFails(t *testing.T) {
	s := newTestService(t)
	createPlaylist(t, s, "Friday Night")
	if _, err := s.Join("nonsense", JoinRequest{UserId: "u2"}); err == nil {
		t.Fatal("expected an unknown token to be refused")
	}
}

func TestRevokedInviteCannotBeUsed(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Friday Night")
	inv, _ := s.Invite(res.Playlist.Id, owner, 0, 0)

	if err := s.RevokeInvite(res.Playlist.Id, owner, inv.Token); err != nil {
		t.Fatalf("RevokeInvite: %v", err)
	}
	_, err := s.Join(inv.Token, JoinRequest{UserId: "u2"})
	if err == nil {
		t.Fatal("a revoked invite must not be usable")
	}
	if AsError(err).Code != ErrInviteRevoked {
		t.Fatalf("expected %s, got %s", ErrInviteRevoked, AsError(err).Code)
	}
}

func TestExpiredInviteCannotBeUsed(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Friday Night")
	// A TTL that has already elapsed by the time the join is attempted.
	inv, err := s.Invite(res.Playlist.Id, owner, 0, 1)
	if err != nil {
		t.Fatalf("Invite: %v", err)
	}
	// clock.NowMs is monotonic, so a millisecond is enough to pass a 1ms TTL
	// only after time actually advances. Rather than sleep, rewind the invite.
	for i := range s.playlists[res.Playlist.Id].Invites {
		if TokenMatches(inv.Token, s.playlists[res.Playlist.Id].Invites[i].TokenHash) {
			s.playlists[res.Playlist.Id].Invites[i].ExpiresAtMs = nowMs() - 1
		}
	}
	_, err = s.Join(inv.Token, JoinRequest{UserId: "u2"})
	if err == nil {
		t.Fatal("an expired invite must not be usable")
	}
	if AsError(err).Code != ErrInviteExpired {
		t.Fatalf("expected %s, got %s", ErrInviteExpired, AsError(err).Code)
	}
}

func TestInviteUseCapIsEnforced(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Single use")
	inv, err := s.Invite(res.Playlist.Id, owner, 1, 0)
	if err != nil {
		t.Fatalf("Invite: %v", err)
	}
	if _, err := s.Join(inv.Token, JoinRequest{UserId: "u2"}); err != nil {
		t.Fatalf("first join should work: %v", err)
	}
	if _, err := s.Join(inv.Token, JoinRequest{UserId: "u3"}); err == nil {
		t.Fatal("a single-use invite must not admit a second user")
	}
}

func TestInviteLimitIsEnforced(t *testing.T) {
	s := newServiceWith(t, Options{MaxInvites: 2})
	res, owner := createPlaylist(t, s, "Few links")

	first, err := s.Invite(res.Playlist.Id, owner, 0, 0)
	if err != nil {
		t.Fatalf("invite 1: %v", err)
	}
	if _, err := s.Invite(res.Playlist.Id, owner, 0, 0); err != nil {
		t.Fatalf("invite 2: %v", err)
	}
	_, err = s.Invite(res.Playlist.Id, owner, 0, 0)
	if err == nil {
		t.Fatal("the invite cap must be enforced")
	}
	if AsError(err).Code != ErrInviteLimit {
		t.Fatalf("expected %s, got %s", ErrInviteLimit, AsError(err).Code)
	}

	// The cap bounds outstanding links, not history: revoking one frees a slot.
	if err := s.RevokeInvite(res.Playlist.Id, owner, first.Token); err != nil {
		t.Fatalf("RevokeInvite: %v", err)
	}
	if _, err := s.Invite(res.Playlist.Id, owner, 0, 0); err != nil {
		t.Fatalf("a revoked invite must free a slot: %v", err)
	}
}

func TestJoinIsIdempotentForAnExistingMember(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Friday Night")
	inv, _ := s.Invite(res.Playlist.Id, owner, 0, 0)

	if _, err := s.Join(inv.Token, JoinRequest{UserId: "u2", DisplayName: "Rahul"}); err != nil {
		t.Fatalf("first join: %v", err)
	}
	again, err := s.Join(inv.Token, JoinRequest{UserId: "u2", DisplayName: "Rahul"})
	if err != nil {
		t.Fatalf("re-joining must not be an error: %v", err)
	}
	if !again.AlreadyMember {
		t.Fatal("expected AlreadyMember to be reported")
	}
	if again.Playlist.MemberCountIfAny() != 2 {
		t.Fatalf("re-joining must not duplicate the member: %d", again.Playlist.MemberCountIfAny())
	}
}

func TestPlaylistCapIsEnforced(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Full house")
	joinAs(t, s, res.Playlist.Id, owner, "u2", "B")
	joinAs(t, s, res.Playlist.Id, owner, "u3", "C")
	// MaxMembers is 4 in the test options, so one seat remains.
	joinAs(t, s, res.Playlist.Id, owner, "u4", "D")

	inv, err := s.Invite(res.Playlist.Id, owner, 0, 0)
	if err != nil {
		t.Fatalf("Invite: %v", err)
	}
	_, err = s.Join(inv.Token, JoinRequest{UserId: "u5"})
	if err == nil {
		t.Fatal("expected the playlist to be full")
	}
}

func TestPreviewInviteReportsDeadLinks(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Friday Night")
	inv, _ := s.Invite(res.Playlist.Id, owner, 0, 0)

	preview, err := s.PreviewInvite(inv.Token)
	if err != nil {
		t.Fatalf("PreviewInvite: %v", err)
	}
	if !preview.Joinable {
		t.Fatalf("expected a live invite to be joinable: %s", preview.Reason)
	}
	if preview.Name != "Friday Night" {
		t.Fatalf("unexpected name %q", preview.Name)
	}

	if err := s.RevokeInvite(res.Playlist.Id, owner, inv.Token); err != nil {
		t.Fatalf("RevokeInvite: %v", err)
	}
	dead, err := s.PreviewInvite(inv.Token)
	if err != nil {
		// A revoked invite may legitimately be reported as gone; either answer
		// is acceptable as long as it is not "welcome in".
		return
	}
	if dead.Joinable {
		t.Fatal("a revoked invite must not present as joinable")
	}
}

// ---------------------------------------------------------------------------
// Tracks
// ---------------------------------------------------------------------------

func TestAddTrackSetsAttributionFromTheCredential(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Tracks")

	// The client tries to attribute the track to somebody else. The AddedBy
	// fields must come from the credential, not the request.
	forged := map[string]interface{}{
		"videoId":     "v1",
		"title":       "Song",
		"addedByName": "Somebody Else",
	}
	snap, added, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{forged})
	if err != nil {
		t.Fatalf("AddTracks: %v", err)
	}
	if added != 1 {
		t.Fatalf("expected 1 added, got %d", added)
	}
	if snap.Tracks[0].AddedByUserId != "owner-1" {
		t.Fatalf("attribution must come from the credential, got %q", snap.Tracks[0].AddedByUserId)
	}
	if snap.Tracks[0].AddedByName == "Somebody Else" {
		t.Fatal("a client must not be able to forge AddedByName")
	}
}

func TestAddTrackAssignsDensePositions(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Order")
	snap, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{
		track("a"), track("b"), track("c"),
	})
	if err != nil {
		t.Fatalf("AddTracks: %v", err)
	}
	for i, tr := range snap.Tracks {
		if tr.Position != i {
			t.Fatalf("track %d has position %d", i, tr.Position)
		}
	}
}

func TestSameSongTwiceGetsDistinctEntries(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Duplicates")
	// The brief's "same song added by multiple users" case: a duplicate is
	// allowed, and the two entries have to be independently addressable.
	snap, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{
		track("same"), track("same"),
	})
	if err != nil {
		t.Fatalf("AddTracks: %v", err)
	}
	if len(snap.Tracks) != 2 {
		t.Fatalf("expected both entries kept, got %d", len(snap.Tracks))
	}
	if snap.Tracks[0].EntryId == snap.Tracks[1].EntryId {
		t.Fatal("two occurrences of one song must have distinct entry ids")
	}

	// Removing one must leave the other.
	snap, err = s.RemoveTrack(res.Playlist.Id, owner, 0, snap.Tracks[0].EntryId)
	if err != nil {
		t.Fatalf("RemoveTrack: %v", err)
	}
	if len(snap.Tracks) != 1 {
		t.Fatalf("expected one entry left, got %d", len(snap.Tracks))
	}
}

func TestAddTrackSkipsUnreadableRows(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Partial")
	snap, added, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{
		track("good"),
		{"title": "no videoId"},
		{"videoId": "also-good"},
	})
	if err != nil {
		t.Fatalf("AddTracks: %v", err)
	}
	if added != 2 {
		t.Fatalf("expected 2 usable rows, got %d", added)
	}
	if len(snap.Tracks) != 2 {
		t.Fatalf("expected 2 tracks, got %d", len(snap.Tracks))
	}
}

func TestAddTrackFailsWhenNothingIsUsable(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Nothing")
	if _, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{{"title": "no id"}}); err == nil {
		t.Fatal("expected an error when no row could be read")
	}
}

func TestTrackLimitIsEnforced(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Bounded")
	// MaxTracks is 10 in the test options.
	for i := 0; i < 10; i++ {
		if _, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{track("v")}); err != nil {
			t.Fatalf("add %d: %v", i, err)
		}
	}
	_, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{track("overflow")})
	if err == nil {
		t.Fatal("expected the track limit to refuse an 11th track")
	}
	if AsError(err).Code != ErrTrackLimit {
		t.Fatalf("expected %s, got %s", ErrTrackLimit, AsError(err).Code)
	}
}

// A batch larger than the remaining room is trimmed rather than refused: the
// part that fits is still what the user asked for.
func TestOversizedBatchIsTrimmedToTheLimit(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Trimmed")
	batch := make([]map[string]interface{}, 0, 25)
	for i := 0; i < 25; i++ {
		batch = append(batch, track("v"))
	}
	snap, added, err := s.AddTracks(res.Playlist.Id, owner, 0, batch)
	if err != nil {
		t.Fatalf("AddTracks: %v", err)
	}
	if added != 10 {
		t.Fatalf("expected 10 added, got %d", added)
	}
	if len(snap.Tracks) != 10 {
		t.Fatalf("expected 10 tracks, got %d", len(snap.Tracks))
	}
}

func TestRemoveTrackIsIdempotent(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Idempotent")
	snap, _, _ := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{track("v1")})
	entry := snap.Tracks[0].EntryId

	if _, err := s.RemoveTrack(res.Playlist.Id, owner, 0, entry); err != nil {
		t.Fatalf("first remove: %v", err)
	}
	// Two people tapping remove on the same row is ordinary; the second is not
	// an error, because the state it asked for is now true.
	if _, err := s.RemoveTrack(res.Playlist.Id, owner, 0, entry); err != nil {
		t.Fatalf("second remove must not be an error: %v", err)
	}
}

// ---------------------------------------------------------------------------
// Movement
// ---------------------------------------------------------------------------

func TestMoveTrackToEveryPosition(t *testing.T) {
	positions := []struct {
		name string
		from int
		to   int
		want []string
	}{
		{"forward", 0, 2, []string{"b", "c", "a"}},
		{"backward", 2, 0, []string{"c", "a", "b"}},
		{"adjacent", 0, 1, []string{"b", "a", "c"}},
		{"no-op", 1, 1, []string{"a", "b", "c"}},
	}
	for _, tc := range positions {
		t.Run(tc.name, func(t *testing.T) {
			s := newTestService(t)
			res, owner := createPlaylist(t, s, "Move")
			snap, _, _ := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{
				track("a"), track("b"), track("c"),
			})
			entry := snap.Tracks[tc.from].EntryId

			moved, err := s.MoveTrack(res.Playlist.Id, owner, 0, entry, tc.to)
			if err != nil {
				t.Fatalf("MoveTrack: %v", err)
			}
			got := make([]string, 0, len(moved.Tracks))
			for _, tr := range moved.Tracks {
				got = append(got, tr.VideoId)
			}
			for i := range tc.want {
				if got[i] != tc.want[i] {
					t.Fatalf("expected %v, got %v", tc.want, got)
				}
			}
			for i, tr := range moved.Tracks {
				if tr.Position != i {
					t.Fatalf("positions must stay dense, track %d has %d", i, tr.Position)
				}
			}
		})
	}
}

func TestMoveTrackClampsOutOfRangeDestination(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Clamp")
	snap, _, _ := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{
		track("a"), track("b"), track("c"),
	})
	entry := snap.Tracks[0].EntryId

	// A client that believes the list is longer means "the end".
	moved, err := s.MoveTrack(res.Playlist.Id, owner, 0, entry, 99)
	if err != nil {
		t.Fatalf("MoveTrack: %v", err)
	}
	if moved.Tracks[len(moved.Tracks)-1].VideoId != "a" {
		t.Fatal("an out-of-range move should land at the end")
	}
	if _, err := s.MoveTrack(res.Playlist.Id, owner, 0, entry, -5); err != nil {
		t.Fatalf("a negative destination should clamp to the front: %v", err)
	}
}

func TestMoveUnknownTrackFails(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Move")
	if _, err := s.MoveTrack(res.Playlist.Id, owner, 0, "nope", 0); err == nil {
		t.Fatal("expected moving an unknown entry to fail")
	}
}

// ---------------------------------------------------------------------------
// Revisions
// ---------------------------------------------------------------------------

func TestRevisionAdvancesOncePerMutation(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Revisions")
	id := res.Playlist.Id

	if res.Playlist.Revision != 1 {
		t.Fatalf("expected revision 1 after create, got %d", res.Playlist.Revision)
	}
	snap, _, _ := s.AddTracks(id, owner, 0, []map[string]interface{}{track("v1")})
	if snap.Revision != 2 {
		t.Fatalf("expected revision 2 after an add, got %d", snap.Revision)
	}
	snap, _ = s.MoveTrack(id, owner, 0, snap.Tracks[0].EntryId, 0)
	// A no-op move must not advance the revision.
	if snap.Revision != 2 {
		t.Fatalf("a no-op move must not bump the revision, got %d", snap.Revision)
	}
	snap, _ = s.RemoveTrack(id, owner, 0, snap.Tracks[0].EntryId)
	if snap.Revision != 3 {
		t.Fatalf("expected revision 3 after a remove, got %d", snap.Revision)
	}
}

func TestStaleRevisionIsRejected(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Conflict")
	id := res.Playlist.Id

	// Two clients both believe they are at revision 1.
	if _, _, err := s.AddTracks(id, owner, 1, []map[string]interface{}{track("first")}); err != nil {
		t.Fatalf("first write at revision 1: %v", err)
	}
	_, _, err := s.AddTracks(id, owner, 1, []map[string]interface{}{track("second")})
	if err == nil {
		t.Fatal("a write from a stale revision must be refused")
	}
	if AsError(err).Code != ErrConflict {
		t.Fatalf("expected %s, got %s", ErrConflict, AsError(err).Code)
	}
}

func TestZeroRevisionIsAcceptedAsUnstated(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Lenient")
	if _, _, err := s.AddTracks(res.Playlist.Id, owner, 0, []map[string]interface{}{track("v")}); err != nil {
		t.Fatalf("a zero revision means unstated and must be accepted: %v", err)
	}
}

// ---------------------------------------------------------------------------
// Deltas and reconnection
// ---------------------------------------------------------------------------

func TestDeltasReturnOnlyWhatWasMissed(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Deltas")
	id := res.Playlist.Id

	snap, _, _ := s.AddTracks(id, owner, 0, []map[string]interface{}{track("a")}) // rev 2
	at := snap.Revision
	s.AddTracks(id, owner, 0, []map[string]interface{}{track("b")}) // rev 3
	s.AddTracks(id, owner, 0, []map[string]interface{}{track("c")}) // rev 4

	deltas, err := s.Deltas(id, owner, at)
	if err != nil {
		t.Fatalf("Deltas: %v", err)
	}
	if deltas.Resync {
		t.Fatal("a client one revision behind must not need a resync")
	}
	if len(deltas.Changes) != 2 {
		t.Fatalf("expected 2 missed changes, got %d", len(deltas.Changes))
	}
	if deltas.Changes[0].Revision != at+1 {
		t.Fatalf("expected the run to start at %d, got %d", at+1, deltas.Changes[0].Revision)
	}
	if deltas.Snapshot != nil {
		t.Fatal("a delta answer must not also carry a snapshot")
	}
}

func TestDeltasFromCurrentRevisionAreEmpty(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Current")

	deltas, err := s.Deltas(res.Playlist.Id, owner, res.Playlist.Revision)
	if err != nil {
		t.Fatalf("Deltas: %v", err)
	}
	if deltas.Resync {
		t.Fatal("a current client must not be told to resync")
	}
	if len(deltas.Changes) != 0 {
		t.Fatalf("expected no changes, got %d", len(deltas.Changes))
	}
}

func TestDeltasResyncWhenHistoryIsExhausted(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Ancient")
	id := res.Playlist.Id

	// HistoryLimit is 20 in the test options, so 30 mutations push the earliest
	// changes out of the window. Renames rather than additions, because the test
	// track cap is 10 and a revision need not cost a slot.
	for i := 0; i < 30; i++ {
		name := fmt.Sprintf("Ancient %d", i)
		if _, err := s.UpdateMetadata(id, owner, MetadataPatch{Name: &name}); err != nil {
			t.Fatalf("rename %d: %v", i, err)
		}
	}
	deltas, err := s.Deltas(id, owner, 1)
	if err != nil {
		t.Fatalf("Deltas: %v", err)
	}
	if !deltas.Resync {
		t.Fatal("a client older than the history window must be told to resync")
	}
	if deltas.Snapshot == nil {
		t.Fatal("a resync must carry a snapshot")
	}
	if len(deltas.Changes) != 0 {
		t.Fatal("a resync must not also carry a partial delta")
	}
	if deltas.Snapshot.Revision != deltas.ToRevision {
		t.Fatalf("snapshot revision %d must equal ToRevision %d", deltas.Snapshot.Revision, deltas.ToRevision)
	}
}

func TestDeltasRequireACredential(t *testing.T) {
	s := newTestService(t)
	res, _ := createPlaylist(t, s, "Private")
	if _, err := s.Deltas(res.Playlist.Id, Credential{UserId: "owner-1"}, 1); err == nil {
		t.Fatal("deltas must require a valid credential")
	}
}

// ---------------------------------------------------------------------------
// Membership lifecycle
// ---------------------------------------------------------------------------

func TestOwnerCannotLeave(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Mine")
	if err := s.Leave(res.Playlist.Id, owner); err == nil {
		t.Fatal("the owner must not be able to leave")
	}
}

func TestCollaboratorCanLeave(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Shared")
	guest := joinAs(t, s, res.Playlist.Id, owner, "u2", "Rahul")

	if err := s.Leave(res.Playlist.Id, guest); err != nil {
		t.Fatalf("Leave: %v", err)
	}
	if _, err := s.Get(res.Playlist.Id, guest); err == nil {
		t.Fatal("a departed collaborator must lose access")
	}
	// The playlist itself survives.
	if _, err := s.Get(res.Playlist.Id, owner); err != nil {
		t.Fatalf("the playlist must survive a collaborator leaving: %v", err)
	}
}

// Attribution survives the departure of the person it names — the playlist
// should not silently rewrite who added what.
func TestAttributionSurvivesDeparture(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "History")
	guest := joinAs(t, s, res.Playlist.Id, owner, "u2", "Rahul")
	s.AddTracks(res.Playlist.Id, guest, 0, []map[string]interface{}{track("v1")})

	if err := s.Leave(res.Playlist.Id, guest); err != nil {
		t.Fatalf("Leave: %v", err)
	}
	snap, err := s.Get(res.Playlist.Id, owner)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if snap.Tracks[0].AddedByName != "Rahul" {
		t.Fatalf("attribution must survive a departure, got %q", snap.Tracks[0].AddedByName)
	}
}

// ---------------------------------------------------------------------------
// Deletion
// ---------------------------------------------------------------------------

func TestDeleteHidesThePlaylist(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Doomed")
	id := res.Playlist.Id

	if err := s.Delete(id, owner); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := s.Get(id, owner); err == nil {
		t.Fatal("a deleted playlist must not be readable")
	}
	// And it must not appear in a list.
	list, err := s.List(owner)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	for _, item := range list {
		if item.Id == id {
			t.Fatal("a deleted playlist must not be listed")
		}
	}
}

// ---------------------------------------------------------------------------
// Listing
// ---------------------------------------------------------------------------

func TestListUsesTheTokenNotTheUserId(t *testing.T) {
	s := newTestService(t)
	res, owner := createPlaylist(t, s, "Mine")
	joinAs(t, s, res.Playlist.Id, owner, "u2", "Rahul")

	// Claiming the owner's userId with no token must reveal nothing.
	none, err := s.List(Credential{UserId: "owner-1"})
	if err == nil && len(none) > 0 {
		t.Fatal("listing by userId alone must not reveal playlists")
	}
}

func TestListTotalsEveryHeldCredential(t *testing.T) {
	s := newTestService(t)
	_, firstCred := createPlaylist(t, s, "First")
	second, secondCred := createPlaylist(t, s, "Second")

	// One token reaches one playlist. Both tokens reach both, which is what a
	// real client does: it holds a capability per playlist, not one account.
	one, err := s.List(firstCred)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(one) != 1 {
		t.Fatalf("one credential must reach one playlist, got %d", len(one))
	}
	both, err := s.List(firstCred, secondCred)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(both) != 2 {
		t.Fatalf("two credentials must reach two playlists, got %d", len(both))
	}
	if both[0].Id != second.Playlist.Id {
		t.Fatal("the union must still be ordered by most recent update")
	}
}

func TestListOrdersByMostRecentlyUpdated(t *testing.T) {
	s := newTestService(t)
	first, firstCred := createPlaylist(t, s, "First")
	_, secondCred := createPlaylist(t, s, "Second")

	// Touching the first must bring it to the top.
	if _, _, err := s.AddTracks(first.Playlist.Id, firstCred, 0, []map[string]interface{}{track("v1")}); err != nil {
		t.Fatalf("AddTracks: %v", err)
	}

	list, err := s.List(firstCred, secondCred)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("expected 2 playlists, got %d", len(list))
	}
	if list[0].Id != first.Playlist.Id {
		t.Fatal("the most recently updated playlist must come first")
	}
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// MemberCountIfAny is a test convenience: Snapshot has no such field, so this
// keeps the assertions above readable.
func (s *Snapshot) MemberCountIfAny() int { return len(s.Members) }
