"""Generate one wg-quick config per routed Wi-Call gateway."""
import argparse
import base64
import ipaddress
import os
import re
from pathlib import Path


def render(local, remote, tunnel, peer_tunnel, public_key, endpoint, port):
    local = ipaddress.IPv4Network(local)
    remote = ipaddress.IPv4Network(remote)
    tunnel = ipaddress.IPv4Interface(tunnel)
    peer_tunnel = ipaddress.IPv4Address(peer_tunnel)
    lan_blocks = [ipaddress.IPv4Network(block) for block in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    if any(not any(network.subnet_of(block) for block in lan_blocks) for network in (local, remote, tunnel.network)):
        raise ValueError("Only private IPv4 sites are supported")
    if local.overlaps(remote) or local.overlaps(tunnel.network) or remote.overlaps(tunnel.network):
        raise ValueError("The two LANs and the tunnel subnet must be distinct")
    if peer_tunnel not in tunnel.network or peer_tunnel in (tunnel.ip, tunnel.network.network_address, tunnel.network.broadcast_address):
        raise ValueError("The peer needs a different address on the tunnel subnet")
    if not re.fullmatch(r"[A-Za-z0-9+/]{43}=", public_key) or len(base64.b64decode(public_key, validate=True)) != 32:
        raise ValueError("Expected a WireGuard public key")
    if not re.fullmatch(r"(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\]):[0-9]{1,5}", endpoint):
        raise ValueError("Expected a peer hostname or address and port")
    if not 1 <= int(endpoint.rsplit(":", 1)[1]) <= 65535 or not 1 <= port <= 65535:
        raise ValueError("Invalid WireGuard port")
    return (
        "[Interface]\n"
        f"Address = {tunnel}\n"
        f"ListenPort = {port}\n"
        "PostUp = wg set %i private-key /etc/wireguard/%i.key\n\n"
        "[Peer]\n"
        f"PublicKey = {public_key}\n"
        f"AllowedIPs = {peer_tunnel}/32, {remote}\n"
        f"Endpoint = {endpoint}\n"
        "PersistentKeepalive = 25\n"
    )


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--lan", required=True)
    cli.add_argument("--peer-lan", required=True)
    cli.add_argument("--tunnel", required=True)
    cli.add_argument("--peer-tunnel", required=True)
    cli.add_argument("--peer-key", required=True)
    cli.add_argument("--peer-endpoint", required=True)
    cli.add_argument("--port", type=int, default=51820)
    cli.add_argument("--output", required=True, type=Path)
    args = cli.parse_args()
    content = render(args.lan, args.peer_lan, args.tunnel, args.peer_tunnel, args.peer_key, args.peer_endpoint, args.port)
    with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as config:
        config.write(content)


if __name__ == "__main__":
    main()
