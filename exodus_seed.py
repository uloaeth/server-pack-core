#!/usr/bin/env python3
"""Recover the seed of an Exodus wallet from passphrase.json + seed.seco.

Usage:
    python3 exodus_seed.py                    # prompt for passphrase, use ./seed.seco
    python3 exodus_seed.py -s path/seed.seco  # explicit file
    python3 exodus_seed.py -p 'passphrase'    # no prompt (stays in history)
    python3 exodus_seed.py --storage          # also try storage.seco
    python3 exodus_seed.py --deep             # wider matrix if the normal one fails
    python3 exodus_seed.py --inspect          # only describe the files

Layout: Exodus stores the plaintext password in passphrase.json (if
"system": true) and the encrypted mnemonic in seed.seco. The real layout varies
between versions, so salt/iv/iterations/modes are tried and the result is
validated against BIP39 (word list + checksum) before accepting it.

Dependency: pycryptodome (pip install pycryptodome) or, failing that,
cryptography.
"""

import argparse
import hashlib
import json
import os
import re
import sys

# --- crypto backend ---------------------------------------------------------

try:
    from Crypto.Cipher import AES as _AES
    _BACKEND = "pycryptodome"
except ImportError:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        _BACKEND = "cryptography"
    except ImportError:
        sys.exit("Missing pycryptodome or cryptography: pip install pycryptodome")

try:
    from argon2.low_level import hash_secret_raw, Type as _ArgonType
    _HAS_ARGON = True
except ImportError:
    _HAS_ARGON = False


def _cbc(key, iv, data):
    if _BACKEND == "pycryptodome":
        return _AES.new(key, _AES.MODE_CBC, iv).decrypt(data)
    dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return dec.update(data) + dec.finalize()


def _gcm(key, nonce, data, tag_first=False):
    if len(data) <= 16:
        raise ValueError("short")
    body, tag = (data[16:], data[:16]) if tag_first else (data[:-16], data[-16:])
    if _BACKEND == "pycryptodome":
        return _AES.new(key, _AES.MODE_GCM, nonce=nonce).decrypt_and_verify(body, tag)
    return AESGCM(key).decrypt(nonce, body + tag, None)


def _unpad(data):
    if not data:
        return b""
    n = data[-1]
    return data[:-n] if 0 < n <= 16 and data[-n:] == bytes([n]) * n else data


# --- BIP39 validation -------------------------------------------------------

