#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""EXPERIMENTAL / UNOFFICIAL. Read README.md first.

Hands the saved web session over to the TV (CouchKit Home, experimentalBatteries edition); the TV then
queries device batteries on its own. login.py calls this after signing in. It also works standalone to
re-send a still-valid saved session without 2FA (e.g. after the app's data was cleared):

    .venv/bin/python tvsession.py --device HOST:PORT

It never signs in with a password and never sends one. The TV moves the hand-off file into private
storage and deletes the external copy on first read.
"""
import argparse
import contextlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SESSION_DIR = os.path.join(HERE, "session")
ACCOUNT_FILE = os.path.join(SESSION_DIR, "account.txt")

FILES = "/sdcard/Android/data/io.couchkit.home/files"
REMOTE = f"{FILES}/icloud_session.json"
PUBKEY_REMOTE = f"{FILES}/bridge_pub.der"
ADB = shutil.which("adb") or "adb"

# Do not include other family members' devices
WITH_FAMILY = False


class LoginRequired(Exception):
    pass


def device_arg(parser):
    parser.add_argument("--device", required=True, metavar="HOST:PORT",
                        help="adb address of the TV, e.g. 192.0.2.10:5555")


def read_account():
    if not os.path.isfile(ACCOUNT_FILE):
        return ""
    with open(ACCOUNT_FILE, encoding="utf-8") as f:
        return f.read().strip()


def connect_saved():
    """Connects with the saved cookies only. authenticate=False: no keychain lookup, no password."""
    from pyicloud import PyiCloudService
    from pyicloud.exceptions import (
        PyiCloud2FARequiredException,
        PyiCloudAuthRequiredException,
        PyiCloudFailedLoginException,
    )

    account = read_account()
    if not account:
        raise LoginRequired("no saved session (run login.py)")
    api = PyiCloudService(account, cookie_directory=SESSION_DIR, with_family=WITH_FAMILY,
                          refresh_interval=None, authenticate=False)
    try:
        api.authenticate()
    except (PyiCloudFailedLoginException, PyiCloudAuthRequiredException,
            PyiCloud2FARequiredException) as e:
        raise LoginRequired(str(e)) from e
    if api.requires_2fa or api.requires_2sa or not api.is_trusted_session:
        raise LoginRequired("session not trusted")
    return api


def export(api, password_enc=None):
    """Everything the TV needs to query on its own. A password is included only if the user opted in
    with --store-password, and then only encrypted to the TV's Keystore key."""
    sd = api.session.data
    findme = (api.data.get("webservices") or {}).get("findme") or {}
    dsid = (api.data.get("dsInfo") or {}).get("dsid")
    if not sd.get("session_token") or not findme.get("url") or not dsid:
        raise LoginRequired("session_token / service url / dsid missing")
    cookies = []
    for c in api.session.cookies:
        cookies.append({
            "name": c.name,
            "value": c.value or "",
            "domain": c.domain,
            "path": c.path or "/",
            "expires": int(c.expires) * 1000 if c.expires else 0,
            "secure": bool(c.secure),
        })
    return {
        "v": 1,
        "exported": int(time.time() * 1000),
        "apple_id": api.account_name,
        "client_id": api.params.get("clientId") or sd.get("client_id"),
        "session_token": sd.get("session_token"),
        "trust_token": sd.get("trust_token", ""),
        "account_country": sd.get("account_country", ""),
        "dsid": str(dsid),
        "findme_url": findme["url"],
        "with_family": WITH_FAMILY,
        "cookies": cookies,
        **({"password_enc": password_enc} if password_enc else {}),
    }


def encrypt_for_tv(device, password):
    """Opt-in only (--store-password). Encrypts the password to the TV's Android Keystore public key
    (RSA-OAEP, SHA-256 / MGF1 SHA-1). The launcher writes the public key on its first battery query;
    the private key never leaves the Keystore. Returns None if the key is not available."""
    import base64
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    fd, tmp = tempfile.mkstemp(prefix="tvpub.", suffix=".der", dir=SESSION_DIR)
    os.close(fd)
    try:
        try:
            adb("connect", device, timeout=15)
            code, _ = adb("-s", device, "pull", PUBKEY_REMOTE, tmp)
        except (subprocess.TimeoutExpired, OSError):
            return None
        if code != 0 or os.path.getsize(tmp) == 0:
            return None
        with open(tmp, "rb") as f:
            key = load_der_public_key(f.read())
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.remove(tmp)
    ct = key.encrypt(password.encode("utf-8"), padding.OAEP(
        mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    return base64.b64encode(ct).decode()


def adb(*args, timeout=30):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    return r.returncode, (r.stdout + r.stderr).strip()


def push(device, payload):
    """Writes one file into the app's files/ folder on the TV; touches nothing else."""
    fd, tmp = tempfile.mkstemp(prefix="handoff.", suffix=".json", dir=SESSION_DIR)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        try:
            adb("connect", device, timeout=15)
            code, msg = adb("-s", device, "push", tmp, REMOTE)
        except subprocess.TimeoutExpired:
            print(f"x TV not reachable ({device}). Is it on and on the same network?")
            return False
        except OSError as e:
            print(f"x cannot run adb: {e}")
            return False
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.remove(tmp)
    if code != 0:
        print(f"x adb push failed: {msg}")
        return False
    return True


def main():
    parser = argparse.ArgumentParser(description="Re-send the saved session to the TV (no password, no 2FA).")
    device_arg(parser)
    args = parser.parse_args()
    try:
        api = connect_saved()
        ok = push(args.device, export(api))
    except LoginRequired as e:
        print(f"x saved session unusable ({e}). Run login.py.")
        return 1
    print("ok: session sent; the TV picks it up on its next refresh." if ok else "x not sent.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
