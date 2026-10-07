package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ihimanshunayak/FreeMusic/backend/playlist"
)

// HTTP-level tests for the playlist routes.
//
// The service has its own tests, which cover every rule in depth. These cover
// what the service cannot: that a route reaches the right method, that a
// credential travels through the Authorization header intact, that a service
// error arrives as the status and code a client will switch on, and that the
// handlers add no trust of their own. That last one is the reason this file
// exists — a handler is exactly where a userId out of a request body could
// accidentally become an authorisation decision.

// playlistTestServer mounts the playlist routes over a fresh in-memory service.
func playlistTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	previous := playlists
	previousLimiter := playlistLimiter

	svc, err := playlist.NewService(playlist.NewMemoryStore(), playlist.Options{
		MaxPlaylists: 20,
		MaxMembers:   4,
		MaxTracks:    50,
		MaxInvites:   20,
		HistoryLimit: 50,
	})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	playlists = svc
	// Zero disables the limiter, so a test never fails on a rate it is not
	// exercising. The limiter has its own behaviour and is covered separately.
	playlistLimiter = newIPRateLimiter(0, 0, 0)

	mux := http.NewServeMux()
	registerPlaylistRoutes(mux)
	ts := httptest.NewServer(corsMiddleware(mux))

	t.Cleanup(func() {
		ts.Close()
		playlists = previous
		playlistLimiter = previousLimiter
	})
	return ts
}

// apiCall performs a request and decodes the JSON response, returning the status
// alongside it so a test can assert on both.
func apiCall(t *testing.T, ts *httptest.Server, method, path, token string, body interface{}) (int, map[string]interface{}) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal: %v", err)
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, ts.URL+path, reader)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	defer res.Body.Close()

	out := map[string]interface{}{}
	raw, _ := io.ReadAll(res.Body)
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &out)
	}
	return res.StatusCode, out
}

// object pulls a nested object out of a decoded response.
func object(t *testing.T, body map[string]interface{}, key string) map[string]interface{} {
	t.Helper()
	v, ok := body[key].(map[string]interface{})
	if !ok {
		t.Fatalf("response has no object %q: %v", key, body)
	}
	return v
}

// array pulls a nested array out of a decoded response.
func array(t *testing.T, body map[string]interface{}, key string) []interface{} {
	t.Helper()
	v, ok := body[key].([]interface{})
	if !ok {
		t.Fatalf("response has no array %q: %v", key, body)
	}
	return v
}

func str(body map[string]interface{}, key string) string {
	if v, ok := body[key].(string); ok {
		return v
	}
	return ""
}

// number reads a JSON number as a float64, which is what encoding/json gives.
func number(body map[string]interface{}, key string) float64 {
	if v, ok := body[key].(float64); ok {
		return v
	}
	return -1
}

// createOverHTTP is the shared setup: a playlist created through the real route
// so the test exercises the response shape a client actually parses.
func createOverHTTP(t *testing.T, ts *httptest.Server, name string) (playlistId, ownerToken, memberToken string) {
	t.Helper()
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists", "", map[string]interface{}{
		"playlistType": "COLLABORATIVE",
		"name":         name,
		"description":  "made by a test",
		"userId":       "owner-1",
		"displayName":  "Himanshu",
	})
	if status != http.StatusCreated {
		t.Fatalf("create: status %d, body %v", status, body)
	}
	created := object(t, body, "playlist")
	id := str(created, "id")
	if id == "" {
		t.Fatalf("create returned no id: %v", body)
	}
	ownerToken = str(body, "ownerToken")
	memberToken = str(body, "memberToken")
	if ownerToken == "" || memberToken == "" {
		t.Fatalf("create returned no tokens: %v", body)
	}
	// The two must differ. The owner token is what makes someone an owner, so if
	// the member token could act as it, every collaborator would be an owner.
	if ownerToken == memberToken {
		t.Fatal("ownerToken and memberToken must differ")
	}
	return id, ownerToken, memberToken
}

