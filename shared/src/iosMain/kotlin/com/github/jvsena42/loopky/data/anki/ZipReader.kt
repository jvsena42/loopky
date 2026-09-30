package com.github.jvsena42.loopky.data.anki

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * The little of the ZIP format an `.apkg` needs, over the system zlib.
 *
 * iOS has no unzip API — `Compression.framework` does raw deflate streams, not zip containers — so
 * the container is parsed here and each entry inflated with zlib, which Kotlin/Native already
 * exposes as a platform library. No new dependency, matching the "no new dependencies" rule the
 * common [ApkgReader] states for the Android side.
 *
 * Deliberately partial. It reads the **central directory** (never the local headers, whose sizes
 * are unreliable when a streaming writer sets them to zero and defers to a data descriptor), and
 * supports the only two methods an `.apkg` uses: stored and deflate. Zip64, encryption and
 * multi-disk archives are refused rather than half-handled.
 */
internal object ZipReader {

    /** One file in the archive: where its bytes are, and how they are packed. */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    )

    private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50
    private const val CENTRAL_FILE_HEADER = 0x02014b50
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    /** Max bytes of trailing comment to scan back through looking for the EOCD record. */
    private const val MAX_COMMENT = 64 * 1024

    // Field offsets within each record, from the ZIP appnote. Named because they *are* the format:
    // a bare `cursor + 28` says nothing, and getting one wrong misreads every entry after it.
    private const val EOCD_ENTRY_COUNT = 10
    private const val EOCD_DIRECTORY_OFFSET = 16
    private const val EOCD_MIN_SIZE = 22

    private const val CENTRAL_HEADER_SIZE = 46
    private const val CENTRAL_METHOD = 10
    private const val CENTRAL_COMPRESSED_SIZE = 20
    private const val CENTRAL_UNCOMPRESSED_SIZE = 24
    private const val CENTRAL_NAME_LENGTH = 28
    private const val CENTRAL_EXTRA_LENGTH = 30
    private const val CENTRAL_COMMENT_LENGTH = 32
    private const val CENTRAL_LOCAL_OFFSET = 42

    private const val LOCAL_HEADER_SIZE = 30
    private const val LOCAL_NAME_LENGTH = 26
    private const val LOCAL_EXTRA_LENGTH = 28

    private const val ZIP_MAGIC_LENGTH = 4
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val RAW_DEFLATE_WINDOW_BITS = -15
    private const val Z_FINISH = 4
    private const val INFLATE_GUESS = 4

    fun isZip(header: ByteArray): Boolean =
        header.size >= ZIP_MAGIC_LENGTH &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())

    /** Every entry in the central directory, keyed by name in archive order. */
    fun entries(bytes: ByteArray): List<Entry> {
        val eocd = findEndOfCentralDirectory(bytes) ?: error("Not a zip file: no end-of-directory record")
        val count = bytes.u16(eocd + EOCD_ENTRY_COUNT)
        var cursor = bytes.u32(eocd + EOCD_DIRECTORY_OFFSET)

        val out = ArrayList<Entry>(count)
        repeat(count) {
            if (cursor + CENTRAL_HEADER_SIZE > bytes.size ||
                bytes.u32(cursor) != CENTRAL_FILE_HEADER
            ) {
                return out
            }
            val nameLength = bytes.u16(cursor + CENTRAL_NAME_LENGTH)
            val extraLength = bytes.u16(cursor + CENTRAL_EXTRA_LENGTH)
            val commentLength = bytes.u16(cursor + CENTRAL_COMMENT_LENGTH)
            out += Entry(
                name = bytes.decodeToString(
                    cursor + CENTRAL_HEADER_SIZE,
                    cursor + CENTRAL_HEADER_SIZE + nameLength,
                ),
                method = bytes.u16(cursor + CENTRAL_METHOD),
                compressedSize = bytes.u32(cursor + CENTRAL_COMPRESSED_SIZE),
                uncompressedSize = bytes.u32(cursor + CENTRAL_UNCOMPRESSED_SIZE),
                localHeaderOffset = bytes.u32(cursor + CENTRAL_LOCAL_OFFSET),
            )
            cursor += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        return out
    }

    /**
     * The entry's bytes.
     *
     * The payload starts after the *local* header, whose name and extra lengths are read here —
     * they can differ from the central directory's, which is why the offset cannot be precomputed.
     */
    fun read(bytes: ByteArray, entry: Entry): ByteArray {
        val local = entry.localHeaderOffset
        require(local + LOCAL_HEADER_SIZE <= bytes.size) { "Truncated zip: entry ${entry.name} is past the end" }
        val nameLength = bytes.u16(local + LOCAL_NAME_LENGTH)
        val extraLength = bytes.u16(local + LOCAL_EXTRA_LENGTH)
        val start = local + LOCAL_HEADER_SIZE + nameLength + extraLength
        val end = start + entry.compressedSize
        require(end <= bytes.size) { "Truncated zip: entry ${entry.name} is past the end" }

        val payload = bytes.copyOfRange(start, end)
        return when (entry.method) {
            METHOD_STORED -> payload
            METHOD_DEFLATE -> inflateRaw(payload, entry.uncompressedSize)
            else -> error("Unsupported zip compression method ${entry.method} for ${entry.name}")
        }
    }

    /**
     * Raw deflate (no zlib or gzip wrapper), which is what a zip entry holds — hence the negative
     * window bits.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun inflateRaw(source: ByteArray, expectedSize: Int): ByteArray {
        if (source.isEmpty()) return ByteArray(0)
        // The central directory's size is trusted for allocation but not for correctness: a zero
        // there (a streamed archive) still has to inflate, so fall back to a generous guess.
        val capacity = if (expectedSize > 0) expectedSize else source.size * INFLATE_GUESS + 1024
        val out = ByteArray(capacity)

        val written = source.usePinned { input ->
            out.usePinned { output ->
                inflateInto(input.addressOf(0), source.size, output.addressOf(0), capacity)
            }
        }
        return if (written == capacity) out else out.copyOf(written)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun inflateInto(
        input: CPointer<ByteVar>,
        inputSize: Int,
        output: CPointer<ByteVar>,
        outputSize: Int,
    ): Int = memScoped {
        val stream = alloc<z_stream>()
        stream.next_in = input.reinterpret()
        stream.avail_in = inputSize.convert()
        stream.next_out = output.reinterpret()
        stream.avail_out = outputSize.convert()

        // -15: raw deflate, no header — a zip entry carries none.
        check(inflateInit2(stream.ptr, RAW_DEFLATE_WINDOW_BITS) == Z_OK) { "zlib refused to start" }
        try {
            val status = inflate(stream.ptr, Z_FINISH)
            check(status == Z_STREAM_END || status == Z_OK) { "Corrupt zip entry (zlib $status)" }
            outputSize - stream.avail_out.toInt()
        } finally {
            inflateEnd(stream.ptr)
        }
    }

    /** Scan back from the end for the end-of-central-directory signature. */
    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        val earliest = maxOf(0, bytes.size - MAX_COMMENT - EOCD_MIN_SIZE)
        for (i in bytes.size - EOCD_MIN_SIZE downTo earliest) {
            if (bytes.u32(i) == END_OF_CENTRAL_DIRECTORY) return i
        }
        return null
    }

    /** Zip integers are little-endian and unsigned. */
    private fun ByteArray.u16(at: Int): Int = littleEndian(at, Short.SIZE_BYTES)

    private fun ByteArray.u32(at: Int): Int = littleEndian(at, Int.SIZE_BYTES)

    /** [width] bytes at [at], least significant first — how every integer in a zip is written. */
    private fun ByteArray.littleEndian(at: Int, width: Int): Int =
        (0 until width).fold(0) { acc, offset ->
            acc or ((this[at + offset].toInt() and BYTE_MASK) shl (offset * BITS_PER_BYTE))
        }
}
