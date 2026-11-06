#!/usr/bin/env python3
"""Intenta abrir los blobs sellados de Phantom con contraseñas candidatas.

Uso:
  tools/phantom-decrypt.py --case ~/spc/in-XXX -p 'contraseña'
  tools/phantom-decrypt.py --case ~/spc/in-XXX --wordlist rockyou.txt
  tools/phantom-decrypt.py --case ~/spc/in-XXX --logins

El formato es scrypt(N=10000, r=8, p=1) + XChaCha20-Poly1305 sobre JSON.
Solo prueba en local; no escribe nada fuera de decrypted/wallets/phantom.
"""
import argparse
import base64
import hashlib
import json
import sys
from pathlib import Path

from nacl.bindings import crypto_aead_xchacha20poly1305_ietf_decrypt

ROOT = Path(__file__).resolve().parent.parent
WORDS = (ROOT / "bip39.txt").read_text().split()
INDEX = {word: i for i, word in enumerate(WORDS)}
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58decode(value):
    number = 0
    for char in value:
        if char not in ALPHABET:
            raise ValueError("base58")
        number = number * 58 + ALPHABET.index(char)
    body = number.to_bytes((number.bit_length() + 7) // 8, "big") if number else b""
    return b"\0" * (len(value) - len(value.lstrip("1"))) + body


def open_blob(blob, password):
    salt = b58decode(blob["salt"])
    nonce = b58decode(blob["nonce"])
    ciphertext = b58decode(blob["encrypted"])
    iterations = int(blob.get("iterations", 10000))
    digest = (blob.get("digest") or "sha256").replace("-", "")
    key = hashlib.pbkdf2_hmac(digest, password.encode(), salt, iterations, 32)
    return crypto_aead_xchacha20poly1305_ietf_decrypt(ciphertext, b"", nonce, key)


def mnemonic(entropy):
    bits = "".join(f"{byte:08b}" for byte in entropy)
    bits += "".join(f"{byte:08b}" for byte in hashlib.sha256(entropy).digest())[: len(entropy) * 8 // 32]
    return " ".join(WORDS[int(bits[i:i + 11], 2)] for i in range(0, len(bits), 11))


def candidates(case, password, wordlist, logins, limit):
    seen = set()

    def add(value):
        value = value.strip()
        if value and value not in seen and len(seen) < limit:
            seen.add(value)

    if password:
        add(password)
    if logins:
        for file in (case / "decrypted" / "secrets").glob("*-logins.tsv"):
            for line in file.read_text("utf-8", "replace").splitlines():
                parts = line.split("\t")
                if len(parts) >= 3:
                    add(parts[2])
    if wordlist:
        for line in Path(wordlist).read_text("utf-8", "replace").splitlines():
            add(line)
    return list(seen)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True)
    parser.add_argument("-p", "--password")
    parser.add_argument("--wordlist")
    parser.add_argument("--logins", action="store_true")
    parser.add_argument("--limit", type=int, default=5000)
    args = parser.parse_args()

    case = Path(args.case).expanduser().resolve()
    folder = case / "decrypted" / "wallets" / "phantom"
    if not folder.is_dir():
        print("! no hay blobs de Phantom en el caso", file=sys.stderr)
        return 1
    seeds = sorted(folder.glob("*vault-seed*.json"))
    keys = sorted(folder.glob("*encryption-encryptionKey.json"))
    if not seeds:
        print("! no hay seed sellado", file=sys.stderr)
        return 1

    candidates_list = candidates(case, args.password, args.wordlist, args.logins, args.limit)
    if not candidates_list:
        print("! sin contraseñas candidatas; usa -p, --wordlist o --logins", file=sys.stderr)
        return 1
    print(f"probando {len(candidates_list)} candidatas contra {len(seeds)} seed(s)")

    for candidate in candidates_list:
        for path in seeds:
            blob = json.loads(path.read_text())["content"]
            try:
                plain = open_blob(blob, candidate)
            except Exception:
                continue
            stem = path.name.split("vault-seed-")[-1].split(".")[0]
            out = folder / f"phantom-seed-{stem}"
            out.with_suffix(".bin").write_bytes(plain)
            text = None
            try:
                document = json.loads(plain)
                text = document.get("mnemonic") or document.get("seed") or document.get("secret")
            except Exception:
                document = None
            if not text:
                if len(plain) in (16, 32):
                    options = [plain]
                else:
                    options = [plain[-16:], plain[-32:], plain[:16], plain[:32]]
                for entropy in options:
                    if len(entropy) in (16, 32):
                        text = mnemonic(entropy)
                        break
            if text:
                out.with_suffix(".txt").write_text(text.strip() + "\n")
                print(f"✓ {path.name}: {text[:60]}…")
            else:
                print(f"✓ {path.name}: abierto, sin mnemonic reconocible (.bin)")
            if document and isinstance(document, dict):
                out.with_suffix(".json").write_text(json.dumps(document, indent=2) + "\n")
            for key_path in keys:
                try:
                    key = open_blob(json.loads(key_path.read_text())["encryptedKey"], candidate)
                except Exception:
                    continue
                (folder / "phantom-encryption-key.bin").write_bytes(key)
            print(f"  contraseña: {candidate}")
            return 0

    print("✗ ninguna candidata abrió los blobs", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
