#!/usr/bin/env bash
# Mint ZeroClaw pairing codes and hand them to ZeroDroid through a deep link.
# The token travels only inside the zerodroid:// URI; it is never printed.
set -euo pipefail

mode="link"
name=""
show_token=false
data_dir="$HOME/.zeroclaw"
gateway="${ZEROCLAW_GATEWAY:-http://127.0.0.1:42617}"
wss_port="${ZERODROID_WSS_PORT:-9781}"
enroll_port="${ZERODROID_ENROLL_PORT:-9782}"
phone_host="${ZERODROID_HOST:-}"

usage() {
  cat <<'USAGE'
Usage: tools/pair-phone.sh [options]

Options:
  --mode link|print   Open the link with termux-open-url (link) or print it (print)
  --name NAME         Device label included in the link (optional)
  --data-dir DIR      ZeroClaw data dir (default: ~/.zeroclaw)
  --wss-port N        daemon WSS port (default: 9782)
  --enroll-port N     daemon enrollment port (default: 9782)
  --show-token        Print the token on stdout (unsafe; use only for debugging)
  -h, --help          Show this help

Facts verified against the ZeroClaw CLI and gateway on 2026-11-20:
  - enrollment code: zeroclaw security enroll-paircode --new
      prints "pairing code : <code>" and "SAS          : <sas>"
  - gateway code:    zeroclaw gateway get-paircode --new
      prints "POST /pair with header X-Pairing-Code: <code>"
  - token:           POST /pair with that header, JSON response field "token"
  - CA:              <data-dir>/data/tls/ca.crt
USAGE
}

urlencode() {
  local s="$1" out="" c i
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out+="$c" ;;
      *) printf -v hex '%%%02X' "'$c"; out+="$hex" ;;
    esac
  done
  printf '%s' "$out"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) mode="${2:?--mode needs link or print}"; shift 2 ;;
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    --data-dir) data_dir="${2:?--data-dir needs a value}"; shift 2 ;;
    --wss-port) wss_port="${2:?--wss-port needs a value}"; shift 2 ;;
    --enroll-port) enroll_port="${2:?--enroll-port needs a value}"; shift 2 ;;
    --show-token) show_token=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$mode" in link|print) ;; *) echo "Invalid mode: $mode" >&2; exit 2 ;; esac

ca_path="$data_dir/data/tls/ca.crt"
[[ -r "$ca_path" ]] || { echo "CA not readable: $ca_path" >&2; exit 1; }
if ! command -v sha256sum >/dev/null; then echo "sha256sum is required" >&2; exit 1; fi

enroll_out="$(zeroclaw security enroll-paircode --new)"
enroll_code="$(printf '%s\n' "$enroll_out" | sed -n 's/.*pairing code[[:space:]]*:[[:space:]]*\([^[:space:]]*\).*/\1/p' | head -1)"
sas="$(printf '%s\n' "$enroll_out" | sed -n 's/.*SAS[[:space:]]*:[[:space:]]*\([^[:space:]]*\).*/\1/p' | head -1)"
[[ -n "$enroll_code" ]] || { echo "Could not read the enrollment pairing code" >&2; exit 1; }

gateway_out="$(zeroclaw gateway get-paircode --new)"
gateway_code="$(printf '%s\n' "$gateway_out" | sed -n 's/.*X-Pairing-Code:[[:space:]]*\([^[:space:]]*\).*/\1/p' | head -1)"
[[ -n "$gateway_code" ]] || { echo "Could not read the gateway pairing code" >&2; exit 1; }

pair_json="$(curl -fsS -X POST "$gateway/pair" -H "X-Pairing-Code: $gateway_code")"
token="$(printf '%s' "$pair_json" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
[[ -n "$token" ]] || { echo "Pairing response contained no token" >&2; exit 1; }

if [[ -z "$phone_host" ]]; then
  if command -v ip >/dev/null; then
    phone_host="$(ip -4 -o addr show scope global 2>/dev/null | awk 'NR==1 {split($4,a,"/"); print a[1]}')" || phone_host=""
  fi
  if [[ -z "$phone_host" ]] && command -v hostname >/dev/null; then
    phone_host="$(hostname -I 2>/dev/null | awk '{print $1}')" || phone_host=""
  fi
fi
host="${phone_host:-$(printf '%s' "$gateway" | sed -E 's#^[a-z]+://##; s#/.*$##; s#:[0-9]+$##')}"
ca_fp="$(openssl x509 -in "$ca_path" -outform der 2>/dev/null | sha256sum | cut -d' ' -f1)"
[[ -n "$ca_fp" ]] || { echo "Could not compute the CA fingerprint" >&2; exit 1; }

link="zerodroid://pair?v=1&code=$(urlencode "$enroll_code")&token=$(urlencode "$token")&host=$(urlencode "$host")&wss=$wss_port&enroll=$enroll_port&ca_fp=$ca_fp"
[[ -z "$name" ]] || link+="&name=$(urlencode "$name")"

echo "ZeroClaw enrollment code: $enroll_code"
[[ -z "$sas" ]] || echo "Expected SAS on the phone: $sas"
echo "CA SHA-256: $ca_fp"
echo "WARNING: the link contains a one-time token. Do not screenshot or share it."
if [[ "$show_token" == true ]]; then echo "Token: $token"; fi

case "$mode" in
  link) command -v termux-open-url >/dev/null || { echo "termux-open-url not found" >&2; exit 1; }
        termux-open-url "$link"
        echo "Link opened on the phone." ;;
  print) printf '%s\n' "$link" ;;
esac
