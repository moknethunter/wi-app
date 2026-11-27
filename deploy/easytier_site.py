"""Generate a private EasyTier v2.6.4 configuration for a routed WiKall gateway."""

import argparse
import getpass
from ipaddress import IPv4Address, IPv4Interface, IPv4Network
import json
import os
from pathlib import Path
import re

PRIVATE_NETWORKS = tuple(map(IPv4Network, ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")))


def private(network):
    return any(network.subnet_of(block) for block in PRIVATE_NETWORKS)


def render(name, secret, tunnel, lan, listen, peers):
    tunnel = IPv4Interface(tunnel)
    lan = IPv4Network(lan)
    if not private(tunnel.network) or not private(lan) or tunnel.network.overlaps(lan):
        raise ValueError("The LAN and tunnel must be distinct private IPv4 networks")
    if tunnel.ip in (tunnel.network.network_address, tunnel.network.broadcast_address):
        raise ValueError("The tunnel address must identify a gateway host")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,48}", name):
        raise ValueError("Invalid network name")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", secret):
        raise ValueError("Use a randomly generated URL-safe network secret of at least 32 characters")

    def endpoint(value):
        address, separator, port = value.partition(":")
        ip = IPv4Address(address)
        if not private(IPv4Network(f"{ip}/32")) or ip in lan or ip in tunnel.network:
            raise ValueError("Endpoints must be private underlay addresses outside the LAN and tunnel")
        if not separator or not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535:
            raise ValueError("Expected an endpoint port between 1 and 65535")
        return f"udp://{ip}:{int(port)}"

    listener = endpoint(listen)
    peer_uris = list(dict.fromkeys(map(endpoint, peers)))
    if listener in peer_uris:
        raise ValueError("A gateway cannot use its own listener as a peer")

    lines = [
        f'ipv4 = "{tunnel}"',
        f'listeners = ["{listener}"]',
        'rpc_portal = "127.0.0.1:0"',
        "",
        "[network_identity]",
        f'network_name = "{name}"',
        f'network_secret = "{secret}"',
        "",
        "[flags]",
        "private_mode = true",
        "proxy_forward_by_system = true",
        "enable_exit_node = false",
        "enable_ipv6 = false",
        "accept_dns = false",
        "disable_encryption = false",
        "",
        "[[proxy_network]]",
        f'cidr = "{lan}"',
    ]
    for uri in peer_uris:
        lines.extend(("", "[[peer]]", f"uri = {json.dumps(uri)}"))
    return "\n".join(lines) + "\n"


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--network", default="wikall")
    cli.add_argument("--tunnel", required=True)
    cli.add_argument("--lan", required=True)
    cli.add_argument("--listen", required=True)
    cli.add_argument("--peer", action="append", default=[])
    cli.add_argument("--output", required=True, type=Path)
    args = cli.parse_args()
    secret = getpass.getpass("EasyTier network secret: ")
    try:
        content = render(args.network, secret, args.tunnel, args.lan, args.listen, args.peer)
        with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as config:
            config.write(content)
    except (ValueError, OSError) as error:
        cli.error(str(error))


if __name__ == "__main__":
    main()
