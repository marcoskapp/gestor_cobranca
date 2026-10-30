#!/bin/bash
# =============================================================================
# Funções comuns dos scripts do sandbox de demonstração (docs/tecnico/operacao/sandbox.md).
# =============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly COMPOSE_FILE="${REPO_ROOT}/infra/docker/sandbox/compose.yml"
readonly COMPOSE_PROJECT=gestor-cobranca-sandbox
readonly APP_PORT="${SANDBOX_APP_PORT:-58080}"
readonly MAIL_PORT="${SANDBOX_MAIL_PORT:-58025}"
readonly DB_PORT="${SANDBOX_DB_PORT:-55432}"
readonly APP_URL="http://localhost:${APP_PORT}"

# Mapa chave → valor compatível com o Bash 3.2 do macOS (sem `declare -A`): cada chave vira a variável
# KV_<chave normalizada>. Chaves usadas: assessoria, pessoa e tipo de id (letras, números e ':').
kv_set() { printf -v "KV_${1//[^a-zA-Z0-9]/_}" '%s' "$2"; }
kv_get() { local name="KV_${1//[^a-zA-Z0-9]/_}"; printf '%s' "${!name:-}"; }

log() { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${1}" "${2}" >&2; }
die() { log ERRO "$1"; exit 1; }

require_cmd() {
  local c
  for c in "$@"; do
    command -v "${c}" >/dev/null 2>&1 || die "comando obrigatório ausente: ${c}"
  done
}

require_docker() {
  require_cmd docker curl
  docker info >/dev/null 2>&1 || die "o Docker não está acessível (daemon parado ou sem permissão)"
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 ausente (comando 'docker compose')"
}

readonly APP_IMAGE=gestor-cobranca-sandbox/app:local

compose() {
  docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}" "$@"
}

# Imagem da aplicação (infra/docker/app/Dockerfile). Construída com `docker build` (e não `compose build`) para
# aceitar SANDBOX_BUILD_NETWORK=host sem exigir permissões extras do Buildx em ambientes com proxy local.
build_app_image() {
  local network=()
  if [[ -n "${SANDBOX_BUILD_NETWORK:-}" && "${SANDBOX_BUILD_NETWORK}" != default ]]; then
    network=(--network "${SANDBOX_BUILD_NETWORK}")
  fi
  docker build ${network[@]+"${network[@]}"} -f "${REPO_ROOT}/infra/docker/app/Dockerfile" \
    --build-arg "NODE_IMAGE=${SANDBOX_NODE_IMAGE:-node:22-bookworm-slim}" \
    --build-arg "MAVEN_IMAGE=${SANDBOX_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}" \
    --build-arg "JRE_IMAGE=${SANDBOX_JRE_IMAGE:-eclipse-temurin:21-jre-noble}" \
    -t "${APP_IMAGE}" "${REPO_ROOT}"
}

# Linhas (sem comentários) do arquivo de tokens de demonstração da API pública, lido de dentro do contêiner:
# token_id, assessoria, credor, nome, escopos, token — separados por tabulação.
sandbox_tokens() {
  compose exec -T app cat /sandbox-data/api-tokens.txt 2>/dev/null | grep -v '^#' | grep -v '^$'
}

# Índice dos PDFs fictícios de demonstração da Importação PDF (gerados pela carga no volume do sandbox):
# assessoria, credor, carteira, arquivo, finalidade — separados por tabulação.
sandbox_pdf_index() {
  compose exec -T app cat /sandbox-data/pdfs/index.tsv 2>/dev/null | grep -v '^#' | grep -v '^$'
}

# sandbox_pdf <assessoria> <arquivo> → conteúdo do PDF de demonstração (stdout, binário). Sem stdin (</dev/null): o
# `exec` consumiria a entrada de quem chama em laço (while read … <<< índice).
sandbox_pdf() {
  compose exec -T app cat "/sandbox-data/pdfs/$1/$2" </dev/null
}

# Estado de prontidão (UP só depois da carga de dados fictícios) e detalhes da carga, se publicados.
readiness_json() {
  # sem -f: em 503 (fora de prontidão) o corpo traz a etapa e a falha da carga
  curl -sS --max-time 5 "${APP_URL}/actuator/health/readiness" 2>/dev/null || true
}

print_summary() {
  cat >&2 <<EOF

  Sandbox do Gestor de Cobrança no ar (dados FICTÍCIOS)

  Aplicação ........ ${APP_URL}
  Swagger (API) .... ${APP_URL}/swagger-ui/index.html  (aberto sem login no sandbox; chamadas pedem token)
  Mailpit (e-mails)  http://localhost:${MAIL_PORT}
  PostgreSQL ....... localhost:${DB_PORT} (banco cobranca; somente para inspeção)

  Assessorias (campo "Assessoria" do login): aurora, boreal   |   plataforma: plataforma
  Usuários: <perfil>@<assessoria>.exemplo.test (ex.: gestor@aurora.exemplo.test)
            administrador da plataforma: admin@plataforma.exemplo.test
  Senha de demonstração: ${SANDBOX_DEMO_PASSWORD:-Sandbox@2026!}

  API pública: tokens de demonstração em ./scripts/sandbox/tokens.sh · docs em ${APP_URL}/public-api/docs
  Importação PDF: PDFs fictícios de demonstração em ./scripts/sandbox/pdfs.sh (copia para ./sandbox-pdfs/)
  Lista completa de usuários e roteiro de demonstração: docs/tecnico/operacao/sandbox.md
EOF
}
