package playlist

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
)

// Sizes of the three opaque values this package mints.
//
// 32 bytes is the standard for a value that has to be unguessable rather than
// merely unique — the same width as a session key. A shorter token would still
// be "random", but shortening it is the kind of change that looks harmless and
// is not, so the width is stated once here and never inline.
const (
	TokenBytes = 32
	// IdBytes is smaller because a playlist id is not a secret: it is shared in
	// URLs and log lines. It only has to not collide.
	IdBytes = 12
)

// NewToken returns a new secret, hex-encoded, and the SHA-256 of it. The caller
// returns the first to the client once and stores the second.
//
// The token is never stored, logged, or derived from anything. A leak of the
// store therefore yields hashes, which cannot be replayed as credentials.
func NewToken() (token string, hash string) {
	raw := make([]byte, TokenBytes)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand failing is a broken platform, not a recoverable condition
		// — every security decision below depends on this being unpredictable.
		panic("playlist: crypto/rand unavailable: " + err.Error())
	}
	token = hex.EncodeToString(raw)
	return token, HashToken(token)
}

// HashToken hashes a presented token for comparison against a stored hash.
//
// SHA-256 without a salt or a work factor is correct here and would be wrong for
// a password: a token is 32 bytes of platform randomness, so there is no
// dictionary to search and nothing for a slow hash to buy.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// TokenMatches reports whether a presented token hashes to a stored hash, in
// constant time.
//
// subtle.ConstantTimeCompare rather than == because a byte-by-byte comparison
// leaks how much of a guess was correct, which is enough to recover a token one
// byte at a time given enough attempts. party.go already uses this primitive for
// its own tokens; doing less here would be the odd one out.
func TokenMatches(token, storedHash string) bool {
	if token == "" || storedHash == "" {
		return false
	}
	computed := HashToken(token)
	return subtle.ConstantTimeCompare([]byte(computed), []byte(storedHash)) == 1
}

// NewPlaylistId returns a new playlist id. Not a secret — see IdBytes.
func NewPlaylistId() string {
	return "pl_" + randomHex(IdBytes)
}

// NewEntryId returns a new track-entry id. Not a secret: two entries of the same
// song must be distinguishable, which a counter within a playlist could also do,
// but this survives an entry being copied between playlists during a merge.
func NewEntryId() string {
	return "tr_" + randomHex(IdBytes)
}

// randomHex returns n random bytes as 2n hex characters.
func randomHex(n int) string {
	raw := make([]byte, n)
	if _, err := rand.Read(raw); err != nil {
		panic("playlist: crypto/rand unavailable: " + err.Error())
	}
	return hex.EncodeToString(raw)
}

// pairKey is the map key for a compatibility score between two users.
//
// The two ids are sorted so that (A,B) and (B,A) produce one key: a score is a
// property of the pair, and a lookup that missed because the arguments arrived
// in the other order would be a bug that only shows for one of the two people
// looking at it.
func pairKey(a, b string) string {
	if a > b {
		a, b = b, a
	}
	return a + "|" + b
}
