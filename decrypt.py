#!/usr/bin/env python3
"""Descifrado offline de lo que trae el harvest.

Subcomandos:
  exodus    <dir>        seed.seco/storage.seco (contenedor SECO v0)
  electrum  <wallet>     wallet JSON de Electrum, con o sin contrasena
  phantom   <dir>        chrome.storage.local de la extension Phantom
  cookies   <Cookies>    SQLite de cookies/Login Data de Chromium (v10)
  dpapi     ...          descifra un masterkey DPAPI del perfil

Dependencias: cryptography (obligatoria), plyvel (solo phantom).
"""

import argparse
import base64
import gzip
import hashlib
import hmac
import json
import os
import sqlite3
import struct
import sys
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HEADER_SIZE = 224
CHECKSUM_SIZE = 32
METADATA_SIZE = 256
SALT_SIZE, CIPHER_SIZE = 32, 32
BLOB_KEY_IV_SIZE, BLOB_KEY_TAG_SIZE, BLOB_KEY_KEY_SIZE = 12, 16, 32
BLOB_IV_SIZE, BLOB_TAG_SIZE = 12, 16


def _cbc_decrypt(key, iv, data):
    dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return dec.update(data) + dec.finalize()


def unpad(data):
    if not data:
        return data
    n = data[-1]
    return data[:-n] if 0 < n <= 16 and data[-n:] == bytes([n]) * n else data


def write_secret(path, data):
    with open(path, "wb") as out:
        out.write(data if isinstance(data, bytes) else data.encode())
    os.chmod(path, 0o600)
    print(f"-> {path}")


# --- exodus -----------------------------------------------------------------

def read_seco(path):
    data = open(path, "rb").read()
    if data[:4] != b"SECO":
        raise ValueError("no es un contenedor SECO")
    off = HEADER_SIZE + CHECKSUM_SIZE
    meta = data[off:off + METADATA_SIZE]
    off += METADATA_SIZE
    if len(meta) < METADATA_SIZE:
        raise ValueError("metadata truncada")
    if meta[SALT_SIZE + 12:SALT_SIZE + 12 + CIPHER_SIZE].rstrip(b"\0") != b"aes-256-gcm":
        raise ValueError("cifrado no soportado")
    n, r, p = struct.unpack(">LLL", meta[SALT_SIZE:SALT_SIZE + 12])
    cur = SALT_SIZE + 12 + CIPHER_SIZE
    key_iv = meta[cur:cur + BLOB_KEY_IV_SIZE]
    key_tag = meta[cur + BLOB_KEY_IV_SIZE:cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE]
    wrapped = meta[cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE:
                   cur + BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE + BLOB_KEY_KEY_SIZE]
    cur += BLOB_KEY_IV_SIZE + BLOB_KEY_TAG_SIZE + BLOB_KEY_KEY_SIZE
    blob_iv = meta[cur:cur + BLOB_IV_SIZE]
    blob_tag = meta[cur + BLOB_IV_SIZE:cur + BLOB_IV_SIZE + BLOB_TAG_SIZE]
    (size,) = struct.unpack(">L", data[off:off + 4])
    blob = data[off + 4:off + 4 + size]
    if len(blob) < size:
        raise ValueError("blob truncado")
    return dict(salt=meta[:SALT_SIZE], n=n, r=r, p=p, key_iv=key_iv,
                key_tag=key_tag, wrapped=wrapped, blob_iv=blob_iv,
                blob_tag=blob_tag, blob=blob)


def seco_decrypt(container, passphrase):
    if len(passphrase) < 4:
        raise ValueError("passphrase vacia")
    kek = hashlib.scrypt(passphrase, salt=container["salt"], n=container["n"],
                         r=container["r"], p=container["p"], dklen=32,
                         maxmem=256 * container["n"] * container["r"] + (1 << 22))
    data_key = AESGCM(kek).decrypt(container["key_iv"],
                                   container["wrapped"] + container["key_tag"], None)
    return AESGCM(data_key).decrypt(container["blob_iv"],
                                    container["blob"] + container["blob_tag"], None)


def _zstd(data):
    try:
        import zstandard
        return zstandard.ZstdDecompressor().decompress(data)
    except ImportError:
        import subprocess
        return subprocess.run(["zstd", "-dc"], input=data, capture_output=True,
                              check=True).stdout


def _inflate_all(data, start=0):
    """Infla miembros gzip concatenados saltando el framing intermedio."""
    out = b""
    pos = start
    while True:
        pos = data.find(b"\x1f\x8b", pos)
        if pos < 0:
            break
        dec = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            out += dec.decompress(data[pos:])
            out += dec.flush()
        except zlib.error:
            break
        if not dec.unused_data:
            break
        pos = len(data) - len(dec.unused_data)
    return out or None


