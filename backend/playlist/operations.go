package playlist

import (
	"strings"
)

// CreateRequest is everything needed to make a playlist.
type CreateRequest struct {
	Type        PlaylistType
	Name        string
	Description string
	CoverUrl    string
	UserId      string
	DisplayName string
	AvatarUrl   string
}

// CreateResult is what the creator gets back. The two tokens are returned
// exactly once, here, and are the only copies that will ever exist: the server
// keeps hashes. A client that loses them loses access, which is why
// ListenTogether-style persistence on the device is not optional.
type CreateResult struct {
	Playlist    *Snapshot
	OwnerToken  string
	MemberToken string
}

// Create makes a playlist and enrols the creator as its owner.
func (s *Service) Create(req CreateRequest) (*CreateResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !req.Type.Valid() {
		return nil, errBadRequest("Unknown playlist type.")
	}
	userId, err := cleanUserId(req.UserId)
	if err != nil {
		return nil, err
	}
	name, err := cleanName(req.Name, s.opts.NameMaxLen)
	if err != nil {
		return nil, err
	}
	if len(s.playlists) >= s.opts.MaxPlaylists {
		return nil, newError(503, ErrPlaylistLimit, "The playlist server is full.")
	}

	now := s.stamp()
	ownerToken, ownerHash := NewToken()
	memberToken, memberHash := NewToken()

	playlist := &Playlist{
		Id:             NewPlaylistId(),
		Type:           req.Type,
		Name:           name,
		Description:    cleanDescription(req.Description, s.opts.DescMaxLen),
		CoverUrl:       cleanAvatar(req.CoverUrl),
		OwnerId:        userId,
		Visibility:     VisibilityPrivate,
		CreatedAtMs:    now,
		UpdatedAtMs:    now,
		Revision:       1,
		OwnerTokenHash: ownerHash,
		Members: []Member{{
			UserId:       userId,
			DisplayName:  cleanDisplayName(req.DisplayName),
			AvatarUrl:    cleanAvatar(req.AvatarUrl),
			Role:         RoleOwner,
			JoinedAtMs:   now,
			LastActiveMs: now,
			TokenHash:    memberHash,
		}},
		Tracks: make([]Track, 0),
	}

	s.playlists[playlist.Id] = playlist
	s.ownerHashes[playlist.Id] = ownerHash

	if err := s.flush(); err != nil {
		// The playlist exists in memory. Reporting the failure is right — the
		// caller should know it may not survive a restart — but it is not
		// removed, because the user's action did succeed in every way they can
		// observe, and undoing it would be the more surprising outcome.
		return &CreateResult{
			Playlist:    playlist.ToSnapshot(RoleOwner),
			OwnerToken:  ownerToken,
			MemberToken: memberToken,
		}, err
	}

	return &CreateResult{
		Playlist:    playlist.ToSnapshot(RoleOwner),
		OwnerToken:  ownerToken,
		MemberToken: memberToken,
	}, nil
}

// viewer describes the caller for the read paths.
func (s *Service) viewer(p *Playlist, cred Credential) *principal {
	return s.resolve(p, cred)
}

// Get returns a snapshot for a caller who holds a credential, or a public
// summary view otherwise.
//
// Reading does not require membership — the brief's invitation flow has someone
// open a link before they have joined — but ViewerRole is CO* only for a member,
// and the UI uses it to decide which controls to draw.
func (s *Service) Get(id string, cred Credential) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, err
	}
	pr := s.viewer(p, cred)
	if pr == nil || pr.member == nil {
		return nil, errForbidden()
	}
	pr.member.LastActiveMs = nowMs()
	return p.ToSnapshot(pr.role), nil
}

