#!/usr/bin/env bash
# Rebuild ts-kit and refresh the copies npm made under each service's
# node_modules.
#
# `.npmrc` sets install-links=true, so `"@trainticket/ts-kit": "file:../.."`
# is COPIED into services/<name>/node_modules/@trainticket/ts-kit rather than
# symlinked. Editing platform/ts-kit therefore has no effect on a service's
# tests until the copy is refreshed -- a previous change was reviewed against a
# stale copy and its tests passed vacuously. Run this after every ts-kit edit.
#
# Deliberately does NOT run `npm install`: the eight package-lock.json files
# must stay byte-identical to HEAD.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root/platform/ts-kit"
npm run --silent build

services=(account ancillary-service customer-service loyalty-membership notification offer-management waitlist)
for service in "${services[@]}"; do
  target="$root/services/$service/node_modules/@trainticket/ts-kit"
  if [ -L "$target" ]; then
    echo "$service: symlinked, nothing to copy"
    continue
  fi
  if [ ! -d "$target" ]; then
    echo "$service: no installed ts-kit copy, skipping" >&2
    continue
  fi
  rm -rf "$target/dist" "$target/src"
  cp -R "$root/platform/ts-kit/dist" "$target/dist"
  cp -R "$root/platform/ts-kit/src" "$target/src"
  cp "$root/platform/ts-kit/package.json" "$root/platform/ts-kit/tsconfig.json" "$root/platform/ts-kit/tsconfig.test.json" "$target/"
  if diff -rq "$root/platform/ts-kit/dist" "$target/dist" >/dev/null; then
    echo "$service: refreshed"
  else
    echo "$service: REFRESH FAILED" >&2
    exit 1
  fi
done
