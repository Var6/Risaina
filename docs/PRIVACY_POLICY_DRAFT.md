# Privacy policy (DRAFT — review before any public release)

Risaina is a private app for two people. This draft describes how it handles data.

**What we collect.** Your email address and a display name (for your account), the messages,
pings, photos and voice notes you choose to send, optional dates you add to the timeline, your
notification preferences, and a push-notification token for your device. No analytics, no
advertising identifiers, no contacts. Location only while you share it (see "Live location").

**Where it lives.** On servers you (the operator) run: MongoDB for records and Cloudflare R2 for
photos and voice notes, both access-controlled. Data in transit is encrypted with TLS. Content is
not end-to-end encrypted; the operator of the server can technically access it.

**Who can see it.** Only you and the one partner you paired with. Nothing is shared with third
parties. Firebase Cloud Messaging is used to wake the app; the push payload contains identifiers
only, never content.

**Trivia questions.** For a trivia duel the server (never your phone) asks Open Trivia Database
(opentdb.com) for questions, sending only a category and a difficulty. No data about you or your
partner is sent.

**Notifications and widgets.** You control whether previews appear in notifications and what
widgets may show. Quiet hours stop alerts during the hours you choose.

**Permissions.** Microphone (only while you record a voice note), notifications (only if you
allow them), wallpaper (only when you explicitly apply one), location (only while you share it;
never in the background otherwise).

**Live location (optional).** Shared only when you turn it on, or when you send a panic alert with
location sharing allowed. While it is on, your partner sees your latest position, its accuracy and
your battery level, and your phone shows a notification the whole time. The server keeps only the
latest position, never a history, and deletes it as soon as you stop, when the time you chose ends,
when you sign out, unlink or delete your account, and after 12 hours without an update. Positions are
never written into notifications, push messages or logs. Your partner cannot request your location.

**Your choices.** Export your data as JSON from Settings → Privacy. Unlink your partner at any
time. Delete your account to remove your profile, sessions, devices, and media; the pairing ends
for both people.

**Retention.** Data is kept until you delete it or your account. Abandoned uploads are removed
after 24 hours.

**Contact.** [operator contact to be added]