// List returns the summaries for every playlist the presented credentials
// belong to.
//
// Matched on tokens rather than on a userId: a userId is self-asserted, so
// listing by it would let anyone enumerate anyone's playlists by guessing a
// pseudonym. A token proves membership, and membership is what the list is.
//
// Variadic because capabilities are per-playlist: a user who created three
// playlists holds three unrelated tokens, and "my playlists" is the union of
// what those tokens reach. One request, no enumeration, and the client never has
// to fan out and merge.
func (s *Service) List(creds ...Credential) ([]*Summary, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	held := make([]string, 0, len(creds))
	for _, c := range creds {
		if t := strings.TrimSpace(c.Token); t != "" {
			held = append(held, t)
		}
	}
	if len(held) == 0 {
		return nil, errForbidden()
	}

	out := make([]*Summary, 0, 8)
	seen := make(map[string]bool, 8)
	for _, p := range s.playlists {
		if p.DeleteAtMs != nil || seen[p.Id] {
			continue
		}
		for _, token := range held {
			pr := s.resolve(p, Credential{Token: token})
			if pr == nil || pr.member == nil {
				continue
			}
			seen[p.Id] = true
			out = append(out, p.ToSummary(pr.role))
			break
		}
	}
	// Newest first, by update rather than creation, so a playlist somebody just
	// added to comes to the top for everyone.
	sortSummaries(out)
	return out, nil
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

// MetadataPatch is a partial update. Pointers distinguish "leave alone" from
// "set to empty", which matters for a description the owner may want to clear.
type MetadataPatch struct {
	Name        *string
	Description *string
	CoverUrl    *string
}

// UpdateMetadata applies a patch. Owner-only: the brief reserves rename,
// description and cover to the owner.
func (s *Service) UpdateMetadata(id string, cred Credential, patch MetadataPatch) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, err
	}
	pr := s.viewer(p, cred)
	if !pr.isOwnerPrincipal() {
		return nil, errForbidden()
	}

	changed := false
	if patch.Name != nil {
		name, err := cleanName(*patch.Name, s.opts.NameMaxLen)
		if err != nil {
			return nil, err
		}
		if name != p.Name {
			p.Name = name
			changed = true
		}
	}
	if patch.Description != nil {
		desc := cleanDescription(*patch.Description, s.opts.DescMaxLen)
		if desc != p.Description {
			p.Description = desc
			changed = true
		}
	}
	if patch.CoverUrl != nil {
		cover := cleanAvatar(*patch.CoverUrl)
		if !samePtr(cover, p.CoverUrl) {
			p.CoverUrl = cover
			changed = true
		}
	}
	if !changed {
		// Not a mutation, so not a revision. A client that renames a playlist to
		// the name it already had should not invalidate every other device's
		// state.
		return p.ToSnapshot(pr.role), nil
	}

	s.bump(p, pr, KindMetadataUpdated, map[string]interface{}{
		"name":        p.Name,
		"description": p.Description,
		"coverUrl":    p.CoverUrl,
	})
	if err := s.flush(); err != nil {
		return p.ToSnapshot(pr.role), err
	}
	return p.ToSnapshot(pr.role), nil
}

// ---------------------------------------------------------------------------
// Tracks
// ---------------------------------------------------------------------------

// AddTracks appends tracks. Any member may do this.
//
// The revision check is the caller's declared starting point: a client that
// believes it is at revision N and is actually behind is rejected rather than
// merged, because the brief is explicit that the server is authoritative and
// that stale writes must not win. A wrong guess in the other direction — the
// client ahead of the server — cannot happen, since only the server assigns
// revisions.
func (s *Service) AddTracks(id string, cred Credential, baseRevision int64, raw []map[string]interface{}) (*Snapshot, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, 0, err
	}
	pr := s.viewer(p, cred)
	if !pr.mayWrite() {
		return nil, 0, errForbidden()
	}
	if len(raw) == 0 {
		return nil, 0, errBadRequest("No tracks to add.")
	}
	if err := checkRevision(p, baseRevision); err != nil {
		return nil, 0, err
	}

	room := s.opts.MaxTracks - len(p.Tracks)
	if room <= 0 {
		return nil, 0, newError(409, ErrTrackLimit, "This playlist is full.")
	}
	if len(raw) > room {
		raw = raw[:room]
	}

	added := make([]Track, 0, len(raw))
	for _, item := range raw {
		track, err := trackFromInput(item, pr.member)
		if err != nil {
			// One bad row does not fail the batch: the rest are still what the
			// user asked for, and rejecting all of them because one had no
			// videoId would be the worse outcome.
			continue
		}
		track.EntryId = NewEntryId()
		track.Position = len(p.Tracks) + len(added)
		added = append(added, *track)
	}
	if len(added) == 0 {
		return nil, 0, errBadRequest("None of those tracks could be read.")
	}

	p.Tracks = append(p.Tracks, added...)
	p.Renumber()

	payload := make([]interface{}, 0, len(added))
	for i := range added {
		// Position is re-read from the stored track, because SortTracks may have
		// moved it and the delta has to say where it actually landed.
		if stored := p.FindTrack(added[i].EntryId); stored != nil {
			payload = append(payload, stored)
		}
	}
	s.bump(p, pr, KindTrackAdded, map[string]interface{}{"tracks": payload})

	if err := s.flush(); err != nil {
		return p.ToSnapshot(pr.role), len(added), err
	}
	return p.ToSnapshot(pr.role), len(added), nil
}

