#!/bin/bash
# =============================================================================
# Operações com a conta mestre do RDS, executadas numa instância do ASG via SSM Run
# Command (sem abrir portas). Requer o ambiente aplicado com db_admin_access = true; ao
# final, desligue-o novamente (terraform apply com db_admin_access = false).
#
#   scripts/deploy/db-admin.sh --env <ambiente> bootstrap-roles
#       Primeira implantação: executa scripts/db/bootstrap-roles.sql (idempotente).
#   scripts/deploy/db-admin.sh --env <ambiente> rotate-password <app_owner|app_runtime_login>
#       Aplica no banco a senha atual do cofre para o papel (ver README, rotação).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ENVIRONMENT=""
ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
validate_env "${ENVIRONMENT}"
require_cmd aws jq "${TF_BIN}"

case "${ARGS[0]:-}" in
  bootstrap-roles)
    [[ ${#ARGS[@]} -eq 1 ]] || die "bootstrap-roles não aceita argumentos"
    remote="/usr/local/libexec/gestor-cobranca/db-admin.sh bootstrap-roles"
    ;;
  rotate-password)
    [[ ${#ARGS[@]} -eq 2 && ( "${ARGS[1]}" == app_owner || "${ARGS[1]}" == app_runtime_login ) ]] \
      || die "uso: rotate-password <app_owner|app_runtime_login>"
    remote="/usr/local/libexec/gestor-cobranca/db-admin.sh rotate-password ${ARGS[1]}"
    ;;
  *)
    die "uso: db-admin.sh --env <ambiente> bootstrap-roles | rotate-password <papel>"
    ;;
esac

REGION="$(tf_output "${ENVIRONMENT}" region)"
ASG="$(tf_output "${ENVIRONMENT}" autoscaling_group_name)"
LOG_GROUP="$(tf_output "${ENVIRONMENT}" run_command_log_group_name)"

# No primeiro deploy (bootstrap_mode), a instância está InService mas ainda não está
# saudável no ALB; o health check do ASG é do EC2, então ela aparece como Healthy.
INSTANCE="$(pick_instance "${ASG}" "${REGION}")"
run_on_instance "${REGION}" "${INSTANCE}" "${LOG_GROUP}" 600 \
  "db-admin ${ARGS[*]} ${ENVIRONMENT} por ${USER:-ci}" "${remote}" \
  || die "operação '${ARGS[*]}' falhou (db_admin_access = true aplicado?)"

log INFO "operação '${ARGS[*]}' concluída. Desligue db_admin_access e aplique o Terraform."
