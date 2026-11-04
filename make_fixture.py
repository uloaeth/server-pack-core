#!/usr/bin/env python3
"""Build a synthetic harvest to exercise decrypt.py end to end."""
import base64
import hashlib
import hmac
import json
import os
import shutil
import sqlite3
import struct
import sys
import uuid
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root = Path(sys.argv[1] if len(sys.argv) > 1 else "fixture")
shutil.rmtree(root, ignore_errors=True)
sid = "S-1-5-21-1111-2222-3333-1001"
protect = root / "dpapi" / "Protect" / sid
protect.mkdir(parents=True)
password = "Hunter2!"
secret = hashlib.sha1(password.encode("utf-16le")).digest()


def cbc(key, iv, data):
    enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return enc.update(data) + enc.finalize()


def make_masterkey(key, guid, material_secret=None, folder=None):
    secret = material_secret or hashlib.sha1(password.encode("utf-16le")).digest()
    salt = os.urandom(16)
    count, hash_alg, crypt_alg = 1000, 0x8004, 0x6610  # sha1 / aes-256
    material = b""
    index = 1
    while len(material) < 32 + 16:
        block = hmac.new(secret, salt + struct.pack("!L", index), "sha1").digest()
        index += 1
        for _ in range(count - 1):
            again = hmac.new(secret, block, "sha1").digest()
            block = bytes(x ^ y for x, y in zip(block, again))
        material += block
    mk_key, iv = material[:32], material[32:48]
    clear = os.urandom(16) + b"\0" * 20 + b"\0" * 28 + key
    stored = hmac.new(hmac.new(secret, clear[:16], "sha1").digest(), key, "sha1").digest()
    clear = clear[:16] + stored + clear[36:]
    mk = (struct.pack("<L", 2) + salt + struct.pack("<LLL", count, hash_alg, crypt_alg)
          + cbc(mk_key, iv, clear))
    header = b"\0" * 96 + struct.pack("<Q", len(mk)) + b"\0" * 24
    (folder or protect).joinpath(guid).write_bytes(header + mk)


def make_blob(plain, guid, master=None):
    master = master or masterkey
    salt = os.urandom(32)
    hmac_key = os.urandom(32)
    session = hmac.new(hashlib.sha1(master).digest(), salt, "sha1").digest()
    material = session + b"\0" * 64
    ipad = bytes(b ^ 0x36 for b in material[:64])
    opad = bytes(b ^ 0x5C for b in material[:64])
    material = hashlib.sha1(ipad).digest() + hashlib.sha1(opad).digest()
    material = bytes(
        int(f"{b:08b}"[:7] + ("1" if f"{b:08b}"[:7].count("1") % 2 == 0 else "0"), 2)
        for b in material)
    pad = 16 - len(plain) % 16
    cipher = cbc(material[:32], b"\0" * 16, plain + bytes([pad]) * pad)
    tag = hmac.new(hmac_key, cipher, "sha1").digest()
    desc = "Google Chrome".encode("utf-16-le") + b"\0\0"
    return (struct.pack("<L", 1)
            + bytes.fromhex("d08c9ddf0115d1118c7a00c04fc297eb")
            + struct.pack("<L", 1)
            + bytes.fromhex(guid.replace("-", ""))
            + struct.pack("<L", 0x10)
            + struct.pack("<L", len(desc)) + desc
            + struct.pack("<L", 0x6610)
            + struct.pack("<L", 12) + b"AES-256-CBC\0"
            + struct.pack("<L", len(salt)) + salt
            + struct.pack("<L", len(hmac_key)) + hmac_key
            + struct.pack("<L", 0x8004)
            + struct.pack("<L", 0) + b""
            + struct.pack("<L", len(tag)) + tag
            + struct.pack("<L", len(cipher)) + cipher)


masterkey = os.urandom(64)
guid = str(uuid.uuid4())
make_masterkey(masterkey, guid)

machinekey = os.urandom(20)
system_guid = str(uuid.uuid4())
system_dir = root / "dpapi" / "Protect" / "S-1-5-18"
system_dir.mkdir(parents=True)
system_master = os.urandom(64)
make_masterkey(system_master, system_guid, machinekey, system_dir)
(protect / "Preferred").write_text(guid + "\n")

aes = os.urandom(32)
app_key = os.urandom(32)
app_bound = b"APPB" + make_blob(app_key, system_guid, system_master)
chrome = root / "browsers" / "chrome"
(chrome / "Default" / "Network").mkdir(parents=True)
(chrome / "Local State").write_text(json.dumps({"os_crypt": {
    "encrypted_key": "DPAPI" + base64.b64encode(make_blob(aes, guid)).decode(),
    "app_bound_encrypted_key": base64.b64encode(app_bound).decode()}}))
(chrome / "keys.txt").write_text(
    "browser=chrome\nprofiles=Default\napp_bound=" + base64.b64encode(app_bound).decode() + "\n")

db = sqlite3.connect(chrome / "Default" / "Network" / "Cookies")
db.execute("CREATE TABLE cookies (host_key TEXT, name TEXT, value TEXT, encrypted_value BLOB)")
for host, name, value in ((".example.com", "session", "s3cr3t-cookie"),
                          ("v20.example.com", "app", "app-bound-cookie"),
                          ("github.com", "user_session", "gh-token-abc")):
    nonce = os.urandom(12)
    prefix = b"v20" if host.startswith("v20") else b"v10"
    key = app_key if host.startswith("v20") else aes
    enc = prefix + nonce + AESGCM(key).encrypt(nonce, value.encode(), None)
    db.execute("INSERT INTO cookies VALUES (?,?,?,?)", (host, name, "", enc))
db.commit()
db.close()

logins = sqlite3.connect(chrome / "Default" / "Login_Data")
logins.execute("CREATE TABLE logins (origin_url TEXT, username_value TEXT, password_value BLOB)")
nonce = os.urandom(12)
logins.execute("INSERT INTO logins VALUES (?,?,?)",
               ("https://example.com", "alice",
                b"v10" + nonce + AESGCM(aes).encrypt(nonce, b"p4ssw0rd!", None)))
logins.commit()
logins.close()

history = sqlite3.connect(chrome / "Default" / "History")
history.execute("CREATE TABLE urls (url TEXT, title TEXT, visit_count INTEGER)")
history.execute("INSERT INTO urls VALUES ('https://example.com/a', 'Example', 9)")
history.commit()
history.close()

token = "M" + "x" * 24 + "." + "y" * 6 + "." + "z" * 27
discord = root / "wallets" / "bfnaelmomeimhlpmgjnjophhpkkoljpa" / "Default"
discord.mkdir(parents=True)
blob = make_blob(json.dumps({"t": token, "e": "false"}).encode(), guid)
(discord / "000005.ldb").write_bytes(
    b"\x00_" + b"token\x01dQw4w9WgXcQ:" + base64.b64encode(blob) + b"\x00")

print("fixture:", root)
print("password:", password)
print("machinekey:", machinekey.hex())
