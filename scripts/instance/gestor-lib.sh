#!/bin/bash
# shellcheck disable=SC2034  # constantes consumidas pelos scripts que carregam esta biblioteca
# =============================================================================
# Funções comuns aos scripts da instância (bootstrap e execução de migrations).
# Instalado em /usr/local/libexec/gestor-cobranca/gestor-lib.sh pelo user-data.
#
# Espera /etc/gestor-cobranca/instance.conf (gerado pelo Terraform, sem segredos) com:
#   GC_ENVIRONMENT, GC_REGION, GC_ARTIFACTS_BUCKET, GC_ARTIFACT_PREFIX,
#   GC_SECRETS_SOURCE (ssm|s3), GC_SECRETS_SSM_PREFIX, GC_SECRETS_BUCKET, ...
# =============================================================================

readonly GC_CONF_DIR=/etc/gestor-cobranca
readonly GC_INSTALL_DIR=/opt/gestor-cobranca
readonly GC_LOG_DIR=/var/log/gestor-cobranca
readonly GC_STATE_DIR=/var/lib/gestor-cobranca
readonly GC_SERVICE_USER=gestor
readonly GC_JAR_NAME=gestor-cobranca.jar

# Versão de artefato: letras, dígitos, ponto, hífen e sublinhado (ex.: 1.4.0, 2026.10.22-3f9c1ab).
readonly GC_VERSION_REGEX='^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$'

gc_json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\t'/\\t}"
  printf '%s' "${s}"
}

gc_log() {
  # Uma linha JSON por evento, no mesmo formato dos logs da aplicação (campos level,
  # stream, logger, env, instance, message), coletada pelo CloudWatch Agent no grupo
  # technical. Mensagens nunca contêm segredos.
  printf '{"@timestamp":"%s","level":"%s","stream":"technical","logger":"%s","env":"%s","instance":"%s","message":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$1" "${GC_COMPONENT:-bootstrap}" \
    "$(gc_json_escape "${GC_ENVIRONMENT:-unknown}")" "$(gc_json_escape "${GC_INSTANCE_ID:-unknown}")" \
    "$(gc_json_escape "$2")"
}

gc_fail() {
  gc_log ERROR "$1"
  exit 1
}

gc_load_conf() {
  [[ -r "${GC_CONF_DIR}/instance.conf" ]] || gc_fail "instance.conf ausente"
  # shellcheck source=/dev/null
  source "${GC_CONF_DIR}/instance.conf"
  local required=(GC_ENVIRONMENT GC_REGION GC_ARTIFACTS_BUCKET GC_ARTIFACT_PREFIX GC_SECRETS_SOURCE)
  local name
  for name in "${required[@]}"; do
    [[ -n "${!name:-}" ]] || gc_fail "variável ${name} ausente em instance.conf"
  done
  export AWS_REGION="${GC_REGION}" AWS_DEFAULT_REGION="${GC_REGION}"
}

# --- IMDSv2 ------------------------------------------------------------------------

gc_imds_token() {
  curl -fsS --retry 5 --retry-connrefused --max-time 5 -X PUT \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300' \
    http://169.254.169.254/latest/api/token
}

gc_imds_get() {
  local path="$1" token
  token="$(gc_imds_token)"
  curl -fsS --retry 5 --retry-connrefused --max-time 5 \
    -H "X-aws-ec2-metadata-token: ${token}" \
    "http://169.254.169.254/latest/meta-data/${path}"
}

gc_validate_version() {
  [[ "$1" =~ $GC_VERSION_REGEX ]] || gc_fail "versão de artefato inválida: '$1'"
}

# --- Artefato --------------------------------------------------------------------------