_WORDS = set("""
abandon ability able about above absent absorb abstract absurd abuse access accident
account accuse achieve acid acoustic acquire across act action actor actress actual
adapt add addict address adjust admit adult advance advice aerobic affair afford afraid
again age agent agree ahead aim air airport aisle alarm album alcohol alert alien all
alley allow almost alone alpha already also alter always amateur amazing among amount
amused analyst anchor ancient anger angle angry animal ankle announce annual another
answer antenna antique anxiety any apart apology appear apple approve april arch arctic
area arena argue arm armed armor army around arrange arrest arrive arrow art artefact
artist artwork ask aspect assault asset assist assume asthma athlete atom attack attend
attitude attract auction audit august aunt author auto autumn average avocado avoid awake
aware away awesome awful awkward axis baby bachelor bacon badge bag balance balcony ball
bamboo banana banner bar barely bargain barrel base basic basket battle beach bean beauty
because become beef before begin behave behind believe below belt bench benefit best
betray better between beyond bicycle bid bike bind biology bird birth bitter black blade
blame blanket blast bleak bless blind blood blossom blouse blue blur blush board boat body
boil bomb bone bonus book boost border boring borrow boss bottom bounce box boy bracket
brain brand brass brave bread breeze brick bridge brief bright bring brisk broccoli broken
bronze broom brother brown brush bubble buddy budget buffalo build bulb bulk bullet bundle
bunker burden burger burst bus business busy butter buyer buzz cabbage cabin cable cactus
cage cake call calm camera camp can canal cancel candy cannon canoe canvas canyon capable
capital captain car carbon card cargo carpet carry cart case cash casino castle casual cat
catalog catch category cattle caught cause caution cave ceiling celery cement census
century cereal certain chair chalk champion change chaos chapter charge chase chat cheap
check cheese chef cherry chest chicken chief child chimney choice choose chronic chuckle
chunk churn cigar cinnamon circle citizen city civil claim clap clarify claw clay clean
clerk clever click client cliff climb clinic clip clock clog close cloth cloud clown club
clump cluster clutch coach coast coconut code coffee coil coin collect color column combine
come comfort comic common company concert conduct confirm congress connect consider
control convince cook cool copper copy coral core corn correct cost cotton couch country
couple course cousin cover coyote crack cradle craft cram crane crash crater crawl crazy
cream credit creek crew cricket crime crisp critic crop cross crouch crowd crucial cruel
cruise crumble crunch crush cry crystal cube culture cup cupboard curious current curtain
curve cushion custom cute cycle dad damage damp dance danger daring dash daughter dawn day
deal debate debris decade december decide decline decorate decrease deer defense define
defy degree delay deliver demand demise denial dentist deny depart depend deposit depth
deputy derive describe desert design desk despair destroy detail detect develop device
devote diagram dial diamond diary dice diesel diet differ digital dignity dilemma dinner
dinosaur direct dirt disagree discover disease dish dismiss disorder display distance
divert divide divorce dizzy doctor document dog doll dolphin domain donate donkey donor
door dose double dove draft dragon drama drastic draw dream dress drift drill drink drip
drive drop drum dry duck dumb dune during dust dutch duty dwarf dynamic eager eagle early
earn earth easily east easy echo ecology economy edge edit educate effort egg eight either
elbow elder electric elegant element elephant elevator elite else embark embody embrace
emerge emotion employ empower empty enable enact end endless endorse enemy energy enforce
engage engine enhance enjoy enlist enough enrich enroll ensure enter entire entry envelope
episode equal equip era erase erode erosion error erupt escape essay essence estate eternal
ethics evidence evil evoke evolve exact example excess exchange excite exclude excuse
execute exercise exhaust exhibit exile exist exit exotic expand expect expire explain
expose express extend extra eye eyebrow fabric face faculty fade faint faith fall false
fame family famous fan fancy fantasy farm fashion fat fatal father fatigue fault favorite
feature february federal fee feed feel female fence festival fetch fever few fiber fiction
field figure file film filter final find fine finger finish fire firm first fiscal fish fit
fitness fix flag flame flash flat flavor flee flight flip float flock floor flower fluid
flush fly foam focus fog foil fold follow food foot force forest forget fork fortune forum
forward fossil foster found fox fragile frame frequent fresh friend fringe frog front frost
frown frozen fruit fuel fun funny furnace fury future gadget gain galaxy gallery game gap
garage garbage garden garlic garment gas gasp gate gather gauge gaze general genius genre
gentle genuine gesture ghost giant gift giggle ginger giraffe girl give glad glance glare
glass glide glimpse globe gloom glory glove glow glue goat goddess gold good goose gorilla
gospel gossip govern gown grab grace grain grant grape grass gravity great green grid grief
grit grocery group grow grunt guard guess guide guilt guitar gun gym habit hair half hammer
hamster hand happy harbor hard harsh harvest hat have hawk hazard head health heart heavy
hedgehog height hello helmet help hen hero hidden high hill hint hip hire history hobby
hockey hold hole holiday hollow home honey hood hope horn horror horse hospital host hotel
hour hover hub huge human humble humor hundred hungry hunt hurdle hurry hurt husband hybrid
ice icon idea identify idle ignore ill illegal illness image imitate immense immune impact
impose improve impulse inch include income increase index indicate indoor industry infant
inflict inform inhale inherit initial inject injury inmate inner innocent input inquiry
insane insect inside inspire install intact interest into invest invite involve iron island
isolate issue item ivory jacket jaguar jar jazz jealous jeans jelly jewel job join joke
journey joy judge juice jump jungle junior junk just kangaroo keen keep ketchup key kick kid
kidney kind kingdom kiss kit kitchen kite kitten kiwi knee knife knock know lab label labor
ladder lady lake lamp language laptop large later latin laugh laundry lava law lawn lawsuit
layer lazy leader leaf learn leave lecture left leg legal legend leisure lemon lend length
lens leopard lesson letter level liar liberty library license life lift light like limb
limit link lion liquid list little live lizard load loan lobster local lock logic lonely
long loop lottery loud lounge love loyal lucky luggage lumber lunar lunch luxury lyrics
machine mad magic magnet maid mail main major make mammal man manage mandate mango mansion
manual maple marble march margin marine market marriage mask mass master match material
math matrix matter maximum maze meadow mean measure meat mechanic medal media melody melt
member memory mention menu mercy merge merit merry mesh message metal method middle midnight
milk million mimic mind minimum minor minute miracle mirror misery miss mistake mix mixed
mixture mobile model modify mom moment monitor monkey monster month moon moral more morning
mosquito mother motion motor mountain mouse move movie much muffin mule multiply muscle
museum mushroom music must mutual myself mystery myth naive name napkin narrow nasty nation
nature near neck need negative neglect neither nephew nerve nest net network neutral never
news next nice night noble noise nominee noodle normal north nose notable note nothing
notice novel now nuclear number nurse nut oak obey object oblige obscure observe obtain
obvious occur ocean october odor off offer office often oil okay old olive olympic omit once
one onion online only open opera opinion oppose option orange orbit orchard order ordinary
organ orient original orphan ostrich other outdoor outer output outside oval oven over own
owner oxygen oyster ozone pact paddle page pair palace palm panda panel panic panther paper
parade parent park parrot party pass patch path patient patrol pattern pause pave payment
peace peanut pear peasant pelican pen penalty pencil people pepper perfect permit person
pet phone photo phrase physical piano picnic picture piece pig pigeon pill pilot pink
pioneer pipe pistol pitch pizza place planet plastic plate play please pledge pluck plug
plunge poem poet point polar pole police pond pony pool popular portion position possible
post potato pottery poverty powder power practice praise predict prefer prepare present
pretty prevent price pride primary print priority prison private prize problem process
produce profit program project promote proof property prosper protect proud provide public
pudding pull pulp pulse pumpkin punch pupil puppy purchase purity purpose purse push put
puzzle pyramid quality quantum quarter question quick quit quiz quote rabbit raccoon race
rack radar radio rail rain raise rally ramp ranch random range rapid rare rate rather raven
raw razor ready real reason rebel rebuild recall receive recipe record recycle reduce
reflect reform refuse region regret regular reject relax release relief rely remain remember
remind remove render renew rent reopen repair repeat replace report require rescue resemble
resist resource response result retire retreat return reunion reveal review reward rhythm
rib ribbon rice rich ride ridge rifle right rigid ring riot ripple risk ritual rival river
road roast robot robust rocket romance roof rookie room rose rotate rough round route royal
rubber rude rug rule run runway rural sad saddle sadness safe sail salad salmon salon salt
salute same sample sand satisfy satoshi sauce sausage save say scale scan scare scatter
scene scheme school science scissors scorpion scout scrap screen script scrub sea search
season seat second secret section security seed seek segment select sell seminar senior
sense sentence series service session settle setup seven shadow shaft shallow share shed
shell sheriff shield shift shine ship shiver shock shoe shoot shop short shoulder shove
shrimp shrug shuffle shy sibling sick side siege sight sign silent silk silly silver similar
simple since sing siren sister situate six size skate sketch ski skill skin skirt skull
slab slam sleep slender slice slide slight slim slogan slot slow slush small smart smile
smoke smooth snack snake snap sniff snow soap soccer social sock soda soft solar soldier
solid solution solve someone song soon sorry sort soul sound soup source south space spare
spatial spawn speak special speed spell spend sphere spice spider spike spin spirit split
spoil sponsor spoon sport spot spray spread spring spy square squeeze squirrel stable stadium
staff stage stairs stamp stand start state stay steak steel stem step stereo stick still
sting stock stomach stone stool story stove strategy street strike strong struggle student
stuff stumble style subject submit subway success such sudden suffer sugar suggest suit
summer sun sunny sunset super supply supreme sure surface surge surprise surround survey
suspect sustain swallow swamp swap swarm swear sweet swift swim swing switch sword symbol
symptom syrup system table tackle tag tail talent talk tank tape target task taste tattoo
taxi teach team tell ten tenant tennis tent term test text thank that theme then theory
there they thing this thought three thrive throw thumb thunder ticket tide tiger tilt
timber time tiny tip tired tissue title toast tobacco today toddler toe together toilet
token tomato tomorrow tone tongue tonight tool tooth top topic topple torch tornado tortoise
toss total tourist toward tower town toy track trade traffic tragic train transfer trap
trash travel tray treat tree trend trial tribe trick trigger trim trip trophy trouble truck
true truly trumpet trust truth try tube tuition tumble tuna tunnel turkey turn turtle twelve
twenty twice twin twist two type typical ugly umbrella unable unaware uncle uncover under
undo unfair unfold unhappy uniform unique unit universe unknown unlock until unusual unveil
update upgrade uphold upon upper upset urban urge usage use used useful useless usual utility
vacant vacuum vague valid valley valve van vanish vapor various vast vault vehicle velvet
vendor venture venue verb verify version very vessel veteran viable vibrant vicious victory
video view village vintage violin virtual virus visa visit visual vital vivid vocal voice
void volcano volume vote voyage wage wagon wait walk wall walnut want warfare warm warrior
wash wasp waste water wave way wealth weapon wear weasel weather web wedding weekend weird
welcome west wet whale what wheat wheel when where whip whisper wide width wife wild will
win window wine wing wink winner winter wire wisdom wise wish witness wolf woman wonder wood
wool word work world worry worth wrap wreck wrestle wrist write wrong yard year yellow you
young youth zebra zero zone zoo
""".split())

