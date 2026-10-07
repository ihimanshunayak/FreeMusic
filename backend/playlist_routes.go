package main

import (
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/ihimanshunayak/FreeMusic/backend/config"
	"github.com/ihimanshunayak/FreeMusic/backend/playlist"
)

// Collaborative playlists and Blends over HTTP.
//
// Split out of main.go rather than appended to it: main.go is already the
// party service's home and is over a thousand lines, and the two features share
// nothing but the JSON envelope. This file owns every route below
// /api/playlists, plus the service they read.
//
// Authentication is a capability carried in the Authorization header, not an
// account. See the audit, §15, for why: the client asserts its own userId, so a
// userId can never be an authorisation principal. Every handler here therefore
// resolves a token and never trusts a body's claim about who the caller is.

var (
	playlists       *playlist.Service
	playlistLimiter *ipRateLimiter
)

// initPlaylists builds the service and its limiter. Called from main so a
// failure to open a configured store stops the process instead of leaving a
// server that silently cannot save.
func initPlaylists() {
	opts := playlist.Options{
		MaxPlaylists: config.PlaylistMaxPlaylists,
		MaxMembers:   config.PlaylistMaxMembers,
		MaxTracks:    config.PlaylistMaxTracks,
		MaxInvites:   config.PlaylistMaxInvites,
		HistoryLimit: config.PlaylistHistoryLimit,
	}

	var store playlist.Store
	if config.PlaylistStorePath != "" {
		fileStore, err := playlist.NewFileStore(config.PlaylistStorePath)
		if err != nil {
			log.Fatalf("playlist store: %v", err)
		}
		store = fileStore
		log.Printf("playlists: persisting to %s", config.PlaylistStorePath)
	} else {
		store = playlist.NewMemoryStore()
		// Said out loud because it is the difference between "my playlists are
		// safe" and "my playlists last until the next deploy", and the free tier
		// makes the second one easy to end up with by accident.
		log.Printf("playlists: PLAYLIST_STORE_PATH is unset, so playlists live in memory and will not survive a restart")
	}

	svc, err := playlist.NewService(store, opts)
	if err != nil {
		log.Fatalf("playlist service: %v", err)
	}
	playlists = svc
	playlistLimiter = newIPRateLimiter(time.Minute, config.PlaylistCreateRatePerMinute, config.RateLimitMaxEntries)
}

// registerPlaylistRoutes mounts every playlist endpoint on a mux.
func registerPlaylistRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/playlists", handleCreatePlaylist)
	mux.HandleFunc("GET /api/playlists", handleListPlaylists)
	mux.HandleFunc("GET /api/playlists/{id}", handleGetPlaylist)
	mux.HandleFunc("POST /api/playlists/{id}", handlePatchPlaylist)
	mux.HandleFunc("DELETE /api/playlists/{id}", handleDeletePlaylist)

	mux.HandleFunc("GET /api/playlists/{id}/deltas", handlePlaylistDeltas)

	mux.HandleFunc("POST /api/playlists/{id}/tracks", handleAddPlaylistTracks)
	mux.HandleFunc("DELETE /api/playlists/{id}/tracks/{entryId}", handleRemovePlaylistTrack)
	mux.HandleFunc("POST /api/playlists/{id}/tracks/{entryId}/move", handleMovePlaylistTrack)
	mux.HandleFunc("POST /api/playlists/{id}/tracks/clear", handleClearPlaylistTracks)

	mux.HandleFunc("POST /api/playlists/{id}/invites", handleCreatePlaylistInvite)
	mux.HandleFunc("DELETE /api/playlists/{id}/invites", handleRevokePlaylistInvite)
	// Invitation redemption is scoped to the token, not to a playlist — the
	// recipient does not know the playlist id yet, and must not need to. It lives
	// under its own prefix because `/api/playlists/{id}/...` would make
	// `/api/playlists/invites/{token}` ambiguous with a playlist whose id is
	// literally "invites".
	mux.HandleFunc("GET /api/playlist-invites/{token}", handlePreviewPlaylistInvite)
	mux.HandleFunc("POST /api/playlist-invites/{token}", handleJoinPlaylist)

	mux.HandleFunc("POST /api/playlists/{id}/leave", handleLeavePlaylist)
	mux.HandleFunc("DELETE /api/playlists/{id}/members/{userId}", handleRemovePlaylistMember)
}

// ---------------------------------------------------------------------------
// Request shapes
// ---------------------------------------------------------------------------

