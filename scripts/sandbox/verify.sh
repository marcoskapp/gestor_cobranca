#!/bin/bash
# =============================================================================
# Verificação de fumaça do sandbox no ar (pela API, como um cliente):
#   1. login de todos os usuários de demonstração (um por perfil em cada assessoria) e do administrador da plataforma;
#   2. 2 assessorias × 3 credores × 4 carteiras, com casos, acordos e pagamentos;
#   3. isolamento entre assessorias: o usuário de uma assessoria não enxerga credores, carteiras, casos, devedores
#      nem usuários da outra (listagens e consulta direta por id → 404);
#   4. escopo por credor: o segundo operador só enxerga o primeiro credor.
#
#   ./scripts/sandbox/verify.sh
#
# Requer curl e jq. Sai com código diferente de zero na primeira divergência.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_cmd curl jq
readonly PASSWORD="${SANDBOX_DEMO_PASSWORD:-Sandbox@2026!}"
readonly PEOPLE=(admin gestor supervisor operador operador2 financeiro auditor juridico advogado analista estrategia
                 qualidade compliance integracao)
FAILURES=0

fail() { log FALHA "$1"; FAILURES=$((FAILURES + 1)); }
ok() { log OK "$1"; }

# login <assessoria> <e-mail> → resposta do login (stdout). O login tem limite por IP (app.security.rate-limit,
# 20/min): ao receber 429 o script espera a janela e tenta de novo, sem afrouxar o limite.
login() {
  local body out code attempt
  body="$(jq -nc --arg t "$1" --arg e "$2" --arg p "${PASSWORD}" '{tenant: $t, email: $e, password: $p}')"
  for attempt in 1 2 3 4 5 6 7 8; do
    out="$(curl -sS --max-time 20 -w '\n%{http_code}' -H 'Content-Type: application/json' -d "${body}" \
      "${APP_URL}/api/v1/auth/login")"
    code="${out##*$'\n'}"
    if [[ "${code}" != 429 ]]; then
      printf '%s' "${out%$'\n'*}"
      return 0
    fi
    log INFO "limite de logins por minuto atingido; aguardando a janela (tentativa ${attempt})"
    sleep 15
  done
  return 1
}

# token <assessoria> <pessoa> → access token já obtido (vazio se o login falhou)
token() { printf '%s' "${TOKENS[$1:$2]:-}"; }

# get <token> <caminho> → corpo (stdout); código HTTP em HTTP_STATUS
get() {
  local out
  out="$(curl -sS --max-time 30 -w '\n%{http_code}' -H "Authorization: Bearer $1" "${APP_URL}$2")"
  HTTP_STATUS="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}"
}

[[ "$(readiness_json)" == *'"status":"UP"}' ]] || die "o sandbox não está pronto em ${APP_URL} (rode ./scripts/sandbox/up.sh)"

declare -A TOKENS=()
log INFO "1. login de cada perfil em cada assessoria"
for tenant in aurora boreal; do
  for person in "${PEOPLE[@]}"; do
    email="${person}@${tenant}.exemplo.test"
    response="$(login "${tenant}" "${email}" || true)"
    status="$(jq -r '.status // empty' <<<"${response}" 2>/dev/null || true)"
    if [[ "${status}" == "AUTHENTICATED" ]]; then
      TOKENS["${tenant}:${person}"]="$(jq -r '.accessToken' <<<"${response}")"
      roles="$(jq -r '.user.roles | join(",")' <<<"${response}")"
      ok "${tenant}/${person} autenticado (perfis: ${roles})"
    else
      fail "${tenant}/${person}: login não autenticou (${status:-sem resposta})"
    fi
  done
done
platform="$(login plataforma admin@plataforma.exemplo.test || true)"
if [[ "$(jq -r '.status // empty' <<<"${platform}" 2>/dev/null)" == "AUTHENTICATED" ]]; then
  ptoken="$(jq -r '.accessToken' <<<"${platform}")"
  tenants="$(get "${ptoken}" /api/v1/platform/tenants | jq -r '[.[] | select(.platform | not) | .slug] | sort | join(",")')"
  [[ "${tenants}" == *aurora*boreal* ]] && ok "plataforma autenticada; assessorias: ${tenants}" \
    || fail "plataforma não lista aurora e boreal (${tenants})"
else
  fail "administrador da plataforma: login não autenticou"
fi

