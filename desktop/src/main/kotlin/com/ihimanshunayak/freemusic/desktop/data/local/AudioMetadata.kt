// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - reading tags off local files.
//
// NAME
//     AudioMetadata.kt - ID3v2, Vorbis Comment, MP4 atom and embedded picture reader.
//
// DESCRIPTION
//     A library scan that only reads filenames is a worse experience than the
//     same music in any other player, because the file a person downloads is
//     called `Artist - Title.mp3` at best and `01 track.mp3` at worst. The tags
//     inside the file are what is actually correct.
//
//     Three container families carry almost all music, and each stores tags in
//     a different place and format:
//
//       ID3v2   MP3 (and AIFF): a header block before the audio, with frames.
//               Text frames carry an encoding byte that decides the charset.
//       Vorbis  FLAC and Ogg: key=value pairs at the start of a metadata block,
//               always UTF-8, plus a BASE64 picture block.
//       MP4     M4A and AAC: a nested atom tree under `moov.udta.meta.ilst`,
//               with numbers that are sometimes big-endian and sometimes not.
//
//     All three are parsed here rather than pulling in a tagging library,
//     because the same parsers are needed for the *download* path where a file
//     has just been written and its tags have to be verified, and because a
//     library that only reads is a small fraction of what a tagging dependency
//     would bring in.
//
// RESPONSIBILITIES
//     - Read title, artist, album, album artist, track number, year and genre.
//     - Extract embedded cover art and embedded lyrics.
//     - Report whether a file carries any tags at all.
//     - Read the audio duration where the container states it cheaply.
//
// DEPENDENCIES
//     None beyond the standard library.
//
// INTEGRATION NOTES
//     - Every reader is bounded: a malformed file must not be able to make the
//       scanner allocate an unbounded array or loop forever. Sizes come from the
//       file itself, so each one is clamped and each loop is capped by the
//       bytes actually available.
//     - A file that fails to parse returns null fields rather than throwing, and
//       the caller falls back to the filename.

package com.ihimanshunayak.freemusic.desktop.data.local