// createPlaylistRequest mirrors playlist.CreateRequest, with the credential
// fields kept separate so a body cannot smuggle in an owner token.
type createPlaylistRequest struct {
	PlaylistType string `json:"playlistType"`
	Name         string `json:"name"`
	Description  string `json:"description"`
	CoverUrl     string `json:"coverUrl"`
	UserId       string `json:"userId"`
	DisplayName  string `json:"displayName"`
	AvatarUrl    string `json:"avatarUrl"`
}

// patchPlaylistRequest uses pointers so "absent" and "empty" stay distinct: an
// owner clearing a description must not be read as not having sent one.
type patchPlaylistRequest struct {
	Name        *string `json:"name"`
	Description *string `json:"description"`
	CoverUrl    *string `json:"coverUrl"`
}

type addTracksRequest struct {
	BaseRevision *int64                   `json:"baseRevision"`
	Tracks       []map[string]interface{} `json:"tracks"`
}

type moveTrackRequest struct {
	BaseRevision *int64 `json:"baseRevision"`
	To           *int   `json:"to"`
}

type baseRevisionRequest struct {
	BaseRevision *int64 `json:"baseRevision"`
}

type createInviteRequest struct {
	MaxUses *int   `json:"maxUses"`
	TTLMs   *int64 `json:"ttlMs"`
}

type revokeInviteRequest struct {
	InviteToken string `json:"inviteToken"`
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// playlistCredential builds a credential from the request.
//
// The userId comes from the body or query rather than from the token, because the
// token deliberately encodes nothing about its holder — it is a bare random
// value. That means a caller can name a different userId than the one that
// joined, which is why the userId is used only for attribution and for deciding
// which member row a token belongs to, and never to grant access. Access is
// decided by the token alone.
func playlistCredential(r *http.Request, userId string) playlist.Credential {
	return playlist.Credential{
		UserId: strings.TrimSpace(userId),
		Token:  parseBearerToken(r),
	}
}

// writePlaylistError turns a service error into a response. One place, so the
// status and code a client sees is decided by the service and not per handler.
func writePlaylistError(w http.ResponseWriter, err error) {
	pe := playlist.AsError(err)
	jsonError(w, pe.Status, pe.Code, pe.Message)
}

// decodeOptionalBody decodes a body, treating an empty one as absent. A DELETE
// or a bodyless POST is a legitimate request, so an empty body is not a
// malformed one.
func decodeOptionalBody(w http.ResponseWriter, r *http.Request, dst interface{}) bool {
	if r.ContentLength == 0 {
		return true
	}
	return decodeJSONBody(w, r, dst)
}

// int64Param reads a required query parameter as a number.
func int64Param(r *http.Request, key string) (int64, bool) {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return 0, false
	}
	val, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return 0, false
	}
	return val, true
}

// ---------------------------------------------------------------------------
// Playlist lifecycle
// ---------------------------------------------------------------------------

func handleCreatePlaylist(w http.ResponseWriter, r *http.Request) {
	if !playlistLimiter.Allow(clientIP(r)) {
		jsonError(w, http.StatusTooManyRequests, "create_rate_limited",
			"You can create a couple of playlists per minute. Please try again shortly.")
		return
	}
	var req createPlaylistRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	result, err := playlists.Create(playlist.CreateRequest{
		Type:        playlist.PlaylistType(strings.ToUpper(strings.TrimSpace(req.PlaylistType))),
		Name:        req.Name,
		Description: req.Description,
		CoverUrl:    req.CoverUrl,
		UserId:      req.UserId,
		DisplayName: req.DisplayName,
		AvatarUrl:   req.AvatarUrl,
	})
	if err != nil {
		writePlaylistError(w, err)
		return
	}

	// The two tokens exist in this response and nowhere else. A client that drops
	// them has lost the playlist, which is why the Android side persists them
	// before it renders anything.
	jsonResponse(w, http.StatusCreated, map[string]interface{}{
		"playlist":    result.Playlist,
		"ownerToken":  result.OwnerToken,
		"memberToken": result.MemberToken,
	})
}

