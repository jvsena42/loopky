import Foundation
import Shared

/// Native mirror of the shared `ErrorReason`.
///
/// The bridged `ErrorReason` is a Kotlin enum, which crosses to Swift as a *class* whose entries
/// are class properties (`ErrorReason.sessionexpired` — lowercased, no separators). Switching on
/// it can therefore never be exhaustive, which is exactly the hazard this file used to carry: a
/// new reason compiled without a warning and silently rendered the generic copy, while Android's
/// `when` failed the build.
///
/// Mirroring it as a real Swift enum moves that guarantee back. `title(for:)` and `message(for:)`
/// switch over `LoopkyErrorReason` with **no `default`**, so adding a case here without giving it
/// copy is a compile error. `bridged` below is the single bridge point, and the one place a new
/// shared reason has to be mapped — the debug assertion in `init(_:)` catches forgetting it.
enum LoopkyErrorReason: CaseIterable {
    case offline
    case sessionExpired
    case sessionUnreachable
    case notFound
    case noHomeserverAccount
    case notSignedIn
    case ringNotInstalled
    case authFailed
    case authRelayUnreachable
    case serverBusy
    case storageFull
    case homeserverLookupFailed
    case unknown

    /// The bridged entry each case mirrors. Kotlin enum entries are singletons, so comparing them
    /// is well defined.
    ///
    /// The mapping is written in this direction on purpose. Switching over the *bridged* value can
    /// never be exhaustive, so a case that nothing matched stayed unreachable without a warning —
    /// which is how `.unknown` came to crash the debug app (#193): it was the classifier's own
    /// catch-all and the one case the initializer could not produce. Switching over `self` has no
    /// `default`, so every case here must name its entry or the build fails.
    var bridged: ErrorReason {
        switch self {
        case .offline: return ErrorReason.offline
        case .sessionExpired: return ErrorReason.sessionexpired
        case .sessionUnreachable: return ErrorReason.sessionunreachable
        case .notFound: return ErrorReason.notfound
        case .noHomeserverAccount: return ErrorReason.nohomeserveraccount
        case .notSignedIn: return ErrorReason.notsignedin
        case .ringNotInstalled: return ErrorReason.ringnotinstalled
        case .authFailed: return ErrorReason.authfailed
        case .authRelayUnreachable: return ErrorReason.authrelayunreachable
        case .serverBusy: return ErrorReason.serverbusy
        case .storageFull: return ErrorReason.storagefull
        case .homeserverLookupFailed: return ErrorReason.homeserverlookupfailed
        case .unknown: return ErrorReason.unknown
        }
    }

    /// The only place the bridged singletons are matched.
    init(_ reason: ErrorReason) {
        guard let matched = LoopkyErrorReason.allCases.first(where: { $0.bridged == reason }) else {
            // A reason added on the Kotlin side and not mirrored above lands here. It renders the
            // generic copy rather than crashing a user, but trips in debug so it is caught.
            assertionFailure("Unmapped ErrorReason: \(reason.name). Add it to LoopkyErrorReason.")
            self = .unknown
            return
        }
        self = matched
    }
}

/// Maps the shared `ErrorReason` to user-facing copy, mirroring the Android `ErrorMessages.kt`.
///
/// ViewModels deliberately carry no message text: the Pubky FFI's diagnostic string
/// (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`) used to be
/// rendered to users verbatim.
enum ErrorCopy {

    static func title(for reason: ErrorReason) -> String {
        title(for: LoopkyErrorReason(reason))
    }

    static func message(for reason: ErrorReason) -> String {
        message(for: LoopkyErrorReason(reason))
    }

    static func title(for reason: LoopkyErrorReason) -> String {
        switch reason {
        case .offline:
            return NSLocalizedString("You're offline", comment: "Error title: no connectivity")
        case .sessionExpired:
            return NSLocalizedString("Session expired", comment: "Error title: needs re-auth")
        case .sessionUnreachable:
            return NSLocalizedString(
                "Couldn't reconnect",
                comment: "Error title: the /session round trip failed at the transport layer"
            )
        case .notFound:
            return NSLocalizedString("Not found", comment: "Error title: missing record")
        case .noHomeserverAccount:
            return NSLocalizedString(
                "Your account isn't set up yet",
                comment: "Error title: pubky has no homeserver account"
            )
        case .notSignedIn:
            return NSLocalizedString("Not signed in", comment: "Error title: no session")
        case .ringNotInstalled:
            return NSLocalizedString("Pubky Ring not found", comment: "Error title: Ring missing")
        case .authFailed:
            return NSLocalizedString("Sign-in didn't finish", comment: "Error title: auth failed")
        case .authRelayUnreachable:
            return NSLocalizedString(
                "Sign-in couldn't connect",
                comment: "Error title: auth relay unreachable"
            )
        case .serverBusy:
            return NSLocalizedString(
                "The server is busy",
                comment: "Error title: homeserver rate-limiting"
            )
        case .storageFull:
            return NSLocalizedString(
                "Your storage is full",
                comment: "Error title: homeserver storage quota exceeded"
            )
        case .homeserverLookupFailed:
            return NSLocalizedString(
                "Couldn't check that",
                comment: "Error title: pkarr/DHT lookup did not answer"
            )
        case .unknown:
            return NSLocalizedString("Something went wrong", comment: "Error title: generic")
        }
    }

