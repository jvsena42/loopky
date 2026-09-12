package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.util.Log
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/**
 * "Only the owner may read this", on a host that spells it with a mode and on one that spells it
 * with an ACL (#301).
 *
 * The four callers hold a session secret, a live `pubkyauth://` credential and a downloaded
 * executable, and every one of them used to ask for a POSIX mode inside a `runCatching` that logged
 * at debug and carried on. That reads as "best-effort where the filesystem cannot express it",
 * which is true of FAT32 and untrue of NTFS: Windows *can* express this, through a DACL, and
 * nothing ever asked. So the guarantee quietly became "none" on the one row where `loopky login`
 * prints the words "owner-readable only".
 *
 * **Create-time, not after the fact.** [attributes] is what the callers pass to `createFile` and
 * `createTempFile`, so the permissions land in the same syscall as the file and the readable window
 * never exists. [restrict] is the fallback for a file somebody else created, and it is second
 * choice for exactly that reason.
 *
 * Still best-effort, and deliberately: a host with neither view is a real host, and refusing to run
 * there would trade a capability for a guarantee it was never going to give. What changed is that
 * "best-effort" now tries both spellings before giving up, and that a caller which *claims* the
 * property can ask [supported] first rather than asserting something untrue.
 */
object OwnerOnly {

    /**
     * A created file and whether it actually came out owner-only.
     *
     * Two fields rather than a bare [Path] because a caller that *prints a promise* about the file
     * has to be able to tell — `--qr-out` says "owner-readable only" about a live credential, and a
     * false assurance there is worse than none, since somebody told the file is protected will
     * leave it in place through the approval window.
     *
     * [ownerOnly] is the answer for **this path**, not for the host. [supported] asks the default
     * filesystem, which is right for the three internal writers but wrong for a path that came from
     * argv: `--qr-out E:\qr.png` on an exFAT stick gets past an `acl` view that exists for every
     * Windows path and then fails inside `SetFileSecurity`.
     */
    data class Created(val path: Path, val ownerOnly: Boolean)

    /**
     * Create an owner-only file, restricted before it can hold anything.
     *
     * The creation lives here rather than at the call sites so that the create-then-narrow order —
     * the part that matters, and the part that was wrong in `JsonFileStore` — is written once. It
     * also keeps the varargs spread inside this file instead of at all five callers.
     */
    // The JDK's create-with-attributes calls are varargs and [attributes] hands back nought or one.
    // Copying that array to open a file is not a cost worth reshaping this API for, and the
    // alternative — the attribute-less overload, narrowed afterwards — reopens the readable window
    // this file exists to close.
    @Suppress("SpreadOperator")
    fun createFile(path: Path): Created {
        val created = runCatching { Files.createFile(path, *attributes(directory = false)) }
            .getOrElse { Files.createFile(path) }
        return Created(created, restrict(created))
    }

    /** [createFile]'s temp-file twin, for the write-then-`ATOMIC_MOVE` shape three callers use. */
    // The JDK's create-with-attributes calls are varargs and [attributes] hands back nought or one.
    // Copying that array to open a file is not a cost worth reshaping this API for, and the
    // alternative — the attribute-less overload, narrowed afterwards — reopens the readable window
    // this file exists to close.
    @Suppress("SpreadOperator")
    fun createTempFile(directory: Path, prefix: String, suffix: String): Path {
        val created = runCatching {
            Files.createTempFile(directory, prefix, suffix, *attributes(directory = false))
        }.getOrElse { Files.createTempFile(directory, prefix, suffix) }
        restrict(created)
        return created
    }

    /** Create [dir] and everything above it, owner-only. */
    // The JDK's create-with-attributes calls are varargs and [attributes] hands back nought or one.
    // Copying that array to open a file is not a cost worth reshaping this API for, and the
    // alternative — the attribute-less overload, narrowed afterwards — reopens the readable window
    // this file exists to close.
    @Suppress("SpreadOperator")
    fun createDirectories(dir: Path): Path {
        val created = runCatching { Files.createDirectories(dir, *attributes(directory = true)) }
            .getOrElse { Files.createDirectories(dir) }
        restrict(created, directory = true)
        return created
    }

