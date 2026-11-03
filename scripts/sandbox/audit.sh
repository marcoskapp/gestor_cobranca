#!/bin/bash
# =============================================================================
# Auditoria da interface do sandbox, perfil a perfil (decisão 688): percorre menu, atalhos, busca, abas e o
# primeiro registro de cada lista e relata, por perfil × página, respostas 403, avisos "Acesso negado", cartões
# lado a lado desalinhados e o documento rolando além da área de conteúdo.
#
#   ./scripts/sandbox/audit.sh                         perfis padrão (todos da aurora + 2 da boreal)
#   ./scripts/sandbox/audit.sh aurora/juridico ...     só os perfis informados (assessoria/pessoa)
#   ./scripts/sandbox/audit.sh --local [perfis]        força o Node local (sem a imagem do Playwright)
#   ./scripts/sandbox/audit.sh --docker [perfis]       exige a imagem oficial do Playwright
#
# Relatório em ./sandbox-audit/ (relatorio.md e resultado.json). Saída 0 sem problemas, 1 com problemas,
# 2 quando a auditoria não roda. Variáveis: AUDIT_PARALLEL (3), AUDIT_DETAIL (1), AUDIT_WIDTH/AUDIT_HEIGHT
# (1280×800), AUDIT_OUT, SANDBOX_PLAYWRIGHT_IMAGE. Guia: docs/tecnico/operacao/sandbox.md (Auditoria da interface).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# A auditoria usa o código 2 para "não rodou" (1 = problemas encontrados).
fatal() { log ERRO "$1"; exit 2; }

readonly AUDIT_DIR="${REPO_ROOT}/scripts/sandbox/audit"
OUT_DIR="${AUDIT_OUT:-${REPO_ROOT}/sandbox-audit}"
MODE=auto
PROFILES=""
for arg in "$@"; do
  case "${arg}" in
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    --local) MODE=local ;;
    --docker) MODE=docker ;;
    -*) fatal "opção desconhecida: ${arg} (veja --help)" ;;
    *)
      [[ "${arg}" =~ ^[a-z0-9-]+/[a-z0-9]+$ ]] || fatal "perfil inválido: ${arg} (use assessoria/pessoa, ex.: aurora/juridico)"
      PROFILES="${PROFILES:+${PROFILES} }${arg}"
      ;;
  esac
done

# Versão do Playwright fixada no package.json: a imagem usa a mesma, para os navegadores baterem com a biblioteca.
PW_VERSION="$(sed -n 's/.*"playwright": *"\([0-9][0-9.]*\)".*/\1/p' "${AUDIT_DIR}/package.json")"
[[ -n "${PW_VERSION}" ]] || fatal "versão do Playwright não encontrada em ${AUDIT_DIR}/package.json"
IMAGE="${SANDBOX_PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright:v${PW_VERSION}-noble}"

require_cmd curl
curl -fsS --max-time 10 "${APP_URL}/actuator/health/readiness" >/dev/null 2>&1 \
  || fatal "o sandbox não está pronto em ${APP_URL} (rode ./scripts/sandbox/up.sh)"
mkdir -p "${OUT_DIR}"

docker_image_available() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || return 1
  docker image inspect "${IMAGE}" >/dev/null 2>&1 && return 0
  log INFO "baixando ${IMAGE}"
  docker pull "${IMAGE}" >/dev/null 2>&1
}

run_docker() {
  log INFO "auditoria na imagem ${IMAGE}"
  # rede do host: o navegador acessa o sandbox em localhost, como no Node local (no Docker Desktop, ative
  # "Enable host networking"; sem isso use --local). Código copiado para /work: o repositório fica só leitura.
  local status=0
  docker run --rm --init --ipc=host --network host \
    --user "$(id -u):$(id -g)" -e HOME=/tmp -e npm_config_cache=/tmp/.npm \
    -e APP_URL="${APP_URL}" -e AUDIT_OUT=/out -e AUDIT_PROFILES="${PROFILES}" \
    -e AUDIT_PARALLEL="${AUDIT_PARALLEL:-3}" -e AUDIT_DETAIL="${AUDIT_DETAIL:-1}" \
    -e AUDIT_WIDTH="${AUDIT_WIDTH:-1280}" -e AUDIT_HEIGHT="${AUDIT_HEIGHT:-800}" \
    -e SANDBOX_DEMO_PASSWORD="${SANDBOX_DEMO_PASSWORD:-Sandbox@2026!}" \
    -v "${AUDIT_DIR}:/audit:ro" -v "${OUT_DIR}:/out" \
    "${IMAGE}" bash -c 'mkdir -p /tmp/work && cd /audit && cp audit.ts package.json package-lock.json /tmp/work/ \
      && cd /tmp/work && npm ci --omit=dev --no-audit --no-fund --loglevel=error && node audit.ts' || status=$?
  return "${status}"
}

run_local() {
  require_cmd node npm
  local version major minor
  version="$(node -p 'process.versions.node')"
  major="${version%%.*}"
  minor="${version#*.}"; minor="${minor%%.*}"
  # Node 22.18+ executa TypeScript diretamente (remoção de tipos), sem etapa de compilação
  if (( major < 22 )) || (( major == 22 && minor < 18 )); then
    fatal "Node ${version} sem suporte a TypeScript nativo: use Node 22.18+ ou a imagem do Playwright (--docker)"
  fi
  log INFO "auditoria com o Node local ${version} (Playwright ${PW_VERSION})"
  local installed=""
  if [[ -f "${AUDIT_DIR}/node_modules/playwright/package.json" ]]; then
    installed="$(cd "${AUDIT_DIR}" && node -p 'require("./node_modules/playwright/package.json").version')"
  fi
  if [[ "${installed}" != "${PW_VERSION}" ]]; then
    (cd "${AUDIT_DIR}" && npm ci --omit=dev --no-audit --no-fund --loglevel=error) || fatal "npm ci falhou em ${AUDIT_DIR}"
  fi
  # navegador da mesma versão do Playwright (não baixa de novo se já estiver em PLAYWRIGHT_BROWSERS_PATH ou no cache)
  (cd "${AUDIT_DIR}" && npx --no-install playwright install chromium >/dev/null) \
    || fatal "não foi possível instalar o Chromium do Playwright (rede bloqueada? use --docker)"
  local status=0
  (cd "${AUDIT_DIR}" && env APP_URL="${APP_URL}" AUDIT_OUT="${OUT_DIR}" AUDIT_PROFILES="${PROFILES}" node audit.ts) || status=$?
  return "${status}"
}

status=0
case "${MODE}" in
  docker)
    docker_image_available || fatal "imagem ${IMAGE} indisponível (registro bloqueado? use --local)"
    run_docker || status=$?
    ;;
  local)
    run_local || status=$?
    ;;
  auto)
    if docker_image_available; then
      run_docker || status=$?
    else
      log AVISO "imagem ${IMAGE} indisponível; usando o Node local"
      run_local || status=$?
    fi
    ;;
esac

case "${status}" in
  0) log OK "auditoria sem problemas (${OUT_DIR}/relatorio.md)" ;;
  1) log ERRO "auditoria encontrou problemas: veja ${OUT_DIR}/relatorio.md" ;;
  *) log ERRO "a auditoria não terminou (código ${status}): veja a saída acima e ${OUT_DIR}/relatorio.md" ;;
esac
exit "${status}"
