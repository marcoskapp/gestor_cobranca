#!/bin/bash
# =============================================================================
# Provisiona os segredos de um ambiente diretamente no cofre — os valores NUNCA passam
# pelo Terraform, pelo state, por tfvars nem por argumentos de linha de comando.
#
#   scripts/ops/provision-secrets.sh --env <ambiente> generate [--only <nome>] [--force]
#       Gera valores aleatórios (senhas, chaves AES-256, chave do blind index, par RSA JWT)
#       e grava os ausentes (ou o indicado com --only; --force sobrescreve = rotação).
#   scripts/ops/provision-secrets.sh --env <ambiente> set <nome>
#       Grava um valor informado por stdin (ex.: credenciais SMTP do provedor).
#   scripts/ops/provision-secrets.sh --env <ambiente> check
#       Lista quais segredos existem (sem exibir valores).
#
# Destino conforme a saída secrets_source do Terraform:
#   ssm → SecureString em /gestor-cobranca/<env>/<nome>, cifrada com a chave KMS do ambiente
#   s3  → s3://<bucket-de-segredos>/<env>/<nome>, SSE, acesso restrito por política
#
# Nomes:
#   app/db-password                senha de app_runtime_login
#   migration/db-owner-password    senha de app_owner (somente migrations)
#   app/crypto-key-<id>            chave AES-256 de campo (Base64, 32 bytes)
#   app/crypto-blind-index-key     chave HMAC do blind index (Base64, 32 bytes; NÃO rotacionar sem reindexação)
#   app/jwt-private-key            chave RSA 2048 PKCS#8 (PEM)
#   app/jwt-public-key             chave pública X.509 (PEM)
#   app/smtp-username, app/smtp-password   credenciais do provedor SMTP (set)
#   app/helpdesk-github-token      OPCIONAL: token fine-grained do GitHub para os chamados do chat de
#                                  suporte (set); obrigatório só com helpdesk_tickets_enabled = true
# =============================================================================
set -Eeuo pipefail
umask 077

# shellcheck source=../deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/../deploy/lib.sh"

ENVIRONMENT=""
ACTION=""
NAME=""
ONLY=""
FORCE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --force) FORCE=true; shift ;;
    generate|check) ACTION="$1"; shift ;;
    set)
      ACTION="set"
      [[ $# -ge 2 ]] || die "uso: set <nome>"
      NAME="$2"
      shift 2
      ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
validate_env "${ENVIRONMENT}"
require_cmd aws jq openssl "${TF_BIN}"

REGION="$(tf_output "${ENVIRONMENT}" region)"
SOURCE="$(tf_output "${ENVIRONMENT}" secrets_source)"
if [[ "${SOURCE}" == ssm ]]; then
  PREFIX="$(tf_output "${ENVIRONMENT}" ssm_parameter_prefix)"
  KMS_KEY="$(tf_output "${ENVIRONMENT}" secrets_kms_key_arn)"
else
  BUCKET="$(tf_output "${ENVIRONMENT}" secrets_bucket_name)"
fi

# crypto_key_ids não é saída: as chaves AES são geradas para os ids informados.
CRYPTO_KEY_IDS="${CRYPTO_KEY_IDS:-k1}"

ALL_NAMES=(app/db-password migration/db-owner-password app/crypto-blind-index-key app/jwt-private-key app/jwt-public-key app/smtp-username app/smtp-password)
# Opcionais: só exigidos quando a integração correspondente é ligada no Terraform (o user-data os
# lê somente nesse caso); nunca gerados, só gravados com 'set'.
OPTIONAL_NAMES=(app/helpdesk-github-token)
for id in ${CRYPTO_KEY_IDS}; do
  [[ "${id}" =~ ^[a-z][a-z0-9]{0,15}$ ]] || die "id de chave inválido: ${id}"
  ALL_NAMES+=("app/crypto-key-${id}")
done

valid_name() {
  local n
  for n in "${ALL_NAMES[@]}" "${OPTIONAL_NAMES[@]}"; do [[ "$1" == "${n}" ]] && return 0; done
  return 1
}

secret_exists() {
  if [[ "${SOURCE}" == ssm ]]; then
    aws ssm get-parameter --region "${REGION}" --name "${PREFIX}/$1" --query Parameter.Name --output text >/dev/null 2>&1
  else
    aws s3api head-object --region "${REGION}" --bucket "${BUCKET}" --key "${ENVIRONMENT}/$1" >/dev/null 2>&1
  fi
}

# put_secret <nome> <arquivo> — o valor é lido de arquivo (nunca de argumento).
put_secret() {
  local name="$1" file="$2"
  if [[ "${SOURCE}" == ssm ]]; then
    aws ssm put-parameter --region "${REGION}" --name "${PREFIX}/${name}" --type SecureString \
      --key-id "${KMS_KEY}" --tier "$( [[ $(wc -c < "${file}") -gt 4096 ]] && echo Advanced || echo Standard )" \
      --overwrite --value "file://${file}" >/dev/null
  else
    aws s3 cp --region "${REGION}" --only-show-errors --sse AES256 "${file}" "s3://${BUCKET}/${ENVIRONMENT}/${name}"
  fi
  log INFO "segredo ${name} gravado (${SOURCE})"
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# Senhas: 40 caracteres alfanuméricos (seguros em EnvironmentFile, pgpass e psql).
gen_password() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c 40; }
gen_key32() { openssl rand -base64 32 | tr -d '\n'; }

generate_one() {
  local name="$1" file="${work}/value"
  case "${name}" in
    app/db-password|migration/db-owner-password) gen_password > "${file}" ;;
    app/crypto-key-*|app/crypto-blind-index-key) gen_key32 > "${file}" ;;
    app/jwt-private-key)
      openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${work}/jwt-private.pem" 2>/dev/null
      openssl pkey -in "${work}/jwt-private.pem" -pubout -out "${work}/jwt-public.pem"
      put_secret app/jwt-private-key "${work}/jwt-private.pem"
      put_secret app/jwt-public-key "${work}/jwt-public.pem"
      return
      ;;
    app/jwt-public-key) return ;;  # gerada com a privada
    app/smtp-username|app/smtp-password)
      log AVISO "${name} vem do provedor SMTP: use 'set ${name}'"
      return
      ;;
    app/helpdesk-github-token)
      log AVISO "${name} é criado no GitHub: use 'set ${name}'"
      return
      ;;
    *) die "segredo desconhecido: ${name}" ;;
  esac
  put_secret "${name}" "${file}"
}

