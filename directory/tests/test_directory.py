import asyncio
import hashlib
import hmac
import tempfile
import time
import unittest
import uuid
from pathlib import Path

from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

from server import Directory, encode, make_app, proof, realm_for


def public_bytes(key):
    return key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


class DirectoryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.host = ec.generate_private_key(ec.SECP256R1())
        self.member = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.other = ec.generate_private_key(ec.SECP256R1())
        self.realm = realm_for(public_bytes(self.host))
        self.other_realm = realm_for(public_bytes(self.other))
        self.pushed = []
        async def push(token, call_id):
            self.pushed.append((token, call_id))
            return True
        self.directory = Directory(Path(self.folder.name) / "calls.db",
            {self.realm: encode(public_bytes(self.host)), self.other_realm: encode(public_bytes(self.other))},
            "stun:call.example.test:3478", "turn:call.example.test:3478?transport=udp", "test-secret", push)
        self.client = TestClient(TestServer(make_app(self.directory, allow_internal_proxy=True)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.owner, self.ice = await self.join("99000", self.host)
        await self.owner.send_json({"type": "ROSTER", "entries": {"99001": encode(public_bytes(self.member))}})
        await self.until(lambda: self.directory.db.execute("SELECT 1 FROM roster").fetchone() is not None)

    async def until(self, condition):
        for _ in range(100):
            if condition():
                return
            await asyncio.sleep(0.01)
        self.fail("Server condition not reached")

    async def auth(self, number, key, realm=None, stale=None):
        realm = realm or self.realm
        ws = await self.client.ws_connect("/v1/socket")
        challenge = (await ws.receive_json(timeout=2))["challenge"]
        body = proof(realm, number, stale or challenge)
        signature = (key.sign(body, ec.ECDSA(hashes.SHA256())) if isinstance(key, ec.EllipticCurvePrivateKey)
                     else key.sign(body, padding.PKCS1v15(), hashes.SHA256()))
        await ws.send_json({"type": "AUTH", "realm": realm, "number": number, "signature": encode(signature)})
        return ws

    async def join(self, number, key, realm=None):
        ws = await self.auth(number, key, realm)
        response = await ws.receive_json(timeout=2)
        self.assertEqual(response["type"], "READY")
        return ws, response["ice"]

    async def send(self, ws, kind, call_id, **fields):
        await ws.send_json({"type": kind, "id": call_id, **fields})

    async def expect(self, ws, kind, call_id):
        msg = await ws.receive_json(timeout=2)
        self.assertEqual((msg["type"], msg["id"]), (kind, call_id))
        return msg

    async def test_callee_accepts_before_media_and_connection_can_resume(self):
        member, _ = await self.join("99001", self.member)
        call_id = str(uuid.uuid4())
        await self.send(self.owner, "INVITE", call_id, number="99001")
        self.assertEqual((await self.expect(member, "INVITE", call_id))["number"], "99000")
        await self.send(self.owner, "OFFER", call_id, payload="unaccepted")
        await self.send(member, "ACCEPT", call_id)
        await self.expect(self.owner, "ACCEPT", call_id)
        await self.send(self.owner, "OFFER", call_id, payload="accepted")
        self.assertEqual((await self.expect(member, "OFFER", call_id))["payload"], "accepted")
        await member.close()
        member, _ = await self.join("99001", self.member)
        await self.send(member, "REPAIR", call_id)
        await self.expect(self.owner, "REPAIR", call_id)
        await self.send(self.owner, "RESTART", call_id)
        await self.expect(member, "RESTART", call_id)
        await self.send(member, "END", call_id)
        await self.expect(self.owner, "END", call_id)

    async def test_lan_handoff_needs_both_authenticated_parties(self):
        member, _ = await self.join("99001", self.member)
        call_id = str(uuid.uuid4())
        await self.send(self.owner, "HANDOFF_REQUEST", call_id, number="99001", payload="caller")
        self.assertEqual((await self.expect(member, "HANDOFF_CHECK", call_id))["number"], "99000")
        self.assertIsNone(self.directory.row(self.realm, call_id))
        await self.send(member, "HANDOFF_CONFIRM", call_id, number="99000", payload="caller")
        await asyncio.sleep(0.02)
        self.assertIsNone(self.directory.row(self.realm, call_id))
        await self.send(member, "HANDOFF_CONFIRM", call_id, number="99000", payload="callee")
        self.assertEqual((await self.expect(member, "HANDOFF", call_id))["number"], "99000")
        self.assertEqual((await self.expect(self.owner, "HANDOFF", call_id))["number"], "99001")
        await self.send(self.owner, "OFFER", call_id, payload="handoff audio")
        self.assertEqual((await self.expect(member, "OFFER", call_id))["payload"], "handoff audio")

    async def test_offline_missed_push_and_reconnect_acknowledgement(self):
        member, _ = await self.join("99001", self.member)
        await member.send_json({"type": "PUSH", "token": "test-device-token"})
        await self.until(lambda: self.directory.db.execute("SELECT 1 FROM tokens").fetchone() is not None)
        await member.close()
        await self.until(lambda: (self.realm, "99001") not in self.directory.sessions)
        call_id = str(uuid.uuid4())
        await self.send(self.owner, "INVITE", call_id, number="99001")
        await self.until(lambda: self.directory.row(self.realm, call_id) is not None)
        with self.directory.db:
            self.directory.db.execute("UPDATE calls SET expires=0 WHERE id=?", (call_id,))
        await self.directory.sweep()
        await self.expect(self.owner, "TIMEOUT", call_id)
        self.assertEqual(self.pushed, [("test-device-token", call_id)])
        member, _ = await self.join("99001", self.member)
        missed = await self.expect(member, "MISSED", call_id)
        self.assertTrue(missed["pushed"])
        await self.send(member, "ACK", call_id)
        await self.until(lambda: self.directory.row(self.realm, call_id)["ack"] == 1)
        await self.directory.sweep()
        self.assertEqual(len(self.pushed), 1)

    async def test_replay_and_copied_number_cannot_authenticate(self):
        for ws in (
            await self.auth("99001", self.member, stale="previous-nonce"),
            await self.auth("99001", self.host),
            await self.auth("99001", self.member, realm=self.other_realm),
        ):
            self.assertEqual((await ws.receive(timeout=2)).type, WSMsgType.CLOSE)
            self.assertEqual(ws.close_code, 1008)

    async def test_revocation_disconnects_and_prevents_sign_in(self):
        member, _ = await self.join("99001", self.member)
        await self.owner.send_json({"type": "ROSTER", "entries": {}})
        self.assertEqual((await member.receive(timeout=2)).type, WSMsgType.CLOSE)
        rejected = await self.auth("99001", self.member)
        self.assertEqual((await rejected.receive(timeout=2)).type, WSMsgType.CLOSE)

    async def test_turn_credential_matches_coturn_rest_auth(self):
        turn = self.ice[1]
        self.assertGreater(int(turn["username"].split(":")[0]), int(time.time()))
        import base64
        self.assertEqual(turn["credential"], base64.b64encode(
            hmac.new(b"test-secret", turn["username"].encode(), hashlib.sha1).digest()).decode())

    async def test_unconfigured_number_returns_unavailable(self):
        call_id = str(uuid.uuid4())
        await self.send(self.owner, "INVITE", call_id, number="99002")
        await self.expect(self.owner, "UNAVAILABLE", call_id)
        self.assertIsNone(self.directory.row(self.realm, call_id))


if __name__ == "__main__":
    unittest.main()
