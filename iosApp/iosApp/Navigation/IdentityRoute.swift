import Foundation

/// The signed-out flows: create an account, restore one, or deal with a key that has none.
///
/// A separate enum from `DeckRoute` because these live in the signed-*out* branch of `RootView`,
/// and because they need a **path**, not a single destination — restore start → phrase →
/// unregistered key → signup start → phone → local signup is six pushes deep.
///
/// `adoptHeldKey` travels with the route, replacing a back-stack probe Android has to do: it reads
/// the intent off the nav back stack with `lastOrNull` precisely because a start-over pushes a
/// *second* `SIGNUP_START`, and reading the oldest would let an abandoned attempt outvote the
/// screen the user is standing on. Carried as a payload, that ordering hazard cannot arise.
///
/// Backup is **not** here: it is only ever reached from Settings or the Profile nag, both of which
/// are signed-in surfaces, so it is a sheet over the tabs (`BackupFlowView`) rather than a leg of
/// this path.
enum IdentityRoute: Hashable {
    /// `adoptHeldKey` — register the key this device already holds, rather than minting a new one.
    case signupStart(adoptHeldKey: Bool)
    case signupPhone
    case signupLightning
    case signupInvite
    case signupLocal(adoptHeldKey: Bool)

    case restoreStart
    case restorePhrase
    case restoreFile

    /// `loopkyHoldsKey` decides whether this screen may offer to register the key, or only to
    /// check the phrase again. The `KeyCustody` is rebuilt on the far side; no secret crosses here.
    case unregisteredKey(pubky: String, loopkyHoldsKey: Bool)
}

extension Array where Element == IdentityRoute {

    /// The `adoptHeldKey` intent of the signup attempt the user is actually standing in.
    ///
    /// Read from the **last** signup-start on the path, matching Android's `lastOrNull`: a
    /// start-over pushes a fresh one, and an abandoned earlier attempt must not outvote it.
    var adoptHeldKey: Bool {
        for route in reversed() {
            if case .signupStart(let adopt) = route { return adopt }
        }
        return false
    }

    /// Drop everything back to and including the last signup start, then push a fresh one.
    ///
    /// Android pops `SIGNUP_START` inclusive for the same reason: leaving the spent verification
    /// screen *and* the old start entry underneath a new one meant "back" walked into a dead flow.
    mutating func startSignupOver(adoptHeldKey: Bool) {
        while let last = self.last {
            removeLast()
            if case .signupStart = last { break }
        }
        append(.signupStart(adoptHeldKey: adoptHeldKey))
    }
}