_KNOWN = {"en": _WORDS}


def _validate(words):
    """(is_listed, checksum_ok) for a candidate mnemonic."""
    if len(words) not in (12, 15, 18, 21, 24):
        return False, False
    if not all(w in _WORDS for w in words):
        return False, False
    bits = "".join(format(_WORDS_IDX[w], "011b") for w in words)
    cs = len(bits) % 32
    ent_bits = len(bits) - cs
    if ent_bits not in (128, 160, 192, 224, 256):
        return True, False
    entropy = int(bits[:ent_bits], 2).to_bytes(ent_bits // 8, "big")
    digest = hashlib.sha256(entropy).digest()
    expected = format(digest[0], "08b")[:cs]
    return True, bits[ent_bits:] == expected


_WORDS_IDX = {w: i for i, w in enumerate(sorted(_WORDS))}

# --- recovery ---------------------------------------------------------------

KDFS = [
    ("pbkdf2-sha512", 1000),
    ("pbkdf2-sha512", 100000),
    ("pbkdf2-sha512", 10000),
    ("pbkdf2-sha512", 200000),
    ("pbkdf2-sha256", 100000),
    ("pbkdf2-sha512", 500000),
]


def _derive(kdf, secret, salt, iters, dklen):
    if kdf == "pbkdf2-sha512":
        return hashlib.pbkdf2_hmac("sha512", secret, salt, iters, dklen=dklen)
    if kdf == "pbkdf2-sha256":
        return hashlib.pbkdf2_hmac("sha256", secret, salt, iters, dklen=dklen)
    if kdf.startswith("scrypt:"):
        n = int(kdf.split(":")[1])
        return hashlib.scrypt(secret, salt=salt, n=n, r=8, p=1, dklen=dklen,
                              maxmem=256 * n * 8)
    if kdf == "argon2id":
        if not _HAS_ARGON:
            raise ValueError("no argon2")
        return hash_secret_raw(secret, salt, time_cost=3, memory_cost=65536,
                               parallelism=2, hash_len=dklen, type=_ArgonType.ID)
    raise ValueError(kdf)


def _clean(raw):
    if not raw:
        return []
    if not all(32 <= c < 127 or c in (9, 10, 13) for c in raw):
        return []
    words = re.split(r"[\s,;]+", raw.decode("ascii").strip().lower())
    return [w for w in words if w]


def recover(secret, salt, iv, iters, mode, kdf, dklen, ct, tag_first=False):
    key = _derive(kdf, secret, salt, iters, dklen)
    raw = _cbc(key, iv, ct) if mode == "cbc" else _gcm(key, iv, ct, tag_first)
    return _clean(_unpad(raw) if mode == "cbc" else raw)


def variants(data, deep):
    """Generate attempts; most likely first."""
    layouts = [(16, 16), (16, 12), (12, 16)] if not deep else [
        (16, 16), (16, 12), (12, 16), (8, 8), (8, 16), (32, 16), (16, 32)]
    kdfs = KDFS if not deep else KDFS + [
        ("scrypt:16384", 0), ("scrypt:32768", 0), ("argon2id", 0)]
    offsets = (0,) if not deep else (0, 1, 2, 4)
    tags = (False, True) if deep else (False,)
    for off in offsets:
        body = data[off:]
        for salt_len, iv_len in layouts:
            if len(body) < salt_len + iv_len + 24:
                continue
            for swap in ((False,) if not deep else (False, True)):
                salt = body[iv_len:salt_len + iv_len] if swap else body[:salt_len]
                iv = body[:iv_len] if swap else body[salt_len:salt_len + iv_len]
                ct = body[salt_len + iv_len:]
                for mode in ("cbc", "gcm"):
                    for tag_first in (tags if mode == "gcm" else (False,)):
                        for kdf, iters in kdfs:
                            label = (f"off={off} salt={salt_len} iv={iv_len}"
                                     f"{' swap' if swap else ''} {mode}/{kdf}"
                                     f"/{iters or '-'}")
                            yield label, dict(salt=salt, iv=iv, ct=ct, mode=mode,
                                              kdf=kdf, iters=iters,
                                              tag_first=tag_first)


def _read_if(path):
    try:
        return open(path, "rb").read()
    except OSError:
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("-s", "--seed", default="seed.seco", help="path to seed.seco")
    ap.add_argument("-j", "--json", default="passphrase.json", help="path to passphrase.json")
    ap.add_argument("-p", "--passphrase", help="passphrase (prompted if omitted)")
    ap.add_argument("-o", "--out", default="exodus_seed.txt", help="output file")
    ap.add_argument("--storage", action="store_true", help="also decrypt storage.seco")
    ap.add_argument("--verbose", action="store_true", help="show every attempt")
    ap.add_argument("--deep", action="store_true",
                    help="wide matrix (offsets, swap, scrypt, argon2)")
    ap.add_argument("--inspect", action="store_true", help="only describe the files")
    ap.add_argument("--no-strip", action="store_true", help="do not trim the passphrase")
    args = ap.parse_args()

    data = open(args.seed, "rb").read()
    if len(data) < 48:
        sys.exit(f"{args.seed}: too short ({len(data)} bytes)")

    if data[:4] == b"SECO":
        import decrypt
        sys.argv = ["decrypt", "exodus", os.path.dirname(args.seed) or "."]
        sys.argv += ["-j", args.json, "-o", args.out]
        if args.storage:
            sys.argv.append("--storage")
        return decrypt.main()

    if args.inspect:
        for name, blob in (("seed", data),
                           ("storage", _read_if("storage.seco")),
                           ("passphrase.json", _read_if(args.json))):
            if blob:
                print(f"{name}: {len(blob)} bytes, sha256={hashlib.sha256(blob).hexdigest()[:16]}")
                print(" ", blob[:64].hex(" "))
        return

    secret = args.passphrase
    params = {}
    if os.path.exists(args.json):
        meta = json.load(open(args.json))
        secret = secret or meta.get("passphrase")
        params = meta
    if not secret:
        import getpass
        secret = getpass.getpass("Exodus passphrase: ")
    if not args.no_strip:
        secret = secret.strip()
    secret = secret.encode()

    found = None
    best = None
    for label, v in variants(data, args.deep):
        try:
            words = recover(secret, v["salt"], v["iv"], v["iters"], v["mode"],
                            v["kdf"], 32, v["ct"], v["tag_first"])
        except Exception as exc:
            if args.verbose:
                print(f"  x {label}: {exc}")
            continue
        if not words:
            continue
        listed, checksum = _validate(words)
        if not listed:
            if args.verbose:
                print(f"  ? {label}: {' '.join(words)[:60]}")
            continue
        print(f"{'VALID' if checksum else 'words ok, checksum NO'} [{label}]")
        print(" ".join(words))
        if checksum:
            found = words
            break
        best = best or words
    if not found and best:
        found = best

    if not found:
        sys.exit("No mnemonic recovered. Try --verbose or check the passphrase.")

    ok = _validate(found)[1]
    with open(args.out, "w") as out:
        out.write(" ".join(found) + "\n")
    os.chmod(args.out, 0o600)
    print(f"\nSaved to {args.out} ({len(found)} words)"
          f"{'' if ok else '  [checksum not verified]'}")

    if args.storage and os.path.exists("storage.seco"):
        try:
            with open("storage.seco", "rb") as fh:
                raw = fh.read()
            salt, iv = raw[:16], raw[16:32]
            key = hashlib.pbkdf2_hmac("sha512", secret, salt, 100000, dklen=32)
            plain = _unpad(_cbc(key, iv, raw[32:]))
            path = "exodus_storage.json"
            with open(path, "wb") as out:
                out.write(plain)
            os.chmod(path, 0o600)
            print(f"storage.seco -> {path}")
        except Exception as exc:
            print(f"storage.seco failed: {exc}")


if __name__ == "__main__":
    main()