// ---------------------------------------------------------------------------
// Lifecycle over HTTP
// ---------------------------------------------------------------------------

func TestPlaylistCreateAndListOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Road Trip")

	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("list: status %d, body %v", status, body)
	}
	items := array(t, body, "playlists")
	if len(items) != 1 {
		t.Fatalf("expected one playlist, got %d", len(items))
	}
	first, _ := items[0].(map[string]interface{})
	if str(first, "id") != id {
		t.Fatalf("listed the wrong playlist: %v", first)
	}
	if str(first, "viewerRole") != "OWNER" {
		t.Fatalf("expected OWNER, got %q", str(first, "viewerRole"))
	}
	if durable, _ := body["durable"].(bool); durable {
		t.Fatal("a memory store must report itself as not durable")
	}
}

func TestPlaylistCreateIsRateLimited(t *testing.T) {
	// A real limiter, unlike the rest of this file, because the limit is the
	// behaviour under test.
	previous := playlists
	svc, err := playlist.NewService(playlist.NewMemoryStore(), playlist.Options{})
	if err != nil {
		t.Fatalf("NewService: %v", err)
	}
	playlists = svc
	previousLimiter := playlistLimiter
	// A real window, because a zero window makes every entry instantly stale and
	// the limiter a no-op.
	playlistLimiter = newIPRateLimiter(time.Minute, 1, 10)
	t.Cleanup(func() {
		playlists = previous
		playlistLimiter = previousLimiter
	})

	mux := http.NewServeMux()
	registerPlaylistRoutes(mux)
	ts := httptest.NewServer(corsMiddleware(mux))
	defer ts.Close()

	first, _ := apiCall(t, ts, http.MethodPost, "/api/playlists", "", map[string]interface{}{
		"playlistType": "COLLABORATIVE", "name": "One", "userId": "u1",
	})
	if first != http.StatusCreated {
		t.Fatalf("first create: status %d", first)
	}
	second, body := apiCall(t, ts, http.MethodPost, "/api/playlists", "", map[string]interface{}{
		"playlistType": "COLLABORATIVE", "name": "Two", "userId": "u1",
	})
	if second != http.StatusTooManyRequests {
		t.Fatalf("second create: status %d, body %v", second, body)
	}
	if str(body, "error") != "create_rate_limited" {
		t.Fatalf("expected create_rate_limited, got %q", str(body, "error"))
	}
}

func TestPlaylistRoutesRejectAnUnknownType(t *testing.T) {
	ts := playlistTestServer(t)
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists", "", map[string]interface{}{
		"playlistType": "NUKE", "name": "Bad", "userId": "u1",
	})
	if status != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d (%v)", status, body)
	}
	if str(body, "error") != playlist.ErrBadRequest {
		t.Fatalf("expected %s, got %q", playlist.ErrBadRequest, str(body, "error"))
	}
}

// TestPlaylistRoutesRequireACredential is the reason this file exists: every
// route that reads or writes must refuse a caller who presents nothing, and a
// userId in the query string must not change that.
func TestPlaylistRoutesRequireACredential(t *testing.T) {
	ts := playlistTestServer(t)
	id, _, _ := createOverHTTP(t, ts, "Private")

	cases := []struct {
		name   string
		method string
		path   string
		body   interface{}
	}{
		{"get", http.MethodGet, "/api/playlists/" + id, nil},
		{"get claiming the owner id", http.MethodGet, "/api/playlists/" + id + "?userId=owner-1", nil},
		{"deltas", http.MethodGet, "/api/playlists/" + id + "/deltas?from=1", nil},
		{"add tracks", http.MethodPost, "/api/playlists/" + id + "/tracks?userId=owner-1", map[string]interface{}{
			"tracks": []map[string]interface{}{{"videoId": "v", "title": "T"}},
		}},
		{"rename", http.MethodPost, "/api/playlists/" + id + "?userId=owner-1", map[string]interface{}{"name": "Mine"}},
		{"delete", http.MethodDelete, "/api/playlists/" + id + "?userId=owner-1", nil},
		{"invite", http.MethodPost, "/api/playlists/" + id + "/invites?userId=owner-1", nil},
		{"clear", http.MethodPost, "/api/playlists/" + id + "/tracks/clear?userId=owner-1", nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			status, body := apiCall(t, ts, c.method, c.path, "", c.body)
			if status != http.StatusForbidden {
				t.Fatalf("expected 403, got %d (%v)", status, body)
			}
		})
	}
}

