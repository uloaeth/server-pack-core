import hashlib
import os
import struct
import sys
import unittest
import zlib
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import decrypt  # noqa: E402

WORDS = (ROOT / "bip39.txt").read_text().split()
INDEX = {word: i for i, word in enumerate(WORDS)}


def mnemonic(entropy):
    bits = "".join(f"{byte:08b}" for byte in entropy)
    bits += "".join(f"{byte:08b}" for byte in hashlib.sha256(entropy).digest())[: len(entropy) * 8 // 32]
    return " ".join(WORDS[int(bits[i:i + 11], 2)] for i in range(0, len(bits), 11))


def bitcoin_seed(entropy):
    """What Exodus serialize() stores: 64-byte seed || entropy."""
    seed = hashlib.pbkdf2_hmac("sha512", mnemonic(entropy).encode(), b"mnemonic", 2048)
    return seed + entropy


def exodus_container(payload):
    compressor = zlib.compressobj(9, zlib.DEFLATED, 31)
    gz = compressor.compress(payload) + compressor.flush()
    return struct.pack(">L", len(gz)) + gz + os.urandom(64)


class ExodusPayloadTest(unittest.TestCase):
    def test_entropy_16(self):
        entropy = os.urandom(16)
        words = decrypt.exodus_payload_words(bitcoin_seed(entropy))
        self.assertEqual(words, mnemonic(entropy).split())

    def test_entropy_32(self):
        entropy = os.urandom(32)
        words = decrypt.exodus_payload_words(bitcoin_seed(entropy))
        self.assertEqual(words, mnemonic(entropy).split())

    def test_legacy_ascii(self):
        phrase = mnemonic(os.urandom(16))
        words = decrypt.exodus_payload_words(os.urandom(64) + phrase.encode())
        self.assertEqual(words, phrase.split())

    def test_bad_checksum(self):
        entropy = bytearray(os.urandom(16))
        payload = bytearray(bitcoin_seed(bytes(entropy)))
        payload[64] ^= 0x01
        self.assertIsNone(decrypt.exodus_payload_words(bytes(payload)))

    def test_container_is_unwrapped(self):
        entropy = os.urandom(16)
        how, payload = decrypt.decode_payload(exodus_container(bitcoin_seed(entropy)))
        self.assertEqual(how, "bitcoin-seed")
        self.assertEqual(decrypt.exodus_payload_words(payload), mnemonic(entropy).split())


class V20PrefixTest(unittest.TestCase):
    def test_strips_host_hash(self):
        key = os.urandom(32)
        nonce = os.urandom(12)
        value = b"session-value"
        blob = b"v20" + nonce + AESGCM(key).encrypt(nonce, os.urandom(32) + value, None)
        self.assertEqual(decrypt.decrypt_chromium(blob, None, [key]), value.decode())


if __name__ == "__main__":
    unittest.main()
