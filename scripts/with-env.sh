#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
[[ $# -gt 0 ]] || { printf 'Usage: bash scripts/with-env.sh <command> [args...]\n' >&2; exit 2; }
ngaaa_env
exec "$@"