case "${ACTION}" in
  check)
    for n in "${ALL_NAMES[@]}"; do
      if secret_exists "${n}"; then echo "ok        ${n}"; else echo "AUSENTE   ${n}"; fi
    done
    for n in "${OPTIONAL_NAMES[@]}"; do
      if secret_exists "${n}"; then echo "ok        ${n} (opcional)"; else echo "opcional  ${n} (não cadastrado)"; fi
    done
    ;;
  generate)
    if [[ -n "${ONLY}" ]]; then
      valid_name "${ONLY}" || die "segredo desconhecido: ${ONLY}"
      [[ "${ONLY}" != app/crypto-blind-index-key || "${FORCE}" == false ]] \
        || die "a chave do blind index não pode ser rotacionada sem reindexação dos dados"
      if secret_exists "${ONLY}" && [[ "${FORCE}" == false ]]; then
        die "${ONLY} já existe; use --force para rotacionar"
      fi
      generate_one "${ONLY}"
    else
      [[ "${FORCE}" == false ]] || die "--force exige --only <nome>"
      for n in "${ALL_NAMES[@]}"; do
        if secret_exists "${n}"; then
          log INFO "${n} já existe; mantido"
        else
          generate_one "${n}"
        fi
      done
    fi
    ;;
  set)
    valid_name "${NAME}" || die "segredo desconhecido: '${NAME}'"
    [[ ! -t 0 ]] || log INFO "digite o valor e finalize com Ctrl-D"
    cat > "${work}/value"
    # Remove a quebra de linha final de valores de uma linha.
    if [[ "$(wc -l < "${work}/value")" -le 1 ]]; then
      printf '%s' "$(cat "${work}/value")" > "${work}/value.trim" && mv "${work}/value.trim" "${work}/value"
    fi
    [[ -s "${work}/value" ]] || die "valor vazio"
    case "${NAME}" in
      app/jwt-private-key|app/jwt-public-key) ;;
      *)
        # Segredos de uma linha vão para EnvironmentFile entre aspas simples.
        if grep -q "'" "${work}/value" || [[ "$(wc -l < "${work}/value")" -gt 0 ]]; then
          die "${NAME}: o valor deve ter uma única linha e não pode conter aspas simples"
        fi
        ;;
    esac
    case "${NAME}" in
      app/db-password|migration/db-owner-password)
        grep -Eq '^[A-Za-z0-9._~+=-]{16,128}$' "${work}/value" \
          || die "${NAME}: senha deve ter 16-128 caracteres [A-Za-z0-9._~+=-] (prefira 'generate')"
        ;;
      app/helpdesk-github-token)
        # Só token fine-grained (escopo de um repositório, validade obrigatória); o clássico dá acesso amplo.
        grep -Eq '^github_pat_[A-Za-z0-9_]{20,255}$' "${work}/value" \
          || die "${NAME}: use um token fine-grained do GitHub (começa com github_pat_)"
        ;;
      *) ;;
    esac
    put_secret "${NAME}" "${work}/value"
    ;;
  *)
    die "ação obrigatória: generate | set <nome> | check"
    ;;
esac