log INFO "2. volumes por assessoria"
declare -A CREDITOR_IDS=() PORTFOLIO_IDS=() CASE_IDS=() DEBTOR_IDS=() USER_IDS=()
for tenant in aurora boreal; do
  token="$(token "${tenant}" gestor)"
  [[ -n "${token}" ]] || { fail "${tenant}: sem token do gestor"; continue; }
  creditors="$(get "${token}" '/api/v1/creditors?size=50')"
  ncred="$(jq '.totalElements' <<<"${creditors}")"
  CREDITOR_IDS[${tenant}]="$(jq -r '[.content[].id] | join(" ")' <<<"${creditors}")"
  portfolios="$(get "${token}" '/api/v1/portfolios?size=50')"
  nport="$(jq '.totalElements' <<<"${portfolios}")"
  PORTFOLIO_IDS[${tenant}]="$(jq -r '.content[0].id' <<<"${portfolios}")"
  per_creditor="$(jq -r '[.content | group_by(.creditorId)[] | length] | map(tostring) | join(",")' <<<"${portfolios}")"
  cases="$(get "${token}" '/api/v1/collection/cases?size=1')"
  ncases="$(jq '.totalElements' <<<"${cases}")"
  CASE_IDS[${tenant}]="$(jq -r '.content[0].id' <<<"${cases}")"
  DEBTOR_IDS[${tenant}]="$(jq -r '.content[0].debtorId' <<<"${cases}")"
  agreements="$(get "${token}" '/api/v1/negotiation/agreements?size=50' | jq -r '[.content[].status] | group_by(.) | map("\(.[0])=\(length)") | join(" ")')"
  fin="$(token "${tenant}" financeiro)"
  payments="$(get "${fin}" '/api/v1/payment/payments?size=50' | jq -r '[.content[].status] | group_by(.) | map("\(.[0])=\(length)") | join(" ")')"
  users="$(get "$(token "${tenant}" admin)" '/api/v1/users?size=50')"
  USER_IDS[${tenant}]="$(jq -r '.content[0].id' <<<"${users}")"
  active="$(jq '[.content[] | select(.status == "ACTIVE")] | length' <<<"${users}")"
  matters="$(get "$(token "${tenant}" juridico)" '/api/v1/legal/matters?size=20' | jq -r '[.content[].stage] | join(",")')"
  if [[ "${ncred}" == 3 && "${nport}" == 12 && "${per_creditor}" == "4,4,4" && "${ncases}" -gt 0 ]]; then
    ok "${tenant}: ${ncred} credores × ${per_creditor} carteiras = ${nport}; ${ncases} casos; ${active} usuários ativos"
  else
    fail "${tenant}: esperado 3 credores × 4 carteiras; obtido ${ncred} credores, ${nport} carteiras (${per_creditor}), ${ncases} casos"
  fi
  log INFO "${tenant}: acordos [${agreements}] · pagamentos [${payments}] · jurídico [${matters}]"
done

log INFO "3. isolamento entre assessorias"
check_isolation() {
  local from="$1" other="$2" token="$(token "$1" gestor)" admin="$(token "$1" admin)" id
  for id in ${CREDITOR_IDS[${other}]}; do
    get "${token}" "/api/v1/creditors/${id}" >/dev/null
    [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou credor de ${other} (HTTP ${HTTP_STATUS})"
  done
  get "${token}" "/api/v1/portfolios/${PORTFOLIO_IDS[${other}]}" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou carteira de ${other} (HTTP ${HTTP_STATUS})"
  get "${token}" "/api/v1/collection/cases/${CASE_IDS[${other}]}" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou caso de ${other} (HTTP ${HTTP_STATUS})"
  get "${token}" "/api/v1/debtors/${DEBTOR_IDS[${other}]}" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou devedor de ${other} (HTTP ${HTTP_STATUS})"
  get "${admin}" "/api/v1/users/${USER_IDS[${other}]}" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou usuário de ${other} (HTTP ${HTTP_STATUS})"
  local seen
  seen="$(get "${token}" '/api/v1/creditors?size=50' | jq -r '[.content[].id] | join(" ")')"
  for id in ${CREDITOR_IDS[${other}]}; do
    [[ " ${seen} " != *" ${id} "* ]] || fail "${from} lista credor de ${other}"
  done
  ok "${from} não enxerga credores, carteira, caso, devedor nem usuário de ${other}"
}
check_isolation aurora boreal
check_isolation boreal aurora

log INFO "4. escopo por credor (segundo operador, só o primeiro credor)"
for tenant in aurora boreal; do
  n="$(get "$(token "${tenant}" operador2)" '/api/v1/creditors?size=50' | jq '.totalElements')"
  [[ "${n}" == 1 ]] && ok "${tenant}/operador2 enxerga ${n} credor" || fail "${tenant}/operador2 enxerga ${n} credores (esperado 1)"
done

if (( FAILURES > 0 )); then
  die "${FAILURES} verificação(ões) falharam"
fi
log INFO "todas as verificações passaram"
