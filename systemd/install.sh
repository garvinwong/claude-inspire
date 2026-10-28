#!/usr/bin/env bash
# Install / update the Claude Inspire hub as a systemd user service.
# Tip: `loginctl enable-linger $USER` keeps it running without an open login session.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p ~/.config/systemd/user
sed "s|@REPO@|$REPO|g" "$REPO/systemd/palm-hub.service" > ~/.config/systemd/user/palm-hub.service
chmod 644 ~/.config/systemd/user/palm-hub.service
systemctl --user daemon-reload
systemctl --user enable palm-hub.service >/dev/null
systemctl --user restart palm-hub.service
sleep 2
systemctl --user is-active palm-hub.service
curl -s --max-time 3 127.0.0.1:5612/api/health; echo
