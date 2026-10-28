#!/usr/bin/env bash
# Download the pinned dependencies from deps.lock into libs/{runtime,test} and verify their SHA-256.
# Usage: ./fetch_deps.sh      (needs curl and sha256sum; files already present with the right hash are kept)
set -euo pipefail
cd "$(dirname "$0")"
REPOS=(https://dl.google.com/dl/android/maven2 https://repo1.maven.org/maven2)
fail=0
while read -r sha dir coord; do
  case "$sha" in ''|'#'*) continue ;; esac
  IFS=: read -r g a v ext <<<"$coord"
  out=libs/$dir/${g}__${a}-${v}.${ext}
  mkdir -p "libs/$dir"
  if [ -f "$out" ] && [ "$(sha256sum "$out" | cut -d' ' -f1)" = "$sha" ]; then echo "ok    $out"; continue; fi
  rm -f "$out.part"
  for r in "${REPOS[@]}"; do
    curl -fsL --retry 2 -o "$out.part" "$r/${g//.//}/$a/$v/$a-$v.$ext" && break
    rm -f "$out.part"
  done
  if [ ! -f "$out.part" ]; then echo "MISSING $coord" >&2; fail=1; continue; fi
  if [ "$(sha256sum "$out.part" | cut -d' ' -f1)" != "$sha" ]; then
    echo "SHA-256 MISMATCH $coord" >&2; rm -f "$out.part"; fail=1; continue
  fi
  mv "$out.part" "$out"; echo "fetch $out"
done < deps.lock
exit $fail
