package playlist

import "strings"

// InviteResult carries the invitation token, which exists in plaintext exactly
// once — here. The store keeps only its hash, so a second reading is impossible
// by construction rather than by policy.
type InviteResult struct {
	Token       string `json:"inviteToken"`
	ExpiresAtMs int64  `json:"expiresAtMs"`
	// MaxUses is echoed so the owner can see what they set.
	MaxUses int `json:"maxUses,omitempty"`
}

// JoinRequest is the identity presented when accepting an invitation.
//
// Deliberately shaped like protocol.JoinRequest — same field names, same
// validation limits — so the Android client sends the identity it already builds
// for a party without translating it, and so the two places that accept a
// display name cannot drift apart on what is acceptable.
type JoinRequest struct {
	UserId      string `json:"userId"`
	DisplayName string `json:"displayName"`
	AvatarUrl   string `json:"avatarUrl,omitempty"`
}

// Invite mints an invitation to a playlist. Owner-only, because the brief
// reserves inviting to the owner.
//
// A fresh invite per call rather than reusing an outstanding one. Reuse would
// mean an owner who wants to send an invitation to one person cannot stop
// another from using an older link they already hold, and the audit's security
// section is explicit that revocation has to be meaningful.
func (s *Service) Invite(id string, cred Credential, maxUses int, ttlMs int64) (*InviteResult, error) {
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
	if len(p.Invites) >= s.opts.MaxInvites {
		// Drop the ones that can no longer be used before refusing. An owner who
		// has generated twenty links, all expired, should not be told they are
		// out of links.
		p.Invites = pruneInvites(p.Invites, nowMs())
		if len(p.Invites) >= s.opts.MaxInvites {
			return nil, newError(409, ErrInviteLimit, "Revoke an invitation before making another.")
		}
	}
	if ttlMs <= 0 {
		ttlMs = s.opts.InviteTTLMs
	}
	if maxUses < 0 {
		maxUses = 0
	}

	token, hash := NewToken()
	now := nowMs()
	p.Invites = append(p.Invites, Invite{
		TokenHash:   hash,
		CreatedBy:   pr.member.UserId,
		CreatedAtMs: now,
		ExpiresAtMs: now + ttlMs,
		MaxUses:     maxUses,
	})

	if err := s.flush(); err != nil {
		return &InviteResult{Token: token, ExpiresAtMs: now + ttlMs, MaxUses: maxUses}, err
	}
	return &InviteResult{Token: token, ExpiresAtMs: now + ttlMs, MaxUses: maxUses}, nil
}

// RevokeInvite withdraws an outstanding invitation. Owner-only.
//
// The token is identified by its plaintext value, because the owner holds the
// link they want to withdraw. The hash is computed here and used to find the
// record; the plaintext is never stored, so this is the only way to name one.
func (s *Service) RevokeInvite(id string, cred Credential, inviteToken string) error {
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

	hash := HashToken(strings.TrimSpace(inviteToken))
	now := nowMs()
	revoked := false
	for i := range p.Invites {
		if !TokenMatches(inviteToken, p.Invites[i].TokenHash) {
			continue
		}
		_ = hash
		if p.Invites[i].RevokedAtMs == nil {
			p.Invites[i].RevokedAtMs = &now
			revoked = true
		}
	}
	if !revoked {
		// Either it never existed or it was already revoked. Both leave the
		// caller's intent satisfied, so neither is an error.
		return nil
	}
	return s.flush()
}

// JoinPreview is what an invitation link can show before anybody commits to
// joining: enough to recognise the playlist, and nothing else.
type JoinPreview struct {
	PlaylistId  string       `json:"playlistId"`
	Type        PlaylistType `json:"playlistType"`
	Name        string       `json:"name"`
	Description string       `json:"description,omitempty"`
	CoverUrl    *string      `json:"coverUrl,omitempty"`
	MemberCount int          `json:"memberCount"`
	TrackCount  int          `json:"trackCount"`
	CoverThumbs []string     `json:"coverThumbs,omitempty"`
	OwnerName   string       `json:"ownerName"`
	ExpiresAtMs int64        `json:"expiresAtMs"`
	// Joinable is false when the invitation is expired, revoked, exhausted, or
	// the playlist is full. The reason is in Reason so the screen can say why
	// rather than showing a dead button.
	Joinable bool   `json:"joinable"`
	Reason   string `json:"reason,omitempty"`
}

