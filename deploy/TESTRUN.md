# Trial run on a host

A one-off launch to look at the running system. **No autostart**: no service is installed, and
nothing comes up by itself after the machine reboots.

This is not suitable for permanent operation — that needs systemd and TLS, see `README.md`.

---

## What you need

- Java 21 or newer on the host. Check with `java -version`
- SSH access
- Port 8080 free (or any other, set by a flag below)

Opening the port to the outside is **neither needed nor wanted** — all access goes through an
SSH tunnel, see "Why through a tunnel".

---

## 1. Upload

From your local machine:

```bash
scp messenger.jar SHA256SUMS you@host:~/
```

On the host, check that what arrived is what you sent:

```bash
sha256sum -c SHA256SUMS
```

It should answer `messenger.jar: OK`. If it does not, the file was corrupted in transit;
upload it again. This jar is what hands the browser the code that encrypts the conversation,
so the check is not a formality.

## 2. Start

```bash
mkdir -p ~/anteroom-test
cd ~/anteroom-test

nohup java -jar ~/messenger.jar \
  --app.data-dir=$HOME/anteroom-test/data \
  --server.port=8080 \
  --app.public-url=http://localhost:8080 \
  > $HOME/anteroom-test/anteroom.log 2>&1 &

echo $! > $HOME/anteroom-test/anteroom.pid
```

`nohup … &` keeps the process alive after SSH disconnects. The PID goes into a file so there
is something to stop later.

The first start takes about twenty seconds: the database is created, the server key pair is
generated, migrations run.

## 3. Check that it came up

```bash
tail -40 ~/anteroom-test/anteroom.log
```

Three lines that should be there:

```
Отпечаток бандла (файлов: 17): sha256:9a2274282ab94fec2dbba1d8d6d6c1696e16bc87e147fa91167d0fc7e8e51779
Undertow started on port 8080 (http)
Владелец ещё не назначен. Ссылка действительна 24 часа:
http://localhost:8080/join#...
```

**The bundle fingerprint** should match the one in the release notes: it says the server is
serving browsers exactly that code.

**Copy the owner link in full, including the part after `#`.** It is printed once; the database
holds only its hash, and it cannot be recovered. Lose it before opening it and you stop the
server, delete `data/` and start over (see "Starting from scratch").

## 4. Open it in a browser

Bring up a tunnel from your local machine:

```bash
ssh -N -L 8080:localhost:8080 you@host
```

The command does not return while the tunnel is alive. Leave it in its own window.

Now open the printed link **as it is**, `http://localhost:8080/join#...` — it already points at
localhost, because that is what `--app.public-url` was set to.

For a second device, to test invitations and direct messages: bring up the same tunnel from
another machine, or open `http://127.0.0.1:8080` in the same browser — that is a different
origin, so a different key store and a different device identity.

---

## Why through a tunnel rather than the server's address

Not out of caution. Without it, half the system does not work.

**A browser exposes `crypto.subtle` only in a secure context** — HTTPS or `localhost`. On
`http://server-address:8080` it is simply absent, and everything that computes SHA-256 in the
browser breaks: issuing invitations and creating one-time notes. The camera used for key
transfer by QR also requires a secure context.

Through a tunnel the origin becomes `localhost`, and all of that works without configuring TLS
at all.

The second reason is substantive: over plain HTTP the bundle travels in the clear, and anyone
on the path can substitute it. End-to-end encryption does not save you — substituted JavaScript
hands over the keys itself. Setting up Let's Encrypt for a one-off test is not worth it, and a
tunnel closes the question entirely.

For permanent operation, put a TLS proxy in front (`deploy/Caddyfile`) and **make sure** the
application gets `--server.forward-headers-strategy=framework`. Without it the application
behind a proxy sees the scheme as `http`, the browser sends `Origin: https://…`, and Spring
rejects the WebSocket upgrade with 403: the page opens, and the feed never comes alive.

---

## Stopping

```bash
kill $(cat ~/anteroom-test/anteroom.pid)
```

The server shuts down cleanly: it finishes requests in flight and closes the database. This
takes a few seconds.

Check that it stopped:

```bash
pgrep -af messenger.jar || echo "not running"
```

If it hangs for some reason:

```bash
kill -9 $(cat ~/anteroom-test/anteroom.pid)
```

`-9` gives no chance to close the database properly. SQLite in WAL mode survives this — the
journal replays on the next open — but do not do it without reason.

The tunnel stops with `Ctrl+C` in its window.

---

## Starting again

The data is still there and the owner link will not be printed, because an owner already
exists. Just repeat the command from step 2. Rooms, members and unexpired messages are in
place.

## Starting from scratch

```bash
kill $(cat ~/anteroom-test/anteroom.pid)
rm -rf ~/anteroom-test/data
```

This erases the database, the server key and the attachments. Everything is recreated on the
next start and the owner link is printed again. Device identities live separately in the
browser and are unaffected — but they will get different cards in the new rooms.

## Removing it completely

```bash
kill $(cat ~/anteroom-test/anteroom.pid)
rm -rf ~/anteroom-test ~/messenger.jar ~/SHA256SUMS
```

Nothing is left on the system: no service was installed, nothing was added to autostart, no
ports were opened.

---

## If something is wrong

**`java: command not found`, or a version below 21.** Install JRE 21:
`apt install openjdk-21-jre-headless` or the equivalent for your system.

**"отказ по правам на server.key" in the log.** The file's permissions are too open:
`chmod 600 ~/anteroom-test/data/server.key`. This is not pedantry — substituting this key
allows impersonating the server during login.

**Port in use.** Change `--server.port=8080` to something else and do not forget the tunnel and
`--app.public-url`: all three numbers have to agree.

**The "create link" button does nothing, and the browser console shows an error about
`crypto`.** You did not go through the tunnel. See "Why through a tunnel".

**The page opens but the connection never establishes.** The tunnel forwards a TCP port and
WebSocket works over it fine — check that the tunnel is alive and that the port in the address
is the same one.

---

## What this mode does not have

- **Autostart.** The server does not come up after a reboot; this is deliberate.
- **TLS.** Nothing faces outward, SSH does the encrypting.
- **Backups.** Not needed for a test; for permanent operation see `deploy/backup.sh`.
- **A request body limit on the proxy.** There is no proxy — the application itself limits
  attachments to 20 MB.
