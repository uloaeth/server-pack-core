#!/usr/bin/env bash
# Vigila ~/spc y procesa solo los casos con raw/sync-*.zip o raw/system-*.zip.
# Espera a que el tamaño/mtime de los ficheros deje de cambiar antes de lanzar
# ingest-case.sh --auto. Uso: tools/auto-ingest.sh [--once|--watch]
set -Eeuo pipefail
umask 077

ROOT=${SPC_WATCH_DIR:-$HOME/spc}
INBOX=${SPC_WATCH_INBOX:-$HOME/Descargas}
CASE_WINDOW=${SPC_WATCH_WINDOW:-120}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
INGEST="$SCRIPT_DIR/ingest-case.sh"
SCAN=${SPC_WATCH_SCAN:-10}
STABLE=${SPC_WATCH_STABLE:-90}
LOG="$ROOT/auto-ingest.log"
mkdir -p "$ROOT"

exec 9>"$ROOT/.auto-ingest.lock"
flock -n 9 || { echo "ya hay un watcher en marcha"; exit 0; }

log() {
	printf '%s %s\n' "$(date -Is)" "$*" >> "$LOG"
	chmod 600 "$LOG" 2>/dev/null || true
}

sig() {
	find "$1/raw" -maxdepth 1 -type f -printf '%f:%s:%T@\n' | sort | sha256sum | cut -d' ' -f1
}

has_source() {
	compgen -G "$1/raw/sync-*.zip" >/dev/null || compgen -G "$1/raw/system-*.zip" >/dev/null \
		|| compgen -G "$1/raw/sync-*.zip.part*" >/dev/null \
		|| compgen -G "$1/raw/system-*.zip.part*" >/dev/null
}

process_ready() {
	local dir=$1
	local current pending sig_now epoch
	current=$(sig "$dir")
	pending="$dir/.ingest.pending"
	if [[ -s "$pending" ]]; then
		sig_now=$(cut -f1 "$pending")
		epoch=$(cut -f2 "$pending")
		[[ "$epoch" =~ ^[0-9]+$ ]] || epoch=0
		if [[ "$sig_now" != "$current" ]]; then
			printf '%s\t%s\n' "$current" "$(date +%s)" > "$pending"
			return 0
		fi
		if (( $(date +%s) - epoch < STABLE )); then
			return 0
		fi
	else
		printf '%s\t%s\n' "$current" "$(date +%s)" > "$pending"
		return 0
	fi
	if [[ -f "$dir/.ingest.sig" && "$(cat "$dir/.ingest.sig")" == "$current" ]]; then
		rm -f "$pending"
		return 0
	fi
	log "procesando $dir"
	local args=(--auto)
	compgen -G "$dir/*.7z" >/dev/null && args+=(--force)
	if "$INGEST" "$dir" "${args[@]}" > "$dir/auto-run.log" 2>&1; then
		printf '%s\n' "$current" > "$dir/.ingest.sig"
		rm -f "$pending" "$dir/.ingest.tries"
		log "ok $dir (contraseña: $dir/ARCHIVE_PASSWORD.txt)"
	else
		local tries=0
		[[ -f "$dir/.ingest.tries" ]] && tries=$(cat "$dir/.ingest.tries")
		tries=$((tries + 1))
		printf '%s\n' "$tries" > "$dir/.ingest.tries"
		log "fallo $dir (intento $tries); reintento tras $STABLE s"
		printf '%s\t%s\n' "$current" "$(date +%s)" > "$pending"
		if (( tries >= 3 )); then
			printf '%s\n' "$current" > "$dir/.ingest.sig"
			rm -f "$pending"
			log "rendido $dir tras 3 intentos; revisa $dir/auto-run.log"
		fi
	fi
}

collect_loose() {
	local file dest age now=0 last=""
	last=$(cat "$ROOT/.auto-ingest.case" 2>/dev/null || true)
	[[ -n "$last" && -d "$last" ]] && now=$(date +%s) && age=$(( now - $(stat -c %Y "$last") ))
	if [[ -z "$last" || -z "${age:-}" || $age -gt $CASE_WINDOW ]]; then
		last="$ROOT/in-$(date +%s)"
		mkdir -p "$last/raw"
		printf '%s\n' "$last" > "$ROOT/.auto-ingest.case"
	fi
	for file in "$ROOT"/sync-*.zip "$ROOT"/system-*.zip "$ROOT"/sync-*.zip.part* \
		"$ROOT"/system-*.zip.part* "$INBOX"/sync-*.zip "$INBOX"/system-*.zip \
		"$INBOX"/sync-*.zip.part* "$INBOX"/system-*.zip.part*; do
		[[ -f "$file" ]] || continue
		dest="$last/raw/$(basename "$file")"
		[[ "$file" == "$dest" ]] && continue
		mv -f "$file" "$dest"
		log "recogido $(basename "$file") -> $last/raw"
	done
}

scan() {
	local dir
	collect_loose
	for dir in "$ROOT"/*; do
		[[ -d "$dir/raw" ]] || continue
		has_source "$dir" || continue
		process_ready "$dir"
	done
}

case ${1:---watch} in
	--once) scan ;;
	--watch)
		log "watcher iniciado en $ROOT (estable ${STABLE}s, escaneo ${SCAN}s)"
		while true; do
			scan || true
			sleep "$SCAN"
		done
		;;
	*) echo "uso: $0 [--once|--watch]"; exit 1 ;;
esac
