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
    if material_secret:
        secret = material_secret
    else:
        pwd = hashlib.sha1(password.encode("utf-16le")).digest()
        secret = hmac.new(pwd, (sid + "\0").encode("utf-16le"), hashlib.sha1).digest()
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


def make_blob(plain, guid, master=None, entropy=None):
    master = master or masterkey
    salt = os.urandom(32)
    hmac_key = os.urandom(32)
    key_hash = hashlib.sha1(master).digest()
    padded = key_hash + b"\0" * 64
    inner = hashlib.sha1(bytes(b ^ 0x36 for b in padded[:64]) + salt).digest()
    session = hashlib.sha1(bytes(b ^ 0x5C for b in padded[:64]) + inner
                           + (entropy or b"")).digest()
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
            + uuid.UUID(guid).bytes_le
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
# Real ElevationService envelope, wrapped as SYSTEM then user DPAPI.
header = b"\x03\x00" + b"C:\\Program Files\\Google\\Chrome"
envelope = (struct.pack("<L", len(header)) + header
            + struct.pack("<L", len(app_key)) + app_key)
app_bound = b"APPB" + make_blob(make_blob(envelope, guid, masterkey), system_guid, system_master)
chrome = root / "browsers" / "chrome"
(chrome / "Default" / "Network").mkdir(parents=True)
(chrome / "Local State").write_text(json.dumps({"os_crypt": {
    "encrypted_key": "DPAPI" + base64.b64encode(make_blob(aes, guid)).decode(),
    "app_bound_encrypted_key": base64.b64encode(app_bound).decode()}}))
(chrome / "keys.txt").write_text(
    "browser=chrome\nprofiles=Default\napp_bound=" + base64.b64encode(app_bound).decode()
    + "\nv20=" + app_key.hex() + "\n")

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

# Flag 4 with a synthetic Google Chromekey1 (KSP) and the static XOR constant.
KSP_ENTROPY = b"xT5rZW5qVVbrvpuA\x00"
FLAG4_XOR = bytes.fromhex("208A6DA7114A44E6B463114BD9DA397E939ADEFEDBF4588117926B0F2C503158")
ksp = os.urandom(32)
ksp_blob = make_blob(b"KDBM" + struct.pack("<L", 1) + struct.pack("<L", len(ksp)) + ksp,
                     system_guid, system_master, KSP_ENTROPY)
(root / "hives" / "SystemKeys").mkdir(parents=True)
(root / "hives" / "SystemKeys" / "Google Chromekey1").write_bytes(ksp_blob)
enc_key = cbc(ksp, b"\0" * 16, app_key)
key2 = bytes(a ^ b for a, b in zip(app_key, FLAG4_XOR))
iv4 = os.urandom(12)
content4 = b"\x04" + enc_key + iv4 + AESGCM(key2).encrypt(iv4, app_key, None)
envelope4 = (struct.pack("<L", len(header)) + header
             + struct.pack("<L", len(content4)) + content4)
app_bound4 = b"APPB" + make_blob(make_blob(envelope4, guid, masterkey), system_guid, system_master)
flag4 = root / "browsers" / "flag4"
(flag4 / "Default" / "Network").mkdir(parents=True)
(flag4 / "Local State").write_text(json.dumps({"os_crypt": {
    "app_bound_encrypted_key": base64.b64encode(app_bound4).decode()}}))
(flag4 / "keys.txt").write_text(
    "browser=flag4\nprofiles=Default\napp_bound=" + base64.b64encode(app_bound4).decode() + "\n")
db4 = sqlite3.connect(flag4 / "Default" / "Network" / "Cookies")
db4.execute("CREATE TABLE cookies (host_key TEXT, name TEXT, value TEXT, encrypted_value BLOB)")
nonce4 = os.urandom(12)
db4.execute("INSERT INTO cookies VALUES (?,?,?,?)",
            ("v20flag4.example.com", "app", "",
             b"v20" + nonce4 + AESGCM(app_key).encrypt(nonce4, b"flag4-cookie", None)))
db4.commit()
db4.close()

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
# 0.2.7 already validates the token in-session, so no password is needed.
(root / "discord").mkdir(exist_ok=True)
(root / "discord" / "token.txt").write_text("token=" + token + "\n")

# Newer Discord stores v10 AES-GCM blobs with a DPAPI user key in Local State.
discord_aes = os.urandom(32)
discord_state = root / "discord" / "localstorage"
discord_state.mkdir()
(discord_state / "Local State").write_text(json.dumps({"os_crypt": {
    "encrypted_key": "DPAPI" + base64.b64encode(make_blob(discord_aes, guid)).decode()}}))
nonce = os.urandom(12)
v10 = b"v10" + nonce + AESGCM(discord_aes).encrypt(
    nonce, json.dumps({"t": token, "e": "false"}).encode(), None)
(discord_state / "000006.ldb").write_bytes(
    b"\x00_" + b"token\x01dQw4w9WgXcQ:" + base64.b64encode(v10) + b"\x00")

# ChromElevator v20 output is already decrypted; decrypt.py consumes the TSV.
abe = chrome / "abe"
abe.mkdir()
(abe / "cookies.tsv").write_text("host\tname\tvalue\tpath\nabe.example\tabc\tv20-cookie\t/\n")
(abe / "passwords.tsv").write_text("url\tuser\tpass\nhttps://abe.example\talice\tabe-pass\n")

print("fixture:", root)
print("password:", password)
print("machinekey:", machinekey.hex())
