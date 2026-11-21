#!/usr/bin/env bash
# Render the README banner and the GitHub social preview from banner.html.
#   tools/banner/render.sh
# Writes docs/images/banner.jpg (1600x720 @2x) and docs/images/social-preview.png (1280x640).
# Needs google-chrome (or chromium) and ImageMagick.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/docs/images"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

chrome="$(command -v google-chrome-stable || command -v google-chrome || command -v chromium)"
page="file://$here/banner.html"

shot() { # url width height scale file
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor="$4" --window-size="$2,$(( $3 + 200 ))" \
    --virtual-time-budget=3000 --screenshot="$5" "$1" >/dev/null 2>&1
}

shot "$page" 1600 720 2 "$tmp/banner.png"
magick "$tmp/banner.png" -crop 3200x1440+0+0 +repage -resize 1600x720 -quality 90 -strip "$out/banner.jpg"

shot "$page?social" 1280 640 2 "$tmp/social.png"
magick "$tmp/social.png" -crop 2560x1280+0+0 +repage -resize 1280x640 -strip "$out/social-preview.png"

ls -l "$out"
