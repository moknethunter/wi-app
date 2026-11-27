# Wi-Call Internet and site routing

The Android app prefers the pinned local server. On an unavailable local route, it uses the configured HTTPS directory. An accepted local call can also move to Internet signaling if the local path fails and both authenticated phones confirm the same active call. The deployment uses a single directory process with a persistent SQLite database.

## Directory and TURN

1. Start the owner phone in Server mode. Use **نسخ المفتاح العام لإعداد خدمة الإنترنت** to copy its DER public key in URL-safe Base64. Add it to `/etc/wi-call/realms.json` as the value and the uppercase SHA-256 fingerprint of the decoded bytes as the property name. Register each independent owner as its own realm. Owner authentication publishes that owner's existing active user numbers and device public keys.
2. Install `directory/requirements.txt` into a Python 3.12 virtual environment at `/opt/wi-call/venv` and copy `directory/` to `/opt/wi-call/directory`. Install the supplied systemd unit and prepare `/etc/wi-call/directory.env` from the example with root-only permissions. Start the directory. The service binds only to loopback and stores SQLite in its systemd state directory.
3. Configure the public DNS name and use the supplied Caddy virtual host with `WI_DIRECTORY_HOST` set in the Caddy service. Check `https://your-directory-host/healthz` over HTTPS. Enter `https://your-directory-host` in **إعداد الاتصال عبر الإنترنت** on each Wi-Call phone. Connect the owner once to synchronize activation records. A previously activated client may then authenticate even when the owner phone is offline.
4. Run coturn with `use-auth-secret`, a strong `static-auth-secret` matching `WI_TURN_SECRET`, a configured realm, and a TLS certificate for the TURN hostname. Set its public relay address if behind NAT. Configure UDP 3478, TCP 5349, and the chosen UDP relay port range on the public host. Restrict coturn from relaying to private, loopback and infrastructure-only addresses. Test direct media and a call forced through TURN on distinct networks.
5. For FCM, create an Android app with application ID `com.mokasas.wikall` and build in a trusted environment with the Firebase client configuration variables `WI_FCM_APP_ID`, `WI_FCM_PROJECT`, `WI_FCM_SENDER_ID`, and `WI_FCM_API_KEY`. Configure the directory service with `WI_FCM_PROJECT` and Google Application Default Credentials authorized for FCM HTTP v1. The delivery queue also syncs when the callee reconnects without FCM. A FCM notification contains no phone number. The app obtains the number from the authenticated directory.

## Optional routed WireGuard sites

Run WireGuard on the two Linux gateways, with private keys under `/etc/wireguard/<interface>.key` mode 0600. Generate each site's `.conf` using `python3 deploy/wireguard_site.py --help`. Use distinct LAN subnets and opposite addresses from a third tunnel subnet. Supply the peer's public key and reachable UDP endpoint.

Enable IPv4 forwarding and routes to the remote LAN on the respective routers or use these gateways as their LANs' default routers. Apply an explicit firewall policy allowing only the intended inter-site traffic. Allow Wi-Call's TCP 8787 and the UDP media flow between the authorized phones. Run `wg-quick up <interface>` on each gateway. Check the handshake and routing, then use the pinned owner IP via **تغيير عنوان الخادم** on the client. Routed gateways do not automatically carry mDNS advertisements.
A WireGuard tunnel needs a reachable path between the gateways. Without an Internet path, use a direct link with routable endpoint addresses.

## Release acceptance

Run the CI Python and Android tests. Before a release, test upgrades in place from the deployed signing identity and keep the original activation files and Android Keystore. Confirm two physical Android phones can both hear audio on router Wi-Fi without Internet and on a hotspot hosted by each phone in turn. Confirm local preference when Internet is available, Wi-Fi to mobile data, two different Wi-Fi networks, forced TURN, switching Internet access during a call, moving an active call from LAN to the Internet, missed delivery after reconnect with and without FCM, and rejection of copied or revoked activation. For sites using WireGuard, validate with two routed gateways. Record actual device, Android version, and network type for each test.

## Optional routed EasyTier sites

Use EasyTier v2.6.4 on Linux gateways. Download `easytier-core` from the
official release and verify the archive checksum. Each gateway needs a Linux
TUN device, a LAN interface and a reachable private underlay IP, for example
from a direct Ethernet link or a private wireless backhaul.

| Gateway | LAN | Underlay endpoint | EasyTier address |
| --- | --- | --- | --- |
| A | 192.168.10.0/24 | 192.168.50.1:11010 | 10.88.0.1/24 |
| B | 192.168.20.0/24 | 192.168.50.2:11010 | 10.88.0.2/24 |

Generate one file per gateway with `python3 deploy/easytier_site.py`. For A
pass `--lan 192.168.10.0/24 --listen 192.168.50.1:11010 --peer
192.168.50.2:11010 --tunnel 10.88.0.1/24 --output gateway-a.toml`.
For B, use its own LAN, listener and tunnel address and list A's endpoint
as the peer. Use the same randomly generated network secret on all trusted
gateways. Repeat `--peer` to add additional reachable gateways. Every
participating LAN needs a distinct subnet.

1. Install the binary at `/usr/local/bin/easytier-core`, each gateway's
   configuration at `/etc/wikall/easytier.toml` with mode 0600, and
   `easytier-wikall.service` under `/etc/systemd/system/`.
2. Enable IPv4 forwarding on both gateways and permit UDP 11010 between their
   underlay addresses. Scope LAN/TUN forwarding to authorized WiKall devices,
   allowing signaling TCP 8787 and WebRTC UDP between those devices. Keep the
   firewall active and permit the intended traffic in both directions.
3. Use each gateway as its LAN's router or configure the LAN routers to send
   the other site's subnet through the corresponding gateway. Start
   `easytier-wikall.service` on both sites and check route advertisements and
   Linux routing in both directions before starting a call.
4. Point the WiKall client at the owner's LAN address using the manual server
   address setting and a signed activation. For automatic Internet fallback,
   configure the HTTPS directory on both phones and leave **محلي فقط** off.
   The call screen shows which path is active.
5. Unplug WAN and disable mobile data to verify bidirectional audio over the
   gateways. Then restore Internet connectivity, disconnect the private
   backhaul and check whether WiKall can place a call through the directory.
   Restore the backhaul and confirm that a new call prefers the local route.

The gateway config advertises only the LAN subnet, with external bootstrap and
public STUN disabled by the service. Internet fallback is provided by WiKall's
directory. The Android app uses the gateway through ordinary IP routing.
