#!/usr/bin/env bash
# Tests for tools/pair-phone.sh using stubs for zeroclaw, curl and termux-open-url.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/bin" "$tmp/data/data/tls"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -subj /CN=t -keyout "$tmp/key.pem" -out "$tmp/data/data/tls/ca.crt" 2>/dev/null
ca_fp="$(openssl x509 -in "$tmp/data/data/tls/ca.crt" -outform der | sha256sum | cut -d' ' -f1)"

cat >"$tmp/bin/zeroclaw" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  *"enroll-paircode --new"*) printf '    pairing code : AbCdEf0123456789AbCdEf0123456789\n    SAS          : A1B2-C3D4\n' ;;
  *"get-paircode --new"*) printf '  Use this one-time code to pair a new device:\n    POST /pair with header X-Pairing-Code: ZyXwVu9876543210ZyXwVu9876543210\n' ;;
  *) echo "unexpected zeroclaw args: $*" >&2; exit 1 ;;
esac
STUB

cat >"$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
printf '{"paired":true,"persisted":true,"token":"zc_TESTTOKEN_do-not-leak"}\n'
STUB

cat >"$tmp/bin/termux-open-url" <<'STUB'
#!/usr/bin/env bash
printf '%s' "$1" >"$TERMUX_ARG_FILE"
STUB
chmod +x "$tmp/bin/"*

out="$tmp/out.txt"
PATH="$tmp/bin:$PATH" TERMUX_ARG_FILE="$tmp/arg.txt" \
  bash "$root/tools/pair-phone.sh" --mode link --name t --data-dir "$tmp/data" >"$out" 2>&1 || {
    echo "FAIL: pair-phone.sh exited non-zero"; cat "$out"; exit 1
  }

arg="$(cat "$tmp/arg.txt")"
fail=0
check() { if eval "$2"; then echo "ok: $1"; else echo "FAIL: $1"; fail=1; fi; }

check "link scheme" '[[ "$arg" == zerodroid://pair?v=1\&* ]]'
check "enrollment code" '[[ "$arg" == *"code=AbCdEf0123456789AbCdEf0123456789"* ]]'
check "single code param" '[[ "$(grep -o "code=" <<<"$arg" | wc -l)" -eq 1 ]]'
check "wss port" '[[ "$arg" == *"&wss=9781&"* ]]'
check "enroll port" '[[ "$arg" == *"&enroll=9782&"* ]]' 
check "token" '[[ "$arg" == *"token=zc_TESTTOKEN_do-not-leak"* ]]'
check "ca fingerprint" '[[ "$arg" == *"ca_fp=$ca_fp"* ]]'
check "stdout hides token" '! grep -q "zc_TESTTOKEN_do-not-leak" "$out"'
check "warning present" 'grep -q "Do not screenshot or share it" "$out"'
check "SAS present" 'grep -q "A1B2-C3D4" "$out"'

if [[ $fail -ne 0 ]]; then echo "--- stdout ---"; cat "$out"; fi
exit $fail