func TestPlaylistRoutesRefuseAGarbageToken(t *testing.T) {
	ts := playlistTestServer(t)
	id, _, _ := createOverHTTP(t, ts, "Guarded")
	status, _ := apiCall(t, ts, http.MethodGet, "/api/playlists/"+id, strings.Repeat("f", 64), nil)
	if status != http.StatusForbidden {
		t.Fatalf("expected 403 for an unknown token, got %d", status)
	}
}

func TestPlaylistRenameAndDeleteOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, memberToken := createOverHTTP(t, ts, "Before")

	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"?userId=owner-1", ownerToken,
		map[string]interface{}{"name": "After", "description": ""})
	if status != http.StatusOK {
		t.Fatalf("rename: status %d, body %v", status, body)
	}
	renamed := object(t, body, "playlist")
	if str(renamed, "name") != "After" {
		t.Fatalf("rename did not apply: %v", renamed)
	}
	if number(renamed, "revision") != 2 {
		t.Fatalf("a rename must bump the revision, got %v", number(renamed, "revision"))
	}
	if _, present := renamed["description"]; present && str(renamed, "description") != "" {
		t.Fatalf("an explicit empty description must clear it, got %q", str(renamed, "description"))
	}

	// The member token is not the owner token, so it must not rename.
	status, _ = apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"?userId=owner-1", memberToken,
		map[string]interface{}{"name": "Stolen"})
	if status != http.StatusForbidden {
		t.Fatalf("a member token must not rename: status %d", status)
	}

	status, _ = apiCall(t, ts, http.MethodDelete, "/api/playlists/"+id+"?userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("delete: status %d", status)
	}
	status, _ = apiCall(t, ts, http.MethodGet, "/api/playlists/"+id, ownerToken, nil)
	if status != http.StatusNotFound {
		t.Fatalf("a deleted playlist must 404, got %d", status)
	}
}

// ---------------------------------------------------------------------------
// Tracks over HTTP
// ---------------------------------------------------------------------------

