#!/bin/bash
# =============================================================================
# Entrypoint da imagem da aplicação.
#
# Com GC_GENERATE_LOCAL_SECRETS=true (somente ambientes locais/sandbox), gera na primeira subida, no
# diretório GC_SECRETS_DIR (volume persistente), o par RSA do JWT e as chaves de criptografia de
# dados pessoais (AES-256 + blind index). Nada disso é versionado nem embutido na imagem; as chaves
# são reaproveitadas nas subidas seguintes (dados cifrados continuam legíveis). Recusado no perfil
# prod: em produção as chaves vêm do Secrets Manager (scripts/instance/user-data.sh).
# =============================================================================
set -Eeuo pipefail

readonly JAR=/opt/gestor-cobranca/gestor-cobranca.jar
readonly SECRETS_DIR="${GC_SECRETS_DIR:-/var/lib/gestor-cobranca/secrets}"

log() { printf '%s [entrypoint] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }

if [[ "${GC_GENERATE_LOCAL_SECRETS:-false}" == "true" ]]; then
  if [[ ",${SPRING_PROFILES_ACTIVE:-}," == *",prod,"* ]]; then
    log "GC_GENERATE_LOCAL_SECRETS não é permitido com o perfil prod"
    exit 1
  fi
  umask 077
  mkdir -p "${SECRETS_DIR}"
  if [[ ! -s "${SECRETS_DIR}/jwt-private.pem" || ! -s "${SECRETS_DIR}/jwt-public.pem" ]]; then
    log "gerando par RSA do JWT em ${SECRETS_DIR}"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${SECRETS_DIR}/jwt-private.pem" 2>/dev/null
    openssl pkey -in "${SECRETS_DIR}/jwt-private.pem" -pubout -out "${SECRETS_DIR}/jwt-public.pem"
  fi
  if [[ ! -s "${SECRETS_DIR}/crypto.env" ]]; then
    log "gerando chaves de criptografia de dados pessoais em ${SECRETS_DIR}"
    {
      echo "APP_CRYPTO_KEY_K1=$(openssl rand -base64 32)"
      echo "APP_CRYPTO_ACTIVE_KEY_ID=k1"
      echo "APP_CRYPTO_BLIND_INDEX_KEY=$(openssl rand -base64 32)"
    } > "${SECRETS_DIR}/crypto.env"
  fi
  set -a
  # shellcheck disable=SC1091
  source "${SECRETS_DIR}/crypto.env"
  set +a
  export APP_SECURITY_JWT_PRIVATE_KEY="file:${SECRETS_DIR}/jwt-private.pem"
  export APP_SECURITY_JWT_PUBLIC_KEY="file:${SECRETS_DIR}/jwt-public.pem"
fi

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar "${JAR}" "$@"
