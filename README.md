# Anteroom

End-to-end encrypted web messenger. It opens in a browser, so there is nothing to install.
Access is by invitation only — no email address, no phone number, no username. Messages are
deleted when their lifetime expires.

You host it yourself: one jar file and a data directory.

The interface is in English by default and can be switched to Russian from the key sheet.
Russian documentation: [README.ru.md](README.ru.md).

---

## Status

Working software, deployed and used, but **it has never been audited by anyone outside the
project.** The cryptography is standard and the constructions are conventional, which is not
the same thing as a review. Read [Security](#security) before you rely on this for anything
that matters.

## What it guarantees

- The server cannot read the conversation. It stores ciphertext and never holds the keys.
- No email address, phone number or name is requested or stored anywhere.
- A message is erased from disk when its lifetime runs out — whether or not anyone read it,
  and whether or not the server was running the whole time.
- An invitation can be limited by lifetime and by number of uses. Once redeemed, it no
  longer opens the room.

## What it does not give you

Read this before anyone relies on it.

- **Deletion on the other side is not guaranteed.** The message disappears from the interface
  and from the server, but a screenshot, a photograph of the screen or a modified client will
  keep it. No messenger solves this.
- **The server sees metadata**: who, when, in which room, how large the message is and how
  long it lives. Not the content, but the fact of the conversation.
- **A removed member keeps whatever they already read** and can decrypt the history of earlier
  key epochs. That is exactly why the maximum message lifetime is five days.
- **Virus scanning of attachments is impossible in principle** — the server only ever sees
  ciphertext.
- The operator of the server can substitute the code served to the browser. When you host it
  yourself this means "you can attack yourself", but if the server belongs to someone else,
  trusting them is a requirement, not an option.

---

## Quick start

You need Java 21 and a reverse proxy that terminates TLS. Caddy is the simplest.

```bash
./gradlew bootJar          # build/libs/messenger.jar
java -jar build/libs/messenger.jar --app.data-dir=./data
```

For a one-off trial run on a host, without installing a service or enabling autostart, see
[deploy/TESTRUN.md](deploy/TESTRUN.md). It goes over an SSH tunnel, which is not caution for
its own sake: over plain HTTP a browser does not expose `crypto.subtle`, and invitations and
one-time notes stop working.

### Installing as a service

```bash
sudo useradd -r -s /usr/sbin/nologin messenger
sudo mkdir -p /opt/messenger /var/lib/messenger
sudo cp messenger.jar /opt/messenger/
sudo chown -R messenger:messenger /var/lib/messenger
```

Ready-made files live in `deploy/`, so there is nothing to retype:

```bash
sudo cp deploy/messenger.service /etc/systemd/system/
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile   # change the domain
sudo cp deploy/backup.sh /usr/local/bin/messenger-backup
```

The access log in the supplied `Caddyfile` is configured so that it does not become a
connection history: the query string is stripped entirely and the address is truncated to
its subnet. By the design of the system there are no secrets in the query string — those
live in the URL fragment and never reach the server — but the login challenge, the device
key and the signature do appear there, and there is no reason to put them on disk.

```bash
sudo systemctl enable --now messenger
sudo journalctl -u messenger -f
```

### First start

Starting with an empty data directory, the server creates the database, generates its own
key pair and prints an owner invitation link to the log:

```
Владелец ещё не назначен. Ссылка действительна 24 часа:
https://chat.example.org/join#hT9x...QaZ.k4Vb...9Lm
```

Open it in a browser and you become the owner. **The link is printed once**; on later starts
it is not printed at all, because an owner already exists. If you lose it before opening it,
stop the server, delete the database and start over — there is no other data yet.

Copy the link in full, including the part after `#`. The key is in there, and without it the
link is useless.

---

## Configuration

By command-line flag or in `application.yml`:

| Setting | Default | What it does |
|---|---|---|
| `app.data-dir` | `./data` | Data directory: database, blobs, server key |
| `server.port` | `8080` | Port |
| `app.file.max-bytes` | `22020096` | Ceiling on file size (ciphertext) |
| `app.file.room-quota-bytes` | `2147483648` | Quota per room |
| `app.file.disk-quota-bytes` | `21474836480` | Quota for the whole server |
| `server.undertow.max-http-post-size` | `22085632` | Container's ceiling on the request body |
| `server.forward-headers-strategy` | unset | **Must be `framework` behind a TLS proxy** |
| `app.invite-ttl-hours` | `24` | Default invitation lifetime |
| `app.work.bits` | `18` | Proof-of-work cost for rooms and invitations; `0` disables it |
| `app.limit.create-per-hour` | `5` | Rooms per hour per device |
| `app.limit.create-per-hour-ip` | `20` | Rooms per hour per address |
| `app.limit.invite-per-hour` | `20` | Invitations per hour per device |
| `app.limit.write-per-minute` | `60` | Writes per minute: feed, direct messages, notes, attachments |
| `app.challenge.per-ip-limit` | `30` | Login challenges per address per minute |
| `app.challenge.max-live` | `10000` | Ceiling on outstanding challenges |
| `app.challenge.max-addresses` | `20000` | Ceiling on addresses counted in one window |

Rate-limiter counters live in memory and are never written to disk: IP addresses are among
the keys, and storing them would be precisely the connection log that has no business
existing here. Restarting the server resets the counters.

The proof of work on creating a room is not a CAPTCHA but processor time: at 18 bits a
browser spends a fraction of a second, while someone creating rooms by the thousand pays a
thousand times that. A CAPTCHA would require a third-party service, which means an outside
observer at every login. On a closed server, where a handful of people who know each other
create the rooms, the check can be turned off entirely with `app.work.bits=0`.

### One setting is mandatory behind a reverse proxy

```
--server.forward-headers-strategy=framework
```

Without it the application sees the scheme as `http`, because the proxy has already
terminated TLS, while the browser sends `Origin: https://…`. Spring treats these as different
origins and **rejects the WebSocket upgrade with 403**. The page still opens and looks like it
works; the feed simply never comes alive, which from the outside resembles anything except the
actual cause. Look for `403` on `/ws` in the proxy log.

It is deliberately off by default: it makes the application trust `X-Forwarded-*` headers,
which is only acceptable when there really is a proxy of yours in front. An application
exposed directly to the internet would believe a forged header.

`server.undertow.max-http-post-size` must stay above `app.file.max-bytes`, and the two numbers
should only ever be changed together. If the container's ceiling ends up lower, attachments
stop arriving: Undertow drops the connection before the application can answer with a clear
refusal.

Message lifetimes are chosen in the room itself rather than in configuration: 48 hours by
default, five days maximum.

### What lives in the data directory

```
/var/lib/messenger/
├── messenger.db        rooms, members, invitations, message ciphertexts
├── messenger.db-wal    write-ahead log
├── server.key          the server's key pair, mode 600
└── blobs/              encrypted files, one per attachment
```

The server refuses to start if the permissions on `server.key` are too open. That is not
pedantry: substituting this key allows impersonating the server during login.

### Containers

For people who already run containers. The main path is systemd, above.

```bash
docker compose up -d --build
```

`docker-compose.yml` brings up the application with Caddy in front of it. Data lives in the
named volume `messenger-data`, and the application is not published outside — Caddy terminates
TLS.

**A container hides the data directory behind a volume, and backups are easy to forget.** That
volume is exactly what needs copying: the server key, the database and the attachments are in
it. Inside the container it is the same `/var/lib/messenger`, and `deploy/backup.sh` works
there too.

---

## How it works

Spring Boot, a single process. WebSocket for messages, ordinary HTTP for files, SQLite for
data, the filesystem for attachments. The client is static content inside the same jar.

**Login.** The server issues a one-time challenge, the browser signs it with the device key,
and the signature is verified when the connection is established. There is no password.

**Identity.** A device has two key pairs: Ed25519 for signing and X25519 for encryption. Both
are derived deterministically from a 32-byte seed generated in the browser on first use and
stored in IndexedDB. The seed can be exported as 24 BIP-39 words, as a passphrase-encrypted
`key.enc` file, or transferred to another device by QR code.

**Rooms.** A room has a symmetric key (XChaCha20-Poly1305) encrypted separately for each
member's X25519 key. Changing the membership starts a new key epoch. Removing a member rotates
the epoch, so they stop receiving new messages immediately; what they already read stays with
them, which is why the lifetime ceiling is low.

**Invitations.** The token is generated in the browser and only its SHA-256 hash reaches the
server. The link carries `#<token>.<ephemeral private key>` in the fragment — the room key
itself is not in the link. It sits on the server wrapped under the ephemeral public key and is
deleted when the invitation is redeemed. Putting the room key in the fragment directly would
turn every forwarded link into a permanent key to the room.

**Names.** A member gets a playing card rather than a chosen nickname, derived from
`HMAC(room_id, public key)`. It cannot be picked, so it cannot be forged. Cards are never
reused, which caps a room at 52 members over its whole lifetime. The card is a memory aid; the
actual verification is the fingerprint and a four-word phrase compared over another channel.

**Direct messages** live inside a room and are hidden from the server by fan-out: the text is
encrypted once with a random key, and that key is placed into 52 envelopes, one per seat. The
recipient's slot holds a real sealed box, the rest hold random bytes of the same length. Sealed
box output is indistinguishable from random, so the server does not learn the recipient. The
schema has no sender or recipient column; the signature is inside the ciphertext.

**Language.** English by default, Russian by a switch in the key sheet; the choice lives in
`localStorage` and switching reloads the page. There is no auto-detection from the browser
locale: it would turn one link into two different pages for two people and make bug reports
unverifiable. Server refusals travel as **codes**, not sentences — the server does not know
which language the browser chose, and a sentence assembled there cannot be translated. The
dictionary is a single file, [`static/js/i18n.js`](src/main/resources/static/js/i18n.js).

**Crypto is libsodium in the browser**, vendored into the jar rather than loaded from a CDN.
The server does not participate. Ed25519 verification on the server side is the JDK's own
implementation; no third-party crypto library is pulled in.

The design decisions and the reasoning behind them are in [CLAUDE.md](CLAUDE.md), which is in
Russian. It is the one document to read before changing anything: it records why each choice
was made and which invariants a change must not break.

---

## Building

```bash
./gradlew bootJar         # build/libs/messenger.jar
./gradlew test
./gradlew releaseHashes   # build/libs/SHA256SUMS
```

Java 21 is pinned by the Gradle toolchain, so the system `JAVA_HOME` does not affect the build
and the jar you build matches the one we build.

**The build is reproducible**: two runs over the same sources produce a byte-identical jar.
That means a published checksum can be reproduced rather than taken on trust.

This is not a claim you have to believe. CI builds the jar twice on a clean runner, with the
Gradle cache switched off so no restored output can fake the match, and compares the two files
byte for byte — see [`.github/workflows/ci.yml`](.github/workflows/ci.yml). The resulting
checksum is printed in the job summary.

Nor do you have to believe the CI. [`verify.sh`](verify.sh) performs the same two checks on
your own machine, needing nothing but a JDK 21 and this repository:

```bash
./verify.sh
```

### Bundle fingerprint

A checksum of the jar tells you the file was not tampered with in transit. The bundle
fingerprint answers a different question: whether the code the server is handing to browsers
right now is the code you think it is. It is printed on every start.

```bash
journalctl -u messenger | grep 'Отпечаток бандла'
```

It is computed over everything that goes to the browser — markup, modules and the vendored
crypto — rather than over the whole jar, because a jar also contains classes and dependencies
whose checksum changes for reasons unrelated to the served code.

### Tests

```bash
./gradlew test
./gradlew test --tests '*AcceptanceTest*'   # the security acceptance checklist
```

There are no JavaScript tests and no npm: libsodium is vendored without a bundler. The few
things that cannot be checked from Java — QR transfer, file encryption in the browser, direct
message envelopes — have assertion pages under `src/test/resources/browser/`, served at
`/browser/` by `./gradlew bootRun`. **Pages that touch device identity wipe the key storage
for their origin, so do not open them against a live deployment.**

---

## Security

The system is designed so that the server is transport and storage for ciphertext and nothing
else. The rules that follow from that are not negotiable and are documented in
[CLAUDE.md](CLAUDE.md):

1. Plaintext never leaves the browser.
2. Secrets travel in the URL fragment, after `#`. The fragment does not reach the server, does
   not appear in web server logs and is not sent in the `Referer` header. Any code that moves a
   fragment secret into a query parameter or a path is a bug.
3. The server stores only SHA-256 hashes of tokens, never the tokens themselves.
4. Device private keys live in the browser's IndexedDB and are never transmitted.
5. Full URLs, message contents and member public keys are never logged.

**Reporting a vulnerability.** Use GitHub's private vulnerability reporting on the Security tab
of this repository. Please do not open a public issue for anything that affects
confidentiality. There is no bug bounty; this is a self-hosted project maintained by one
person.

**No audit has been performed.** If you are choosing a messenger for a situation where being
wrong has serious consequences, prefer something that has been reviewed by people who do this
professionally.

---

## License

[GNU Affero General Public License v3.0](LICENSE).

AGPL rather than a permissive license on purpose. Trust in a web messenger rests on the served
code matching the published source — which is why the bundle fingerprint is printed at every
start. A license that allowed running a modified, closed server for other people would make
that check meaningless.

Vendored third-party code keeps its own licenses, listed in
[src/main/resources/static/vendor/PROVENANCE.md](src/main/resources/static/vendor/PROVENANCE.md).
