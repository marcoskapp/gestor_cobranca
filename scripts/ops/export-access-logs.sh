#!/bin/bash
# =============================================================================
# Exportação mensal do log de acesso (CloudWatch → S3) para o arquivo de 5 anos.
#
#   scripts/ops/export-access-logs.sh --env <ambiente> [--month AAAA-MM]
#
# Sem --month, exporta o mês anterior (UTC). Destino:
#   s3://<bucket-de-logs>/cloudwatch-export/access/<AAAA-MM>/...
# O lifecycle do bucket move o arquivo para Glacier após 30 dias e o expira após 5 anos.
# Agendar no pipeline (ex.: dia 2 de cada mês); não há serviço de agendamento autorizado
# na AWS para isso (EventBridge/Lambda não constam da lista). A exportação é idempotente
# por mês: uma exportação já concluída para o mesmo prefixo é recusada.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=../deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/../deploy/lib.sh"

ENVIRONMENT=""
MONTH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --month) MONTH="$2"; shift 2 ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
validate_env "${ENVIRONMENT}"
require_cmd aws jq date "${TF_BIN}"

[[ -n "${MONTH}" ]] || MONTH="$(date -u -d "$(date -u +%Y-%m-01) -1 month" +%Y-%m)"
[[ "${MONTH}" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]] || die "--month deve ser AAAA-MM"

REGION="$(tf_output "${ENVIRONMENT}" region)"
BUCKET="$(tf_output "${ENVIRONMENT}" logs_bucket_name)"
# Mapas são devolvidos por tf_output como JSON.
LOG_GROUP="$(jq -r '.access' <<<"$(tf_output "${ENVIRONMENT}" log_group_names)")"
[[ "${LOG_GROUP}" == /gestor-cobranca/* ]] || die "grupo de log de acesso não encontrado"

from_ms=$(( $(date -u -d "${MONTH}-01 00:00:00" +%s) * 1000 ))
to_ms=$(( $(date -u -d "${MONTH}-01 00:00:00 +1 month" +%s) * 1000 - 1 ))
prefix="cloudwatch-export/access/${MONTH}"

existing="$(aws s3api list-objects-v2 --region "${REGION}" --bucket "${BUCKET}" --prefix "${prefix}/" \
  --max-keys 1 --query 'KeyCount' --output text)"
if [[ "${existing}" != "0" ]]; then
  die "já existe exportação em s3://${BUCKET}/${prefix}/"
fi

task_id="$(aws logs create-export-task --region "${REGION}" \
  --task-name "access-${ENVIRONMENT}-${MONTH}" \
  --log-group-name "${LOG_GROUP}" \
  --from "${from_ms}" --to "${to_ms}" \
  --destination "${BUCKET}" --destination-prefix "${prefix}" \
  --query taskId --output text)"
log INFO "exportação ${task_id} de ${LOG_GROUP} (${MONTH}) para s3://${BUCKET}/${prefix}/"

while :; do
  status="$(aws logs describe-export-tasks --region "${REGION}" --task-id "${task_id}" \
    --query 'exportTasks[0].status.code' --output text)"
  case "${status}" in
    COMPLETED) log INFO "exportação concluída"; exit 0 ;;
    CANCELLED|FAILED) die "exportação ${task_id} terminou com ${status}" ;;
    *) sleep 30 ;;
  esac
done
