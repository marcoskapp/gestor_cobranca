#!/bin/bash
# =============================================================================
# Publica o sandbox na internet por um túnel temporário da Cloudflare (Quick Tunnel, sem conta) e mostra o endereço
# https://<aleatório>.trycloudflare.com. O túnel roda como serviço do próprio Compose (perfil tunnel), único proxy em
# que a aplicação confia (APP_HTTP_TRUSTED_PROXIES) — ver docs/tecnico/operacao/sandbox.md §2b e decisão 689.
#
#   ./scripts/sandbox/tunnel.sh            sobe o túnel (o sandbox precisa estar no ar: ./scripts/sandbox/up.sh)
#   ./scripts/sandbox/tunnel.sh --stop     derruba o túnel (a aplicação continua em http://localhost:58080)
#
# ATENÇÃO: com o túnel no ar, QUALQUER pessoa com o endereço chega à tela de login, e a senha de demonstração é
# pública. Use só com dados fictícios e derrube o túnel ao fim da demonstração.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case "${1:-}" in
  "") ;;
  --stop)
    require_docker
    compose --profile tunnel rm -sf tunnel >/dev/null
    log INFO "túnel encerrado; o sandbox segue em ${APP_URL}"
    exit 0 ;;
  -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
  *) die "uso: $0 [--stop]" ;;
esac
[[ $# -le 1 ]] || die "uso: $0 [--stop]"

require_docker
[[ "$(readiness_json)" == *'"status":"UP"}' ]] || die "o sandbox não está pronto em ${APP_URL}: rode ./scripts/sandbox/up.sh antes"

log INFO "subindo o túnel da Cloudflare (perfil tunnel)"
compose --profile tunnel up -d --no-build tunnel

url=""
deadline=$(( $(date +%s) + 90 ))
while [[ -z "${url}" ]]; do
  url="$(compose --profile tunnel logs tunnel 2>/dev/null | grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' | grep -v '^https://api\.' | tail -1 || true)"
  [[ -n "${url}" ]] && break
  if (( $(date +%s) >= deadline )); then
    compose --profile tunnel logs --tail 40 tunnel >&2 || true
    die "o túnel não informou o endereço em 90 s (logs acima)"
  fi
  sleep 2
done

cat >&2 <<MSG

  Túnel no ar: ${url}
  (o endereço muda a cada subida do túnel; a propagação do DNS pode levar alguns segundos)

  ATENÇÃO: qualquer pessoa com este endereço chega ao login e a senha de demonstração é pública.
  Derrube ao terminar: ./scripts/sandbox/tunnel.sh --stop

MSG
printf '%s\n' "${url}"