func TestPlaylistTrackRoutesRoundTrip(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Queue")

	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=owner-1", ownerToken,
		map[string]interface{}{
			"baseRevision": 1,
			"tracks": []map[string]interface{}{
				{"videoId": "v1", "title": "A", "artist": "X"},
				{"videoId": "v2", "title": "B", "artist": "Y"},
				{"videoId": "v3", "title": "C", "artist": "Z"},
			},
		})
	if status != http.StatusOK {
		t.Fatalf("add: status %d, body %v", status, body)
	}
	if added := number(body, "added"); added != 3 {
		t.Fatalf("expected 3 added, got %v", added)
	}
	snapshot := object(t, body, "playlist")
	tracks := array(t, snapshot, "tracks")
	if len(tracks) != 3 {
		t.Fatalf("expected 3 tracks, got %d", len(tracks))
	}
	last, _ := tracks[2].(map[string]interface{})
	entryId := str(last, "entryId")
	if entryId == "" {
		t.Fatal("the server must assign an entryId")
	}
	if str(last, "addedByName") != "Himanshu" {
		t.Fatalf("attribution missing: %v", last)
	}
	if number(last, "position") != 2 {
		t.Fatalf("positions must be dense and zero-based, got %v", number(last, "position"))
	}

	// Move the last one to the front and confirm the order actually changed — the
	// bug this covers shipped once as a silent no-op.
	status, body = apiCall(t, ts, http.MethodPost,
		"/api/playlists/"+id+"/tracks/"+entryId+"/move?userId=owner-1", ownerToken,
		map[string]interface{}{"baseRevision": 2, "to": 0})
	if status != http.StatusOK {
		t.Fatalf("move: status %d, body %v", status, body)
	}
	snapshot = object(t, body, "playlist")
	tracks = array(t, snapshot, "tracks")
	order := make([]string, 0, len(tracks))
	for _, raw := range tracks {
		track, _ := raw.(map[string]interface{})
		order = append(order, str(track, "title"))
	}
	if strings.Join(order, ",") != "C,A,B" {
		t.Fatalf("expected C,A,B after the move, got %v", order)
	}

	// A stale write is refused rather than applied.
	status, body = apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=owner-1", ownerToken,
		map[string]interface{}{
			"baseRevision": 1,
			"tracks":       []map[string]interface{}{{"videoId": "vX", "title": "Stale"}},
		})
	if status != http.StatusConflict {
		t.Fatalf("expected 409 for a stale write, got %d (%v)", status, body)
	}
	if str(body, "error") != playlist.ErrConflict {
		t.Fatalf("expected %s, got %q", playlist.ErrConflict, str(body, "error"))
	}

	// Remove, then clear.
	status, body = apiCall(t, ts, http.MethodDelete,
		"/api/playlists/"+id+"/tracks/"+entryId+"?baseRevision=3&userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("remove: status %d, body %v", status, body)
	}
	snapshot = object(t, body, "playlist")
	if len(array(t, snapshot, "tracks")) != 2 {
		t.Fatalf("remove left %d tracks", len(array(t, snapshot, "tracks")))
	}

	status, body = apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks/clear?userId=owner-1", ownerToken,
		map[string]interface{}{"baseRevision": 4})
	if status != http.StatusOK {
		t.Fatalf("clear: status %d, body %v", status, body)
	}
	snapshot = object(t, body, "playlist")
	if len(array(t, snapshot, "tracks")) != 0 {
		t.Fatal("clear left tracks behind")
	}
}

func TestPlaylistAddRejectsAnEmptyList(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Empty add")
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=owner-1", ownerToken,
		map[string]interface{}{"baseRevision": 1, "tracks": []map[string]interface{}{}})
	if status != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d (%v)", status, body)
	}
}

func TestPlaylistMoveRequiresADestination(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "No destination")
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks/tr_whatever/move?userId=owner-1",
		ownerToken, map[string]interface{}{"baseRevision": 1})
	if status != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d (%v)", status, body)
	}
}

func TestPlaylistDeltasOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Deltas")

	for i := 0; i < 3; i++ {
		status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=owner-1", ownerToken,
			map[string]interface{}{
				"tracks": []map[string]interface{}{{"videoId": fmt.Sprintf("v%d", i), "title": "T"}},
			})
		if status != http.StatusOK {
			t.Fatalf("add %d: status %d (%v)", i, status, body)
		}
	}

	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists/"+id+"/deltas?from=1&userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("deltas: status %d (%v)", status, body)
	}
	if resync, _ := body["resync"].(bool); resync {
		t.Fatal("a client inside the window must not be told to resync")
	}
	changes := array(t, body, "changes")
	if len(changes) != 3 {
		t.Fatalf("expected 3 changes, got %d", len(changes))
	}
	first, _ := changes[0].(map[string]interface{})
	if number(first, "revision") != 2 {
		t.Fatalf("the first change must be revision 2, got %v", number(first, "revision"))
	}

	// A client beyond the end of the history is told to take a snapshot.
	status, body = apiCall(t, ts, http.MethodGet, "/api/playlists/"+id+"/deltas?from=9999&userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("future deltas: status %d (%v)", status, body)
	}
	if resync, _ := body["resync"].(bool); !resync {
		t.Fatal("a client ahead of the server must be told to resync")
	}
	if _, present := body["snapshot"]; !present {
		t.Fatal("a resync must carry a snapshot")
	}
}