# Baixa artifacts/<versão>/gestor-cobranca.jar e SHA256SUMS para o diretório informado e
# valida o hash. Falha (sem deixar o JAR no destino) se o hash não conferir.
gc_download_artifact() {
  local version="$1" dest_dir="$2" base work expected actual
  gc_validate_version "${version}"
  base="s3://${GC_ARTIFACTS_BUCKET}/${GC_ARTIFACT_PREFIX}/${version}"
  work="$(mktemp -d "${GC_STATE_DIR}/download.XXXXXX")"

  aws s3 cp --only-show-errors "${base}/SHA256SUMS" "${work}/SHA256SUMS" \
    || { rm -rf "${work}"; gc_fail "não foi possível baixar ${base}/SHA256SUMS"; }
  aws s3 cp --only-show-errors "${base}/${GC_JAR_NAME}" "${work}/${GC_JAR_NAME}" \
    || { rm -rf "${work}"; gc_fail "não foi possível baixar ${base}/${GC_JAR_NAME}"; }

  expected="$(awk -v f="${GC_JAR_NAME}" '$2 == f || $2 == "*" f { print $1 }' "${work}/SHA256SUMS")"
  [[ "${expected}" =~ ^[0-9a-f]{64}$ ]] || { rm -rf "${work}"; gc_fail "SHA256SUMS não contém o hash de ${GC_JAR_NAME}"; }
  actual="$(sha256sum "${work}/${GC_JAR_NAME}" | awk '{ print $1 }')"
  if [[ "${expected}" != "${actual}" ]]; then
    rm -rf "${work}"
    gc_fail "hash do artefato ${version} não confere (esperado ${expected}, obtido ${actual})"
  fi

  install -o root -g "${GC_SERVICE_USER}" -m 0640 "${work}/${GC_JAR_NAME}" "${dest_dir}/${GC_JAR_NAME}"
  rm -rf "${work}"
  gc_log INFO "artefato ${version} validado (sha256 ${actual})"
}

# --- Segredos ----------------------------------------------------------------------------

# Lê um segredo pelo nome relativo (ex.: app/db-password) e o imprime em stdout.
#   ssm: SecureString em ${GC_SECRETS_SSM_PREFIX}/<nome>   (requer aprovação SSM + KMS)
#   s3 : objeto s3://${GC_SECRETS_BUCKET}/<ambiente>/<nome> (bucket dedicado, acesso restrito)
# O valor nunca é registrado em log nem passado como argumento de processo.
gc_read_secret() {
  local name="$1"
  case "${GC_SECRETS_SOURCE}" in
    ssm)
      aws ssm get-parameter --with-decryption \
        --name "${GC_SECRETS_SSM_PREFIX}/${name}" \
        --query Parameter.Value --output text
      ;;
    s3)
      aws s3 cp --only-show-errors "s3://${GC_SECRETS_BUCKET}/${GC_ENVIRONMENT}/${name}" -
      ;;
    *)
      gc_fail "GC_SECRETS_SOURCE inválido: ${GC_SECRETS_SOURCE}"
      ;;
  esac
}

# Segredos gravados em EnvironmentFile ficam entre aspas simples (sem escapes nem expansão
# no systemd): o valor precisa ser uma linha não vazia sem aspas simples.
gc_assert_env_safe() {
  local name="$1" value="$2"
  [[ -n "${value}" ]] || gc_fail "segredo ${name} vazio"
  if [[ "${value}" == *$'\n'* || "${value}" == *"'"* ]]; then
    gc_fail "segredo ${name} contém aspas simples ou quebra de linha; regenere-o"
  fi
}

# Senhas do banco também são usadas em pgpass e em variáveis do psql: somente caracteres
# que dispensam escape (o provisionamento gera 40 caracteres alfanuméricos).
gc_assert_db_password_safe() {
  local name="$1" value="$2"
  [[ "${value}" =~ ^[A-Za-z0-9._~+=-]{16,128}$ ]] \
    || gc_fail "senha ${name} fora do formato exigido (16-128 caracteres [A-Za-z0-9._~+=-]); regenere-a"
}

# Grava um arquivo de forma atômica com dono e modo definidos, lendo o conteúdo de stdin.
gc_write_file() {
  local path="$1" owner="$2" mode="$3" tmp
  tmp="$(mktemp "${path}.XXXXXX")"
  chmod 0600 "${tmp}"
  cat > "${tmp}"
  chown "${owner}" "${tmp}"
  chmod "${mode}" "${tmp}"
  mv -f "${tmp}" "${path}"
}
