#!/bin/bash
# =============================================================================
# Build e publicação do artefato.
#
#   scripts/deploy/build.sh --env <homologacao|producao> --version <versão> [--skip-tests] [--no-publish]
#
# 1. Frontend Angular (npm ci && npm run build) — se frontend/ existir — copiado para
#    backend/target/classes/static antes do empacotamento: o Spring Boot serve o SPA em /.
# 2. Backend (mvn verify, ou package com --skip-tests) → backend/target/gestor-cobranca.jar
# 3. SHA256SUMS + build-info.json
# 4. Publica em s3://<bucket-artefatos>/artifacts/<versão>/ — versões são IMUTÁVEIS: se a
#    versão já existir no bucket, a publicação é recusada.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ENVIRONMENT=""
VERSION=""
SKIP_TESTS=false
PUBLISH=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --skip-tests) SKIP_TESTS=true; shift ;;
    --no-publish) PUBLISH=false; shift ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done

validate_version "${VERSION}"
[[ "${PUBLISH}" == false ]] || validate_env "${ENVIRONMENT}"
require_cmd mvn java sha256sum jq
[[ "${PUBLISH}" == false ]] || require_cmd aws "${TF_BIN}"

readonly FRONTEND_DIR="${REPO_ROOT}/frontend"
readonly BACKEND_DIR="${REPO_ROOT}/backend"
readonly OUT_DIR="${REPO_ROOT}/backend/target/release/${VERSION}"

java -version 2>&1 | grep -q 'version "21' || die "o build exige JDK 21"

# --- Frontend --------------------------------------------------------------------------
FRONTEND_DIST=""
if [[ -f "${FRONTEND_DIR}/package.json" ]]; then
  require_cmd npm node
  log INFO "build do frontend"
  (cd "${FRONTEND_DIR}" && npm ci --no-audit --no-fund && npm run build -- --configuration production)
  # Angular >= 17 gera dist/<projeto>/browser.
  FRONTEND_DIST="$(find "${FRONTEND_DIR}/dist" -mindepth 1 -maxdepth 3 -type f -name index.html -printf '%h\n' | head -n1)"
  [[ -n "${FRONTEND_DIST}" ]] || die "index.html do frontend não encontrado em frontend/dist"
else
  log AVISO "frontend/ ausente: o artefato conterá somente o backend"
fi

# --- Backend ---------------------------------------------------------------------------
log INFO "build do backend (versão ${VERSION})"
mvn_goal=verify
[[ "${SKIP_TESTS}" == false ]] || mvn_goal="package -DskipTests"
(
  cd "${BACKEND_DIR}"
  # O frontend entra em target/classes/static entre o processamento de recursos e o
  # empacotamento (a segunda execução não limpa target/, então o conteúdo é preservado).
  # A versão do artefato é a do diretório no S3 e do build-info.json; a versão Maven do
  # pom não é alterada.
  mvn -B -ntp clean process-resources
  if [[ -n "${FRONTEND_DIST}" ]]; then
    mkdir -p target/classes/static
    cp -a "${FRONTEND_DIST}/." target/classes/static/
  fi
  # shellcheck disable=SC2086
  mvn -B -ntp ${mvn_goal}
)

readonly JAR="${BACKEND_DIR}/target/gestor-cobranca.jar"
[[ -f "${JAR}" ]] || die "JAR não encontrado: ${JAR}"
if [[ -n "${FRONTEND_DIST}" ]]; then
  unzip -l "${JAR}" | grep -q 'BOOT-INF/classes/static/index.html' || die "o JAR não contém o frontend"
fi

# --- Pacote ----------------------------------------------------------------------------
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"
cp "${JAR}" "${OUT_DIR}/gestor-cobranca.jar"
(cd "${OUT_DIR}" && sha256sum gestor-cobranca.jar > SHA256SUMS)
jq -n \
  --arg version "${VERSION}" \
  --arg commit "$(git -C "${REPO_ROOT}" rev-parse HEAD)" \
  --arg dirty "$(git -C "${REPO_ROOT}" status --porcelain | grep -q . && echo true || echo false)" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg builtBy "${USER:-ci}" \
  --arg sha256 "$(cut -d' ' -f1 "${OUT_DIR}/SHA256SUMS")" \
  '{version: $version, commit: $commit, dirtyTree: ($dirty == "true"), builtAt: $builtAt, builtBy: $builtBy, sha256: $sha256}' \
  > "${OUT_DIR}/build-info.json"
log INFO "pacote em ${OUT_DIR}: $(cat "${OUT_DIR}/SHA256SUMS")"

[[ "${PUBLISH}" == true ]] || exit 0

if [[ "$(jq -r .dirtyTree "${OUT_DIR}/build-info.json")" == true && "${ENVIRONMENT}" == producao ]]; then
  die "árvore de trabalho com alterações não commitadas: publicação em produção recusada"
fi

# --- Publicação ------------------------------------------------------------------------
REGION="$(tf_output "${ENVIRONMENT}" region)"
BUCKET="$(tf_output "${ENVIRONMENT}" artifacts_bucket_name)"
PREFIX="$(tf_output "${ENVIRONMENT}" artifact_prefix)"
DEST="s3://${BUCKET}/${PREFIX}/${VERSION}"

if aws s3api head-object --region "${REGION}" --bucket "${BUCKET}" --key "${PREFIX}/${VERSION}/SHA256SUMS" >/dev/null 2>&1; then
  die "a versão ${VERSION} já foi publicada em ${DEST}; versões são imutáveis — use uma nova versão"
fi

log INFO "publicando em ${DEST}"
# O JAR e o build-info primeiro; SHA256SUMS por último marca a versão como completa.
aws s3 cp --region "${REGION}" --only-show-errors "${OUT_DIR}/gestor-cobranca.jar" "${DEST}/gestor-cobranca.jar"
aws s3 cp --region "${REGION}" --only-show-errors "${OUT_DIR}/build-info.json" "${DEST}/build-info.json"
aws s3 cp --region "${REGION}" --only-show-errors "${OUT_DIR}/SHA256SUMS" "${DEST}/SHA256SUMS"
log INFO "versão ${VERSION} publicada"
