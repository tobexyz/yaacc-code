#!/usr/bin/env python3
"""Prepares the settings directory of a reference player that accepts only paired servers, and prints its pairing token.

The reference daemon (sendspin 9.1.0) has no command that shows its pairing token, so this script writes the identity and the
pairing store that the daemon reads at start (the file names and fields are those of that version; a test aid, not a
specification). Run it with the Python of the rig's virtual environment, which has the `cryptography` package:

    .sendspin-rig/venv/bin/python tools/sendspin-rig/rig-paired.py .sendspin-rig/player-8932
    tools/sendspin-rig/run-local.sh player 8932 PairedRig false        # unpaired access off
    ./gradlew :yaacc:cleanTestDebugUnitTest :yaacc:testDebugUnitTest --tests 'de.yaacc.sendspin.hub.HubRigTest' \
        -Dsendspin.rig.paired=127.0.0.1:8932 -Dsendspin.rig.token=SP:0...

The token is a secret of the player: do not publish it.
"""
import base64
import hashlib
import json
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: rig-paired.py <settings dir of the player>")
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    private = X25519PrivateKey.generate()
    raw = private.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    public = private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    psk = os.urandom(32)
    identity = os.path.join(directory, "identity-daemon.json")
    store = os.path.join(directory, "pairing-store-daemon.json")
    with open(identity, "w") as f:
        json.dump({"private_key": b64url(raw)}, f)
    with open(store, "w") as f:
        json.dump({
            "records": {},
            "pairing_config": {"dynamic_pin_enabled": False, "static_pin_enabled": False, "unpaired_access_enabled": False},
            "pairing_psk": {"psk_id": b64url(hashlib.sha256(b"sendspin-psk-id-v1" + psk).digest()), "psk": b64url(psk)},
            "static_pin": None,
            "pin_failures": 0,
            "last_playback_server_id": None,
        }, f)
    for path in (identity, store):
        os.chmod(path, 0o600)
    # token: "SP:" "0" base32 (RFC 4648, no padding, every 2 written as 9) of client_key || pairing_psk
    body = base64.b32encode(public + psk).decode().rstrip("=").replace("2", "9")
    print("SP:0" + body)


if __name__ == "__main__":
    main()