def _gzip_chunks(data):
    """Stream de bloques: uint32 BE de longitud + gzip."""
    out = b""
    off = 0
    while off + 6 <= len(data):
        if data[off + 4:off + 6] != b"\x1f\x8b":
            break
        (size,) = struct.unpack(">L", data[off:off + 4])
        if size == 0:
            break
        try:
            out += gzip.decompress(data[off + 4:off + 4 + size])
        except Exception:
            break
        off += 4 + size
    return out or None


def decode_payload(data):
    """Devuelve texto JSON desde el blob, descomprimiendo o saltando cabecera."""
    if data[4:6] == b"\x1f\x8b":
        chunks = _gzip_chunks(data)
        if chunks is not None:
            return "gzip-chunks", chunks.decode("utf-8")
    if b"\x1f\x8b" in data[:64]:
        try:
            return "gzip", _inflate_all(data).decode("utf-8")
        except Exception:
            pass
    for name, fn in (("gzip", gzip.decompress), ("zstd", _zstd),
                     ("zlib", zlib.decompress)):
        if data[:1] == b"\x78" or data[:2] == b"\x1f\x8b" or data[:4] == b"\x28\xb5\x2f\xfd":
            try:
                return name, fn(data).decode("utf-8")
            except Exception:
                pass
    index = data.find(b"\x1f\x8b")
    if 0 <= index < 64:
        try:
            return f"gzip+{index}", gzip.decompress(data[index:]).decode("utf-8")
        except Exception:
            pass
    for marker in (b'{"', b'{', b'["'):
        index = data.find(marker)
        if 0 <= index < 256:
            try:
                return f"json+{index}", data[index:].decode("utf-8")
            except Exception:
                pass
    return None, data.decode("utf-8", "replace")


def cmd_exodus(args):
    source = args.passphrase_json
    if not os.path.exists(source):
        source = os.path.join(args.dir, os.path.basename(source))
    meta = json.load(open(source))
    raw = meta["passphrase"]
    candidates = [("b64", base64.b64decode(raw)), ("literal", raw.encode())]
    seed = os.path.join(args.dir, "seed.seco")
    found = None
    chosen = None
    for name, pw in candidates:
        try:
            found = seco_decrypt(read_seco(seed), pw)
            chosen = pw
            print(f"clave valida ({name}), scrypt n={read_seco(seed)['n']}")
            break
        except Exception:
            found = None
    if found is None:
        sys.exit("la passphrase no abre el contenedor")

    write_secret("exodus_blob.bin", found)
    how, text = decode_payload(found)
    if how:
        print(f"contenido: {how}")
    else:
        print(f"contenido binario, primeros bytes: {found[:48].hex(' ')}")
        print(found[:200].decode("utf-8", "replace"))

    try:
        payload = json.loads(text)
    except Exception:
        write_secret(args.out, text)
        return

    mnemonic = payload.get("mnemonic") or payload.get("seed") or payload.get("secret")
    if mnemonic:
        write_secret(args.out, mnemonic + "\n")
    else:
        write_secret(args.out, text)

    if args.storage:
        path = os.path.join(args.dir, "storage.seco")
        if os.path.exists(path):
            try:
                plain = seco_decrypt(read_seco(path), chosen)
                write_secret("exodus_storage.json", plain)
            except Exception as exc:
                print(f"storage.seco: {exc}")


# --- electrum ---------------------------------------------------------------

def cmd_electrum(args):
    wallet = json.load(open(args.wallet))
    if not wallet.get("use_encryption"):
        for key in ("seed", "xprv", "master_private_keys"):
            if key in wallet:
                print(f"{key}: {wallet[key]}")
        return
    password = args.password or input("Contrasena de Electrum: ")
    key = hashlib.sha256(password.encode()).digest()
    out = {}
    for field in ("seed", "xprv"):
        if field not in wallet:
            continue
        blob = base64.b64decode(wallet[field])
        plain = unpad(_cbc_decrypt(key, blob[:16], blob[16:]))
        out[field] = base64.b64decode(plain).decode()
        print(f"{field}: {out[field]}")
    if out:
        write_secret(args.out, json.dumps(out, indent=2) + "\n")


# --- dpapi ------------------------------------------------------------------

# CALG -> (longitud de clave, hash, longitud de IV/bloque)
ALG = {
    0x660E: (16, "aes", 16),
    0x6610: (32, "aes", 16),
    0x8004: (20, "sha1", 64),
    0x800E: (16, "sha512", 128),
    0x8009: (20, "sha1", 64),
}