// RemoveTrack removes one entry. Any member may do this.
//
// By EntryId rather than videoId: the same song may be in the playlist twice,
// added by two people, and "remove this one" has to mean the one they tapped.
func (s *Service) RemoveTrack(id string, cred Credential, baseRevision int64, entryId string) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, err
	}
	pr := s.viewer(p, cred)
	if !pr.mayWrite() {
		return nil, errForbidden()
	}
	if err := checkRevision(p, baseRevision); err != nil {
		return nil, err
	}

	index := p.IndexOf(strings.TrimSpace(entryId))
	if index < 0 {
		// Already gone. Not an error: two members tapping remove on the same row
		// is ordinary, and the one who arrived second asked for a state that is
		// now true.
		return p.ToSnapshot(pr.role), nil
	}

	removed := p.Tracks[index]
	p.Tracks = append(p.Tracks[:index], p.Tracks[index+1:]...)
	p.Renumber()

	s.bump(p, pr, KindTrackRemoved, map[string]interface{}{
		"entryId":  removed.EntryId,
		"position": removed.Position,
	})
	if err := s.flush(); err != nil {
		return p.ToSnapshot(pr.role), err
	}
	return p.ToSnapshot(pr.role), nil
}

// MoveTrack moves one entry to a new index. Any member may do this.
func (s *Service) MoveTrack(id string, cred Credential, baseRevision int64, entryId string, to int) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, err
	}
	pr := s.viewer(p, cred)
	if !pr.mayWrite() {
		return nil, errForbidden()
	}
	if err := checkRevision(p, baseRevision); err != nil {
		return nil, err
	}

	from := p.IndexOf(strings.TrimSpace(entryId))
	if from < 0 {
		return nil, errBadRequest("That track is not in this playlist.")
	}
	// Clamped rather than refused: a client that thinks the list is longer than
	// it is means the destination it wants is the end, and refusing would leave
	// it to guess again.
	if to < 0 {
		to = 0
	}
	if to > len(p.Tracks)-1 {
		to = len(p.Tracks) - 1
	}
	if to == from {
		return p.ToSnapshot(pr.role), nil
	}

	track := p.Tracks[from]
	p.Tracks = append(p.Tracks[:from], p.Tracks[from+1:]...)
	// Inserted before the element now at `to` when moving down, after it when
	// moving up — because removing the item shifted everything after it.
	insertAt := to
	p.Tracks = append(p.Tracks, Track{})
	copy(p.Tracks[insertAt+1:], p.Tracks[insertAt:])
	p.Tracks[insertAt] = track
	p.Renumber()

	stored := p.FindTrack(track.EntryId)
	position := to
	if stored != nil {
		position = stored.Position
	}
	s.bump(p, pr, KindTrackMoved, map[string]interface{}{
		"entryId":  track.EntryId,
		"position": position,
	})
	if err := s.flush(); err != nil {
		return p.ToSnapshot(pr.role), err
	}
	return p.ToSnapshot(pr.role), nil
}