// PreviewInvite resolves an invitation token to something worth showing.
//
// Deliberately unauthenticated, in the same spirit as the existing
// handlePreviewParty: the whole point of an invite link is that the person
// opening it has not joined yet. It reveals only what the owner already chose to
// share by sending the link.
func (s *Service) PreviewInvite(token string) (*JoinPreview, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, _, err := s.resolveInvite(token)
	if err != nil {
		return nil, err
	}
	invite := s.findInvite(p, token)

	ownerName := ""
	for i := range p.Members {
		if p.Members[i].Role == RoleOwner {
			ownerName = p.Members[i].DisplayName
			break
		}
	}

	preview := &JoinPreview{
		PlaylistId:  p.Id,
		Type:        p.Type,
		Name:        p.Name,
		Description: p.Description,
		CoverUrl:    p.CoverUrl,
		MemberCount: len(p.Members),
		TrackCount:  len(p.Tracks),
		OwnerName:   ownerName,
		Joinable:    true,
	}
	if invite != nil {
		preview.ExpiresAtMs = invite.ExpiresAtMs
	}
	if len(p.Tracks) > 0 {
		preview.CoverThumbs = p.ToSummary(RoleCollaborator).CoverThumbs
	}
	// Re-derive usability for display rather than returning the error: the
	// screen wants to render the playlist *and* explain that the link is dead.
	if reason := s.joinBlockedReason(p, invite); reason != "" {
		preview.Joinable = false
		preview.Reason = reason
	}
	return preview, nil
}

// JoinResult is the outcome of accepting an invitation.
type JoinResult struct {
	Playlist   *Snapshot
	MemberToken string
	// AlreadyMember is true when the caller was in the playlist already. Not an
	// error: people tap their own link, and the second tap should land them in
	// the playlist rather than on a complaint.
	AlreadyMember bool
}

// Join accepts an invitation.
//
// The invite is consumed (its use count incremented) before the member is added,
// so a replay cannot join twice: the second attempt sees Uses already at the
// cap. Both happen under the same lock, so two simultaneous redemptions cannot
// both observe room.
func (s *Service) Join(token string, req JoinRequest) (*JoinResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	userId, err := cleanUserId(req.UserId)
	if err != nil {
		return nil, err
	}
	p, invite, err := s.resolveInvite(token)
	if err != nil {
		return nil, err
	}
	if reason := s.joinBlockedReason(p, invite); reason != "" {
		return nil, newError(409, "join_blocked", reason)
	}

	// Already in, so hand back the playlist rather than adding a duplicate row.
	// A new token is *not* minted in this case: the caller's existing one still
	// works, and issuing a second would leave two live credentials for one seat.
	if existing := p.Member(userId); existing != nil {
		invite.Uses++
		_ = s.flush()
		return &JoinResult{Playlist: p.ToSnapshot(existing.Role), AlreadyMember: true}, nil
	}

	memberToken, memberHash := NewToken()
	now := nowMs()
	p.Members = append(p.Members, Member{
		UserId:       userId,
		DisplayName:  cleanDisplayName(req.DisplayName),
		AvatarUrl:    cleanAvatar(req.AvatarUrl),
		Role:         RoleCollaborator,
		JoinedAtMs:   now,
		LastActiveMs: now,
		TokenHash:    memberHash,
	})
	invite.Uses++

	pr := &principal{member: &p.Members[len(p.Members)-1], role: RoleCollaborator}
	s.bump(p, pr, KindMemberJoined, map[string]interface{}{
		"userId":      userId,
		"displayName": cleanDisplayName(req.DisplayName),
		"avatarUrl":   cleanAvatar(req.AvatarUrl),
	})

	if err := s.flush(); err != nil {
		return &JoinResult{Playlist: p.ToSnapshot(RoleCollaborator), MemberToken: memberToken}, err
	}
	return &JoinResult{Playlist: p.ToSnapshot(RoleCollaborator), MemberToken: memberToken}, nil
}

// Leave removes the caller from a playlist.
//
// An owner cannot leave. There is exactly one owner credential and it is not
// transferable, so an owner who leaves would strand the playlist with a name
// they can no longer change and no way to delete it. The brief lists "owner
// leaves" as a case to handle; refusing it clearly is handling it.
func (s *Service) Leave(id string, cred Credential) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	p, err := s.find(id)
	if err != nil {
		return err
	}
	pr := s.viewer(p, cred)
	if !pr.mayWrite() {
		return errForbidden()
	}
	if pr.isOwnerPrincipal() || pr.role == RoleOwner {
		return newError(409, "owner_cannot_leave", "Delete the playlist, or hand it over first.")
	}

	index := -1
	for i := range p.Members {
		if p.Members[i].UserId == pr.member.UserId {
			index = i
			break
		}
	}
	if index < 0 {
		return nil
	}
	userId := p.Members[index].UserId
	p.Members = append(p.Members[:index], p.Members[index+1:]...)

	// Attribution on tracks they added stays — see Track.AddedByName. The
	// playlist should not silently rewrite who did what.
	s.bump(p, nil, KindMemberLeft, map[string]interface{}{"userId": userId})
	return s.flush()
}

// RemoveMember evicts a collaborator. Owner-only.
func (s *Service) RemoveMember(id string, cred Credential, userId string) error {
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
	userId = strings.TrimSpace(userId)
	if userId == p.OwnerId {
		return newError(409, "cannot_remove_owner", "The owner cannot be removed.")
	}

	index := -1
	for i := range p.Members {
		if p.Members[i].UserId == userId {
			index = i
			break
		}
	}
	if index < 0 {
		return nil
	}
	// The row goes, and with it the token hash. There is no session to expire:
	// resolve() looks up the member on every request, so the credential stops
	// working at this line rather than at some later refresh.
	p.Members = append(p.Members[:index], p.Members[index+1:]...)

	s.bump(p, pr, KindMemberRemoved, map[string]interface{}{"userId": userId})
	return s.flush()
}

