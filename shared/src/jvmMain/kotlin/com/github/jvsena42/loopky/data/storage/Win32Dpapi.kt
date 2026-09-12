package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.util.Log
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * `CryptProtectData` / `CryptUnprotectData`, called directly (#301).
 *
 * **[Function.getFunction] rather than a mapped `Library` interface**, for the reason every other
 * native call in this project gives: an interface is a dynamic proxy `native-image` has to be told
 * about, and the metadata here is hand-curated. Two functions do not justify a registration.
 *
 * **`DATA_BLOB` is laid out by hand rather than as a `Structure` subclass**, for the sharper half of
 * the same reason. A `Structure` is reconstructed reflectively by JNA, needs its own reachability
 * entry, and is the exact shape that has twice made this build emit a second file beside the binary
 * (Architecture.md §13.11). It is two fields — `{DWORD cbData; BYTE *pbData}` — so the layout is
 * four bytes, the platform's pointer alignment, then the pointer.
 *
 * **User scope, which is the entire security property.** `CRYPTPROTECT_LOCAL_MACHINE` is
 * deliberately not passed: it would make the blob decryptable by *every* account on the machine,
 * which is worse than the file store it replaces rather than better. `CRYPTPROTECT_UI_FORBIDDEN` is
 * passed because this runs headless — without it a blob protected under a prompting policy can block
 * on a dialog nobody is there to answer, which is the failure an agent can least recover from.
 */
internal object Win32Dpapi : DpapiCrypto {

    override fun protect(plaintext: ByteArray): ByteArray? = call("CryptProtectData", plaintext)

    override fun unprotect(ciphertext: ByteArray): ByteArray? = call("CryptUnprotectData", ciphertext)

    /**
     * Both calls have the same shape, differing only in direction and in one unused parameter.
     *
     * `CryptProtectData(pDataIn, szDataDescr, pOptionalEntropy, pvReserved, pPromptStruct, dwFlags,
     * pDataOut)`; the unprotect twin takes a `ppszDataDescr` out-parameter in the second slot, which
     * is left null because nothing here reads the description back.
     */
    @Suppress("ReturnCount")
    private fun call(symbol: String, input: ByteArray): ByteArray? = runCatching {
        val inputData = Memory(maxOf(input.size, 1).toLong()).apply { write(0, input, 0, input.size) }
        val entropyData = Memory(ENTROPY.size.toLong()).apply { write(0, ENTROPY, 0, ENTROPY.size) }
        val inputBlob = blob(input.size, inputData)
        val entropyBlob = blob(ENTROPY.size, entropyData)
        val outputBlob = Memory(BLOB_SIZE.toLong()).apply { clear() }

        val ok = Function.getFunction(CRYPT32, symbol).invokeInt(
            arrayOf<Any>(
                inputBlob,
                Pointer.NULL,
                entropyBlob,
                Pointer.NULL,
                Pointer.NULL,
                CRYPTPROTECT_UI_FORBIDDEN,
                outputBlob,
            ),
        )
        if (ok == 0) return null

        val length = outputBlob.getInt(0)
        val data = outputBlob.getPointer(POINTER_OFFSET.toLong()) ?: return null
        try {
            // Guard the length before trusting it: a zero-length result is a valid shape for neither
            // direction here, and `getByteArray` on a negative would throw out of a `runCatching`
            // that is meant to answer null.
            if (length <= 0) return null
            data.getByteArray(0, length)
        } finally {
            // **`LocalFree`, and it is not optional.** This buffer is `LocalAlloc`ed by Windows, not
            // by JNA — every `Memory` above is freed by the JVM, and this one is not owned by it. A
            // process that runs one command and exits would not notice; a long-lived one reading the
            // session on every call would leak the session's length each time.
            Function.getFunction(KERNEL32, "LocalFree").invokePointer(arrayOf<Any>(data))
        }
    }.getOrElse {
        Log.d(TAG, "$symbol failed: ${it.message}")
        null
    }

    /** One `DATA_BLOB`: the length at offset 0, the pointer at the platform's pointer offset. */
    private fun blob(length: Int, data: Pointer): Memory = Memory(BLOB_SIZE.toLong()).apply {
        clear()
        setInt(0, length)
        setPointer(POINTER_OFFSET.toLong(), data)
    }

    private const val TAG = "Loopky/Dpapi"
    private const val CRYPT32 = "crypt32"
    private const val KERNEL32 = "kernel32"

    /** Never prompt: there is nobody at this terminal to answer a dialog. */
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    /**
     * `{DWORD cbData; BYTE *pbData}`, aligned to the pointer. 16 bytes on x64, 8 on x86 — computed
     * rather than hard-coded, because a wrong offset here reads a pointer out of padding and the
     * failure is a segfault rather than a bad answer.
     */
    private val POINTER_OFFSET = Native.POINTER_SIZE
    private val BLOB_SIZE = POINTER_OFFSET * 2

    /**
     * Fixed, compiled in, and **not a secret** — see [DpapiSecureItem] for the honest description.
     * Its only job is to make this blob refuse tooling that sweeps a profile decrypting everything
     * it finds. Changing these bytes invalidates every stored session, which is why the file name
     * carries a version.
     */
    private val ENTROPY = "loopky.session.v1".toByteArray(Charsets.UTF_8)
}