func TestPlaylistDeltasRequireARevision(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Needs from")
	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists/"+id+"/deltas?userId=owner-1", ownerToken, nil)
	if status != http.StatusBadRequest {
		t.Fatalf("expected 400 without `from`, got %d (%v)", status, body)
	}
}

// ---------------------------------------------------------------------------
// Invitations over HTTP
// ---------------------------------------------------------------------------

// joinOverHTTP mints an invite and redeems it, returning the guest's token.
func joinOverHTTP(t *testing.T, ts *httptest.Server, id, ownerToken, userId, name string) string {
	t.Helper()
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("invite: status %d, body %v", status, body)
	}
	inviteToken := str(body, "inviteToken")
	if inviteToken == "" {
		t.Fatalf("no invite token: %v", body)
	}
	if !strings.HasPrefix(str(body, "deepLink"), "freemusic://playlist/invite/") {
		t.Fatalf("unexpected deep link: %q", str(body, "deepLink"))
	}

	status, body = apiCall(t, ts, http.MethodPost, "/api/playlist-invites/"+inviteToken, "",
		map[string]interface{}{"userId": userId, "displayName": name})
	if status != http.StatusOK {
		t.Fatalf("join: status %d, body %v", status, body)
	}
	guestToken := str(body, "memberToken")
	if guestToken == "" {
		t.Fatalf("no member token: %v", body)
	}
	return guestToken
}

func TestPlaylistInvitePreviewIsUnauthenticatedAndNarrow(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Friday Night")

	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("invite: status %d, body %v", status, body)
	}
	inviteToken := str(body, "inviteToken")

	// No credential: the whole point of a shared link is that the recipient does
	// not have access yet.
	status, body = apiCall(t, ts, http.MethodGet, "/api/playlist-invites/"+inviteToken, "", nil)
	if status != http.StatusOK {
		t.Fatalf("preview: status %d, body %v", status, body)
	}
	if str(body, "name") != "Friday Night" {
		t.Fatalf("preview lost the name: %v", body)
	}
	if joinable, _ := body["joinable"].(bool); !joinable {
		t.Fatalf("a live invite must present as joinable: %v", body)
	}
	// A preview must not leak anything that grants access or enumerates members.
	for _, forbidden := range []string{"ownerToken", "memberToken", "members", "tracks", "ownerTokenHash", "inviteToken"} {
		if _, present := body[forbidden]; present {
			t.Fatalf("preview leaked %q: %v", forbidden, body)
		}
	}
}

func TestPlaylistJoinAndLeaveOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Shared")
	guestToken := joinOverHTTP(t, ts, id, ownerToken, "guest-2", "Rahul")

	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists/"+id+"?userId=guest-2", guestToken, nil)
	if status != http.StatusOK {
		t.Fatalf("guest read: status %d (%v)", status, body)
	}
	snapshot := object(t, body, "playlist")
	if str(snapshot, "viewerRole") != "COLLABORATOR" {
		t.Fatalf("expected COLLABORATOR, got %q", str(snapshot, "viewerRole"))
	}
	members := array(t, snapshot, "members")
	if len(members) != 2 {
		t.Fatalf("expected 2 members, got %d", len(members))
	}
	// A snapshot must never carry a token hash, whatever else it carries.
	for _, raw := range members {
		member, _ := raw.(map[string]interface{})
		if _, present := member["tokenHash"]; present {
			t.Fatalf("a snapshot leaked a token hash: %v", member)
		}
	}

	// A collaborator may add, and is attributed.
	status, body = apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=guest-2", guestToken,
		map[string]interface{}{"tracks": []map[string]interface{}{{"videoId": "v9", "title": "Guest pick"}}})
	if status != http.StatusOK {
		t.Fatalf("guest add: status %d (%v)", status, body)
	}
	snapshot = object(t, body, "playlist")
	tracks := array(t, snapshot, "tracks")
	added, _ := tracks[len(tracks)-1].(map[string]interface{})
	if str(added, "addedByName") != "Rahul" {
		t.Fatalf("guest attribution wrong: %v", added)
	}

	// Leaving revokes the token immediately. There is no session to expire,
	// because resolve() reads the store on every request.
	status, _ = apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/leave?userId=guest-2", guestToken, nil)
	if status != http.StatusOK {
		t.Fatalf("leave: status %d", status)
	}
	status, _ = apiCall(t, ts, http.MethodGet, "/api/playlists/"+id, guestToken, nil)
	if status != http.StatusForbidden {
		t.Fatalf("a departed member's token must stop working, got %d", status)
	}

	// And the owner is still fine.
	status, _ = apiCall(t, ts, http.MethodGet, "/api/playlists/"+id, ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("the owner must be unaffected by a departure, got %d", status)
	}
}

func TestPlaylistInviteSingleUseOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "One seat")

	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken,
		map[string]interface{}{"maxUses": 1})
	if status != http.StatusOK {
		t.Fatalf("invite: status %d (%v)", status, body)
	}
	inviteToken := str(body, "inviteToken")

	status, _ = apiCall(t, ts, http.MethodPost, "/api/playlist-invites/"+inviteToken, "",
		map[string]interface{}{"userId": "guest-2", "displayName": "First"})
	if status != http.StatusOK {
		t.Fatalf("first join: status %d", status)
	}
	status, body = apiCall(t, ts, http.MethodPost, "/api/playlist-invites/"+inviteToken, "",
		map[string]interface{}{"userId": "guest-3", "displayName": "Second"})
	if status == http.StatusOK {
		t.Fatalf("a spent invite must not admit another: %v", body)
	}
}

func TestPlaylistRevokeInviteOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Withdrawable")

	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("invite: status %d (%v)", status, body)
	}
	inviteToken := str(body, "inviteToken")

	status, body = apiCall(t, ts, http.MethodDelete, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken,
		map[string]interface{}{"inviteToken": inviteToken})
	if status != http.StatusOK {
		t.Fatalf("revoke: status %d (%v)", status, body)
	}

	status, body = apiCall(t, ts, http.MethodGet, "/api/playlist-invites/"+inviteToken, "", nil)
	if status == http.StatusOK {
		if joinable, _ := body["joinable"].(bool); joinable {
			t.Fatalf("a revoked invite must not present as joinable: %v", body)
		}
	}
	status, _ = apiCall(t, ts, http.MethodPost, "/api/playlist-invites/"+inviteToken, "",
		map[string]interface{}{"userId": "guest-2"})
	if status == http.StatusOK {
		t.Fatal("a revoked invite must not admit anyone")
	}

	status, _ = apiCall(t, ts, http.MethodDelete, "/api/playlists/"+id+"/invites?userId=owner-1", ownerToken, nil)
	if status != http.StatusBadRequest {
		t.Fatalf("revoking without a token must be a 400, got %d", status)
	}
}

func TestPlaylistRemoveMemberOverHTTP(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Kickable")
	guestToken := joinOverHTTP(t, ts, id, ownerToken, "guest-2", "Rahul")

	status, _ := apiCall(t, ts, http.MethodDelete, "/api/playlists/"+id+"/members/guest-2?userId=owner-1", ownerToken, nil)
	if status != http.StatusOK {
		t.Fatalf("remove member: status %d", status)
	}
	status, _ = apiCall(t, ts, http.MethodGet, "/api/playlists/"+id, guestToken, nil)
	if status != http.StatusForbidden {
		t.Fatalf("a removed member's token must stop working, got %d", status)
	}

	// A collaborator must not be able to remove the owner, or anyone else.
	guestToken2 := joinOverHTTP(t, ts, id, ownerToken, "guest-3", "Priya")
	status, _ = apiCall(t, ts, http.MethodDelete, "/api/playlists/"+id+"/members/owner-1?userId=guest-3", guestToken2, nil)
	if status != http.StatusForbidden {
		t.Fatalf("a collaborator must not remove anyone, got %d", status)
	}
}

