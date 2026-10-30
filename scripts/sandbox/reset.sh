#!/bin/bash
# =============================================================================
# Recria o sandbox do zero: remove contêineres e VOLUMES do projeto gestor-cobranca-sandbox (banco,
# chaves geradas, arquivos e logs) e sobe de novo com nova carga de dados fictícios.
#
#   ./scripts/sandbox/reset.sh [--yes] [opções do up.sh]
#
# Só afeta o projeto Compose do sandbox; o ambiente de desenvolvimento (gestor-cobranca) não é tocado.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

CONFIRMED=false
UP_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes|-y) CONFIRMED=true; shift ;;
    *) UP_ARGS+=("$1"); shift ;;
  esac
done

require_docker
if [[ "${CONFIRMED}" != true ]]; then
  read -r -p "Apagar todos os dados do sandbox (${COMPOSE_PROJECT}) e recriar? [s/N] " answer
  [[ "${answer}" =~ ^[sS]$ ]] || die "cancelado"
fi
log INFO "removendo contêineres e volumes do sandbox"
compose down --volumes --remove-orphans
exec "$(dirname "${BASH_SOURCE[0]}")/up.sh" ${UP_ARGS[@]+"${UP_ARGS[@]}"}
