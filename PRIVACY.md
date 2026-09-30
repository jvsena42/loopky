# Loopky Privacy Policy

**Last updated:** 30 August 2026

Loopky is a flashcards app built on [Pubky](https://pubky.org). This policy describes what happens
to your information when you use it.

The short version: **the Loopky project runs no servers of its own.** There is no Loopky account,
no Loopky database, and no analytics.

That does not mean nothing receives your data. Your decks and study progress are written to a
**Pubky homeserver**, and unless you run one yourself that server is operated by someone else — by
default Synonym, who run the homeserver that Loopky's sign-up flow issues accounts on. You hold the
key to that data and can delete it, but the server that stores it is not yours and not ours. See
sections 4 and 5.

---

## 1. Who is responsible

Loopky is an open-source project by João Victor Sena. The source is at
[github.com/jvsena42/loopky](https://github.com/jvsena42/loopky).

Questions about this policy: open an issue at
[github.com/jvsena42/loopky/issues](https://github.com/jvsena42/loopky/issues).

---

## 2. What Loopky does not do

- **No Loopky backend.** The project runs no server, and none of your data is sent to us. It goes
  to your Pubky homeserver instead, which is a different party — see sections 4 and 5.
- **No analytics or telemetry.** No usage tracking, no event logging, no advertising identifiers,
  no third-party SDKs for measurement or attribution.
- **No crash reporting.** Nothing is transmitted when the app fails.
- **No advertising**, and no sale or sharing of personal information for advertising.
- **No account with Loopky.** There is no email address, password, or profile held by us. Your
  account is a Pubky account, held either by Pubky Ring or by Loopky on your own device, and
  hosted on a homeserver. See section 3.

---

## 3. Your identity

Your identity is a Pubky keypair. There are two ways to hold it, and which one you have changes
what is on your device.

**If you sign in with Pubky Ring**, your key lives in that separate app and Loopky never sees your
private key or your recovery phrase. When you sign in, Ring grants Loopky a **session secret**
scoped to Loopky's own storage area. Copying an existing Loopky key *into* Ring is offered as a
backup option, described below — Loopky keeps its own copy either way.

**If you created or restored your account inside Loopky** — which is what "Create account" does —
then Loopky holds your private key itself. It is stored in your device's own secure storage — the Android Keystore on Android, the
Keychain on iOS — and is used to sign your own reads and writes. It is never sent to us (we run no
server), never sent to any third party, and never written to a log.

In both cases the session secret is kept in that same secure storage and is sent only to your
homeserver. **Signing out deletes both the session secret and any key Loopky is holding.** If
Loopky holds the only copy of your key, it warns you before doing that, because the account cannot
be recovered afterwards.

Backing up is your choice and your action. Loopky can show you a recovery phrase, create a
passphrase-encrypted recovery file for you to save wherever you like, or hand your key to Pubky
Ring. Anything that leaves the app — a file you save to Drive or Files, a phrase you write down —
is outside Loopky's control from that moment, and is covered by the terms of wherever you put it.
Exporting to Pubky Ring copies the key into Ring; Loopky keeps its copy too.

Deleting your Loopky data does not delete your Pubky identity.

---

## 4. Where your content is stored

Your decks, cards, images, tags, follows and study progress are written to **your Pubky
homeserver** under `/pub/loopky/`. They are not stored by the Loopky project.

"Your homeserver" means the one your Pubky account is hosted on, which is a real server run by a
real operator. If you signed up through Loopky, that is the homeserver Synonym operate for
[pubky.app](https://pubky.app), and their terms and privacy practices apply to what is stored
there. You can host your own instead, and then the operator is you. Either way the data is written
under your key, so you can read, change and delete it — but it does leave your device, and someone
runs the machine it lands on.

**Published decks are public.** Loopky has no private or local-only decks. Anything you publish can
be read by anyone who knows your public key, and is indexed so that it can be discovered by search
and by topic. Please do not put anything in a deck that you would not put on a public web page.

Your review history and study settings are also written to your homeserver, so that they follow you
to another device. They live under your own key and are not published to any index by Loopky, but
they are subject to whatever access rules your homeserver applies.

Some information is kept only on your device and is never uploaded: your study counters for the
current day, unsent reviews waiting to be written, and your app preferences.

---

## 5. Services Loopky contacts

Loopky talks to a small number of services. Each is contacted only for the purpose listed, and each
has its own operator and its own privacy practices.

| Service | When | What reaches it |
|---|---|---|
| **Your Pubky homeserver** | Whenever you read or write your content | Your content, your public key, your session secret, your IP address |
| **Pubky auth relay** (`httprelay.pubky.app`) | Sign-in only | The encrypted approval handed back by Pubky Ring, your IP address |
| **Pubky Nexus indexer** (`nexus.pubky.app`) | Search, discovery, trending topics, profile pictures | Your search terms, the public keys and decks you look up, your IP address |
| **Homegate** (`homegate.pubky.app`) | Signing up for a new homeserver account only | Your phone number if you verify by SMS, or Lightning payment details if you pay. Your phone number is handled by Homegate and is never stored by Loopky |
| **Unsplash** (`api.unsplash.com`) | Only when you search for a picture from the web | Your search terms and your IP address, under [Unsplash's privacy policy](https://unsplash.com/privacy) |
| **Google Play** or the **App Store** | Only if you tap to install Pubky Ring from the backup screen | A standard store request, to whichever store your device uses |

Loopky does not contact any of these to report on you. It contacts them to do the thing you asked
for.

---

## 6. Microphone

The **Speak** practice mode records audio so you can practise pronunciation.

- Audio is passed to your device's own speech recognition service and converted to text there.
- Loopky does **not** store the recording, and does **not** upload it anywhere.
- Only the recognised text is used, only to grade the card you are answering, and it is discarded
  when you move on.
- The permission is requested the first time you use Speak, and Speak is optional. Declining it
  leaves the rest of the app working normally.

Depending on your device and its settings, your device's speech recognition may itself run in the
cloud rather than on-device — on iOS through Apple's speech recognition, on Android through
Google's. Loopky does not force either mode, so which one you get is your platform's decision. That
processing is governed by Apple's or Google's privacy policy, not this one.

---

## 7. Camera, photos and images

**The camera is used for one thing: reading a QR code that holds someone's pubky**, so you can add
them without typing a long key by hand. Loopky reads only the text decoded from the code. No photo
or video is captured, saved or uploaded, and the camera runs only while that scanner is open. The
permission is requested the first time you open it, and scanning is optional — you can always type
or paste a pubky instead.

Choosing a picture for a card uses the system photo picker — `PhotosPicker` on iOS, the Android
photo picker on Android. Both run outside Loopky and hand it only the one image you select, so
Loopky requests no gallery or storage permission on either platform.

Images you attach to a published deck are uploaded to your homeserver and are public along with the
deck.

---

## 8. Content from other people

Decks, profiles and tags published by other people are fetched from their homeservers and from the
Nexus indexer. Loopky does not verify or moderate them. Treat text and links inside someone else's
deck the way you would treat any content from a stranger on the internet.

---

## 9. Deleting your data

- **Sign out** (Settings) removes the session secret and your locally cached data from the device.
- **Delete a deck** removes its records, cards and images from your homeserver.
- **Delete account** (Settings) permanently removes everything Loopky has written to your
  homeserver, and removes your profile from Loopky's directory so that you no longer appear in
  search or discovery. It cannot be undone.

Delete account does not delete your Pubky identity or your homeserver account, because Loopky does
not own them. Those live in Pubky Ring, and your profile and social follows in the wider Pubky
network are left untouched.

Copies that other people made of a deck you published are theirs, on their own homeservers, and
cannot be recalled. This is a property of publishing to a public network.

---

## 10. Children

Loopky is not directed at children under 13, and we do not knowingly collect information from them.
The Loopky project holds no user data, so there is nothing for us to delete on request. Data on a
homeserver is removed with **Delete account** in Settings, or by contacting that homeserver's
operator. An account created with Loopky's **Create account** is created on your own device; one
you already had in Pubky Ring stays governed by Ring's own terms.

---

## 11. Changes to this policy

Material changes will be published here with an updated date above. The revision history of this
document is public in the repository, so every change is inspectable.

---

## 12. Your rights

The Loopky project holds no personal data, so requests to access, correct, export or delete data
cannot be served by us — we have nothing to serve them from. Your content is written under your own
key and **Delete account** in Settings removes it from your homeserver. For anything held by your
homeserver's operator, including backups and server logs, address that operator. If you have a
question about any of this, open an issue at
[github.com/jvsena42/loopky/issues](https://github.com/jvsena42/loopky/issues).