import com.ihimanshunayak.freemusic.desktop.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** What a file's tags say about it, with nulls where the file was silent. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSeconds: Int? = null,
    /** Raw picture bytes, ready to write to a cache file. */
    val coverBytes: ByteArray? = null,
    val coverMime: String? = null,
    val lyrics: String? = null,
) {
    /** Whether anything useful was found; drives the read-tags-once decision. */
    val hasAnything: Boolean
        get() = title != null || artist != null || album != null || coverBytes != null

    // ByteArray in a data class needs its own equality, or two identical reads
    // compare unequal and defeat any caching that relies on value equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTags) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            year == other.year &&
            genre == other.genre &&
            durationSeconds == other.durationSeconds &&
            coverMime == other.coverMime &&
            lyrics == other.lyrics &&
            (coverBytes?.contentEquals(other.coverBytes ?: ByteArray(0)) ?: (other.coverBytes == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Reads tags from a local audio file.
 *
 * Dispatch is on the file's *content*, not its extension: a `.m4a` that is
 * really an ID3-tagged AAC file is common, and reading it as MP4 would return
 * nothing at all.
 */
object AudioMetadataReader {

    /** Bounds on what will be read, in bytes. */
    private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
    private const val MAX_TEXT_FIELD = 4 * 1024
    private const val MAX_ID3_TAG_BYTES = 16L * 1024 * 1024

    /**
     * Reads [file]'s tags, or nulls when the container is unsupported.
     *
     * @param wantCover skip cover extraction when false, which is what the
     *   scanner does for a folder it has already indexed - the picture is the
     *   expensive part of a scan.
     */
    fun read(file: File, wantCover: Boolean = true): AudioTags? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { handle ->
                when (detect(handle)) {
                    Container.ID3 -> readId3(handle, wantCover)
                    Container.FLAC -> readFlac(handle, wantCover)
                    Container.OGG -> readOgg(handle, wantCover)
                    Container.MP4 -> readMp4(handle, wantCover)
                    Container.UNKNOWN -> null
                }
            }
        }.onFailure { Log.d("tag read failed for ${file.name}: ${it.message}", tag = "metadata") }.getOrNull()
    }

    /** The container families the reader understands. */
    private enum class Container { ID3, FLAC, OGG, MP4, UNKNOWN }

    /**
     * Identifies the container from its magic bytes.
     *
     * MP4 is checked by looking for an `ftyp` box in the first few atoms rather
     * than only at offset 4, because some encoders put a `free` or `wide` box
     * first and would otherwise be misidentified.
     */
    private fun detect(handle: RandomAccessFile): Container {
        if (handle.length() < 12) return Container.UNKNOWN
        val head = ByteArray(12)
        handle.seek(0)
        if (handle.read(head) < 12) return Container.UNKNOWN

        return when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                Container.ID3
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> Container.FLAC
            head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
                head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte() -> Container.OGG
            // "ftyp" at byte 4 is the canonical MP4 signature.
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> Container.MP4
            // A leading free/wide/skip box: look one atom in.
            else -> {
                val firstSize = readU32Be(head, 0).toLong()
                if (firstSize in 8..64 && handle.length() > firstSize + 8) {
                    val probe = ByteArray(8)
                    handle.seek(firstSize)
                    if (handle.read(probe) == 8 &&
                        probe[4] == 'f'.code.toByte() && probe[5] == 't'.code.toByte() &&
                        probe[6] == 'y'.code.toByte() && probe[7] == 'p'.code.toByte()
                    ) {
                        Container.MP4
                    } else {
                        Container.UNKNOWN
                    }
                } else {
                    Container.UNKNOWN
                }
            }
        }
    }

    // ------------------------------------------------------------------ ID3v2

    /**
     * Reads an ID3v2 tag.
     *
     * The header's size field uses syncsafe integers - seven bits per byte - so
     * that a tag never contains a byte pair that looks like an MPEG frame sync.
     * Reading it as a plain integer is a classic bug that silently truncates
     * every tag longer than 127 bytes.
     */
    private fun readId3(handle: RandomAccessFile, wantCover: Boolean): AudioTags {
        handle.seek(0)
        val header = ByteArray(10)
        if (handle.read(header) < 10) return AudioTags()

        val version = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val size = readSyncSafe(header, 6).toLong().coerceAtMost(MAX_ID3_TAG_BYTES)

        var offset = 10L
        // An extended header sits between the header and the first frame and
        // states its own size; skipping past it is required, not optional.
        if (flags and 0x40 != 0 && version >= 3) {
            handle.seek(offset)
            val extended = ByteArray(if (version == 3) 4 else 6)
            if (handle.read(extended) > 0) {
                val declared = if (version == 3) readU32Be(extended, 0).toLong() else readSyncSafe(extended, 0).toLong()
                offset += declared + if (version == 3) 4L else 6L
            }
        }

        val end = (10 + size).coerceAtMost(handle.length())
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var track: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var genre: String? = null
        var lyrics: String? = null
        var cover: ByteArray? = null
        var coverMime: String? = null

        val frameHeaderSize = if (version == 2) 6 else 10

        while (offset + frameHeaderSize <= end) {
            handle.seek(offset)
            val frameHeader = ByteArray(frameHeaderSize)
            if (handle.read(frameHeader) < frameHeaderSize) break

            val id = if (version == 2) {
                String(frameHeader, 0, 3, StandardCharsets.ISO_8859_1)
            } else {
                String(frameHeader, 0, 4, StandardCharsets.ISO_8859_1)
            }
            if (id.all { it == '\u0000' }) break

            val frameSize = if (version == 2) {
                ((frameHeader[3].toInt() and 0xFF) shl 16) or
                    ((frameHeader[4].toInt() and 0xFF) shl 8) or
                    (frameHeader[5].toInt() and 0xFF)
            } else if (version == 4) {
                readSyncSafe(frameHeader, 4)
            } else {
                readU32Be(frameHeader, 4)
            }

            if (frameSize <= 0 || offset + frameHeaderSize + frameSize > end) break

            // A 2-byte encoding/language/NUL header precedes the text in v3/v4.
            val textOffset = if (version == 2) 1 else 1
            val payloadOffset = offset + frameHeaderSize
            val payload = ByteArray(frameSize.coerceAtMost(MAX_TEXT_FIELD * 4))
            handle.seek(payloadOffset + textOffset)
            val read = handle.read(payload, 0, (frameSize - textOffset).coerceAtMost(payload.size))

            if (read > 0) {
                when (id) {
                    "TIT2", "TT2" -> title = decodeId3Text(payload, read)
                    "TPE1", "TP1" -> artist = decodeId3Text(payload, read)
                    "TALB", "TAL" -> album = decodeId3Text(payload, read)
                    "TPE2", "TP2" -> albumArtist = decodeId3Text(payload, read)
                    "TRCK", "TRK" -> track = decodeId3Text(payload, read)?.substringBefore('/')?.trim()?.toIntOrNull()
                    "TPOS", "TPA" -> disc = decodeId3Text(payload, read)?.substringBefore('/')?.trim()?.toIntOrNull()
                    "TDRC", "TYER", "TYE" -> year = decodeId3Text(payload, read)?.take(4)?.toIntOrNull()
                    "TCON", "TCO" -> genre = decodeId3Text(payload, read)
                    "USLT", "ULT" -> lyrics = decodeId3Lyrics(payload, read)
                    "APIC", "PIC" -> if (wantCover && cover == null) {
                        val picture = decodeId3Picture(handle, payloadOffset, frameSize)
                        if (picture != null) {
                            cover = picture.first
                            coverMime = picture.second
                        }
                    }
                }
            }

            offset = payloadOffset + frameSize
        }

        return AudioTags(
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            albumArtist = albumArtist?.takeIf { it.isNotBlank() },
            trackNumber = track,
            discNumber = disc,
            year = year,
            genre = genre?.takeIf { it.isNotBlank() },
            coverBytes = cover,
            coverMime = coverMime,
            lyrics = lyrics?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The `APIC` frame: an encoding byte, a MIME type, a picture type, a
     * description terminator, then the image.
     */
    private fun decodeId3Picture(
        handle: RandomAccessFile,
        payloadOffset: Long,
        frameSize: Int,
    ): Pair<ByteArray, String>? {
        handle.seek(payloadOffset)
        val buffer = ByteArray(frameSize.coerceAtMost(MAX_TEXT_FIELD + 64))
        val read = handle.read(buffer, 0, buffer.size)
        if (read < 4) return null
        val encoding = buffer[0].toInt()

        var index = 1
        val mimeStart = index
        while (index < read && buffer[index] != 0.toByte()) index++
        val mime = String(buffer, mimeStart, index - mimeStart, StandardCharsets.ISO_8859_1)
        index++ // the NUL
        index++ // the picture type byte

        // The description is terminated by the encoding's own NUL width.
        val terminatorWidth = if (encoding == 1 || encoding == 2) 2 else 1
        while (index < read) {
            if (buffer[index] == 0.toByte()) {
                if (terminatorWidth == 1) {
                    index++
                    break
                }
                if (index + 1 < read && buffer[index + 1] == 0.toByte()) {
                    index += 2
                    break
                }
            }
            index++
        }

        val length = (read - index).coerceIn(0, MAX_ARTWORK_BYTES)
        if (length <= 0) return null

        handle.seek(payloadOffset + index)
        val image = ByteArray(length)
        val imageRead = handle.read(image, 0, length)
        if (imageRead <= 0) return null

        val resolvedMime = mime
            .takeIf { it.startsWith("image/") }
            ?: sniffMime(image)
        return image.copyOf(imageRead) to resolvedMime
    }

    /**
     * Decodes an ID3 text frame.
     *
     * The first byte is the encoding: 0 is Latin-1, 1 is UTF-16 with a byte order
     * mark, 2 is UTF-16 big-endian without one, and 3 is UTF-8. Reading the
     * wrong one produces mojibake that looks like a corrupt file.
     */
    private fun decodeId3Text(payload: ByteArray, length: Int): String? {
        if (length <= 1) return null
        val encoding = payload[0].toInt()
        val body = payload.copyOfRange(1, length)
        return runCatching {
            when (encoding) {
                0 -> String(body, StandardCharsets.ISO_8859_1)
                1 -> String(body, StandardCharsets.UTF_16)
                2 -> String(body, StandardCharsets.UTF_16BE)
                else -> String(body, StandardCharsets.UTF_8)
            }.trim('\u0000', ' ', '\uFEFF')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Decodes a `USLT` frame: encoding, language (3 bytes), description, then the text. */
    private fun decodeId3Lyrics(payload: ByteArray, length: Int): String? {
        if (length <= 5) return null
        val encoding = payload[0].toInt()
        var index = 4 // encoding + 3 language bytes
        val terminatorWidth = if (encoding == 1 || encoding == 2) 2 else 1
        while (index < length) {
            if (payload[index] == 0.toByte()) {
                if (terminatorWidth == 1) {
                    index++
                    break
                }
                if (index + 1 < length && payload[index + 1] == 0.toByte()) {
                    index += 2
                    break
                }
            }
            index++
        }
        if (index >= length) return null
        val body = payload.copyOfRange(index, length)
        return runCatching {
            when (encoding) {
                0 -> String(body, StandardCharsets.ISO_8859_1)
                1 -> String(body, StandardCharsets.UTF_16)
                2 -> String(body, StandardCharsets.UTF_16BE)
                else -> String(body, StandardCharsets.UTF_8)
            }.trim('\u0000', '\uFEFF')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------- FLAC

    /**
     * Reads FLAC's Vorbis Comment block and its picture block.
     *
     * FLAC is the easiest of the three: the metadata blocks are a linked list,
     * every comment is UTF-8 `KEY=value`, and the picture block states its own
     * MIME type, so no sniffing is needed.
     */
    private fun readFlac(handle: RandomAccessFile, wantCover: Boolean): AudioTags {
        handle.seek(4)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var track: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var genre: String? = null
        var lyrics: String? = null
        var cover: ByteArray? = null
        var coverMime: String? = null
        var duration: Int? = null

        var last = false
        var guard = 0
        while (!last && guard++ < 128) {
            val header = ByteArray(4)
            if (handle.read(header) < 4) break
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = readU24Be(header, 1)
            val blockStart = handle.filePointer
            last = isLast

            when (type) {
                // StreamInfo: sample rate at offset 10 (20 bits), total samples at 13 (36 bits).
                0 -> {
                    if (length >= 18) {
                        val info = ByteArray(18)
                        if (handle.read(info) == 18) {
                            val sampleRate = ((info[10].toInt() and 0xFF) shl 12) or
                                ((info[11].toInt() and 0xFF) shl 4) or
                                ((info[12].toInt() and 0xF0) shr 4)
                            val totalSamples = ((info[13].toLong() and 0x0F) shl 32) or
                                ((info[14].toLong() and 0xFF) shl 24) or
                                ((info[15].toLong() and 0xFF) shl 16) or
                                ((info[16].toLong() and 0xFF) shl 8) or
                                (info[17].toLong() and 0xFF)
                            if (sampleRate > 0 && totalSamples > 0) {
                                duration = (totalSamples / sampleRate).toInt()
                            }
                        }
                    }
                }
                // Vorbis Comment
                4 -> {
                    val bytes = readBlock(handle, length)
                    if (bytes != null) {
                        val comments = parseVorbisComments(bytes)
                        title = comments["TITLE"]
                        artist = comments["ARTIST"]
                        album = comments["ALBUM"]
                        albumArtist = comments["ALBUMARTIST"] ?: comments["ALBUM ARTIST"]
                        track = comments["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull()
                        disc = comments["DISCNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull()
                        year = comments["DATE"]?.take(4)?.toIntOrNull()
                        genre = comments["GENRE"]
                        lyrics = comments["LYRICS"] ?: comments["UNSYNCEDLYRICS"]
                    }
                }
                // Picture
                6 -> {
                    if (wantCover && cover == null) {
                        val bytes = readBlock(handle, length)
                        if (bytes != null) {
                            val picture = parseFlacPicture(bytes)
                            if (picture != null) {
                                cover = picture.first
                                coverMime = picture.second
                            }
                        }
                    }
                }
            }

            handle.seek(blockStart + length)
        }

        return AudioTags(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            trackNumber = track, discNumber = disc, year = year, genre = genre,
            durationSeconds = duration, coverBytes = cover, coverMime = coverMime, lyrics = lyrics,
        )
    }

    /** Reads exactly [length] bytes, refusing anything implausibly large. */
    private fun readBlock(handle: RandomAccessFile, length: Int): ByteArray? {
        if (length <= 0 || length > MAX_ID3_TAG_BYTES) return null
        val bytes = ByteArray(length)
        val read = handle.read(bytes, 0, length)
        return if (read <= 0) null else bytes.copyOf(read)
    }

    /**
     * Parses Vorbis Comments: a vendor string, a count, then `KEY=value` pairs.
     *
     * Keys are case-insensitive in practice - `ARTIST`, `Artist` and `artist` all
     * occur - so they are upper-cased on read.
     */
    private fun parseVorbisComments(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 8) return result

        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > buffer.remaining()) return result
        buffer.position(buffer.position() + vendorLength)
        if (buffer.remaining() < 4) return result

        val count = buffer.int
        if (count < 0) return result

        repeat(count.coerceAtMost(512)) {
            if (buffer.remaining() < 4) return result
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) return result
            val entry = ByteArray(length)
            buffer.get(entry)
            val text = String(entry, StandardCharsets.UTF_8)
            val equals = text.indexOf('=')
            if (equals > 0) {
                val key = text.substring(0, equals).uppercase()
                // The first occurrence wins: a file with two TITLE fields is
                // malformed, and the first is the one every other player shows.
                if (!result.containsKey(key)) result[key] = text.substring(equals + 1)
            }
        }
        return result
    }

    /**
     * Parses FLAC's PICTURE block: type, MIME length, MIME, description length,
     * description, then width/height/depth/colours and finally the image.
     */
    private fun parseFlacPicture(bytes: ByteArray): Pair<ByteArray, String>? {
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 8) return null

        buffer.int // picture type
        val mimeLength = buffer.int
        if (mimeLength < 0 || mimeLength > buffer.remaining()) return null
        val mimeBytes = ByteArray(mimeLength)
        buffer.get(mimeBytes)
        val mime = String(mimeBytes, StandardCharsets.US_ASCII)

        val descriptionLength = buffer.int
        if (descriptionLength < 0 || descriptionLength > buffer.remaining()) return null
        buffer.position(buffer.position() + descriptionLength)

        if (buffer.remaining() < 20) return null
        buffer.int; buffer.int; buffer.int; buffer.int // width, height, depth, colours
        val imageLength = buffer.int
        if (imageLength <= 0 || imageLength > buffer.remaining()) return null
        if (imageLength > MAX_ARTWORK_BYTES) return null

        val image = ByteArray(imageLength)
        buffer.get(image)
        return image to mime.ifBlank { sniffMime(image) }
    }

    // -------------------------------------------------------------------- Ogg

    /**
     * Reads an Ogg Vorbis or Opus comment header.
     *
     * The comments live in the second page, so the pages have to be walked: the
     * first is the identification header, the second is the comment header. A
     * page's header is 27 bytes plus a segment table whose length is stated in
     * its 26th byte.
     */
    private fun readOgg(handle: RandomAccessFile, wantCover: Boolean): AudioTags {
        handle.seek(0)
        repeat(4) { page ->
            val header = ByteArray(27)
            if (handle.read(header) < 27) return AudioTags()
            if (String(header, 0, 4, StandardCharsets.US_ASCII) != "OggS") return AudioTags()

            val segmentCount = header[26].toInt() and 0xFF
            val segments = ByteArray(segmentCount)
            if (handle.read(segments) < segmentCount) return AudioTags()
            val bodyLength = segments.sumOf { it.toInt() and 0xFF }

            if (page == 1) {
                val body = ByteArray(bodyLength.coerceAtMost(MAX_ID3_TAG_BYTES.toInt()))
                val read = handle.read(body, 0, body.size)
                if (read <= 0) return AudioTags()

                // The comment header starts with a signature and a framing byte.
                val offset = when {
                    body.size > 7 && String(body, 0, 7, StandardCharsets.US_ASCII) == "\u0003vorbis" -> 7
                    body.size > 8 && String(body, 0, 8, StandardCharsets.US_ASCII) == "OpusTags" -> 8
                    else -> return AudioTags()
                }

                val comments = parseVorbisComments(body.copyOfRange(offset, read))
                return AudioTags(
                    title = comments["TITLE"],
                    artist = comments["ARTIST"],
                    album = comments["ALBUM"],
                    albumArtist = comments["ALBUMARTIST"],
                    trackNumber = comments["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
                    discNumber = comments["DISCNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
                    year = comments["DATE"]?.take(4)?.toIntOrNull(),
                    genre = comments["GENRE"],
                    lyrics = comments["LYRICS"] ?: comments["UNSYNCEDLYRICS"],
                    coverBytes = if (wantCover) comments["METADATA_BLOCK_PICTURE"]?.let { decodeBase64Picture(it) } else null,
                )
            }

            handle.seek(handle.filePointer + bodyLength)
        }
        return AudioTags()
    }

    /** Vorbis stores its picture BASE64-encoded in a comment; the payload is a FLAC PICTURE block. */
    private fun decodeBase64Picture(value: String): ByteArray? = runCatching {
        val decoded = java.util.Base64.getDecoder().decode(value.trim())
        parseFlacPicture(decoded)?.first
    }.getOrNull()

    // -------------------------------------------------------------------- MP4

    /**
     * Reads the MP4 atom tree for tags.
     *
     * Only the `moov.udta.meta.ilst` path is walked, and only for the handful of
     * atoms that hold music metadata. A full box parser would be more code than
     * this needs: the tree is shallow, and any atom not on the path can be
     * skipped wholesale using its own size field.
     */
    private fun readMp4(handle: RandomAccessFile, wantCover: Boolean): AudioTags {
        val ilst = findAtom(handle, 0L, handle.length(), listOf("moov", "udta", "meta", "ilst"), 0)
            ?: return AudioTags()

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var track: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var genre: String? = null
        var lyrics: String? = null
        var cover: ByteArray? = null
        var coverMime: String? = null

        val (start, end) = ilst
        var offset = start
        while (offset + 8 <= end) {
            handle.seek(offset)
            val header = ByteArray(8)
            if (handle.read(header) < 8) break
            val size = readU32Be(header, 0).toLong()
            if (size < 8 || offset + size > end) break
            val name = String(header, 4, 4, StandardCharsets.ISO_8859_1)

            // Each item contains a `data` box which states its own type.
            val data = findAtom(handle, offset + 8, offset + size, listOf("data"), 0)
            if (data != null) {
                handle.seek(data.first + 8)
                if (data.second - data.first >= 16) {
                    val typeHeader = ByteArray(8)
                    if (handle.read(typeHeader) == 8) {
                        val dataType = readU32Be(typeHeader, 0)
                        val payloadLength = (data.second - data.first - 16).toInt()
                        when (name) {
                            "\u00A9nam" -> title = readMp4Text(handle, payloadLength)
                            "\u00A9ART" -> artist = readMp4Text(handle, payloadLength)
                            "\u00A9alb" -> album = readMp4Text(handle, payloadLength)
                            "aART" -> albumArtist = readMp4Text(handle, payloadLength)
                            "\u00A9day" -> year = readMp4Text(handle, payloadLength)?.take(4)?.toIntOrNull()
                            "\u00A9gen" -> genre = readMp4Text(handle, payloadLength)
                            "\u00A9lyr" -> lyrics = readMp4Text(handle, payloadLength, MAX_ID3_TAG_BYTES.toInt())
                            "trkn" -> track = readMp4Number(handle, payloadLength)
                            "disk" -> disc = readMp4Number(handle, payloadLength)
                            "covr" -> if (wantCover && cover == null && payloadLength in 1..MAX_ARTWORK_BYTES) {
                                val image = ByteArray(payloadLength)
                                val read = handle.read(image, 0, payloadLength)
                                if (read > 0) {
                                    cover = image.copyOf(read)
                                    // The data type distinguishes JPEG (13) from PNG (14);
                                    // anything else is sniffed so a WebP cover still works.
                                    coverMime = when (dataType) {
                                        13 -> "image/jpeg"
                                        14 -> "image/png"
                                        else -> sniffMime(image)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            offset += size
        }

        val duration = readMp4Duration(handle)

        return AudioTags(
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            trackNumber = track, discNumber = disc, year = year, genre = genre,
            durationSeconds = duration, coverBytes = cover, coverMime = coverMime, lyrics = lyrics,
        )
    }

    /**
     * Finds a nested atom path and returns its content range.
     *
     * `meta` is a full box: it carries a version and flags byte before its
     * children, which is why its content starts 4 bytes later than the header.
     * Getting this wrong is the classic reason an MP4 tag reader finds nothing.
     */
    private fun findAtom(
        handle: RandomAccessFile,
        start: Long,
        end: Long,
        path: List<String>,
        depth: Int,
    ): Pair<Long, Long>? {
        if (path.isEmpty() || depth > 8) return null
        val wanted = path.first()
        var offset = start

        while (offset + 8 <= end) {
            handle.seek(offset)
            val header = ByteArray(8)
            if (handle.read(header) < 8) return null

            var size = readU32Be(header, 0).toLong()
            val name = String(header, 4, 4, StandardCharsets.ISO_8859_1)
            var contentStart = offset + 8

            // A 64-bit `largesize` follows the type when size is 1.
            if (size == 1L) {
                val extended = ByteArray(8)
                if (handle.read(extended) < 8) return null
                size = readU64Be(extended, 0)
                contentStart += 8
            } else if (size == 0L) {
                // size 0 means "to the end of the file".
                size = end - offset
            }

            if (size < 8 || offset + size > end) return null

            if (name == wanted) {
                val isMeta = name == "meta"
                val adjustedStart = if (isMeta) contentStart + 4 else contentStart
                return if (path.size == 1) {
                    adjustedStart to (offset + size)
                } else {
                    findAtom(handle, adjustedStart, offset + size, path.drop(1), depth + 1)
                }
            }

            offset += size
        }
        return null
    }

    /** Reads a UTF-8 text payload. */
    private fun readMp4Text(handle: RandomAccessFile, length: Int, cap: Int = MAX_TEXT_FIELD): String? {
        if (length <= 0) return null
        val bytes = ByteArray(length.coerceAtMost(cap))
        val read = handle.read(bytes, 0, bytes.size)
        if (read <= 0) return null
        return String(bytes, 0, read, StandardCharsets.UTF_8).trim('\u0000').takeIf { it.isNotBlank() }
    }

    /**
     * Reads a numeric payload.
     *
     * `trkn` and `disk` are big-endian shorts preceded by two reserved bytes, so
     * the value is at offset 2 rather than at 0.
     */
    private fun readMp4Number(handle: RandomAccessFile, length: Int): Int? {
        if (length < 4) return null
        val bytes = ByteArray(length.coerceAtMost(16))
        val read = handle.read(bytes, 0, bytes.size)
        if (read < 4) return null
        val value = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return value.takeIf { it > 0 }
    }

    /**
     * Reads the duration out of `mvhd`.
     *
     * The header's version byte decides whether the timescale and duration are
     * 32-bit or 64-bit, and getting that wrong returns a nonsense duration
     * rather than failing, so the version is read rather than assumed.
     */
    private fun readMp4Duration(handle: RandomAccessFile): Int? {
        val mvhd = findAtom(handle, 0L, handle.length(), listOf("moov", "mvhd"), 0) ?: return null
        handle.seek(mvhd.first)
        val version = handle.read()
        if (version < 0) return null
        handle.skipBytes(3) // flags

        val buffer = if (version == 1) ByteArray(28) else ByteArray(16)
        if (handle.read(buffer) < buffer.size) return null

        return if (version == 1) {
            val timescale = readU32Be(buffer, 16).toLong()
            val duration = readU64Be(buffer, 20)
            if (timescale <= 0) null else (duration / timescale).toInt()
        } else {
            val timescale = readU32Be(buffer, 8).toLong()
            val duration = readU32Be(buffer, 12).toLong()
            if (timescale <= 0) null else (duration / timescale).toInt()
        }
    }

    // ------------------------------------------------------------------ shared

    /** ID3's syncsafe integer: seven significant bits per byte, high bit always clear. */
    private fun readSyncSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun readU32Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readU24Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun readU64Be(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    /**
     * Identifies an image by its magic bytes.
     *
     * Used when a container does not name the format, which happens with
     * hand-tagged files and with every WebP cover.
     */
    internal fun sniffMime(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 3, StandardCharsets.US_ASCII) == "GIF" -> "image/gif"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
        else -> "image/jpeg"
    }

    /** The file extension an image MIME type maps to, for the artwork cache. */
    internal fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }
}
