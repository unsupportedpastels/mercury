#!/usr/bin/env bash
# Throwaway, user-owned sshd on 127.0.0.1:2222 — the integration fixture.
#
# It never touches the system sshd or ~/.ssh/authorized_keys: it runs as the
# invoking user with its own host key and its own authorized_keys (a copy of
# your public key) under HERMES_SSH_FIXTURE_DIR.
set -euo pipefail

DIR="${HERMES_SSH_FIXTURE_DIR:-/tmp/mercury-ssh-fixture}"
PORT="${HERMES_SSH_FIXTURE_PORT:-2222}"
KEY="${HERMES_SSH_FIXTURE_KEY:-$HOME/.ssh/id_ed25519}"

case "${1:-start}" in
  start)
    rm -rf "$DIR"; mkdir -p "$DIR"; chmod 700 "$DIR"
    ssh-keygen -q -t ed25519 -N '' -f "$DIR/hostkey"
    cp "$KEY.pub" "$DIR/authorized_keys"
    chmod 600 "$DIR/hostkey" "$DIR/authorized_keys"
    cat > "$DIR/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $DIR/hostkey
PidFile $DIR/sshd.pid
AuthorizedKeysFile $DIR/authorized_keys
LogLevel ERROR
StrictModes no
UsePAM no
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
AllowUsers $(id -un)
EOF
    /usr/sbin/sshd -t -f "$DIR/sshd_config"
    /usr/sbin/sshd -f "$DIR/sshd_config"
    sleep 1
    if ss -ltn | grep -q ":$PORT"; then
      echo "fixture sshd up on 127.0.0.1:$PORT (config: $DIR)"
    else
      echo "fixture sshd failed to listen on $PORT"; exit 1
    fi
    ;;
  stop)
    if [ -f "$DIR/sshd.pid" ]; then kill "$(cat "$DIR/sshd.pid")" 2>/dev/null || true; fi
    rm -rf "$DIR"
    echo "fixture sshd stopped"
    ;;
  *)
    echo "usage: $0 [start|stop]"; exit 2 ;;
esac