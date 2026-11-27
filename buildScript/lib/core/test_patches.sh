#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../../.." && pwd)
VERSION_FILE="$PROJECT_ROOT/nb4a.properties"
VERSION_LINES=$(grep -c '^SINGBOX_VERSION=' "$VERSION_FILE" || true)
if [[ "$VERSION_LINES" -ne 1 ]]; then
  echo "ERROR: expected exactly one SINGBOX_VERSION in $VERSION_FILE, found $VERSION_LINES" >&2
  exit 1
fi
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' "$VERSION_FILE" | cut -d'=' -f2 | tr -d '\r[:space:]')
UPSTREAM_REPO=${SINGBOX_SOURCE_REPO:-"$PROJECT_ROOT/../sing-box"}
PATCH_DIR="$PROJECT_ROOT/buildScript/lib/core/patches/$SINGBOX_VERSION"
OFFICIAL_REPO="https://github.com/SagerNet/sing-box.git"

if [[ ! -d "$PATCH_DIR" ]]; then
  echo "ERROR: no versioned sing-box patch directory: $PATCH_DIR" >&2
  exit 1
fi
if [[ "$(git -C "$UPSTREAM_REPO" remote get-url origin)" != "$OFFICIAL_REPO" ]]; then
  echo "ERROR: sing-box source does not use official origin: $UPSTREAM_REPO" >&2
  exit 1
fi
UPSTREAM_COMMIT=$(git -C "$UPSTREAM_REPO" rev-parse "$SINGBOX_VERSION^{commit}")

tmp_root=$(mktemp -d)
trap 'rm -rf "$tmp_root"' EXIT
source_tree="$tmp_root/sing-box"
mkdir -p "$source_tree"
git -C "$UPSTREAM_REPO" archive "$UPSTREAM_COMMIT" | tar -x -C "$source_tree"
git -C "$source_tree" init --quiet

echo ">> Testing MyBox patches on clean $SINGBOX_VERSION ($UPSTREAM_COMMIT)"
patch_count=0
for patch in "$PATCH_DIR"/*.patch; do
  [[ -f "$patch" ]] || continue
  echo ">> Applying $(basename "$patch")"
  git -C "$source_tree" apply --check "$patch"
  git -C "$source_tree" apply "$patch"
  patch_count=$((patch_count + 1))
done
if [[ "$patch_count" -eq 0 ]]; then
  echo "ERROR: no patch files found in $PATCH_DIR" >&2
  exit 1
fi

test_count=0
while IFS= read -r -d '' test_file; do
  relative_path=${test_file#"$PATCH_DIR"/}
  install -D -m 0644 "$test_file" "$source_tree/$relative_path"
  test_count=$((test_count + 1))
done < <(find "$PATCH_DIR" -type f -name '*_test.go' -print0)
echo ">> Installed $test_count versioned regression tests"

(cd "$source_tree" && go test ./adapter ./experimental/clashapi ./route)