    static func message(for reason: LoopkyErrorReason) -> String {
        switch reason {
        case .offline:
            // The reassurance stays, short: the homeserver is the only source of truth and there
            // is no local cache, so this is exactly the moment a user would fear they had lost
            // everything.
            return NSLocalizedString(
                "Check your connection and try again. Your decks are safe.",
                comment: "Error message: no connectivity"
            )
        case .sessionExpired:
            return NSLocalizedString(
                "Sign in with Pubky Ring again to get back to your decks.",
                comment: "Error message: needs re-auth"
            )
        case .sessionUnreachable:
            // Deliberately not the offline copy (#165): the homeserver session round trip is what
            // failed, and the device's connection was measurably fine every time this was seen —
            // hence naming it only to rule it out. Short because every screen showing this
            // composes it after a consequence ("Couldn't save this deck. …"), so length is paid
            // twice; what was lost is already said there, and "sign in again" is the button.
            return NSLocalizedString(
                "Loopky couldn't restore your sign-in. It's not your connection, so try again.",
                comment: "Error message: the /session round trip failed at the transport layer"
            )
        case .notFound:
            return NSLocalizedString(
                "This deck no longer exists. It may have been deleted.",
                comment: "Error message: missing record"
            )
        case .noHomeserverAccount:
            // Deliberately not "sign in again": there is nothing to sign in to. Saying so is the
            // whole point of this case — it used to fall through to the deck-deleted copy.
            return NSLocalizedString(
                "There's no account for this key yet, so there's nowhere to keep your decks. "
                    + "Setting one up takes a minute.",
                comment: "Error message: pubky has no homeserver account"
            )
        case .notSignedIn:
            return NSLocalizedString(
                "Sign in with Pubky Ring to continue.",
                comment: "Error message: no session"
            )
        case .ringNotInstalled:
            return NSLocalizedString(
                "Loopky signs you in through Pubky Ring. Install it to continue.",
                comment: "Error message: Ring missing"
            )
        case .authFailed:
            return NSLocalizedString(
                "Loopky couldn't confirm the sign-in. Check your connection and try again.",
                comment: "Error message: auth failed"
            )
        case .authRelayUnreachable:
            // Not "you're offline": the relay is its own host, and the homeserver is usually
            // reachable while it is not.
            return NSLocalizedString(
                "The sign-in service isn't responding. Try again in a moment.",
                comment: "Error message: auth relay unreachable"
            )
        case .serverBusy:
            // Not "you're offline": the homeserver answered, so the connection is fine and
            // sending the user to check it points them at something that is not broken.
            return NSLocalizedString(
                "Too many requests at once, so this couldn't be finished. It's not your "
                    + "connection. Wait a moment and try again.",
                comment: "Error message: homeserver rate-limiting"
            )
        case .storageFull:
            // Not "please try again" — a retry against a full quota is the one thing that cannot
            // work, so the copy has to name the two things that can: delete something, or buy room.
            return NSLocalizedString(
                "There's no room left in your account. Delete a deck you no longer study to "
                    + "free up space, or upgrade your storage plan.",
                comment: "Error message: homeserver storage quota exceeded"
            )
        case .homeserverLookupFailed:
            // Never a verdict. This is the lookup that decides whether a recovery phrase belongs
            // to an account, and it failing means we do not know — saying anything about the
            // phrase here would be a guess presented as an answer.
            return NSLocalizedString(
                "Some networks block the connection Loopky needs. Try again, or switch between "
                    + "Wi-Fi and mobile data.",
                comment: "Error message: pkarr/DHT lookup did not answer"
            )
        case .unknown:
            return NSLocalizedString(
                "Something went wrong. Please try again.",
                comment: "Error message: generic"
            )
        }
    }
}