// handleListPlaylists returns the union of what the presented token can reach.
//
// A single token, because this service has no account to hang several off: the
// client stores one credential per playlist and presents the one it is asking
// about. Multiple credentials are supported by the service and reachable via the
// repeatable `token` query parameter, which is what the app uses to draw the
// "my playlists" list.
func handleListPlaylists(w http.ResponseWriter, r *http.Request) {
	creds := make([]playlist.Credential, 0, 4)
	if bearer := parseBearerToken(r); bearer != "" {
		creds = append(creds, playlist.Credential{Token: bearer})
	}
	for _, extra := range r.URL.Query()["token"] {
		if trimmed := strings.TrimSpace(extra); trimmed != "" {
			creds = append(creds, playlist.Credential{Token: trimmed})
		}
	}
	if len(creds) == 0 {
		jsonError(w, http.StatusForbidden, playlist.ErrForbidden, "A playlist credential is required.")
		return
	}

	items, err := playlists.List(creds...)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"playlists": items,
		"durable":   playlists.Durable(),
	})
}

// handleGetPlaylist returns a full snapshot.
//
// A credential is required. There is no public read: the pre-join case is
// covered by the invite preview endpoint, which returns exactly what the sharer
// chose to share and nothing more. Returning the whole playlist — every member's
// name and the full track list — to anyone who guesses an id would make the
// per-playlist capability meaningless.
func handleGetPlaylist(w http.ResponseWriter, r *http.Request) {
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, err := playlists.Get(r.PathValue("id"), playlistCredential(r, userId))
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"playlist": snapshot})
}

func handlePatchPlaylist(w http.ResponseWriter, r *http.Request) {
	var body patchPlaylistRequest
	if !decodeOptionalBody(w, r, &body) {
		return
	}
	// The userId is only needed to attribute the revision entry, so it rides in
	// the query string rather than needing the body to be an object of a
	// different shape.
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, err := playlists.UpdateMetadata(r.PathValue("id"), playlistCredential(r, userId), playlist.MetadataPatch{
		Name:        body.Name,
		Description: body.Description,
		CoverUrl:    body.CoverUrl,
	})
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"playlist": snapshot})
}

func handleDeletePlaylist(w http.ResponseWriter, r *http.Request) {
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	if err := playlists.Delete(r.PathValue("id"), playlistCredential(r, userId)); err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"deleted": true})
}

// ---------------------------------------------------------------------------
// Deltas
// ---------------------------------------------------------------------------

// handlePlaylistDeltas answers "what changed since revision N" for a reconnecting
// client. Either a complete run of changes or a snapshot — never a partial run
// presented as current.
func handlePlaylistDeltas(w http.ResponseWriter, r *http.Request) {
	from, ok := int64Param(r, "from")
	if !ok {
		jsonError(w, http.StatusBadRequest, playlist.ErrBadRequest, "A numeric `from` revision is required.")
		return
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	result, err := playlists.Deltas(r.PathValue("id"), playlistCredential(r, userId), from)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, result)
}

// ---------------------------------------------------------------------------
// Tracks
// ---------------------------------------------------------------------------

func handleAddPlaylistTracks(w http.ResponseWriter, r *http.Request) {
	var body addTracksRequest
	if !decodeJSONBody(w, r, &body) {
		return
	}
	if len(body.Tracks) == 0 {
		jsonError(w, http.StatusBadRequest, playlist.ErrBadRequest, "At least one track is required.")
		return
	}
	base := int64(0)
	if body.BaseRevision != nil {
		base = *body.BaseRevision
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, added, err := playlists.AddTracks(r.PathValue("id"), playlistCredential(r, userId), base, body.Tracks)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"playlist": snapshot,
		"added":    added,
	})
}

func handleRemovePlaylistTrack(w http.ResponseWriter, r *http.Request) {
	base, _ := int64Param(r, "baseRevision")
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, err := playlists.RemoveTrack(r.PathValue("id"), playlistCredential(r, userId), base, r.PathValue("entryId"))
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"playlist": snapshot})
}

func handleMovePlaylistTrack(w http.ResponseWriter, r *http.Request) {
	var body moveTrackRequest
	if !decodeOptionalBody(w, r, &body) {
		return
	}
	if body.To == nil {
		jsonError(w, http.StatusBadRequest, playlist.ErrBadRequest, "A destination index is required.")
		return
	}
	base := int64(0)
	if body.BaseRevision != nil {
		base = *body.BaseRevision
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, err := playlists.MoveTrack(r.PathValue("id"), playlistCredential(r, userId), base, r.PathValue("entryId"), *body.To)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"playlist": snapshot})
}

