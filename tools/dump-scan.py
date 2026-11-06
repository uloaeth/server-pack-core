#!/usr/bin/env python3
"""Busca seeds BIP39 y claves privadas en volcados de memoria.

Uso: tools/dump-scan.py volcado1 [volcado2 ...] [-o salida.txt]
No necesita red. Valida el checksum, así que los falsos positivos son raros.
"""
import argparse
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORDS = (ROOT / "bip39.txt").read_text().split()
INDEX = {word: i for i, word in enumerate(WORDS)}
SIZE = {"12": 16, "24": 32}

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58_len(value):
    number = 0
    for char in value.decode("ascii", "replace"):
        if char not in ALPHABET:
            return -1
        number = number * 58 + ALPHABET.index(char)
    return (number.bit_length() + 7) // 8 if number else 0


BASE58 = re.compile(rb"(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}")
SOLANA = re.compile(rb"[1-9A-HJ-NP-Za-km-z]{85,90}")
HEX64 = re.compile(rb"(?<![0-9a-fA-F])[0-9a-fA-F]{128}(?![0-9a-fA-F])")
JSON_SEED = re.compile(rb'"(?:mnemonic|seed|secret)"\s*:\s*"([^"]{20,400})"')


def strings(data):
    """ASCII and UTF-16LE runs, without loading giant adjacent blocks twice."""
    for match in re.finditer(rb"[\x20-\x7e]{4,}", data):
        yield match.start(), match.group().decode("ascii", "replace")
    for match in re.finditer(rb"(?:[\x20-\x7e]\x00){4,}", data):
        yield match.start(), match.group().decode("utf-16le", "replace")


def checksum_ok(words):
    count = len(words)
    if count not in (12, 24):
        return False
    try:
        bits = "".join(f"{INDEX[word]:011b}" for word in words)
    except KeyError:
        return False
    entropy_bits = len(bits) * 32 // 33
    entropy = bits[:entropy_bits]
    checksum = bits[entropy_bits:]
    data = bytes(int(entropy[i:i + 8], 2) for i in range(0, entropy_bits, 8))
    want = "".join(f"{byte:08b}" for byte in hashlib.sha256(data).digest())[:len(checksum)]
    return checksum == want


def scan(data):
    found = []

    for offset, text in strings(data):
        tokens = re.findall(r"[a-z]+", text.lower())
        for size in (12, 24):
            for start in range(0, len(tokens) - size + 1):
                candidate = tokens[start:start + size]
                if candidate[0] not in INDEX or candidate[-1] not in INDEX:
                    continue
                if checksum_ok(candidate):
                    found.append((offset, " ".join(candidate), "bip39"))

    for match in BASE58.finditer(data):
        found.append((match.start(), match.group().decode(), "b58-xprv"))
    for match in SOLANA.finditer(data):
        if b58_len(match.group()) == 64:
            found.append((match.start(), match.group().decode(), "b58-solana-key"))
    for match in HEX64.finditer(data):
        found.append((match.start(), match.group().decode(), "hex64"))

    for match in JSON_SEED.finditer(data):
        value = match.group(1).decode("utf-8", "replace").strip()
        words = value.split()
        if len(words) in (12, 24) and checksum_ok(words):
            found.append((match.start(), " ".join(words), "json-bip39"))

    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+")
    parser.add_argument("-o", "--output")
    args = parser.parse_args()

    lines = []
    seen = set()
    for name in args.files:
        path = Path(name)
        files = [path] if path.is_file() else [p for p in path.rglob("*") if p.is_file()]
        for file in files:
            try:
                data = file.read_bytes()
            except OSError as error:
                print(f"! {file}: {error}", file=sys.stderr)
                continue
            for offset, value, kind in scan(data):
                key = (kind, value)
                if key in seen:
                    continue
                seen.add(key)
                line = f"{file} @0x{offset:x} {kind}: {value}"
                lines.append(line)
                print(line)

    if args.output and lines:
        Path(args.output).write_text("\n".join(lines) + "\n")
        print(f"\n{len(lines)} hallazgos -> {args.output}", file=sys.stderr)
    if not lines:
        print("Sin seeds ni claves reconocibles.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
