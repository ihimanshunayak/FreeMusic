package playlist

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
)

// FileStore persists the playlist set as a single JSON document.
//
// One document rather than one file per playlist, for the same reason
// PlaylistStore on Android is one document: the set is small, bounded by
// MaxPlaylists, and every mutation is already a whole-set operation. A
// per-playlist layout would add a directory-walk to Load and a partial-write
// failure mode to Save, and would buy nothing this service needs.
//
// Durable only if the path is on a volume that survives a restart. On a host
// with an ephemeral filesystem — Render's free tier, for instance — this is
// correct and useless, which is why Durable() reports what it can actually
// promise rather than what the type name suggests. See the audit, §0.3.
type FileStore struct {
	mu   sync.RWMutex
	path string
	// dirMode and fileMode are explicit so the document is not world-readable.
	// It holds playlist names and visitor display names.
	dirMode  fs.FileMode
	fileMode fs.FileMode
}

// NewFileStore prepares a store at path, creating the parent directory if it is
// missing. It does not read the file: Load does that, so a failure to read is
// reported to the caller rather than swallowed during construction.
func NewFileStore(path string) (*FileStore, error) {
	if path == "" {
		return nil, errors.New("playlist: store path is empty")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("playlist: create store directory: %w", err)
	}
	return &FileStore{path: path, dirMode: 0o700, fileMode: 0o600}, nil
}

// fileDocument is what actually lands on disk, versioned so a future change can
// tell what it is reading.
type fileDocument struct {
	Version   int         `json:"version"`
	SavedAtMs int64       `json:"savedAtMs"`
	Playlists []*Playlist `json:"playlists"`
}

const fileSchemaVersion = 1

// Load reads the document. A missing file is not an error — it is the normal
// state of a fresh deployment — but a corrupt one is, because silently starting
// empty would look exactly like "all my playlists vanished".
func (s *FileStore) Load() ([]*Playlist, error) {
	s.mu.RLock()
	path := s.path
	s.mu.RUnlock()

	raw, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("playlist: read store: %w", err)
	}
	if len(raw) == 0 {
		return nil, nil
	}

	var doc fileDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("playlist: store is not readable JSON: %w", err)
	}
	if doc.Version > fileSchemaVersion {
		return nil, fmt.Errorf(
			"playlist: store is version %d, this build understands %d",
			doc.Version, fileSchemaVersion,
		)
	}

	// Positions are re-derived rather than trusted: the document is the one
	// place a hand-edit or a half-applied upgrade could leave a gap, and every
	// reader downstream assumes dense ordering.
	for _, p := range doc.Playlists {
		if p == nil {
			continue
		}
		p.SortTracks()
	}
	return doc.Playlists, nil
}

// Save writes the whole set atomically.
//
// Written to a sibling temporary file and renamed over the target. rename is
// atomic within a filesystem, so a process killed mid-write leaves either the
// old document or the new one — never a truncated file that would fail to parse
// on the next start and take every playlist with it. This is the same technique
// PlaylistStore uses on Android, for the same reason.
func (s *FileStore) Save(all []*Playlist) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	doc := fileDocument{
		Version:   fileSchemaVersion,
		SavedAtMs: nowMs(),
		Playlists: all,
	}
	// MarshalIndent rather than Marshal: this file is small, and a store an
	// operator can read when something has gone wrong is worth the bytes.
	raw, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return fmt.Errorf("playlist: encode store: %w", err)
	}

	dir := filepath.Dir(s.path)
	tmp, err := os.CreateTemp(dir, ".playlists-*.tmp")
	if err != nil {
		return fmt.Errorf("playlist: create temporary file: %w", err)
	}
	tmpName := tmp.Name()
	// Best effort: if the rename succeeded the file is gone already, and if it
	// did not then the deferred cleanup is the only thing that removes it.
	defer func() { _ = os.Remove(tmpName) }()

	if err := tmp.Chmod(s.fileMode); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("playlist: set store permissions: %w", err)
	}
	if _, err := tmp.Write(raw); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("playlist: write store: %w", err)
	}
	// Flushed before the rename, or the rename can be durable while the contents
	// are not — which is the failure the rename was meant to prevent.
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("playlist: flush store: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("playlist: close store: %w", err)
	}
	if err := os.Rename(tmpName, s.path); err != nil {
		return fmt.Errorf("playlist: replace store: %w", err)
	}
	return nil
}

func (s *FileStore) Close() error { return nil }

// Durable reports true: a file is by definition outliving the process. Whether
// it outlives the *host* is the deployment's business, and cannot be determined
// from here.
func (s *FileStore) Durable() bool { return true }