// ---------------------------------------------------------------------------
// Invite helpers
// ---------------------------------------------------------------------------

// resolveInvite finds the playlist an invitation token belongs to.
//
// Linear over every playlist, because a token is a hash and hashes cannot be
// indexed without storing the plaintext. The set is bounded by MaxPlaylists and
// joins are rare, so this is a scan of a few thousand small comparisons at
// worst — cheaper than the index would be to maintain correctly.
func (s *Service) resolveInvite(token string) (*Playlist, *Invite, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return nil, nil, errBadRequest("An invitation token is required.")
	}
	now := nowMs()
	for _, p := range s.playlists {
		if p.DeleteAtMs != nil {
			continue
		}
		for i := range p.Invites {
			if !TokenMatches(token, p.Invites[i].TokenHash) {
				continue
			}
			if err := p.Invites[i].Usable(now); err != nil {
				return nil, nil, err
			}
			return p, &p.Invites[i], nil
		}
	}
	return nil, nil, newError(404, "no_such_invite", "That invitation is not valid.")
}

// findInvite returns the invite record for a token on a known playlist, or nil.
func (s *Service) findInvite(p *Playlist, token string) *Invite {
	for i := range p.Invites {
		if TokenMatches(token, p.Invites[i].TokenHash) {
			return &p.Invites[i]
		}
	}
	return nil
}

// joinBlockedReason returns a human phrase when a join cannot proceed, or "".
func (s *Service) joinBlockedReason(p *Playlist, invite *Invite) string {
	if invite != nil {
		if invite.Revoked() {
			return "That invitation was withdrawn."
		}
		if invite.Expired(nowMs()) {
			return "That invitation has expired."
		}
		if invite.Exhausted() {
			return "That invitation has already been used."
		}
	}
	if len(p.Members) >= s.opts.MaxMembers {
		return "This playlist is full."
	}
	return ""
}

// pruneInvites drops the invitations that can no longer be used, so the cap
// counts live links rather than history.
func pruneInvites(invites []Invite, now int64) []Invite {
	out := invites[:0]
	for i := range invites {
		iv := invites[i]
		if iv.Revoked() || iv.Expired(now) || iv.Exhausted() {
			continue
		}
		out = append(out, iv)
	}
	return out
}

// ---------------------------------------------------------------------------
// Deltas
// ---------------------------------------------------------------------------

// DeltaResult is what a reconnecting client receives.
//
// Either a run of changes or a full snapshot, never both. The brief is explicit
// that a client must not be handed stale data and told it is current, and the
// clearest way to guarantee that is to make the two answers structurally
// different: if Resync is true there is no list of changes, and if there is a
// list of changes it is complete.
type DeltaResult struct {
	PlaylistId   string       `json:"playlistId"`
	FromRevision int64        `json:"fromRevision"`
	ToRevision   int64        `json:"toRevision"`
	Changes      []Revision   `json:"changes"`
	Resync       bool         `json:"resync"`
	Snapshot     *Snapshot    `json:"snapshot,omitempty"`
}

// Deltas answers "what did I miss since revision N".
//
// fromRevision of 0 means the client holds nothing and always gets a snapshot.
// A client ahead of the server — which cannot happen through normal use, but
// can through a restored backup — is treated as needing a snapshot rather than
// being handed a negative-length list.
func (s *Service) Deltas(id string, cred Credential, fromRevision int64) (*DeltaResult, error) {
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

	result := &DeltaResult{
		PlaylistId:   p.Id,
		FromRevision: fromRevision,
		ToRevision:   p.Revision,
	}
	if fromRevision <= 0 || fromRevision >= p.Revision {
		if fromRevision == p.Revision {
			// Already current: no changes, and no snapshot either — the client's
			// copy is not stale, so re-sending it would only cost bandwidth.
			result.Changes = []Revision{}
			return result, nil
		}
		result.Resync = true
		result.Snapshot = p.ToSnapshot(pr.role)
		return result, nil
	}

	changes := make([]Revision, 0, 8)
	for i := range p.History {
		if p.History[i].Revision > fromRevision {
			changes = append(changes, p.History[i])
		}
	}
	// The gap test. History is bounded, so the oldest retained revision must be
	// exactly fromRevision+1 for the run to be continuous. A first change that
	// is further ahead means the changes in between were dropped, and a client
	// given an incomplete delta would silently diverge — the exact failure the
	// brief warns against.
	if len(changes) == 0 || changes[0].Revision != fromRevision+1 {
		result.Resync = true
		result.Snapshot = p.ToSnapshot(pr.role)
		result.Changes = nil
		return result, nil
	}

	result.Changes = changes
	return result, nil
}
