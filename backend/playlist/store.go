package playlist

import (
	"sync"
)

// Store is the only thing the service layer knows about persistence.
//
// The brief asked for exactly this: business logic must not be wired to SQL, so
// that a durable backend can be swapped in without touching the rules. The two
// implementations shipped here are MemoryStore, which works anywhere including a
// host with no disk, and FileStore, which is durable when a volume is actually
// mounted. See docs/COLLABORATIVE_PLAYLISTS_AND_BLENDS.md §0.3 for why that
// choice is not cosmetic.
type Store interface {
	// Load returns a deep copy of every playlist. The copy matters: callers
	// mutate what they read, and a shared pointer would let one request's
	// uncommitted edit leak into another's read.
	Load() ([]*Playlist, error)

	// Save persists the full set, replacing whatever was there.
	//
	// Whole-set rather than per-record because the set is small (bounded by
	// MaxPlaylists), every mutation already happens under the service's lock, and
	// a per-record API would have to answer questions about partial writes that
	// nothing here needs answered.
	Save(all []*Playlist) error

	// Close releases whatever the store holds. Called on shutdown.
	Close() error

	// Durable reports whether Save survives a restart. Surfaced so the service
	// can warn at startup rather than after somebody loses a playlist.
	Durable() bool
}

// MemoryStore keeps everything in process memory.
//
// This is a real implementation, not a test double: it is the correct choice on
// a host with no persistent disk, where a "durable" store would be durable only
// until the next deploy. Losing playlists on restart is a property of the
// deployment, and this store does not pretend otherwise — it reports
// Durable() == false so the service can say so out loud.
type MemoryStore struct {
	mu   sync.RWMutex
	held []*Playlist
}

func NewMemoryStore() *MemoryStore { return &MemoryStore{} }

func (s *MemoryStore) Load() ([]*Playlist, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return cloneAll(s.held), nil
}

func (s *MemoryStore) Save(all []*Playlist) error {
	// Cloned on the way in for the same reason Load clones on the way out: the
	// caller keeps mutating its own copy, and the store must not observe those
	// edits until the next Save.
	s.mu.Lock()
	defer s.mu.Unlock()
	s.held = cloneAll(all)
	return nil
}

func (s *MemoryStore) Close() error { return nil }

func (s *MemoryStore) Durable() bool { return false }

// cloneAll deep-copies a playlist set.
//
// Hand-written rather than reflection or a JSON round trip because both of those
// would silently copy the unexported-by-tag fields too — Invites and History are
// `json:"-"` on purpose, and a copy that dropped them would corrupt the store
// the moment anything read it back.
func cloneAll(src []*Playlist) []*Playlist {
	if src == nil {
		return nil
	}
	out := make([]*Playlist, 0, len(src))
	for _, p := range src {
		out = append(out, p.Clone())
	}
	return out
}

// Clone returns a deep copy of the playlist, including its invites and history.
func (p *Playlist) Clone() *Playlist {
	if p == nil {
		return nil
	}
	cp := *p

	cp.Members = make([]Member, len(p.Members))
	copy(cp.Members, p.Members)
	// Member has a pointer field, so each one is copied individually.
	for i := range cp.Members {
		if p.Members[i].AvatarUrl != nil {
			url := *p.Members[i].AvatarUrl
			cp.Members[i].AvatarUrl = &url
		}
	}

	cp.Tracks = make([]Track, len(p.Tracks))
	copy(cp.Tracks, p.Tracks)
	for i := range cp.Tracks {
		if p.Tracks[i].Album != nil {
			album := *p.Tracks[i].Album
			cp.Tracks[i].Album = &album
		}
		if p.Tracks[i].ThumbnailUrl != nil {
			url := *p.Tracks[i].ThumbnailUrl
			cp.Tracks[i].ThumbnailUrl = &url
		}
		if p.Tracks[i].DurationMs != nil {
			d := *p.Tracks[i].DurationMs
			cp.Tracks[i].DurationMs = &d
		}
	}

	cp.Invites = make([]Invite, len(p.Invites))
	copy(cp.Invites, p.Invites)
	for i := range cp.Invites {
		if p.Invites[i].RevokedAtMs != nil {
			at := *p.Invites[i].RevokedAtMs
			cp.Invites[i].RevokedAtMs = &at
		}
	}

	cp.History = make([]Revision, len(p.History))
	copy(cp.History, p.History)
	for i := range cp.History {
		if p.History[i].Payload != nil {
			payload := make(map[string]interface{}, len(p.History[i].Payload))
			for k, v := range p.History[i].Payload {
				payload[k] = v
			}
			cp.History[i].Payload = payload
		}
	}

	if p.DeleteAtMs != nil {
		at := *p.DeleteAtMs
		cp.DeleteAtMs = &at
	}
	if p.CoverUrl != nil {
		url := *p.CoverUrl
		cp.CoverUrl = &url
	}
	if p.Blend != nil {
		blend := *p.Blend
		if p.Blend.Compatibility != nil {
			blend.Compatibility = make(map[string]float64, len(p.Blend.Compatibility))
			for k, v := range p.Blend.Compatibility {
				blend.Compatibility[k] = v
			}
		}
		cp.Blend = &blend
	}

	return &cp
}
