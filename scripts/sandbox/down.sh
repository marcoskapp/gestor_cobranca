#!/bin/bash
# =============================================================================
# Para o sandbox de demonstração MANTENDO os dados (volumes). Para apagar tudo: reset.sh.
#
#   ./scripts/sandbox/down.sh
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 0 ]] || die "uso: $0"
require_docker
compose down
log INFO "sandbox parado; os dados foram mantidos (./scripts/sandbox/up.sh --no-build para voltar)"
