#!/bin/bash
# =============================================================================
# Mostra os tokens de demonstração da API pública emitidos pela carga do sandbox (dados FICTÍCIOS):
# assessoria, credor, nome do token, escopos e o token completo (gck_<prefixo>.<segredo>).
#
#   ./scripts/sandbox/tokens.sh            tabela legível
#   ./scripts/sandbox/tokens.sh --raw      linhas separadas por tabulação (para scripts)
#
# O segredo só é exibido na emissão: a carga o guarda em /sandbox-data/api-tokens.txt (volume exclusivo do sandbox,
# permissão 600) e o log mascara gck_. Num reset os tokens são recriados com novos segredos.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

RAW=false
case "${1:-}" in
  --raw) RAW=true ;;
  "") ;;
  -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
  *) die "argumento desconhecido: $1" ;;
esac

require_docker
content="$(sandbox_tokens)" || die "arquivo de tokens indisponível (o sandbox está no ar e a carga terminou?)"
[[ -n "${content}" ]] || die "nenhum token emitido ainda (a carga terminou? veja ./scripts/sandbox/up.sh)"

if [[ "${RAW}" == true ]]; then
  printf '%s\n' "${content}"
  exit 0
fi

printf '\nTokens de demonstração da API pública (base: %s/public-api/v1)\n\n' "${APP_URL}"
while IFS=$'\t' read -r _id tenant creditor name scopes token; do
  printf '  %-7s %-16s %-24s\n    escopos: %s\n    token:   %s\n\n' "${tenant}" "${creditor}" "${name}" \
    "${scopes//,/, }" "${token}"
done <<<"${content}"
printf '  Exemplo: curl -H "Authorization: Bearer <token>" %s/public-api/v1/portfolios\n' "${APP_URL}"
printf '  Documentação: %s/public-api/docs  ·  changelog: %s/public-api/docs/changelog\n\n' "${APP_URL}" "${APP_URL}"
