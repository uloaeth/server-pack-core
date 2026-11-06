import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import unittest
import zlib
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

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


class ElectrumBie1Test(unittest.TestCase):
    def container(self, password, payload):
        secret = hashlib.pbkdf2_hmac("sha512", password.encode(), b"", 1024)
        order = decrypt.SECP256K1_ORDER
        private = ec.derive_private_key(int.from_bytes(secret, "big") % order, ec.SECP256K1())
        ephemeral = ec.generate_private_key(ec.SECP256K1())
        shared = ephemeral.exchange(ec.ECDH(), private.public_key())
        key = hashlib.sha512(b"\x02" + shared).digest()
        padder = padding.PKCS7(128).padder()
        payload = padder.update(payload) + padder.finalize()
        encryptor = Cipher(algorithms.AES(key[16:32]), modes.CBC(key[:16])).encryptor()
        encrypted = encryptor.update(payload) + encryptor.finalize()
        public = ephemeral.public_key().public_bytes(Encoding.X962, PublicFormat.CompressedPoint)
        body = b"BIE1" + public + encrypted
        return base64.b64encode(body + hmac.new(key[32:], body, hashlib.sha256).digest())

    def test_roundtrip(self):
        document = json.dumps({"seed": "x" * 32}).encode()
        blob = self.container("password", zlib.compress(document))
        self.assertEqual(json.loads(decrypt.electrum_unwrap(blob, "password")), {"seed": "x" * 32})

    def test_wrong_password(self):
        blob = self.container("password", zlib.compress(b'{"seed":"y"}'))
        with self.assertRaises(Exception):
            decrypt.electrum_unwrap(blob, "otra")

    def test_john_hash_shape(self):
        blob = self.container("password", zlib.compress(b"{}"))
        john = decrypt.electrum_john(blob)
        self.assertTrue(john.startswith("$electrum$4*"))
        self.assertEqual(len(john.split("*")), 4)
