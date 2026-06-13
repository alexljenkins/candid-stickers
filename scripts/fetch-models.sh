#!/usr/bin/env bash
# Fetches the gitignored MobileCLIP-S0 ONNX encoders into app/src/main/assets/clip/.
# Idempotent: skips a file when it already exists with the pinned sha256.
# Small vendored assets (tokenizer.json, mobilefacenet.tflite) are committed to git
# and are NOT handled here. See docs/MODELS.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIP_DIR="$REPO_ROOT/app/src/main/assets/clip"
BASE_URL="https://huggingface.co/Xenova/mobileclip_s0/resolve/main/onnx"

# file name | pinned sha256 | expected bytes
MODELS=(
  "vision_model_fp16.onnx 22b1d36ecc6837e8205aee05003440a25e1c1ee0c7e2945dbb9dd597211c59dc 22876479"
  "text_model_fp16.onnx 2f74b7b3abd9f3a70dcc60115f627bbadb9534606ac841400f9841f49bf980cc 84971030"
)

sha_of() { sha256sum "$1" | awk '{print $1}'; }

mkdir -p "$CLIP_DIR"

for entry in "${MODELS[@]}"; do
  read -r name sha bytes <<<"$entry"
  dest="$CLIP_DIR/$name"

  if [[ -f "$dest" ]] && [[ "$(sha_of "$dest")" == "$sha" ]]; then
    echo "OK (cached)   $name"
    continue
  fi

  echo "Downloading   $name ($bytes bytes)"
  tmp="$dest.tmp"
  curl -fL --retry 3 -o "$tmp" "$BASE_URL/$name"

  actual_sha="$(sha_of "$tmp")"
  if [[ "$actual_sha" != "$sha" ]]; then
    rm -f "$tmp"
    echo "ERROR: sha256 mismatch for $name" >&2
    echo "  expected: $sha" >&2
    echo "  actual:   $actual_sha" >&2
    exit 1
  fi
  mv "$tmp" "$dest"
  echo "OK (fetched)  $name"
done

echo "All CLIP models present in $CLIP_DIR"
