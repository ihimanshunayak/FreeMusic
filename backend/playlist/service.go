package playlist

import (
	"sync"
)

// Options are the service's bounds. Every field has a zero-value fallback, so a
// caller that sets nothing still gets a working, bounded service — but config
// sets them, because the brief asked for these limits to be configurable rather
// than scattered as literals.
type Options struct {
	MaxPlaylists int
	MaxMembers   int
	MaxTracks    int
	MaxInvites   int
	InviteTTLMs  int64
	HistoryLimit int
	NameMaxLen   int
	DescMaxLen   int
}

// withDefaults fills every unset bound. Returns a value rather than mutating, so
// a caller's Options is not silently rewritten.
func (o Options) withDefaults() Options {
	if o.MaxPlaylists <= 0 {
		o.MaxPlaylists = DefaultMaxPlaylists
	}
	if o.MaxMembers <= 0 {
		o.MaxMembers = DefaultMaxMembers
	}
	if o.MaxTracks <= 0 {
		o.MaxTracks = DefaultMaxTracks
	}
	if o.MaxInvites <= 0 {
		o.MaxInvites = DefaultMaxInvites
	}
	if o.InviteTTLMs <= 0 {
		o.InviteTTLMs = DefaultInviteTTLMs
	}
	if o.HistoryLimit <= 0 {
		o.HistoryLimit = DefaultHistoryLimit
	}
	if o.NameMaxLen <= 0 {
		o.NameMaxLen = DefaultNameMaxLen
	}
	if o.DescMaxLen <= 0 {
		o.DescMaxLen = DefaultDescMaxLen
	}
	return o
}

// Service owns a Store and enforces every rule about it.
//
// One mutex around the whole set rather than a lock per playlist. The set is
// bounded and every mutation is a whole-set Save, so per-playlist locking would
// buy concurrency that the store cannot use and would have to answer ordering
// questions — two playlists saved in the wrong order, a save racing a load —
// that a single lock makes unaskable. This is the same posture PartyStore takes.
type Service struct {
	mu    sync.Mutex
	opts  Options
	store Store
	// playlists is the working set, indexed by id. The store is the durable
	// copy; this is what requests read and write.
	playlists map[string]*Playlist
	// ownerHashes maps playlist id to the hash of its owner credential. Kept
	// beside playlists rather than on them so that no snapshot, clone, or log of
	// a Playlist can carry it.
	ownerHashes map[string]string
	// loaded records whether the initial Load has run, so a caller can tell an
	// empty service from an uninitialised one.
	loaded bool
	// lastStampMs is the highest UpdatedAtMs ever issued. Wall-clock
	// milliseconds are too coarse to order two updates that land in the same
	// millisecond, and "most recently updated" is the order the playlist list is
	// drawn in, so ties would shuffle. Advanced past the wall clock only while
	// mutations outpace one per millisecond, which a hundred-member playlist on
	// Render cannot do. Read and written with mu held.
	lastStampMs int64
}

// NewService builds a service over a store, loading whatever is already there.
//
// A store that fails to load is reported rather than replaced with an empty set:
// starting empty after a read error is indistinguishable from a wipe, and the
// caller is better placed to decide whether that is acceptable.
func NewService(store Store, opts Options) (*Service, error) {
	if store == nil {
		return nil, errBadRequest("A playlist store is required.")
	}
	s := &Service{
		opts:        opts.withDefaults(),
		store:       store,
		playlists:   make(map[string]*Playlist),
		ownerHashes: make(map[string]string),
	}

	all, err := store.Load()
	if err != nil {
		return nil, err
	}
	for _, p := range all {
		if p == nil || p.Id == "" {
			continue
		}
		s.playlists[p.Id] = p
		if p.OwnerTokenHash != "" {
			s.ownerHashes[p.Id] = p.OwnerTokenHash
		}
	}
	s.loaded = true
	for _, p := range s.playlists {
		if p.UpdatedAtMs > s.lastStampMs {
			s.lastStampMs = p.UpdatedAtMs
		}
	}
	return s, nil
}

