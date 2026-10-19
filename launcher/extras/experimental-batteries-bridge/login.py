#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""EXPERIMENTAL / UNOFFICIAL. Read README.md first.

Signs in once (account, password, 2FA), makes the web session trusted, saves its cookies in
session/ and hands the session over to the TV:

    .venv/bin/python login.py --device HOST:PORT [--store-password]

Default (recommended): session-token-only. The password is used for this sign-in only; it is not
written to disk, not put in a keychain and not sent to the TV. When the web session expires (often
within hours), run this script again.

--store-password (opt-in, NOT recommended): additionally sends the password to the TV, RSA-encrypted to
a non-exportable Android Keystore key, so the TV can renew the session by itself.
"""
import argparse
import getpass
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import tvsession  # noqa: E402

from pyicloud import PyiCloudService  # noqa: E402
from pyicloud.exceptions import (  # noqa: E402
    PyiCloudAcceptTermsException,
    PyiCloudAccountLockedException,
    PyiCloudFailedLoginException,
    PyiCloudNoTrustedNumberAvailable,
)

STORE_PASSWORD_WARNING = """
!!! WARNING: --store-password !!!
Your account password will be stored on the TV (RSA-encrypted to a key inside the TV's Android
Keystore). Anyone who controls the TV or the CouchKit Home app may be able to use it. Automated
sign-ins from a TV can get your account flagged or locked. This is unofficial and unsupported.
Type YES to continue, anything else to continue WITHOUT storing the password: """


def patch_mfa_options():
    """Workaround for a pyicloud issue: after SRP, when 2FA is needed, the options page returns 409 + HTML
    and pyicloud raises from the constructor. Only that GET is done with the raw request."""
    orig = PyiCloudService._get_mfa_auth_options

    def patched(self):
        sess = self.session
        real_get = sess.get

        def raw_get(url, **kw):
            if url == self._auth_endpoint:
                return sess.request_raw("GET", url, **kw)
            return real_get(url, **kw)

        sess.get = raw_get
        try:
            return orig(self)
        finally:
            del sess.get

    PyiCloudService._get_mfa_auth_options = patched


def fail(msg):
    print(f"\nx {msg}")
    sys.exit(1)


TERMS_MSG = ("The provider asks you to accept updated terms. This script does not accept terms for you:\n"
             "sign in once in a web browser, review and accept them there, then run this script again.")


def trust(api):
    try:
        return api.trust_session()
    except PyiCloudAcceptTermsException:
        fail(TERMS_MSG)


def two_factor(api):
    try:
        if not api.request_2fa_code():
            fail("This account requires a security key (FIDO); not supported.")
    except PyiCloudNoTrustedNumberAvailable:
        fail("No trusted phone number found.")
    except Exception:
        # Requesting an SMS failed: use the code already shown on a trusted device
        api.use_existing_trusted_device_code()
    if api.two_factor_delivery_notice:
        print(api.two_factor_delivery_notice)
    print("A 6-digit code was sent to one of your trusted devices.")
    for left in (2, 1, 0):
        code = input("2FA code: ").strip().replace(" ", "")
        try:
            ok = api.validate_2fa_code(code)
        except PyiCloudAcceptTermsException:
            fail(TERMS_MSG)
        if ok:
            break
        if left == 0:
            fail("2FA code could not be verified.")
        print(f"Wrong code, {left} attempt(s) left.")


def two_step(api):
    devices = api.trusted_devices or []
    if not devices:
        fail("No trusted device for two-step verification.")
    for i, d in enumerate(devices):
        print(f"  {i}: {d.get('deviceName') or 'SMS ' + str(d.get('phoneNumber', ''))}")
    idx = int(input("Send the code to which device? [0]: ").strip() or 0)
    device = devices[idx]
    if not api.send_verification_code(device):
        fail("Could not send the code.")
    if not api.validate_verification_code(device, input("Code: ").strip()):
        fail("Code could not be verified.")


def show_batteries(api):
    """Checks the device list works (the TV filters the same way). Failure = nothing is sent."""
    try:
        devices = list(api.devices)
    except Exception as e:
        fail(f"Device list not reachable, nothing sent to the TV: {type(e).__name__}: {e}")
    shown = 0
    for dev in devices:
        d = dev.data
        level = float(d.get("batteryLevel") or 0)
        status = d.get("batteryStatus") or "Unknown"
        if level <= 0 and status == "Unknown":
            continue
        bolt = " (charging)" if status in ("Charging", "Charged") else ""
        print(f"  {d.get('name', '?'):<28} {round(level * 100)}%{bolt}")
        shown += 1
    if not shown:
        print(f"  ({len(devices)} devices, none reports a battery level right now)")


def force_password_check():
    """Drops the saved session token so pyicloud cannot reuse an old session and report success
    without ever checking the password. Trust token and cookies stay (usually no new 2FA prompt)."""
    import glob
    import json
    for path in glob.glob(os.path.join(tvsession.SESSION_DIR, "*.session")):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            if data.pop("session_token", None) is not None:
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(data, f)
        except (OSError, ValueError):
            pass


def main():
    parser = argparse.ArgumentParser(description="EXPERIMENTAL: sign in once and hand the session to the TV.")
    tvsession.device_arg(parser)
    parser.add_argument("--store-password", action="store_true",
                        help="opt-in: also store the password on the TV (encrypted). Not recommended.")
    args = parser.parse_args()

    store_password = args.store_password
    if store_password and input(STORE_PASSWORD_WARNING).strip() != "YES":
        store_password = False
        print("Continuing in session-token-only mode.")

    os.makedirs(tvsession.SESSION_DIR, mode=0o700, exist_ok=True)
    os.chmod(tvsession.SESSION_DIR, 0o700)

    saved = tvsession.read_account()
    account = input(f"Account{f' [{saved}]' if saved else ''}: ").strip() or saved
    if not account:
        fail("Account required.")
    password = getpass.getpass("Password (hidden): ")
    force_password_check()
    patch_mfa_options()

    try:
        api = PyiCloudService(account, password, cookie_directory=tvsession.SESSION_DIR,
                              with_family=tvsession.WITH_FAMILY, refresh_interval=None)
    except PyiCloudAcceptTermsException:
        fail(TERMS_MSG)
    except PyiCloudAccountLockedException:
        fail("The account appears to be locked. Unlock it through the provider's official recovery page.")
    except PyiCloudFailedLoginException as e:
        fail(f"Sign-in failed (wrong account or password?): {e}")

    if api.requires_2fa:
        two_factor(api)
    elif api.requires_2sa:
        two_step(api)
    if not api.is_trusted_session:
        trust(api)
    if not api.is_trusted_session:
        fail("Session could not be made trusted; nothing sent to the TV.")

    print("\nChecking device list...")
    show_batteries(api)
    with open(tvsession.ACCOUNT_FILE, "w", encoding="utf-8") as f:
        f.write(account + "\n")
    os.chmod(tvsession.ACCOUNT_FILE, 0o600)
    print("ok: signed in.")

    password_enc = None
    if store_password:
        password_enc = tvsession.encrypt_for_tv(args.device, password)
        if not password_enc:
            print("! The TV's public key is not available yet (open CouchKit Home once and wait for its first\n"
                  "  battery refresh). Continuing in session-token-only mode.")
    password = None

    print("\nSending the session to the TV...")
    try:
        ok = tvsession.push(args.device, tvsession.export(api, password_enc))
    except tvsession.LoginRequired as e:
        fail(f"Session could not be exported: {e}")
    if not ok:
        fail(f"Could not send to {args.device}. Re-send later without a password: "
             f".venv/bin/python tvsession.py --device {args.device}")
    mode = "with encrypted password (opt-in)" if password_enc else "session-token-only"
    print(f"ok: sent ({mode}). The TV refreshes batteries every 10 minutes until the session expires.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
        sys.exit(130)