// ---------------------------------------------------------------------------
// Isolation
// ---------------------------------------------------------------------------

// TestPlaylistTokensAreScopedToOnePlaylist is the property the whole capability
// design rests on: holding a token for one playlist must convey nothing about
// another, even when the same user created both.
func TestPlaylistTokensAreScopedToOnePlaylist(t *testing.T) {
	ts := playlistTestServer(t)
	firstId, firstToken, _ := createOverHTTP(t, ts, "First")
	secondId, secondToken, _ := createOverHTTP(t, ts, "Second")

	status, _ := apiCall(t, ts, http.MethodGet, "/api/playlists/"+secondId, firstToken, nil)
	if status != http.StatusForbidden {
		t.Fatalf("one playlist's token must not open another, got %d", status)
	}
	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists", firstToken, nil)
	if status != http.StatusOK {
		t.Fatalf("list: status %d (%v)", status, body)
	}
	if items := array(t, body, "playlists"); len(items) != 1 {
		t.Fatalf("a token must list only its own playlist, got %d", len(items))
	}

	// Presenting both is how the app draws "my playlists".
	status, body = apiCall(t, ts, http.MethodGet, "/api/playlists?token="+secondToken, firstToken, nil)
	if status != http.StatusOK {
		t.Fatalf("union list: status %d (%v)", status, body)
	}
	if items := array(t, body, "playlists"); len(items) != 2 {
		t.Fatalf("two credentials must reach both playlists, got %d", len(items))
	}
	_ = firstId
}

// TestPlaylistBodyUserIdIsNotAuthority makes the point explicit: naming a userId
// in a body must not grant anything a token does not already grant.
func TestPlaylistBodyUserIdIsNotAuthority(t *testing.T) {
	ts := playlistTestServer(t)
	id, ownerToken, _ := createOverHTTP(t, ts, "Owner is Himanshu")

	// Claim to be the owner, with no token.
	status, _ := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=owner-1", "",
		map[string]interface{}{
			"userId": "owner-1",
			"tracks": []map[string]interface{}{{"videoId": "v", "title": "Mine now"}},
		})
	if status != http.StatusForbidden {
		t.Fatalf("claiming a userId must not grant access, got %d", status)
	}

	// Claim to be someone else, with the owner's token. The write must land,
	// because the token is what grants access and the query's userId only decides
	// which member row the token resolves to.
	status, body := apiCall(t, ts, http.MethodPost, "/api/playlists/"+id+"/tracks?userId=not-the-owner", ownerToken,
		map[string]interface{}{"tracks": []map[string]interface{}{{"videoId": "v", "title": "T"}}})
	if status != http.StatusOK {
		t.Fatalf("the owner token must still work: %d (%v)", status, body)
	}
}

func TestPlaylistUnknownIdIsNotFound(t *testing.T) {
	ts := playlistTestServer(t)
	_, ownerToken, _ := createOverHTTP(t, ts, "Real")
	status, body := apiCall(t, ts, http.MethodGet, "/api/playlists/pl_deadbeefdeadbeefdeadbeef", ownerToken, nil)
	if status != http.StatusNotFound {
		t.Fatalf("expected 404, got %d (%v)", status, body)
	}
	if str(body, "error") != playlist.ErrNotFound {
		t.Fatalf("expected %s, got %q", playlist.ErrNotFound, str(body, "error"))
	}
}