func handleClearPlaylistTracks(w http.ResponseWriter, r *http.Request) {
	var body baseRevisionRequest
	if !decodeOptionalBody(w, r, &body) {
		return
	}
	base := int64(0)
	if body.BaseRevision != nil {
		base = *body.BaseRevision
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	snapshot, err := playlists.ClearTracks(r.PathValue("id"), playlistCredential(r, userId), base)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"playlist": snapshot})
}

// ---------------------------------------------------------------------------
// Invitations
// ---------------------------------------------------------------------------

func handleCreatePlaylistInvite(w http.ResponseWriter, r *http.Request) {
	var body createInviteRequest
	if !decodeOptionalBody(w, r, &body) {
		return
	}
	maxUses, ttlMs := 0, int64(0)
	if body.MaxUses != nil {
		maxUses = *body.MaxUses
	}
	if body.TTLMs != nil {
		ttlMs = *body.TTLMs
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	result, err := playlists.Invite(r.PathValue("id"), playlistCredential(r, userId), maxUses, ttlMs)
	if err != nil {
		writePlaylistError(w, err)
		return
	}

	// The deep link is built here rather than in the client so that the host the
	// app was reached at is the host the link points back to. Same reasoning as
	// handleInviteLanding for a party.
	origin := requestOrigin(r)
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"inviteToken": result.Token,
		"expiresAtMs": result.ExpiresAtMs,
		"maxUses":     result.MaxUses,
		"deepLink":    playlistInviteDeepLink(result.Token, origin),
		"webLink":     origin + "/playlist/invite/" + result.Token,
	})
}

func handleRevokePlaylistInvite(w http.ResponseWriter, r *http.Request) {
	var body revokeInviteRequest
	if !decodeOptionalBody(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.InviteToken) == "" {
		jsonError(w, http.StatusBadRequest, playlist.ErrBadRequest, "An invite token is required.")
		return
	}
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	if err := playlists.RevokeInvite(r.PathValue("id"), playlistCredential(r, userId), body.InviteToken); err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"revoked": true})
}

// handlePreviewPlaylistInvite shows what a link points at, before anyone joins.
//
// Deliberately unauthenticated, in the same spirit as handlePreviewParty: the
// whole point of an invite link is that the recipient does not have access yet.
// Everything returned is what the sharer chose to share.
func handlePreviewPlaylistInvite(w http.ResponseWriter, r *http.Request) {
	preview, err := playlists.PreviewInvite(r.PathValue("token"))
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, preview)
}

func handleJoinPlaylist(w http.ResponseWriter, r *http.Request) {
	var req playlist.JoinRequest
	if !decodeJSONBody(w, r, &req) {
		return
	}
	result, err := playlists.Join(r.PathValue("token"), req)
	if err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"playlist":      result.Playlist,
		"memberToken":   result.MemberToken,
		"alreadyMember": result.AlreadyMember,
	})
}

// ---------------------------------------------------------------------------
// Membership
// ---------------------------------------------------------------------------

func handleLeavePlaylist(w http.ResponseWriter, r *http.Request) {
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	if err := playlists.Leave(r.PathValue("id"), playlistCredential(r, userId)); err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"left": true})
}

func handleRemovePlaylistMember(w http.ResponseWriter, r *http.Request) {
	userId := strings.TrimSpace(r.URL.Query().Get("userId"))
	if err := playlists.RemoveMember(r.PathValue("id"), playlistCredential(r, userId), r.PathValue("userId")); err != nil {
		writePlaylistError(w, err)
		return
	}
	jsonResponse(w, http.StatusOK, map[string]interface{}{"removed": true})
}

// ---------------------------------------------------------------------------
// Deep links
// ---------------------------------------------------------------------------

// playlistInviteScheme is the scheme JamInviteLink already owns on Android.
const playlistInviteScheme = "freemusic"

// playlistInviteDeepLink builds the app link for an invitation.
//
// Shape mirrors the party link on purpose — `freemusic://playlist/invite/<token>`
// against `freemusic://party/<code>` — so one Android intent filter and one
// parser cover both, and a link that already works keeps working when the
// playlist feature ships.
func playlistInviteDeepLink(token, origin string) string {
	var b strings.Builder
	b.WriteString(playlistInviteScheme)
	b.WriteString("://playlist/invite/")
	b.WriteString(url.PathEscape(token))
	if origin != "" {
		b.WriteString("?server=")
		b.WriteString(url.QueryEscape(origin))
	}
	return b.String()
}
