import base64
import unittest

from wireguard_site import render


class SiteConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.key = base64.b64encode(bytes(range(32))).decode()

    def test_only_peer_subnet_is_routed(self):
        config = render("192.168.1.0/24", "192.168.2.0/24", "10.88.0.1/30",
                        "10.88.0.2", self.key, "branch.example.test:51820", 51820)
        self.assertIn("AllowedIPs = 10.88.0.2/32, 192.168.2.0/24", config)
        self.assertNotIn("0.0.0.0/0", config)
        self.assertIn("private-key /etc/wireguard/%i.key", config)

    def test_overlapping_lans_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "distinct"):
            render("192.168.0.0/16", "192.168.2.0/24", "10.88.0.1/30",
                   "10.88.0.2", self.key, "branch.example.test:51820", 51820)

    def test_endpoint_and_local_address_reject_invalid_values(self):
        for endpoint, peer in (("host:51820\nPostUp=command", "10.88.0.2"), ("host:0", "10.88.0.2"),
                               ("host:51820", "10.88.0.1")):
            with self.subTest(endpoint=endpoint, peer=peer), self.assertRaises(ValueError):
                render("192.168.1.0/24", "192.168.2.0/24", "10.88.0.1/30", peer, self.key, endpoint, 51820)
