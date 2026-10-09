import AuthenticationServices
import Security
import UIKit

/// Saving the recovery phrase to the Passwords app, and reading it back.
///
/// The counterpart to Android's `PasswordManagerSheet`, written the same way as `SpeechListener`:
/// no Kotlin binding. The shared ViewModels emit an effect and take the answer back through a
/// callback, so only "can this platform ask at all" crosses through Koin.
///
/// An app cannot write an arbitrary secret into Passwords, but it can write a password for a
/// domain that names it under `webcredentials`. That is what `loopky.app` is here — there is no
/// login form on the site. Both calls fail, quietly, unless the site's
/// `apple-app-site-association` and this build's `webcredentials:loopky.app` entitlement agree.
///
/// Nothing here logs the phrase or an error's text.
@MainActor
final class PasswordManagerSheet: NSObject {
    private static let domain = "loopky.app"

    private var pendingRead: CheckedContinuation<String?, Never>?

    /// False for a refusal, a cancel and a missing association alike: nothing was saved.
    ///
    /// True is not proof either, which is why the ViewModel reads the phrase back before it
    /// records a backup.
    func save(account: String, secret: String) async -> Bool {
        await withCheckedContinuation { continuation in
            SecAddSharedWebCredential(
                Self.domain as CFString, account as CFString, secret as CFString
            ) { error in
                continuation.resume(returning: error == nil)
            }
        }
    }

    /// Raise the system's saved-password sheet for `loopky.app` and return the phrase picked.
    ///
    /// The sheet cannot be narrowed to one account, so whose phrase came back is for the caller
    /// to decide by comparing the words. Nil for a cancel and for nothing saved.
    func read() async -> String? {
        // One sheet at a time; a second request would orphan the first continuation.
        guard pendingRead == nil else { return nil }
        return await withCheckedContinuation { continuation in
            pendingRead = continuation
            let request = ASAuthorizationPasswordProvider().createRequest()
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    private func finishRead(_ secret: String?) {
        pendingRead?.resume(returning: secret)
        pendingRead = nil
    }
}

extension PasswordManagerSheet: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        finishRead((authorization.credential as? ASPasswordCredential)?.password)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        finishRead(nil)
    }
}

extension PasswordManagerSheet: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
