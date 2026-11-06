import SwiftUI
import Shared

/// Why a signed-out visitor is being asked for an account.
///
/// The wording names the action that ran into the wall — `SignInReason` exists so the prompt can
/// say what signing in unlocks rather than "sign in required". The gate is always *also* enforced
/// on the action itself; this only decides the words.
enum SignInPromptCopy {
    static func title(for reason: SignInReason?) -> String {
        NSLocalizedString(key(reason, suffix: "title"), comment: "")
    }

    static func body(for reason: SignInReason?) -> String {
        NSLocalizedString(key(reason, suffix: "body"), comment: "")
    }

    private static func key(_ reason: SignInReason?, suffix: String) -> String {
        switch reason {
        case .clonedeck: return "sign_in_prompt_clone_deck_\(suffix)"
        case .followperson: return "sign_in_prompt_follow_person_\(suffix)"
        case .report: return "sign_in_prompt_report_\(suffix)"
        default: return "sign_in_prompt_follow_deck_\(suffix)"
        }
    }
}

extension View {
    /// The prompt, as a native alert over whatever the visitor was reading.
    ///
    /// Held over the content rather than replacing it: the deck, the profile or the search results
    /// are still perfectly readable without an account, and dismissing must leave the visitor
    /// exactly where they were.
    func signInPrompt(
        reason: SignInReason?,
        onSignIn: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        alert(
            Text(verbatim: SignInPromptCopy.title(for: reason)),
            isPresented: Binding(get: { reason != nil }, set: { if !$0 { onDismiss() } })
        ) {
            Button("sign_in_prompt_cta", action: onSignIn)
            Button("sign_in_prompt_dismiss", role: .cancel, action: onDismiss)
        } message: {
            Text(verbatim: SignInPromptCopy.body(for: reason))
        }
    }
}

/// "Share this on Pubky?" after a create, follow or clone.
///
/// **Announcing, never visibility.** A published deck is public either way; this posts to the
/// author's feed so the people following them see it. Copy that blurs the two describes a privacy
/// control Loopky does not have.
extension View {
    func sharePrompt(
        prompt: DeckSharePrompt?,
        onConfirm: @escaping () -> Void,
        onDismiss: @escaping () -> Void,
        onNeverAsk: @escaping () -> Void
    ) -> some View {
        // An alert, not a confirmationDialog: the dialog's cancel button is detached from the
        // action list and did not render here at all, leaving "Not now" — the safe, common choice
        // — reachable only by guessing that a tap outside dismisses.
        alert(
            Text(verbatim: SharePromptCopy.title(for: prompt)),
            isPresented: Binding(get: { prompt != nil }, set: { if !$0 { onDismiss() } })
        ) {
            Button("share_prompt_confirm", action: onConfirm)
            Button("share_prompt_never", role: .destructive, action: onNeverAsk)
            Button("share_prompt_dismiss", role: .cancel, action: onDismiss)
        } message: {
            Text("share_prompt_body")
        }
    }
}

enum SharePromptCopy {
    static func title(for prompt: DeckSharePrompt?) -> String {
        guard let prompt else { return "" }
        let key: String
        switch prompt.kind {
        case .followed: key = "share_prompt_title_followed"
        case .cloned: key = "share_prompt_title_cloned"
        default: key = "share_prompt_title_created"
        }
        return NSLocalizedString(key, comment: "")
    }
}
