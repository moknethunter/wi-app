"""Wi-Call directory. Terminate public TLS at the reverse proxy."""
import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from collections import Counter

from aiohttp import web
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

HOST = "99000"
NUMBERS = {str(i) for i in range(99000, 100000)}
USER_LIMIT = 30
TTL = 30
RETENTION = 7 * 86400
MEDIA = {"OFFER", "ANSWER", "ICE", "REPAIR", "RESTART"}
ALLOW_INTERNAL_PROXY = web.AppKey("allow_internal_proxy", bool)


def encode(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def decode(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,2048}", value):
        raise ValueError("Invalid encoding")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def realm_for(key):
    return hashlib.sha256(key).hexdigest().upper()


def proof(realm, number, challenge):
    return f"Wi-Call directory authentication 1\n{realm}\n{number}\n{challenge}".encode()


def load_key(value, host=False):
    raw = decode(value)
    key = serialization.load_der_public_key(raw)
    if host:
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise ValueError("Expected P-256 server key")
    elif not isinstance(key, rsa.RSAPublicKey) or key.key_size != 2048:
        raise ValueError("Expected 2048-bit device key")
    return raw, key


def check_signature(key, signature, body):
    if isinstance(key, rsa.RSAPublicKey):
        key.verify(decode(signature), body, padding.PKCS1v15(), hashes.SHA256())
    else:
        key.verify(decode(signature), body, ec.ECDSA(hashes.SHA256()))


def safe_id(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value or value == str(uuid.UUID(int=0)):
        raise ValueError("Invalid call id")
    return value


class Directory:
    def __init__(self, database, realms, stun="", turn="", turn_secret="", push=None):
        self.realms = {}
        for realm, encoded in realms.items():
            data, key = load_key(encoded, host=True)
            if realm != realm_for(data):
                raise ValueError("Realm must be the SHA-256 fingerprint of its owner key")
            self.realms[realm] = key
        self.db = sqlite3.connect(database)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS roster(
                realm TEXT NOT NULL, number TEXT NOT NULL, signing TEXT NOT NULL,
                PRIMARY KEY(realm, number)
            );
            CREATE TABLE IF NOT EXISTS calls(
                realm TEXT NOT NULL, id TEXT NOT NULL, caller TEXT NOT NULL, callee TEXT NOT NULL,
                state TEXT NOT NULL, expires INTEGER NOT NULL, updated INTEGER NOT NULL,
                ack INTEGER NOT NULL DEFAULT 0, pushed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(realm, id)
            );
            CREATE TABLE IF NOT EXISTS tokens(
                realm TEXT NOT NULL, number TEXT NOT NULL, token TEXT NOT NULL,
                PRIMARY KEY(realm, number)
            );
        """)
        # A process restart ends active transports while keeping the missed-call queue.
        with self.db:
            self.db.execute("UPDATE calls SET state='ended' WHERE state='accepted'")
        self.stun, self.turn, self.turn_secret = stun, turn, turn_secret
        self.push = push
        self.sessions = {}
        self.invited = set()
        self.authenticating = Counter()
        self.offline_since = {}
        self.handoffs = {}

    def ice(self, number):
        result = [{"url": self.stun}] if self.stun else []
        if self.turn:
            user = f"{int(time.time()) + 1800}:{secrets.token_hex(8)}:{number}"
            secret = base64.b64encode(hmac.digest(self.turn_secret.encode(), user.encode(), "sha1")).decode()
            result.extend({"url": url, "username": user, "credential": secret} for url in self.turn.split(","))
        return result

    def row(self, realm, call_id):
        return self.db.execute("SELECT * FROM calls WHERE realm=? AND id=?", (realm, call_id)).fetchone()

    async def send(self, realm, number, item):
        target = self.sessions.get((realm, number))
        if not target or target.closed:
            return False
        try:
            await asyncio.wait_for(target.send_json(item), timeout=2)
            return True
        except (OSError, RuntimeError, asyncio.TimeoutError):
            return False

    async def pending(self, realm, number):
        now = int(time.time())
        for row in self.db.execute("""SELECT * FROM calls WHERE realm=? AND callee=? AND
                    ((state='ringing' AND expires>?) OR (state='missed' AND ack=0))
                    ORDER BY updated LIMIT 100""", (realm, number, now)).fetchall():
            key = realm, row["id"]
            if row["state"] == "ringing":
                if key in self.invited:
                    continue
                self.invited.add(key)
                if await self.send(realm, number, {"type": "INVITE", "id": row["id"], "number": row["caller"]}):
                    continue
                self.invited.discard(key)
            else:
                await self.send(realm, number, {"type": "MISSED", "id": row["id"], "number": row["caller"], "at": row["updated"], "pushed": bool(row["pushed"])})

    async def sweep(self):
        now = int(time.time())
        self.handoffs = {key: value for key, value in self.handoffs.items() if value[2] > now}
        expired = self.db.execute("SELECT * FROM calls WHERE state='ringing' AND expires<=?", (now,)).fetchall()
        with self.db:
            self.db.execute("UPDATE calls SET state='missed', updated=? WHERE state='ringing' AND expires<=?", (now, now))
            self.db.execute("DELETE FROM calls WHERE updated<?", (now - RETENTION,))
        for row in expired:
            self.invited.discard((row["realm"], row["id"]))
            await self.send(row["realm"], row["caller"], {"type": "TIMEOUT", "id": row["id"]})
            await self.send(row["realm"], row["callee"], {"type": "END", "id": row["id"]})
        for (realm, number), since in list(self.offline_since.items()):
            if now - since < 25:
                continue
            del self.offline_since[(realm, number)]
            rows = self.db.execute("SELECT * FROM calls WHERE realm=? AND (caller=? OR callee=?) AND state='accepted'",
                                   (realm, number, number)).fetchall()
            for row in rows:
                with self.db:
                    self.db.execute("UPDATE calls SET state='ended', updated=? WHERE realm=? AND id=?", (now, realm, row["id"]))
                other = row["callee"] if row["caller"] == number else row["caller"]
                await self.send(realm, other, {"type": "END", "id": row["id"]})
        pending = self.db.execute("SELECT * FROM calls WHERE state='missed' AND ack=0 AND pushed=0 ORDER BY updated LIMIT 100").fetchall()
        for row in pending:
            realm, callee = row["realm"], row["callee"]
            if (realm, callee) in self.sessions:
                await self.send(realm, callee, {"type": "MISSED", "id": row["id"], "number": row["caller"], "at": row["updated"], "pushed": bool(row["pushed"])})
            elif self.push:
                token = self.db.execute("SELECT token FROM tokens WHERE realm=? AND number=?", (realm, callee)).fetchone()
                if token and await self.push(token[0], row["id"]):
                    with self.db:
                        self.db.execute("UPDATE calls SET pushed=1 WHERE realm=? AND id=?", (realm, row["id"]))

    async def roster(self, realm, entries):
        if not isinstance(entries, dict) or len(entries) > USER_LIMIT or set(entries) - (NUMBERS - {HOST}):
            raise ValueError("Invalid roster")
        for value in entries.values():
            load_key(value)
        previous = dict(self.db.execute("SELECT number, signing FROM roster WHERE realm=?", (realm,)))
        with self.db:
            self.db.execute("DELETE FROM roster WHERE realm=?", (realm,))
            self.db.executemany("INSERT INTO roster VALUES(?,?,?)", ((realm, num, key) for num, key in entries.items()))
            for num, key in previous.items():
                if entries.get(num) != key:
                    self.db.execute("DELETE FROM tokens WHERE realm=? AND number=?", (realm, num))
                    self.db.execute("UPDATE calls SET state='ended', updated=? WHERE realm=? AND (caller=? OR callee=?) AND state IN ('ringing','accepted','missed')",
                                    (int(time.time()), realm, num, num))
        for num, key in previous.items():
            if entries.get(num) != key and (target := self.sessions.get((realm, num))):
                await target.close(code=1008, message=b"Enrollment revoked")

    def busy(self, realm, number):
        return self.db.execute("""SELECT 1 FROM calls WHERE realm=? AND
             (caller=? OR callee=?) AND state IN ('ringing','accepted') AND (state='accepted' OR expires>?) LIMIT 1""",
             (realm, number, number, int(time.time()))).fetchone() is not None

    async def handle(self, realm, number, message):
        kind = message.get("type")
        if kind == "ROSTER" and number == HOST:
            await self.roster(realm, message.get("entries"))
            return
        if kind == "PUSH":
            token = message.get("token")
            if not isinstance(token, str) or not re.fullmatch(r"[a-zA-Z0-9:_-]{10,512}", token):
                raise ValueError("Invalid push token")
            with self.db:
                self.db.execute("INSERT OR REPLACE INTO tokens VALUES(?,?,?)", (realm, number, token))
            return
        if kind == "SYNC":
            await self.pending(realm, number)
            return
        call_id = safe_id(message.get("id"))
        row = self.row(realm, call_id)
        now = int(time.time())
        if kind == "ACK":
            with self.db:
                self.db.execute("UPDATE calls SET ack=1 WHERE realm=? AND id=? AND callee=? AND state='missed'", (realm, call_id, number))
            return
        if kind in ("HANDOFF_REQUEST", "HANDOFF_CONFIRM"):
            if not row:
                await self.handoff(realm, number, call_id, kind, message, now)
            return
        if kind == "INVITE":
            to = message.get("number")
            if not isinstance(to, str) or to not in NUMBERS or to == number:
                raise ValueError("Invalid callee")
            if row:
                return
            if to != HOST and not self.db.execute("SELECT 1 FROM roster WHERE realm=? AND number=?", (realm, to)).fetchone():
                await self.send(realm, number, {"type": "UNAVAILABLE", "id": call_id})
                return
            if self.busy(realm, number) or self.busy(realm, to):
                await self.send(realm, number, {"type": "BUSY", "id": call_id})
                return
            with self.db:
                self.db.execute("INSERT INTO calls (realm,id,caller,callee,state,expires,updated) VALUES (?,?,?,?,?,?,?)",
                                (realm, call_id, number, to, "ringing", now + TTL, now))
            await self.pending(realm, to)
            return
        if not row or number not in (row["caller"], row["callee"]) or row["state"] not in ("ringing", "accepted"):
            return
        to = row["callee"] if number == row["caller"] else row["caller"]
        state = row["state"]
        outgoing = {"type": kind, "id": call_id}
        if state == "ringing" and now >= row["expires"]:
            return
        if kind == "ACCEPT" and number == row["callee"] and state == "ringing":
            state = "accepted"
        elif kind in ("REJECT", "BUSY") and number == row["callee"] and state == "ringing":
            state = "ended"
        elif kind == "TIMEOUT" and number == row["callee"] and state == "ringing":
            state = "missed"
        elif kind == "END":
            state = "missed" if state == "ringing" else "ended"
        elif kind in MEDIA and state == "accepted":
            if (kind in ("OFFER", "RESTART") and number != row["caller"]) or (kind in ("ANSWER", "REPAIR") and number != row["callee"]):
                return
            if kind in ("OFFER", "ANSWER", "ICE"):
                payload = message.get("payload")
                if not isinstance(payload, str) or not payload or len(payload.encode()) > (1200 if kind == "ICE" else 11000):
                    raise ValueError("Invalid media signal")
                outgoing["payload"] = payload
        else:
            return
        with self.db:
            self.db.execute("UPDATE calls SET state=?,updated=? WHERE realm=? AND id=?", (state, now, realm, call_id))
        if state != "ringing":
            self.invited.discard((realm, call_id))
        await self.send(realm, to, outgoing)

    async def handoff(self, realm, number, call_id, kind, message, now):
        peer, role = message.get("number"), message.get("payload")
        if not isinstance(peer, str) or peer not in NUMBERS or peer == number or role not in ("caller", "callee"):
            raise ValueError("Invalid handoff")
        caller, callee = (number, peer) if role == "caller" else (peer, number)
        key = realm, call_id
        if self.busy(realm, caller) or self.busy(realm, callee):
            return
        pending = self.handoffs.get(key)
        if pending and pending[2] <= now:
            self.handoffs.pop(key, None)
            pending = None
        if kind == "HANDOFF_REQUEST" and (not pending or pending[0] == number):
            if len(self.handoffs) >= 256 and not pending:
                return
            self.handoffs[key] = (number, peer, now + 15, caller, callee)
            await self.send(realm, peer, {"type": "HANDOFF_CHECK", "id": call_id, "number": number})
            return
        if not pending or pending[:2] != (peer, number) or pending[3:] != (caller, callee):
            return
        if any((realm, member) not in self.sessions for member in (caller, callee)):
            return
        self.handoffs.pop(key, None)
        with self.db:
            self.db.execute("INSERT INTO calls (realm,id,caller,callee,state,expires,updated) VALUES (?,?,?,?,?,?,?)",
                            (realm, call_id, caller, callee, "accepted", now + TTL, now))
        await self.send(realm, callee, {"type": "HANDOFF", "id": call_id, "number": caller})
        await self.send(realm, caller, {"type": "HANDOFF", "id": call_id, "number": callee})

    async def socket(self, request):
        if not request.secure and not request.app[ALLOW_INTERNAL_PROXY]:
            raise web.HTTPUpgradeRequired()
        ip = request.remote
        if self.authenticating[ip] >= 16 or len(self.sessions) >= 256:
            raise web.HTTPTooManyRequests()
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=16 * 1024)
        self.authenticating[ip] += 1
        identity = None
        try:
            await ws.prepare(request)
            challenge = secrets.token_urlsafe(24)
            await ws.send_json({"type": "CHALLENGE", "challenge": challenge})
            auth = await ws.receive_json(timeout=8)
            realm, number = auth["realm"], auth["number"]
            if auth.get("type") != "AUTH" or realm not in self.realms or number not in NUMBERS:
                raise ValueError("Unknown identity")
            key = self.realms[realm]
            if number != HOST:
                row = self.db.execute("SELECT signing FROM roster WHERE realm=? AND number=?", (realm, number)).fetchone()
                if not row:
                    raise ValueError("Unknown member")
                _, key = load_key(row[0])
            check_signature(key, auth["signature"], proof(realm, number, challenge))
            identity = realm, number
        except (ValueError, TypeError, KeyError, RuntimeError, binascii.Error, InvalidSignature, asyncio.TimeoutError):
            await ws.close(code=1008, message=b"Authentication failed")
            return ws
        finally:
            self.authenticating[ip] -= 1
            if not self.authenticating[ip]:
                del self.authenticating[ip]

        old = self.sessions.get(identity)
        self.sessions[identity] = ws
        self.offline_since.pop(identity, None)
        if old is not None:
            await old.close(code=1000, message=b"Session renewed")
        realm, number = identity
        try:
            await ws.send_json({"type": "READY", "ice": self.ice(number)})
            await self.pending(realm, number)
            start, count = time.monotonic(), 0
            async for frame in ws:
                if frame.type != web.WSMsgType.TEXT or self.sessions.get(identity) is not ws:
                    break
                if time.monotonic() - start >= 5:
                    start, count = time.monotonic(), 0
                count += 1
                if count > 160:
                    break
                try:
                    message = json.loads(frame.data)
                    if not isinstance(message, dict):
                        raise ValueError("Expected object")
                    await self.handle(realm, number, message)
                except (ValueError, KeyError, TypeError):
                    await ws.close(code=1008, message=b"Invalid signal")
                    break
        finally:
            if self.sessions.get(identity) is ws:
                del self.sessions[identity]
                self.offline_since[identity] = int(time.time())
                for row in self.db.execute("SELECT id FROM calls WHERE realm=? AND callee=? AND state='ringing'", identity):
                    self.invited.discard((realm, row[0]))
        return ws


def make_app(directory, *, allow_internal_proxy=False):
    app = web.Application(client_max_size=32 * 1024)
    app[ALLOW_INTERNAL_PROXY] = allow_internal_proxy
    app.router.add_get("/v1/socket", directory.socket)
    app.router.add_get("/healthz", health)

    async def maintain(_):
        async def tick():
            while True:
                await asyncio.sleep(2)
                await directory.sweep()
        task = asyncio.create_task(tick())
        yield
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        directory.db.close()

    app.cleanup_ctx.append(maintain)
    return app


async def health(_):
    return web.json_response({"ok": True})


if __name__ == "__main__":
    from push import FirebasePush
    with open(os.environ["WI_REALMS_FILE"], encoding="utf-8") as config:
        realms = json.load(config)
    project = os.environ.get("WI_FCM_PROJECT")
    turn, secret = os.environ.get("WI_TURN_URLS", ""), os.environ.get("WI_TURN_SECRET", "")
    if turn and len(secret) < 32:
        raise ValueError("Configure a TURN secret of at least 32 characters")
    directory = Directory(os.environ.get("WI_DB", "directory.sqlite3"), realms, os.environ.get("WI_STUN", ""),
                          turn, secret, FirebasePush(project) if project else None)
    address = os.environ.get("WI_BIND", "127.0.0.1")
    context = None
    if os.environ.get("WI_TLS_CERT"):
        import ssl
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(os.environ["WI_TLS_CERT"], os.environ["WI_TLS_KEY"])
    if context is None and address != "127.0.0.1":
        raise ValueError("Direct unencrypted network bind is disabled")
    web.run_app(make_app(directory, allow_internal_proxy=context is None), host=address,
                port=int(os.environ.get("WI_PORT", "9080")), ssl_context=context)
