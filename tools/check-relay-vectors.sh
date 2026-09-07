#!/usr/bin/env bash
# Fail if the vendored Mercury Relay protocol vectors drift from the pinned
# public plugin release. Both ends of the encrypted channel test against the
# same bytes, so a mismatch here means the phone and host no longer agree.
set -euo pipefail
RELAY_PLUGIN_REF="${RELAY_PLUGIN_REF:-v0.1.0}"
BASE="https://raw.githubusercontent.com/unsupportedpastels/mercury-relay-plugin/${RELAY_PLUGIN_REF}/protocol/vectors"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="$ROOT/shared/mercury-core/src/commonTest/resources/relay-protocol"
status=0
for pair in "frames/corpus.json:frames-corpus.json" "secure-channel/corpus.json:secure-channel-corpus.json"; do
  remote="${pair%%:*}"; local_name="${pair##*:}"
  expected=$(curl -fsSL "$BASE/$remote" | sha256sum | cut -d' ' -f1)
  actual=$(sha256sum "$LOCAL_DIR/$local_name" | cut -d' ' -f1)
  if [[ "$expected" == "$actual" ]]; then
    echo "ok       $local_name matches mercury-relay-plugin@$RELAY_PLUGIN_REF"
  else
    echo "MISMATCH $local_name differs from mercury-relay-plugin@$RELAY_PLUGIN_REF" >&2
    status=1
  fi
done
exit $status
