#!/bin/bash
# =============================================================================
# Publica o sandbox na internet por um túnel temporário da Cloudflare (Quick Tunnel, sem conta) e mostra o endereço
# https://<aleatório>.trycloudflare.com. O túnel roda como serviço do próprio Compose (perfil tunnel), único proxy em
# que a aplicação confia (APP_HTTP_TRUSTED_PROXIES) — ver docs/tecnico/operacao/sandbox.md §2b e decisão 689.
#
# Com o túnel no ar, a aplicação é recriada com esse endereço em APP_BASE_URL (decisão 1080): os links enviados por
# e-mail (portal do devedor, canal com o credor, ativação) saem em https — o envio de link exige https — e abrem de
# qualquer aparelho. A aplicação reinicia (cerca de 1 min; os dados ficam). --stop volta tudo para localhost.
#
#   ./scripts/sandbox/tunnel.sh            sobe o túnel (o sandbox precisa estar no ar: ./scripts/sandbox/up.sh)
#   ./scripts/sandbox/tunnel.sh --stop     derruba o túnel (a aplicação volta a http://localhost:58080)
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
    forget_public_url
    if [[ "$(compose exec -T app printenv APP_BASE_URL 2>/dev/null || true)" == https://* ]]; then
      log INFO "túnel encerrado; recriando a aplicação com os links em ${APP_URL}"
      compose up -d --no-build app
      wait_ready 600
    fi
    log INFO "túnel encerrado; o sandbox segue em ${APP_URL}"
    exit 0 ;;
  -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
  *) die "uso: $0 [--stop]" ;;
esac
[[ $# -le 1 ]] || die "uso: $0 [--stop]"

require_docker
[[ "$(readiness_json)" == *'"status":"UP"}' ]] || die "o sandbox não está pronto em ${APP_URL}: rode ./scripts/sandbox/up.sh antes"

log INFO "subindo o túnel da Cloudflare (perfil tunnel)"
# contêiner parado guarda nos logs o endereço da subida anterior (já morto): começa de um contêiner novo
if ! tunnel_running; then
  compose --profile tunnel rm -sf tunnel >/dev/null
fi
# --no-deps: subir o túnel não recria a aplicação (ela é recriada abaixo, uma vez, já com o endereço)
compose --profile tunnel up -d --no-build --no-deps tunnel

url=""
deadline=$(( $(date +%s) + 90 ))
while [[ -z "${url}" ]]; do
  url="$(tunnel_logged_url)"
  [[ -n "${url}" ]] && break
  if (( $(date +%s) >= deadline )); then
    compose --profile tunnel logs --tail 40 tunnel >&2 || true
    die "o túnel não informou o endereço em 90 s (logs acima)"
  fi
  sleep 2
done

if [[ "$(compose exec -T app printenv APP_BASE_URL 2>/dev/null)" != "${url}" ]]; then
  printf '%s\n' "${url}" >"${PUBLIC_URL_FILE}"
  log INFO "recriando a aplicação com os links dos e-mails em ${url} (cerca de 1 min; os dados ficam)"
  compose up -d --no-build app
  wait_ready 600
fi
[[ "$(compose exec -T app printenv APP_BASE_URL 2>/dev/null)" == "${url}" ]] || die "a aplicação não assumiu o endereço do túnel"

cat >&2 <<MSG

  Túnel no ar: ${url}
  (o endereço muda a cada subida do túnel; a propagação do DNS pode levar alguns segundos)
  Links enviados por e-mail (portal do devedor, canal com o credor, ativação) saem com este endereço, em https.

  ATENÇÃO: qualquer pessoa com este endereço chega ao login e a senha de demonstração é pública.
  Derrube ao terminar: ./scripts/sandbox/tunnel.sh --stop

MSG
printf '%s\n' "${url}"
