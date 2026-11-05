#!/bin/bash
# =============================================================================
# Marca uma vez, com a tag retention-class=import, os arquivos de importação gravados antes
# da tag existir (LGPD — docs/tecnico/desenho/importacao.md, "Retenção").
#
#   scripts/ops/tag-import-objects.sh --env <ambiente> [--dry-run]
#
# Percorre TODAS as versões dos objetos sob tenants/<tenant>/imports/ do bucket da aplicação,
# inclusive as versões não correntes (arquivos já descartados pela aplicação, que hoje ficam
# guardados por app_noncurrent_expiration_days). Com a tag, a regra de lifecycle
# imports-noncurrent-expiration expira a versão anterior ao delete marker em 1 dia.
# Idempotente: repetir só regrava a mesma tag. Não lê nem baixa o conteúdo dos arquivos e não
# imprime nomes de arquivo (podem conter dado pessoal): só contagens.
# Exige credenciais de operador com s3:ListBucketVersions e s3:PutObjectVersionTagging no
# bucket da aplicação (o papel das instâncias não tem essas permissões).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=../deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/../deploy/lib.sh"

ENVIRONMENT=""
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
validate_env "${ENVIRONMENT}"
require_cmd aws jq "${TF_BIN}"

REGION="$(tf_output "${ENVIRONMENT}" region)"
BUCKET="$(tf_output "${ENVIRONMENT}" app_bucket_name)"

tagged=0
failed=0
# A CLI pagina sozinha (list-object-versions devolve todas as páginas).
while IFS=$'\t' read -r key version; do
  [[ -n "${key}" ]] || continue
  if [[ "${DRY_RUN}" == true ]]; then
    tagged=$((tagged + 1))
    continue
  fi
  if aws s3api put-object-tagging --region "${REGION}" --bucket "${BUCKET}" --key "${key}" \
      --version-id "${version}" --tagging 'TagSet=[{Key=retention-class,Value=import}]' >/dev/null; then
    tagged=$((tagged + 1))
  else
    failed=$((failed + 1))
  fi
done < <(aws s3api list-object-versions --region "${REGION}" --bucket "${BUCKET}" --prefix "tenants/" --output json \
  | jq -r '(.Versions // [])[] | select(.Key | test("^tenants/[^/]+/imports/")) | [.Key, .VersionId] | @tsv')

if [[ "${DRY_RUN}" == true ]]; then
  log INFO "simulação: ${tagged} versões de arquivos de importação seriam marcadas em ${ENVIRONMENT}"
else
  log INFO "${tagged} versões de arquivos de importação marcadas com retention-class=import em ${ENVIRONMENT}"
fi
[[ "${failed}" -eq 0 ]] || die "${failed} versões não puderam ser marcadas (repita o script)"
