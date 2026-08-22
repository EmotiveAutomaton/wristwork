#!/usr/bin/env bash
# Install the secrets pre-commit hook into this clone. Run once after cloning.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
ln -sf "../../tools/hooks/secret-grep.sh" "$root/.git/hooks/pre-commit" 2>/dev/null || cp "$root/tools/hooks/secret-grep.sh" "$root/.git/hooks/pre-commit"
chmod +x "$root/.git/hooks/pre-commit" "$root/tools/hooks/secret-grep.sh"
echo "pre-commit secrets hook installed"
