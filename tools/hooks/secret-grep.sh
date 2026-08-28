#!/usr/bin/env bash
# Refuses commits that would leak a secret, topic name, tailnet hostname, or API key.
# Installed as .git/hooks/pre-commit by tools/hooks/install.sh; also run by CI with --all.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if [[ "${1:-}" == "--all" ]]; then
  files=$(git ls-files)
else
  files=$(git diff --cached --name-only --diff-filter=ACMR)
fi
[[ -z "$files" ]] && exit 0

# Files that are allowed to contain the placeholder values.
allow_re='^(config\.example\.properties|tools/hooks/secret-grep\.sh)$'

fail=0
# 1. Never commit the real config or key material.
for f in $files; do
  case "$f" in
    config.properties|*.keystore|*.jks|*.pem|local.properties)
      echo "BLOCKED: $f must not be committed"; fail=1;;
  esac
done

# 2. Pattern grep. Tailnet hostnames, ntfy.sh long topics, key-shaped strings, PrusaLink keys.
patterns=(
  '[A-Za-z0-9-]+\.[A-Za-z0-9-]+\.ts\.net'            # tailscale magic DNS
  'ntfy\.sh/[A-Za-z0-9_-]{20,}'                       # ntfy.sh topic URLs
  '(API_KEY|api_key|apikey|PRINTER_API_KEY)\s*[:=]\s*["'"'"']?[A-Za-z0-9]{8,}'
  'tskey-[A-Za-z0-9-]+'                               # tailscale auth keys
  'tk_[A-Za-z0-9]{10,}'                               # ntfy access tokens
  'gh[pous]_[A-Za-z0-9]{30,}'                         # github tokens
  'ssh-(rsa|ed25519) AAAA'
)
# 3. Real topic names, if a config.properties exists locally, are also forbidden in tracked files.
if [[ -f config.properties ]]; then
  while IFS='=' read -r k v; do
    v="${v%%#*}"; v="$(echo "$v" | xargs || true)"
    case "$k" in
      NTFY_BASE_URL|NTFY_TOKEN|NTFY_TOKEN_SVC|PRINTER_HOST|PRINTER_API_KEY|RAID_SSH_HOST|RIG_SSH_HOST)
        [[ -n "$v" && ${#v} -ge 4 ]] && patterns+=("$(printf '%s' "$v" | sed 's/[.[\*^$\/]/\&/g')");;
      # Topic names count as secrets only once they are secret-shaped (ntfy.sh mode, >=24 random
      # chars). Short tailnet-mode words like "tags" would false-positive across the whole repo.
      TOPIC_*)
        [[ -n "$v" && ${#v} -ge 12 ]] && patterns+=("$(printf '%s' "$v" | sed 's/[.[\*^$\/]/\&/g')");;
    esac
  done < config.properties
fi

for f in $files; do
  [[ "$f" =~ $allow_re ]] && continue
  [[ -f "$f" ]] || continue
  for p in "${patterns[@]}"; do
    if grep -nEI -- "$p" "$f" >/dev/null 2>&1; then
      echo "BLOCKED: $f matches secret pattern: $p"; grep -nEI -- "$p" "$f" | head -3; fail=1
    fi
  done
done

exit $fail
