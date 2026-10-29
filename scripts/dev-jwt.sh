#!/usr/bin/env bash
# Generate a DEV-ONLY HS256 JWT for local testing.
# Usage: JWT_SECRET=... ./scripts/dev-jwt.sh <subject> [ROLE[,ROLE...]] [ttl-seconds]
#   ./scripts/dev-jwt.sh alice USER
#   ./scripts/dev-jwt.sh admin1 ADMIN,USER 3600
set -euo pipefail
: "${JWT_SECRET:?set JWT_SECRET (same value the app uses, >= 32 bytes)}"
SUB="${1:?subject required}"
ROLES="${2:-USER}"
TTL="${3:-3600}"
NOW=$(date +%s)
EXP=$((NOW + TTL))
ROLES_JSON=$(printf '%s' "$ROLES" | awk -F, '{for(i=1;i<=NF;i++){printf "%s\"%s\"", (i>1?",":""), $i}}')
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","roles":[%s],"iat":%d,"exp":%d%s}' "$SUB" "$ROLES_JSON" "$NOW" "$EXP" \
  "${JWT_ISSUER:+,\"iss\":\"$JWT_ISSUER\"}" | b64url)
SIG=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIG"
