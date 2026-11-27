import tomllib
import unittest

from easytier_site import render


class EasyTierSiteTests(unittest.TestCase):
    def setUp(self):
        self.options = {
            "name": "wikall-test",
            "secret": "test-only-secret-012345678901234567890",
            "tunnel": "10.88.0.1/24",
            "lan": "192.168.10.0/24",
            "listen": "192.168.50.1:11010",
            "peers": ["192.168.50.2:11010"],
        }

    def test_routes_only_the_local_site(self):
        config = tomllib.loads(render(**self.options))
        self.assertEqual(config["proxy_network"], [{"cidr": "192.168.10.0/24"}])
        self.assertTrue(config["flags"]["proxy_forward_by_system"])
        self.assertFalse(config["flags"]["enable_exit_node"])
        self.assertFalse(config["flags"]["disable_encryption"])
        self.assertEqual(config["rpc_portal"], "127.0.0.1:0")
        self.assertEqual(config["peer"], [{"uri": "udp://192.168.50.2:11010"}])

    def test_rejects_public_and_overlapping_routes(self):
        for update in (
            {"lan": "0.0.0.0/0"},
            {"lan": "10.88.0.0/24"},
            {"peers": ["8.8.8.8:11010"]},
            {"peers": ["192.168.10.10:11010"]},
            {"peers": ["192.168.50.1:11010"]},
        ):
            with self.subTest(update=update), self.assertRaises(ValueError):
                render(**(self.options | update))

    def test_rejects_invalid_secret(self):
        for secret in ("short", '"\n[flags]\nenable_exit_node=true'):
            with self.subTest(), self.assertRaises(ValueError):
                render(**(self.options | {"secret": secret}))