// stamp returns an update timestamp strictly greater than every one already
// issued. See lastStampMs for why a raw wall clock is not enough.
// Called with s.mu held.
func (s *Service) stamp() int64 {
	now := nowMs()
	if now <= s.lastStampMs {
		now = s.lastStampMs + 1
	}
	s.lastStampMs = now
	return now
}

// Durable reports whether the underlying store survives a restart.
func (s *Service) Durable() bool { return s.store.Durable() }

// Count returns how many playlists are held.
func (s *Service) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.playlists)
}

// flush persists the working set. Called with s.mu held.
//
// A failed save is reported to the caller *after* the in-memory change has
// already been applied, and the change is deliberately not rolled back: the
// alternative is a request that appears to fail while having half-succeeded, and
// every caller here is a mutation the user believed worked. What a save failure
// means is "this did not durably land", which the caller surfaces — the same
// reasoning behind PlaylistStore's writable flag on Android.
func (s *Service) flush() error {
	all := make([]*Playlist, 0, len(s.playlists))
	for _, p := range s.playlists {
		all = append(all, p)
	}
	return s.store.Save(all)
}

// Close flushes and releases the store.
func (s *Service) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.store.Save(playlistSlice(s.playlists)); err != nil {
		return err
	}
	return s.store.Close()
}

func playlistSlice(m map[string]*Playlist) []*Playlist {
	out := make([]*Playlist, 0, len(m))
	for _, p := range m {
		out = append(out, p)
	}
	return out
}

// ---------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------

// Credential is what a caller presents to act on a playlist.
type Credential struct {
	// UserId is the client's self-asserted pseudonym. Used for attribution and
	// for deciding *which* member a token belongs to — never on its own to grant
	// access, because any client can claim any value.
	UserId string
	// Token is either a membership token or the owner token. Which one it is
	// depends on which hash it matches.
	Token string
}

// principal is the resolved answer to "who is this and what may they do".
type principal struct {
	member *Member
	role   Role
	// isOwner is true when the credential matched the owner hash, which is the
	// only way to be owner. A member whose UserId equals OwnerId is *not*
	// automatically owner — see the audit, §0.2.
	isOwner bool
}

// mayWrite reports whether the principal can change the playlist.
func (pr *principal) mayWrite() bool { return pr != nil && pr.member != nil }

// isOwnerPrincipal reports whether the principal holds the owner credential.
func (pr *principal) isOwnerPrincipal() bool { return pr != nil && pr.isOwner }

// resolve finds the principal behind a credential.
//
// Two lookups, owner first. The owner token is checked against the owner hash
// and the membership token against every member's hash; a token that matches
// neither yields a nil principal rather than an error, because "who are you" and
// "you may not do this" are different answers and the callers want to
// distinguish them.
func (s *Service) resolve(p *Playlist, cred Credential) *principal {
	if cred.Token != "" {
		if hash, ok := s.ownerHashes[p.Id]; ok && TokenMatches(cred.Token, hash) {
			// The owner still needs a Member row for attribution and for the
			// member list, so it is looked up or synthesised.
			member := p.Member(cred.UserId)
			if member == nil {
				for i := range p.Members {
					if p.Members[i].Role == RoleOwner {
						member = &p.Members[i]
						break
					}
				}
			}
			return &principal{member: member, role: RoleOwner, isOwner: true}
		}
		for i := range p.Members {
			if TokenMatches(cred.Token, p.Members[i].TokenHash) {
				return &principal{member: &p.Members[i], role: p.Members[i].Role}
			}
		}
	}
	return nil
}

// find returns a playlist by id, or a 404 error.
func (s *Service) find(id string) (*Playlist, error) {
	p, ok := s.playlists[id]
	if !ok || p.DeleteAtMs != nil {
		return nil, errNotFound()
	}
	return p, nil
}
