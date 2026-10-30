#!/bin/bash
# =============================================================================
# Sobe o sandbox de demonstração e espera a carga de dados fictícios terminar.
#
#   ./scripts/sandbox/up.sh [--no-build] [--timeout <segundos>]
#
#   --no-build   reutiliza a imagem já construída (gestor-cobranca-sandbox/app:local)
#   --timeout    espera máxima pela prontidão (padrão 1800 s: o primeiro build baixa dependências)
#
# Variáveis opcionais (ver infra/docker/sandbox/compose.yml): SANDBOX_APP_PORT, SANDBOX_MAIL_PORT,
# SANDBOX_DB_PORT, SANDBOX_DEMO_PASSWORD e, para registros/espelhos internos, SANDBOX_NODE_IMAGE,
# SANDBOX_MAVEN_IMAGE, SANDBOX_JRE_IMAGE, SANDBOX_POSTGRES_IMAGE, SANDBOX_MAILPIT_IMAGE e
# SANDBOX_BUILD_NETWORK (rede do build: default|host).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BUILD=true
TIMEOUT=1800
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=false; shift ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
[[ "${TIMEOUT}" =~ ^[0-9]+$ ]] || die "--timeout deve ser um número de segundos"

require_docker

if [[ "${BUILD}" == true ]]; then
  log INFO "construindo a imagem da aplicação (frontend + backend); o primeiro build demora alguns minutos"
  build_app_image
elif ! docker image inspect "${APP_IMAGE}" >/dev/null 2>&1; then
  die "imagem ${APP_IMAGE} inexistente: rode sem --no-build"
fi
log INFO "subindo PostgreSQL, Mailpit e aplicação (projeto ${COMPOSE_PROJECT})"
compose up -d --no-build

log INFO "aguardando a aplicação e a carga de dados fictícios (até ${TIMEOUT} s)"
deadline=$(( $(date +%s) + TIMEOUT ))
last_phase=""
while true; do
  body="$(readiness_json)"
  if [[ "${body}" == *'"status":"UP"}' ]]; then   # situação geral (fim do JSON), não a de um componente
    break
  fi
  phase="$(sed -n 's/.*"phase":"\([^"]*\)".*/\1/p' <<<"${body}")"
  if [[ -n "${phase}" && "${phase}" != "${last_phase}" ]]; then
    log INFO "carga do sandbox: ${phase}"
    last_phase="${phase}"
  fi
  if [[ "${body}" == *'"state":"FAILED"'* ]]; then
    compose logs --tail 80 app >&2 || true
    die "a carga de dados do sandbox falhou (detalhes acima; logs completos: docker compose -p ${COMPOSE_PROJECT} logs app)"
  fi
  if [[ "$(compose ps --status exited --services 2>/dev/null)" == *app* ]]; then
    compose logs --tail 80 app >&2 || true
    die "o contêiner da aplicação parou (logs acima)"
  fi
  (( $(date +%s) < deadline )) || die "tempo esgotado aguardando a prontidão; acompanhe com: docker compose -p ${COMPOSE_PROJECT} logs -f app"
  sleep 5
done

print_summary