    /**
     * Attributes for a new owner-only file or directory, or nothing on a host with neither view.
     *
     * Nothing rather than a throw: `Files.createFile(path)` with an empty array is the same call
     * the old `getOrElse` fallback made, so a host that can express neither ends up exactly where
     * it was instead of losing the file.
     */
    private fun attributes(directory: Boolean): Array<FileAttribute<*>> = when {
        posixSupported -> arrayOf(
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(if (directory) DIR_MODE else FILE_MODE),
            ),
        )
        // No ACL equivalent here on purpose. An `acl:acl` attribute needs a principal, and looking
        // one up can fail on a domain-joined box whose controller is unreachable — inside
        // `createFile` that failure costs the file rather than the mode. Windows is narrowed by
        // [restrict] instead, where a failure costs only what it should.
        else -> emptyArray()
    }

    /** Whether this host can express owner-only at all, so a caller need not claim it blindly. */
    val supported: Boolean get() = posixSupported || aclSupported

    /**
     * Narrow an existing path to its owner.
     *
     * Reports whether it worked. Callers that print a promise about the file — `--qr-out` says
     * "owner-readable only" — are expected to look at the answer; the session store logs it and
     * carries on, because losing a session to a strict guarantee is the worse failure.
     */
    fun restrict(path: Path, directory: Boolean = false): Boolean = runCatching {
        when {
            posixSupported -> {
                Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(if (directory) DIR_MODE else FILE_MODE),
                )
                true
            }
            aclSupported -> restrictAcl(path)
            else -> false
        }
    }.getOrElse {
        Log.d(TAG, "could not make $path owner-only: ${it.message}")
        false
    }

    /**
     * Replace the DACL with one entry: **the current user**, full control.
     *
     * Replacing rather than appending is the point — an inherited "Users: Modify" from the parent
     * is exactly what has to go, and a DENY entry is the wrong tool (ordering rules make them easy
     * to get subtly wrong, and denying SYSTEM breaks backup and anti-malware for no gain).
     * Administrators and SYSTEM are deliberately not listed: an admin can take ownership regardless,
     * exactly as root can read any 0600 file, and that action is at least audited.
     *
     * **The user, never [Files.getOwner].** Windows takes a new object's owner from the creating
     * token's `TOKEN_OWNER`, which LSA sets to `BUILTIN\Administrators` for any elevated process —
     * so naming the owner writes an Administrators-only DACL. The same human's ordinary
     * UAC-filtered token holds that SID as `SE_GROUP_USE_FOR_DENY_ONLY`, and the access check skips
     * group SIDs that are not enabled, so that ACE grants it **nothing**. With
     * `SeTakeOwnershipPrivilege` stripped and the implicit owner's `WRITE_DAC` needing the owner SID
     * enabled, the filtered token cannot even repair the DACL it is locked out of. The user SID is
     * the `TokenUser` of *both* tokens — elevation adds a group, it does not change who you are —
     * so one ACE naming the user works from an elevated and a normal shell alike.
     *
     * **A DACL that cannot be aimed is not written at all.** Failing to restrict leaves the
     * inherited profile DACL, which still keeps other users out and can be narrowed later; writing
     * the wrong principal cannot be undone by the account that needs to undo it. So an
     * unresolvable user returns false rather than falling back to the owner.
     *
     * **Known limit: the DACL is not marked protected.** NIO's `setAcl` reaches `SetFileSecurity`
     * with `DACL_SECURITY_INFORMATION` and no way to set `SE_DACL_PROTECTED`, so a later inheritance
     * propagation on any ancestor (`icacls <ancestor> /reset /T`, an Explorer Security-tab edit,
     * profile repair) re-adds that ancestor's inheritable ACEs. Under a default profile that is
     * SYSTEM and Administrators, which this file already accepts. It becomes real exposure only
     * where an ancestor grants `Users` — `LOOPKY_CONFIG_HOME` on a shared volume, or `--qr-out`
     * into `C:\Temp` during the approval window. `icacls <path> /inheritance:r` is the cheap fix if
     * that ever needs to be durable.
     */
    private fun restrictAcl(path: Path): Boolean {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return false
        val principal = currentUser(path) ?: return false
        val entry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(AclEntryPermission.entries.toSet())
            .build()
        view.acl = listOf(entry)
        return true
    }

    /**
     * The account this process is running as, looked up in the filesystem's own terms.
     *
     * `USERDOMAIN\user` first because that is what resolves for a domain account, and it is the
     * machine name for a local one, so the qualified form is right on both. The bare name is the
     * fallback for a stripped environment. Null when neither resolves — see [restrictAcl] for why
     * that is better than guessing.
     */
    private fun currentUser(path: Path): UserPrincipal? {
        val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: return null
        val lookup = path.fileSystem.userPrincipalLookupService
        val domain = System.getenv("USERDOMAIN")?.takeIf { it.isNotBlank() }
        return runCatching { lookup.lookupPrincipalByName(if (domain == null) user else "$domain\\$user") }
            .recoverCatching { lookup.lookupPrincipalByName(user) }
            .getOrElse {
                Log.d(TAG, "could not resolve the current user for $path: ${it.message}")
                null
            }
    }

    /**
     * Asked of the **default filesystem**, not of a probe path.
     *
     * `getFileAttributeView` on some arbitrary path answers for the volume that path is on, and
     * these callers write to three different ones — a config home, a system temp directory and
     * whichever directory the binary was installed into. Reading the working directory's answer and
     * applying it to all three is how a helper quietly does the wrong thing on a machine with a
     * FAT-formatted stick mounted. `supportedFileAttributeViews()` is the provider's own answer and
     * needs no path at all.
     *
     * **Declared before the two that read it.** An `object`'s properties initialise top to bottom,
     * so a `SUPPORTED_VIEWS` below them would be empty at the moment they are computed and every
     * host would report neither view — failing open, silently, which is the whole defect this file
     * exists to remove.
     */
    private val supportedViews: Set<String> = FileSystems.getDefault().supportedFileAttributeViews()

    private val posixSupported: Boolean = supportedViews.contains("posix")

    private val aclSupported: Boolean = supportedViews.contains("acl")

    private const val TAG = "Loopky/OwnerOnly"
    private const val FILE_MODE = "rw-------"
    private const val DIR_MODE = "rwx------"
}
