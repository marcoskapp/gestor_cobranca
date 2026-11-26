#!/bin/bash
# =============================================================================
# Código do autenticador (TOTP) de uma pessoa do credor de demonstração do sandbox (dados FICTÍCIOS), para entrar no
# portal do credor (/credor/<assessoria>/entrar) ou confirmar uma ação (aceite, pagamento informado).
#
#   ./scripts/sandbox/credor-codigo.sh <assessoria> <pessoa>         código atual e segundos restantes
#   ./scripts/sandbox/credor-codigo.sh <assessoria> <pessoa> --uri   endereço otpauth:// para cadastrar no celular
#
#   ex.: ./scripts/sandbox/credor-codigo.sh aurora hzn-financeiro
#
# Pessoas: hzn-gestao, hzn-financeiro, hzn-juridico, hzn-compliance, hzn-consulta e est-consulta (aurora);
# csr-gestao, csr-financeiro, csr-juridico, csr-compliance e csr-consulta (boreal). E-mail: <pessoa>@credor.exemplo.test.
#
# O segredo é o que a própria ativação gerou (a carga leu o convite no Mailpit e ativou pelo portal); fica no volume do
# sandbox (/sandbox-data/creditor-mfa.txt, permissão 600) e é lido de dentro do contêiner. O código é calculado aqui
# (RFC 6238 com o OpenSSL) e nunca é gravado. Cada código vale UMA vez por pessoa: repetir conta como erro e 5 erros
# bloqueiam a pessoa (desbloqueio: ficha do credor → Portal do credor → "Refazer acesso", como gestor da assessoria).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() { sed -n '2,18p' "$0"; }

TENANT="${1:-}"
PERSON="${2:-}"
MODE="${3:-}"
case "${TENANT}" in -h|--help|"") usage; exit 0 ;; esac
[[ "${TENANT}" =~ ^[a-z0-9-]+$ ]] || die "assessoria inválida (use letras minúsculas, números e hífen; ex.: aurora)"
[[ "${PERSON}" =~ ^[a-z0-9-]+$ ]] || die "pessoa inválida (use letras minúsculas, números e hífen; ex.: hzn-financeiro)"
[[ -z "${MODE}" || "${MODE}" == --uri ]] || die "opção desconhecida: ${MODE} (veja --help)"
(( $# <= 3 )) || die "argumentos demais (veja --help)"

require_docker
require_cmd openssl xxd awk
line="$(sandbox_creditor_mfa | awk -F'\t' -v t="${TENANT}" -v e="${PERSON}@credor.exemplo.test" \
  '$1 == t && $2 == e {print; exit}')" || true
[[ -n "${line}" ]] || die "pessoa ${TENANT}/${PERSON} não encontrada no cofre (o sandbox está no ar e a carga terminou?)"
IFS=$'\t' read -r _tenant email role secret hex <<<"${line}"
[[ "${hex}" =~ ^[0-9a-f]{20,}$ && "${secret}" =~ ^[A-Z2-7]+$ ]] || die "entrada do cofre inválida para ${TENANT}/${PERSON}"

if [[ "${MODE}" == --uri ]]; then
  # pedido explícito: o otpauth:// carrega o segredo; mostre só para cadastrar no celular de quem demonstra
  printf '\nCadastre no aplicativo autenticador (ou gere um QR com este endereço). Não compartilhe:\n\n'
  printf '  otpauth://totp/Gestor%%20de%%20Cobranca%%20-%%20Credor:%s?secret=%s&issuer=Gestor%%20de%%20Cobranca%%20-%%20Credor&algorithm=SHA1&digits=6&period=30\n\n' \
    "${email}" "${secret}"
  exit 0
fi

left="$(totp_seconds_left)"
if (( left < 5 )); then
  log INFO "faltam ${left} s para o código atual vencer; aguardando o próximo"
  sleep "$(( left + 1 ))"
fi
step="$(totp_step)"
code="$(totp_code "${hex}" "${step}")"
left="$(totp_seconds_left)"
printf '\n  %s (%s): %s   — vale por mais %s s\n\n' "${TENANT}/${PERSON}" "${role}" "${code}" "${left}"
printf '  Use uma vez só: o mesmo código de novo conta como erro (5 erros bloqueiam a pessoa).\n'
printf '  Para a próxima entrada ou ação da mesma pessoa, rode de novo depois que este vencer.\n\n'
