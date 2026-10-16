import Foundation
import Shared

/// Which application the homeserver is being told it is talking to, required by pubky 0.10's
/// `ClientId` on every sign-in, sign-up and secret-key write.
///
/// The type wants a domain string (its own example is `franky.pubky.app`), non-empty and at most
/// 253 characters — not the iOS bundle identifier. Kept byte-for-byte identical to
/// `LOOPKY_CLIENT_ID` in `UniffiPubkyClient.kt`: it travels to the homeserver, so the two
/// platforms must identify as the same client.
private let loopkyClientId = "loopky.app"

/// Swift implementation of the shared `RawPubkyClient` interface — a dumb pass-through to
/// the UniFFI-generated free functions in `pubkycore.swift` (backed by
/// `PubkyCore.xcframework`). Every method returns the FFI's native `[status, payload]`
/// array; all `Result` conversion and threading happens on the Kotlin side in
/// `IosPubkyClientAdapter` (see `shared/src/iosMain/.../data/pubky/`).
///
/// Binary payloads cross this boundary Base64-encoded and are decoded to raw `Data` here so
/// blobs land raw on the homeserver. `Loopky.` prefixes disambiguate the module-level UniFFI
/// functions from the protocol methods of the same name.
final class IosPubkyClient: NSObject, RawPubkyClient {

    // MARK: - Keys & mnemonics

    func generateSecretKey() -> [String] { Loopky.generateSecretKey() }

    func getPublicKeyFromSecretKey(secretKey: String) -> [String] {
        Loopky.getPublicKeyFromSecretKey(secretKey: secretKey)
    }

    func generateMnemonicPhrase() -> [String] { Loopky.generateMnemonicPhrase() }

    func generateMnemonicPhraseAndKeypair() -> [String] { Loopky.generateMnemonicPhraseAndKeypair() }

    func mnemonicPhraseToKeypair(mnemonicPhrase: String) -> [String] {
        Loopky.mnemonicPhraseToKeypair(mnemonicPhrase: mnemonicPhrase)
    }

    func validateMnemonicPhrase(mnemonicPhrase: String) -> [String] {
        Loopky.validateMnemonicPhrase(mnemonicPhrase: mnemonicPhrase)
    }

    // MARK: - Recovery files

    func createRecoveryFile(secretKey: String, passphrase: String) -> [String] {
        Loopky.createRecoveryFile(secretKey: secretKey, passphrase: passphrase)
    }

    func decryptRecoveryFile(recoveryFile: String, passphrase: String) -> [String] {
        Loopky.decryptRecoveryFile(recoveryFile: recoveryFile, passphrase: passphrase)
    }

    // MARK: - Auth / sessions

    func signUp(secretKey: String, homeserver: String, signupToken: String?) -> [String] {
        Loopky.signUp(
            secretKey: secretKey,
            homeserver: homeserver,
            signupToken: signupToken,
            clientId: loopkyClientId
        )
    }

    func getSignupToken(homeserverPubky: String, adminPassword: String) -> [String] {
        Loopky.getSignupToken(homeserverPubky: homeserverPubky, adminPassword: adminPassword)
    }

    func signIn(secretKey: String) -> [String] { Loopky.signIn(secretKey: secretKey, clientId: loopkyClientId) }

    func signOut(sessionSecret: String) -> [String] { Loopky.signOut(sessionSecret: sessionSecret) }

    func revalidateSession(sessionSecret: String) -> [String] {
        Loopky.revalidateSession(sessionSecret: sessionSecret)
    }

    func startAuthFlow(capabilities: String) -> [String] {
        Loopky.startAuthFlow(capabilitiesStr: capabilities, clientId: loopkyClientId)
    }

    func awaitAuthApproval() -> [String] { Loopky.awaitAuthApproval() }

    func parseAuthUrl(url: String) -> [String] { Loopky.parseAuthUrl(url: url) }

    func auth(url: String, secretKey: String) -> [String] {
        Loopky.auth(url: url, secretKey: secretKey)
    }

    // MARK: - Records (secret-key auth)

    func publish(recordName: String, recordContent: String, secretKey: String) -> [String] {
        Loopky.publish(recordName: recordName, recordContent: recordContent, secretKey: secretKey)
    }

    func publishHttps(recordName: String, target: String, secretKey: String) -> [String] {
        Loopky.publishHttps(recordName: recordName, target: target, secretKey: secretKey)
    }

    func put(url: String, content: String, secretKey: String) -> [String] {
        Loopky.put(url: url, content: content, secretKey: secretKey, clientId: loopkyClientId)
    }

    func putBytesBase64(url: String, contentBase64: String, secretKey: String) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Loopky.putBytes(url: url, content: data, secretKey: secretKey, clientId: loopkyClientId)
    }

    func get(url: String) -> [String] { Loopky.get(url: url) }

    func getBytes(url: String) -> [String] { Loopky.getBytes(url: url) }

    func list(
        url: String,
        cursor: String?,
        reverse: KotlinBoolean?,
        limit: KotlinInt?,
        shallow: KotlinBoolean?
    ) -> [String] {
        Loopky.list(
            url: url,
            cursor: cursor,
            reverse: reverse?.boolValue,
            limit: limit.map { UInt16(truncating: $0) },
            shallow: shallow?.boolValue
        )
    }

    func deleteFile(url: String, secretKey: String) -> [String] {
        Loopky.deleteFile(url: url, secretKey: secretKey, clientId: loopkyClientId)
    }

    func republishHomeserver(secretKey: String, homeserver: String) -> [String] {
        Loopky.republishHomeserver(secretKey: secretKey, homeserver: homeserver)
    }

    // MARK: - Records (session auth)

    func putWithSession(url: String, content: String, sessionSecret: String) -> [String] {
        Loopky.putWithSession(url: url, content: content, sessionSecret: sessionSecret)
    }

    func putBytesBase64WithSession(
        url: String,
        contentBase64: String,
        sessionSecret: String
    ) -> [String] {
        guard let data = Data(base64Encoded: contentBase64) else {
            return ["true", "Invalid Base64 payload"]
        }
        return Loopky.putBytesWithSession(url: url, content: data, sessionSecret: sessionSecret)
    }

    func deleteWithSession(url: String, sessionSecret: String) -> [String] {
        Loopky.deleteWithSession(url: url, sessionSecret: sessionSecret)
    }

    // MARK: - pubky-app-specs helpers

    func createTagId(uri: String, label: String) -> [String] {
        Loopky.createTagId(uri: uri, label: label)
    }

    // MARK: - DHT resolution

    func resolve(publicKey: String) -> [String] { Loopky.resolve(publicKey: publicKey) }

    func resolveHttps(publicKey: String) -> [String] { Loopky.resolveHttps(publicKey: publicKey) }

    func getHomeserver(pubky: String) -> [String] { Loopky.getHomeserver(pubky: pubky) }

    // MARK: - Network

    func switchNetwork(useTestnet: Bool) -> [String] { Loopky.switchNetwork(useTestnet: useTestnet) }
}
