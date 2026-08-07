# Security Policy

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** on the Security tab of this repository.

Please do not open a public issue for anything affecting confidentiality of messages, keys or
membership. There is no bug bounty — this is a self-hosted project maintained by one person,
and reports are handled on a best-effort basis.

## Supported versions

The tip of `main` only. There are no maintenance branches and no backported fixes.

## What has and has not been reviewed

**The project has never been audited by anyone outside it.** The cryptography is conventional —
libsodium primitives used in their intended shapes — but conventional is not the same as
reviewed. Anyone choosing a messenger for a situation where being wrong carries real
consequences should prefer software that professionals have examined.

There is an internal security checklist of fourteen items, run as an automated test before
every release:

```bash
./gradlew test --tests '*AcceptanceTest*'
```

It covers the invariants below. It does not substitute for review by someone who did not write
the code.

## Invariants

A change that breaks any of these is a blocker, not technical debt.

1. **Plaintext never leaves the browser.** The server stores ciphertext only.
2. **Secrets travel in the URL fragment**, after `#`. The fragment does not reach the server,
   does not appear in web server logs and is not sent in `Referer`. Code that moves a fragment
   secret into a query parameter or a path is a bug.
3. **The server stores only SHA-256 hashes of tokens**, never the tokens themselves.
4. **Device private keys live in the browser's IndexedDB** and are never transmitted.
5. **Full URLs, message contents and member public keys are never logged.** IP addresses exist
   only in the rate limiter's memory and are never written to disk.

## Known limitations

These are design trade-offs, documented rather than hidden. They are not accepted as
vulnerability reports on their own — but a way to make any of them worse, or a mistake in how
one is implemented, is.

- **Ciphertext length leaks.** Message padding is not implemented yet, so the size of a message
  is visible to the server. This is the most concrete gap on the list.
- **The server sees metadata**: who is in which room, when they write, message size, chosen
  lifetime.
- **A removed member keeps what they already read** and can decrypt earlier key epochs. The
  five-day lifetime ceiling exists to bound this.
- **Attachments in direct messages are not supported**, because a blob is a single object and
  the server sees who downloads it. The fan-out that hides the recipient of a text message
  would not hide the recipient of a file.
- **Attachments cannot be scanned for malware.** With end-to-end encryption the server sees
  only ciphertext. The interface says so next to every file rather than burying it in a help
  page.
- **The operator can substitute the served code.** Self-hosting turns this into "you can attack
  yourself"; the bundle fingerprint printed at every start exists so that an operator can check
  what is actually being served.
