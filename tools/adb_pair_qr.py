"""
ADB wireless pairing helper — auto-discovers your phone's pairing service via
mDNS as soon as you enable "Pair device with pairing code" on the phone, then
prompts for the 6-digit code shown on the phone.

This is the reliable alternative to Android Studio's QR-based pair flow. The
vanilla `adb` CLI does *not* implement the inbound pairing-service side of
the QR protocol (that lives only inside Android Studio's bundled daemon), so
we go the other direction: phone advertises a pairing service via mDNS, PC
discovers it.

Usage:
    python adb_pair_qr.py

On your phone:
    Settings -> Developer options -> Wireless debugging -> ENABLE the toggle
    -> tap "Pair device with pairing code".
    A box appears showing IP, port, and a 6-digit code.

Back in this terminal:
    The script auto-detects the IP/port via mDNS, then asks you to type the
    code.

Requires: zeroconf and the `adb` binary on PATH.
"""

import io
import os
import socket
import subprocess
import sys
import time
from typing import Optional, Tuple

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass

from zeroconf import ServiceBrowser, ServiceListener, Zeroconf


PAIRING_SERVICE = "_adb-tls-pairing._tcp.local."


def find_adb() -> str:
    candidates = [
        os.environ.get("ADB"),
        r"C:\Users\ardi\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        r"C:\Android\Sdk\platform-tools\adb.exe",
        "adb",
    ]
    for c in candidates:
        if not c:
            continue
        try:
            subprocess.run([c, "version"], check=True,
                           capture_output=True, timeout=5)
            return c
        except (FileNotFoundError, subprocess.CalledProcessError,
                subprocess.TimeoutExpired):
            continue
    raise SystemExit("adb not found on PATH or known locations")


class PairListener(ServiceListener):
    def __init__(self) -> None:
        self.found: Optional[Tuple[str, int, str]] = None  # (ip, port, name)

    def remove_service(self, zc, type_, name): ...
    def update_service(self, zc, type_, name): ...

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if not info or not info.addresses:
            return
        ip = socket.inet_ntoa(info.addresses[0])
        self.found = (ip, info.port, name)


def discover_phone_pairing(timeout_s: int = 120) -> Optional[Tuple[str, int, str]]:
    """Block until a phone announces `_adb-tls-pairing._tcp` on the LAN, or timeout."""
    zc = Zeroconf()
    listener = PairListener()
    try:
        ServiceBrowser(zc, PAIRING_SERVICE, listener)
        deadline = time.time() + timeout_s
        last_print = 0.0
        while time.time() < deadline:
            if listener.found:
                return listener.found
            now = time.time()
            if now - last_print > 5:
                remaining = int(deadline - now)
                print(f"  ...still waiting ({remaining}s left)", flush=True)
                last_print = now
            time.sleep(0.5)
        return None
    finally:
        zc.close()


def main() -> int:
    adb = find_adb()

    print()
    print("  === ADB Wireless Pairing Helper ===")
    print()
    print("  On your phone:")
    print("    1. Settings -> Developer options -> Wireless debugging -> turn ON")
    print("    2. Tap 'Pair device with pairing code'")
    print("    3. A box appears with IP, port, and a 6-digit code")
    print()
    print("  Searching the LAN for your phone's pairing service via mDNS...")
    print("  (Make sure phone and PC are on the same Wi-Fi.)")
    print()

    found = discover_phone_pairing(timeout_s=120)
    if not found:
        print("  Timed out — no pairing service detected. Either:")
        print("    - Phone and PC are on different Wi-Fi networks")
        print("    - Wireless debugging was not enabled / pair dialog not opened")
        print("    - Router blocks mDNS (try `adb pair <ip>:<port>` manually with")
        print("      values from the phone's pair dialog)")
        return 1

    ip, port, service_name = found
    print()
    print(f"  Found pairing service: {service_name}")
    print(f"  Phone: {ip}:{port}")
    print()
    try:
        code = input("  Type the 6-digit code from the phone: ").strip()
    except (EOFError, KeyboardInterrupt):
        print("\n  Cancelled.")
        return 130

    if not code.isdigit() or len(code) < 4:
        print("  That doesn't look like a pairing code. Try again.")
        return 1

    print(f"\n  Pairing with {ip}:{port}...")
    result = subprocess.run(
        [adb, "pair", f"{ip}:{port}", code],
        capture_output=True, text=True, timeout=30,
    )
    print("  adb pair output:")
    for line in (result.stdout + result.stderr).splitlines():
        if line.strip():
            print(f"    {line}")

    if result.returncode == 0 and "Successfully paired" in (result.stdout + result.stderr):
        print()
        print("  ✓ Paired successfully.")
        print()
        print("  Now look at your phone's Wireless debugging screen — under")
        print("  'IP address & Port' there is a *different* port (the connect")
        print("  port, not the pair port). Use it like:")
        print(f"    adb connect {ip}:<CONNECT_PORT>")
        print()
        print("  Or just run `adb devices` — if mdns auto-connect is on, the")
        print("  device will appear automatically.")
        return 0
    else:
        print()
        print("  Pairing failed. Common causes:")
        print("    - Wrong code (it expires in ~30s — try again with fresh code)")
        print("    - Pair dialog was closed before pairing completed")
        return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nCancelled.")
        sys.exit(130)
