# Loopky Terms of Service

**Last updated:** 12 November 2026

These terms cover the Loopky apps for Android and iOS, the `loopky` command line tool, the Loopky
plugin for AI agents, and the website at [loopky.app](https://loopky.app). Using any of them means
you accept these terms. If you do not, please do not use them.

The short version: **Loopky is free software with no warranty, the app runs on no Loopky server,
and what you publish is public and yours to answer for.** The one server the project runs is the
optional connector for Claude, offered as is.

---

## 1. Who provides Loopky

Loopky is an open-source project by João Victor Sena. The source is at
[github.com/jvsena42/loopky](https://github.com/jvsena42/loopky). There is no company behind it and
nothing to pay for: no price, no subscription, no ads and no in-app purchases.

---

## 2. The software and its licence

The apps, the command line tool and the agent plugin are released under the
[MIT License](https://github.com/jvsena42/loopky/blob/main/LICENSE). You may use, copy, change and
redistribute the code under that licence.

The software is provided **"as is", without warranty of any kind**, express or implied, including
warranties of merchantability, fitness for a particular purpose and non-infringement. Nothing in
these terms takes away a right the MIT License gives you, and nothing here adds a warranty the
licence does not.

---

## 3. The app runs on no Loopky server

There is no Loopky account and no Loopky backend for the apps or the command line tool. Your
identity is a [Pubky](https://pubky.org) keypair, and your decks, cards, images, tags and study
progress are written to a **Pubky homeserver**.

The one exception is the **Loopky connector for Claude** (`https://mcp.loopky.app/mcp`), which you
add only if you choose to. It is a free service run by the project with no warranty and no promise
of availability: it may be rate limited, changed, paused or withdrawn, and access may be cut off
for anyone who abuses it. What it stores is described in section 6 of the
[privacy policy](https://loopky.app/privacy/).

Unless you run one yourself, that homeserver is operated by a third party. If you signed up through
Loopky, it is the homeserver Synonym operate for [pubky.app](https://pubky.app). **That operator's
own terms and policies apply to your account and to everything stored there**, alongside these
terms.

Because the project holds none of your data, it cannot do the things a hosted service could:

- it cannot recover a lost key, recovery phrase or recovery file. If Loopky holds the only copy of
  your key and you lose it or sign out without a backup, the account is gone;
- it cannot restore deleted decks or review history;
- it cannot raise a homeserver's limits or reverse a decision its operator makes.

---

## 4. Limits your homeserver sets

Some limits you will meet in Loopky belong to the homeserver, not to Loopky, and its operator can
change them:

- **Storage.** The default homeserver gives each account a quota, 1 GB on its free tier. Once it is
  full, writes are refused until you delete something. Images imported from an Anki deck count
  against it; a picture added by web address does not.
- **Sessions.** Signing in gives Loopky a time-limited session. It can expire after about an hour,
  and writing then needs you to sign in or approve again. Reading may keep working in the meantime.
- **Availability.** A homeserver, the Pubky indexer or the sign-in relay can be slow or
  unreachable. Loopky needs a connection to load decks and makes no promise that any of those
  services will be available.

---

## 5. What you publish

**Published decks are public.** Loopky has no private or local-only decks. Anything you publish can
be read by anyone, is indexed for search and discovery, and can be followed or copied by other
people. A copy someone else made lives under their key and cannot be recalled.

You keep whatever rights you have in what you publish. **You are responsible for it.** By
publishing you confirm that:

- you have the right to publish the text and images in the deck. That includes pictures you add
  from the web and material from an Anki deck you import: many shared Anki decks are someone
  else's copyrighted work, and importing one publishes it under your key;
- the deck contains no one else's personal information, and nothing you would not put on a public
  web page;
- the content is lawful where you are, and is not meant to harass, defraud or harm anyone.

**There is zero tolerance for objectionable content and abusive users.** That includes hate or
threatening speech, harassment, sexual content involving minors or published without consent,
promotion of terrorism, graphic violence, illegal sales, other people's personal information and
copyright infringement. Decks are not reviewed before they are published; they are acted on when
reported (section 6).

---

## 6. Other people's content

Decks, profiles and tags from other people come from their homeservers and from the Pubky indexer.
Loopky does not verify or endorse them, and is not responsible for what they say or link to.

**Report and block.** Every deck and profile in the apps has a Report action. A report goes to the
moderators of pubky.app, who review reports within 24 hours. Content that breaks section 5 is
removed from the homeservers Synonym operates, which is where accounts created through Loopky live,
and the account that published it is disabled. Reporting an account also blocks it for you, and
you can block any account from its profile; Settings → Blocked accounts lists them and is where you
unblock. A report also adds a public "reported" tag to the account, which hides it from the people
who follow you.

Content on a homeserver someone else runs cannot be removed by the project; Loopky can only hide it
from you. To report it to whoever runs it, contact that homeserver's operator. If the problem is in
Loopky itself, open an issue at
[github.com/jvsena42/loopky/issues](https://github.com/jvsena42/loopky/issues).

---

## 7. The command line tool, AI agents and other apps

The `loopky` command line tool and the agent plugin act with a session you approve, in Pubky Ring
or, if Loopky holds your key, in Loopky itself. That session is limited by design: it **can write only Loopky's own data** (`/pub/loopky/`). It cannot
post, follow or edit your profile elsewhere on Pubky.

Within that limit, an agent you run acts for you. A deck an agent creates is published under your
key and is public, and you are responsible for it as if you had made it by hand. The commands that
import or add cards offer a dry run that reports what would be written; use it.

A sign-in link shown by the tool is a login secret until you approve it. Do not paste it anywhere
other people can read.

**Approving other apps with Loopky.** If Loopky holds your key, it can approve another app's
sign-in, such as the `loopky` tool, the connector for Claude or any other Pubky app, from Settings →
Sessions. Approving gives that app the access its request lists, under your key, until you revoke
it there. The name a request shows is chosen by the app that made it, so approve only a sign-in you
started yourself, in an app you trust. What that app then does with its access is between you and
the app, under its own terms, not these. Loopky refuses a request for your whole account or for
every app's data; use Pubky Ring if you mean to grant one.

---

## 8. Third-party services

Loopky contacts a small number of services run by other parties, each under its own terms: your
homeserver, the Pubky sign-in relay and indexer, Homegate when you sign up, Unsplash when you
search for a picture, your device's speech services for Listen and Speak, and Google Play, the App
Store or TestFlight for the app itself. The [privacy policy](https://loopky.app/privacy/) lists what
reaches each one.

---

## 9. Children

Loopky is not directed at children under 13.

---

## 10. Liability

To the fullest extent the law allows, the Loopky project and its contributors are not liable for
any claim, damages or other liability arising from the software or its use. That includes lost
decks or study progress, a lost key or account, content published by you or by an agent acting for
you, anything an app you approved does with its access, and anything a homeserver or another third-party service does or fails to do.

Some places do not allow some of these limits. Where that is so, they apply only as far as the law
permits.

---

## 11. Changes to these terms

Material changes will be published here with an updated date above. The revision history of this
document is public in the repository, so every change is inspectable.

---

## 12. Contact

Questions about these terms: open an issue at
[github.com/jvsena42/loopky/issues](https://github.com/jvsena42/loopky/issues). For help using
Loopky, see [loopky.app/support](https://loopky.app/support/).