/// Copy for a failed deck-editor operation, composed from `ErrorCopy` exactly as the publish
/// flow's is: the consequence differs per operation, the cause is the shared vocabulary.
///
/// The editor used to render the throwable's own `message`, which is how the card list came to
/// show `"Failed to import session: Request failed: HTTP transport error: error sending request
/// for url (https://_pubky.…/session)"` where the cards belong (#165).
enum DeckEditorErrorCopy {
    static func message(for error: DeckEditorError?) -> String? {
        guard let error else { return nil }
        let consequence: String
        switch error.op {
        case DeckEditorOp.loadcards:
            consequence = NSLocalizedString("deck_editor_error_cards", comment: "")
        case DeckEditorOp.movecard:
            consequence = NSLocalizedString("deck_editor_error_move", comment: "")
        default:
            consequence = NSLocalizedString("deck_editor_error_save", comment: "")
        }
        return "\(consequence) \(ErrorCopy.message(for: error.reason))"
    }
}

/// Copy for the shared `FormError`s, in one place so the publish flow, the deck editor and the
/// card editor cannot drift into wording the same validation differently.
///
/// Like `ErrorReason`, `FormError` is a Kotlin enum whose entries export lowercased with no
/// separators, so this is the only file that has to know that.
enum FormErrorCopy {
    static func message(for error: FormError?) -> String? {
        switch error {
        case FormError.titlerequired:
            return NSLocalizedString("form_error_title_required", comment: "")
        case FormError.titletoolong:
            return NSLocalizedString("form_error_title_too_long", comment: "")
        case FormError.descriptiontoolong:
            return NSLocalizedString("form_error_description_too_long", comment: "")
        case FormError.cardsiderequired:
            return NSLocalizedString("form_error_card_side_required", comment: "")
        case FormError.cardtexttoolong:
            return NSLocalizedString("form_error_card_text_too_long", comment: "")
        case FormError.languagesrequired:
            return NSLocalizedString("deck_languages_required", comment: "")
        default:
            return nil
        }
    }
}

/// Native mirror of the shared `SignupError`, for the same reason `LoopkyErrorReason` mirrors
/// `ErrorReason`: a Kotlin enum crosses as a class whose entries are lowercased class properties,
/// so switching on it can never be exhaustive. Mirroring it moves that guarantee back — the two
/// switches below have no `default`, so adding a case without copy is a compile error.
enum LoopkySignupError: CaseIterable {
    case geoblocked
    case phoneBlocked
    case rateLimited
    case rateLimitedWeekly
    case rateLimitedYearly
    case codeIncorrect
    case invoiceExpired
    case verificationLost
    case unavailable
    case tokenRejected

    /// The only place the bridged singletons are matched.
    init?(_ error: SignupError?) {
        switch error {
        case SignupError.geoblocked: self = .geoblocked
        case SignupError.phoneblocked: self = .phoneBlocked
        case SignupError.ratelimited: self = .rateLimited
        case SignupError.ratelimitedweekly: self = .rateLimitedWeekly
        case SignupError.ratelimitedyearly: self = .rateLimitedYearly
        case SignupError.codeincorrect: self = .codeIncorrect
        case SignupError.invoiceexpired: self = .invoiceExpired
        case SignupError.verificationlost: self = .verificationLost
        case SignupError.unavailable: self = .unavailable
        case SignupError.tokenrejected: self = .tokenRejected
        case nil: return nil
        default:
            assertionFailure("Unmapped SignupError. Add it to LoopkySignupError.")
            self = .unavailable
        }
    }

    /// `TokenRejected` is terminal by design — it is the one signup error that must never offer
    /// "try again", which is why `LocalSignupUiState.canRetry` excludes it.
    var isTerminal: Bool { self == .tokenRejected }
}

enum SignupErrorCopy {

    static func title(for error: SignupError?) -> String? {
        LoopkySignupError(error).map(title(for:))
    }

    static func message(for error: SignupError?) -> String? {
        LoopkySignupError(error).map(message(for:))
    }

    static func title(for error: LoopkySignupError) -> String {
        NSLocalizedString("signup_error_\(error.key)_title", comment: "Signup error title")
    }

    static func message(for error: LoopkySignupError) -> String {
        NSLocalizedString("signup_error_\(error.key)_message", comment: "Signup error message")
    }
}

private extension LoopkySignupError {
    /// The snake_case fragment the string keys are built from.
    var key: String {
        switch self {
        case .geoblocked: return "geoblocked"
        case .phoneBlocked: return "phone_blocked"
        case .rateLimited: return "rate_limited"
        case .rateLimitedWeekly: return "rate_limited_weekly"
        case .rateLimitedYearly: return "rate_limited_yearly"
        case .codeIncorrect: return "code_incorrect"
        case .invoiceExpired: return "invoice_expired"
        case .verificationLost: return "verification_lost"
        case .unavailable: return "unavailable"
        case .tokenRejected: return "token_rejected"
        }
    }
}
