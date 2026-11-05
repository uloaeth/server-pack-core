#!/usr/bin/env bash
# Instala/retira el watcher como servicio de usuario systemd.
set -Eeuo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/spc-ingest.service"

usage() {
	echo "uso: $0 [install|uninstall|status]"
	exit 1
}

install() {
	mkdir -p "$UNIT_DIR"
	cat > "$UNIT" <<EOF
[Unit]
Description=SPC case auto-ingest
After=default.target

[Service]
Type=simple
ExecStart="$REPO/tools/auto-ingest.sh" --watch
Restart=always
RestartSec=15
Environment=SPC_WATCH_STABLE=90

[Install]
WantedBy=default.target
EOF
	systemctl --user daemon-reload
	systemctl --user enable --now spc-ingest.service
	systemctl --user --no-pager status spc-ingest.service || true
	echo "logs: $HOME/spc/auto-ingest.log"
}

uninstall() {
	systemctl --user disable --now spc-ingest.service 2>/dev/null || true
	rm -f "$UNIT"
	systemctl --user daemon-reload
	echo "watcher retirado"
}

case ${1:-install} in
	install) install ;;
	uninstall) uninstall ;;
	status) systemctl --user --no-pager status spc-ingest.service || true ;;
	*) usage ;;
esac