def _hash_name(alg):
    return ALG[alg][1]


def _aes_decrypt(key, iv, data):
    dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return dec.update(data) + dec.finalize()


def derive(passphrase, salt, keylen, count, hash_name):
    out = b""
    i = 1
    while len(out) < keylen:
        u = salt + struct.pack("!L", i)
        i += 1
        d = hmac.new(passphrase, u, hash_name).digest()
        for _ in range(count - 1):
            a = hmac.new(passphrase, d, hash_name).digest()
            d = bytes(x ^ y for x, y in zip(d, a))
        out += d
    return out[:keylen]


class Blob:
    """DPAPI_BLOB tal cual lo define Windows."""

    def __init__(self, data):
        self.raw = data
        cur = 4 + 16 + 4 + 16 + 4
        (desc_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4 + desc_len
        (self.crypt_alg,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        (alg_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4 + alg_len
        (salt_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        self.salt = data[cur:cur + salt_len]
        cur += salt_len
        (hk_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        self.hmac_key = data[cur:cur + hk_len]
        cur += hk_len
        (self.hash_alg,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        (alg_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4 + alg_len
        (hmac_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        self.hmac = data[cur:cur + hmac_len]
        cur += hmac_len
        (data_len,) = struct.unpack("<L", data[cur:cur + 4])
        cur += 4
        self.data = data[cur:cur + data_len]

    def decrypt(self, key):
        hash_name = _hash_name(self.hash_alg)
        block = ALG[self.hash_alg][2]
        key_hash = key if len(key) == 20 else hashlib.sha1(key).digest()
        session = hmac.new(key_hash, self.salt, hash_name).digest()
        if len(session) > block:
            session = hmac.new(session, digestmod=hash_name).digest()
        derived = session
        if len(derived) < ALG[self.crypt_alg][0]:
            derived += b"\0" * block
            ipad = bytes(b ^ 0x36 for b in derived[:block])
            opad = bytes(b ^ 0x5C for b in derived[:block])
            derived = (hashlib.new(hash_name, ipad).digest()
                       + hashlib.new(hash_name, opad).digest())
            derived = _des_parity(derived)
        return unpad(_aes_decrypt(derived[:ALG[self.crypt_alg][0]],
                                  b"\0" * ALG[self.crypt_alg][2], self.data))


def _des_parity(key):
    out = bytearray()
    for b in key:
        bits = f"{b:08b}"[:7]
        out.append(int(bits + ("1" if bits.count("1") % 2 == 0 else "0"), 2))
    return bytes(out)


def masterkey_decrypt(path, password):
    data = open(path, "rb").read()
    master_key_len = struct.unpack("<Q", data[96:104])[0]
    mk = data[128:128 + master_key_len]
    (version,) = struct.unpack("<L", mk[0:4])
    salt = mk[4:20]
    (count,) = struct.unpack("<L", mk[20:24])
    (hash_alg,) = struct.unpack("<L", mk[24:28])
    (crypt_alg,) = struct.unpack("<L", mk[28:32])
    cipher = mk[32:]
    hash_name = _hash_name(hash_alg)
    secret = hashlib.sha1(password.encode("utf-16le")).digest()
    blob = derive(secret, salt, ALG[crypt_alg][0] + ALG[crypt_alg][2], count, hash_name)
    clear = _aes_decrypt(blob[:ALG[crypt_alg][0]], blob[ALG[crypt_alg][0]:], cipher)
    key = clear[-64:]
    stored = clear[16:16 + ALG[hash_alg][0]]
    mac_key = hmac.new(secret, clear[:16], hash_name).digest()
    if hmac.new(mac_key, key, hash_name).digest()[:ALG[hash_alg][0]] != stored:
        raise ValueError("contrasena incorrecta o masterkey corrupto")
    return key


def unprotect(blob, masterkey):
    return blob.decrypt(masterkey)


def cmd_dpapi(args):
    print("masterkey:", masterkey_decrypt(args.file, args.password).hex())


def load_key(args):
    if args.key:
        return bytes.fromhex(args.key.replace(":", "").replace(" ", ""))
    if getattr(args, "keys_file", None):
        with open(args.keys_file, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith("v10="):
                    return bytes.fromhex(line[4:].strip().replace(":", ""))
        sys.exit(f"sin linea v10= en {args.keys_file}")
    state = json.load(open(args.local_state))
    blob = Blob(base64.b64decode(state["os_crypt"]["encrypted_key"])[5:])
    if args.masterkey:
        return unprotect(blob, bytes.fromhex(args.masterkey))
    try:
        import win32crypt
        return win32crypt.CryptUnprotectData(
            base64.b64decode(state["os_crypt"]["encrypted_key"])[5:],
            None, None, None, 0)[1]
    except ImportError:
        sys.exit("sin win32crypt: pasa --masterkey (hex) o --key (hex)")


# --- cookies ----------------------------------------------------------------

def cmd_cookies(args):
    key = load_key(args)
    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if "cookies" in tables:
        rows = conn.execute(
            "SELECT host_key, name, value, encrypted_value FROM cookies")
        for host, name, value, enc in rows:
            if not enc:
                print(f"{host}\t{name}\t{value}")
            elif enc[:3] == b"v20":
                print(f"{host}\t{name}\t<v20 app-bound>")
            elif enc[:3] != b"v10":
                print(f"{host}\t{name}\t<desconocido {enc[:3]!r}>")
            else:
                body = enc[3:]
                print(f"{host}\t{name}\t"
                      f"{AESGCM(key).decrypt(body[:12], body[12:], None).decode('utf-8', 'replace')}")
        return
    rows = conn.execute(
        "SELECT origin_url, username_value, password_value FROM logins")
    for origin, user, enc in rows:
        if not enc:
            continue
        if enc[:3] == b"v20":
            print(f"{origin}\t{user}\t<v20 app-bound>")
        elif enc[:3] == b"v10":
            body = enc[3:]
            print(f"{origin}\t{user}\t"
                  f"{AESGCM(key).decrypt(body[:12], body[12:], None).decode('utf-8', 'replace')}")


# --- phantom ----------------------------------------------------------------

def cmd_phantom(args):
    try:
        import plyvel
    except ImportError:
        sys.exit("phantom necesita plyvel: pip install plyvel")
    db = plyvel.DB(args.dir, create_if_missing=False)
    entries = {k: v for k, v in db}
    db.close()

    def chrome_unwrap(value):
        # 1 byte de esquema + blob DPAPI/propio del perfil
        if value[:1] == b"\x01":
            blob = Blob(value[1:])
            return unprotect(blob, bytes.fromhex(args.masterkey))
        return value

    wrapped_key = entries.get(b"key")
    if wrapped_key and args.masterkey:
        key = chrome_unwrap(wrapped_key)
    else:
        key = None

    for name in (b"vault", b"vaults"):
        if name not in entries:
            continue
        vault = json.loads(chrome_unwrap(entries[name]))
        print(f"{name.decode()}: salt/iteraciones {vault.get('iterations')} "
              f"{vault.get('kdf')}")
        if not (args.password and vault.get("salt") and vault.get("nonce")):
            continue
        from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
        kdf = hashlib.pbkdf2_hmac("sha256", args.password.encode(),
                                  base64.b64decode(vault["salt"]),
                                  int(vault.get("iterations", 10000)), dklen=32)
        nonce = base64.b64decode(vault["nonce"])
        data = base64.b64decode(vault["ciphertext"])
        print(ChaCha20Poly1305(kdf).decrypt(nonce, data, None).decode())


# --- cli --------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)

    ex = sub.add_parser("exodus")
    ex.add_argument("dir")
    ex.add_argument("-j", "--passphrase-json", default="passphrase.json")
    ex.add_argument("-o", "--out", default="exodus_seed.txt")
    ex.add_argument("--storage", action="store_true")
    ex.set_defaults(func=cmd_exodus)

    el = sub.add_parser("electrum")
    el.add_argument("wallet")
    el.add_argument("-p", "--password")
    el.add_argument("-o", "--out", default="electrum_seed.json")
    el.set_defaults(func=cmd_electrum)

    ph = sub.add_parser("phantom")
    ph.add_argument("dir")
    ph.add_argument("-p", "--password")
    ph.add_argument("--masterkey", help="masterkey DPAPI en hex")
    ph.set_defaults(func=cmd_phantom)

    co = sub.add_parser("cookies")
    co.add_argument("db")
    co.add_argument("--key", help="clave AES en hex")
    co.add_argument("--keys-file", help="keys.txt del harvest (linea v10=)")
    co.add_argument("--local-state")
    co.add_argument("--masterkey", help="masterkey DPAPI en hex")
    co.set_defaults(func=cmd_cookies)

    dp = sub.add_parser("dpapi")
    dp.add_argument("file", help="fichero masterkey de Protect/<SID>/")
    dp.add_argument("-p", "--password", required=True)
    dp.set_defaults(func=cmd_dpapi)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
