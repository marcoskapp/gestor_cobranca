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
# SANDBOX_BUILD_NETWORK (rede do build: default|host). Túnel público opcional: ./scripts/sandbox/tunnel.sh.
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
# túnel registrado mas fora do ar (Docker reiniciado, contêiner removido): os links voltam para localhost
if [[ -n "$(public_url)" ]] && ! tunnel_running; then
  log AVISO "túnel registrado ($(public_url)) fora do ar: links voltam para ${APP_URL}; suba de novo com ./scripts/sandbox/tunnel.sh"
  forget_public_url
fi
log INFO "subindo PostgreSQL, Mailpit e aplicação (projeto ${COMPOSE_PROJECT})"
compose up -d --no-build

log INFO "aguardando a aplicação e a carga de dados fictícios (até ${TIMEOUT} s)"
wait_ready "${TIMEOUT}"

print_summary