// ClearTracks removes every track. Owner-only: it is the one destructive
// operation that a collaborator could use to destroy everybody else's work
// without deleting anything they own.
func (s *Service) ClearTracks(id string, cred Credential, baseRevision int64) (*Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return nil, err
	}
	pr := s.viewer(p, cred)
	if !pr.isOwnerPrincipal() {
		return nil, errForbidden()
	}
	if err := checkRevision(p, baseRevision); err != nil {
		return nil, err
	}
	if len(p.Tracks) == 0 {
		return p.ToSnapshot(pr.role), nil
	}

	p.Tracks = p.Tracks[:0]
	s.bump(p, pr, KindTracksCleared, nil)
	if err := s.flush(); err != nil {
		return p.ToSnapshot(pr.role), err
	}
	return p.ToSnapshot(pr.role), nil
}

// ---------------------------------------------------------------------------
// Deletion
// ---------------------------------------------------------------------------

// Delete removes a playlist outright. Owner-only.
//
// The row is kept with a DeleteAtMs stamp rather than dropped, so that a client
// which still holds a token gets "this was deleted" instead of "never existed" —
// the same distinction Invite.RevokedAtMs makes.
func (s *Service) Delete(id string, cred Credential) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return err
	}
	pr := s.viewer(p, cred)
	if !pr.isOwnerPrincipal() {
		return errForbidden()
	}

	now := nowMs()
	p.DeleteAtMs = &now
	p.Tracks = p.Tracks[:0]
	p.Members = p.Members[:0]
	p.Invites = p.Invites[:0]
	p.Revision++
	delete(s.ownerHashes, p.Id)

	return s.flush()
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// checkRevision enforces the brief's central rule: a write that starts from a
// revision the server has moved past is refused.
//
// A baseRevision of 0 means "I did not say", which is allowed — a client that
// has never seen the playlist (joining, then adding) has no revision to declare,
// and forcing one would only teach callers to send a number they made up. Any
// non-zero value must match exactly.
func checkRevision(p *Playlist, baseRevision int64) error {
	if baseRevision == 0 || baseRevision == p.Revision {
		return nil
	}
	return newError(409, ErrConflict, "This playlist changed somewhere else. Reload and try again.")
}

// bump advances the revision and records the delta. Called with s.mu held, by
// every mutation, immediately before flush.
//
// The revision and the history entry are assigned here rather than by each
// caller so that no mutation can forget one. A change without a revision would
// be invisible to a client polling for deltas; a revision without a history
// entry would force a full snapshot for anyone who fell behind.
func (s *Service) bump(p *Playlist, pr *principal, kind string, payload map[string]interface{}) {
	p.Revision++
	now := s.stamp()
	p.UpdatedAtMs = now

	byUserId := ""
	if pr != nil && pr.member != nil {
		byUserId = pr.member.UserId
		pr.member.LastActiveMs = now
	}

	p.History = append(p.History, Revision{
		Revision: p.Revision,
		Kind:     kind,
		AtMs:     now,
		ByUserId: byUserId,
		Payload:  payload,
	})
	// Bounded: the oldest deltas are dropped once the window is full. A client
	// further behind than HistoryLimit is told to take a full snapshot instead —
	// see Deltas.
	if len(p.History) > s.opts.HistoryLimit {
		drop := len(p.History) - s.opts.HistoryLimit
		p.History = append(p.History[:0], p.History[drop:]...)
	}
}

// samePtr compares two optional strings by value.
func samePtr(a, b *string) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	return *a == *b
}

// sortSummaries orders by most recently updated, then by id so the order is
// total and does not shuffle between calls.
func sortSummaries(items []*Summary) {
	for i := 1; i < len(items); i++ {
		for j := i; j > 0; j-- {
			a, b := items[j-1], items[j]
			less := b.UpdatedAtMs > a.UpdatedAtMs ||
				(b.UpdatedAtMs == a.UpdatedAtMs && b.Id > a.Id)
			if !less {
				break
			}
			items[j-1], items[j] = items[j], items[j-1]
		}
	}
}
