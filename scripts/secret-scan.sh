#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Pre-commit / pre-publish scan for secrets and personal data. Exit code 1 on any finding.
# Scans files git would commit (tracked + untracked, not ignored). Add your own private patterns
# (your name, home IP, e-mail, ...) one per line to .secret-scan.local - that file is gitignored,
# so the patterns themselves never get published.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

files="$(git ls-files -co --exclude-standard | grep -v '^receiver/app/src/main/cpp/third_party/' | grep -vE '^(LICENSE|receiver/LICENSE|scripts/secret-scan.sh)$')"
fail=0
hit() { echo "✗ $1"; echo "$2" | sed 's/^/    /' | head -20; fail=1; }

# forbidden files
bad="$(echo "$files" | grep -iE '\.(jks|keystore|p12|pem|key|session|cookiejar|apk|aab|so)$|(^|/)(local|keystore)\.properties$|icloud_session|(^|/)config\.json$' || true)"
[ -n "$bad" ] && hit "forbidden files" "$bad"

scan() { # label, extended regex; placeholders like YOUR_API_KEY are allowed
  local out; out="$(echo "$files" | tr '\n' '\0' | xargs -0 grep -nIiE "$2" -- 2>/dev/null | grep -v '"YOUR_[A-Z_]*"' || true)"
  [ -n "$out" ] && hit "$1" "$out"
}
scan "private key" 'BEGIN [A-Z ]*PRIVATE KEY'
scan "API token" '(AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[0-9A-Za-z]{36}|xox[baprs]-[0-9A-Za-z-]{10,}|sk-[A-Za-z0-9]{32,}|-----BEGIN OPENSSH)'
scan "assigned secret" '(api[_-]?key|apikey|secret|password|passwd|token)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9/+_=-]{16,}["'"'"']'
scan "private IP" '(^|[^0-9.])(192\.168\.[0-9]{1,3}\.[0-9]{1,3}|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})([^0-9]|$)'
scan "home directory path" '(/Users/[a-z]|/home/[a-z][a-z0-9_-]+/|C:\\\\Users\\\\)'
scan "e-mail address" '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.(com|net|org|io|dev|me|co|tr)([^A-Za-z]|$)'

if [ -f .secret-scan.local ]; then
  while IFS= read -r p; do
    [ -z "$p" ] || case "$p" in \#*) ;; *) scan "private pattern (.secret-scan.local)" "$p" ;; esac
  done < .secret-scan.local
fi

if command -v gitleaks >/dev/null; then gitleaks detect --no-banner --source . || fail=1; fi
[ "$fail" = 0 ] && echo "✓ secret scan clean ($(echo "$files" | wc -l | tr -d ' ') files)"
exit "$fail"
