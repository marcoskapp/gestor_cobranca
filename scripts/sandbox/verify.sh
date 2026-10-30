#!/bin/bash
# =============================================================================
# Verificação de fumaça do sandbox no ar (pela API, como um cliente):
#   1. login de todos os usuários de demonstração (um por perfil em cada assessoria) e do administrador da plataforma;
#   2. 2 assessorias × 3 credores × 4 carteiras, com casos, acordos e pagamentos;
#   3. isolamento entre assessorias: o usuário de uma assessoria não enxerga credores, carteiras, casos, devedores
#      nem usuários da outra (listagens e consulta direta por id → 404);
#   4. escopo por credor: o segundo operador só enxerga o primeiro credor;
#   5. API pública com os tokens de demonstração: leitura, importação de um lote fixo (Idempotency-Key), 401 sem
#      token/token inválido, 403/404 em carteira de outro credor e de outra assessoria, 403 do token só de leitura
#      em escrita, páginas públicas da documentação e consumo aparecendo na tela interna (gravado a cada minuto).
#   6. Swagger e /v3/api-docs de todos os grupos abertos sem login (só no sandbox) e a API interna ainda protegida.
#   7. posição do credor: repasse e faturamento da API pelas telas internas (financeiro) e pela API pública (token
#      completo: 200; token só de leitura, sem billing:read/settlement:read: 403).
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
token() { kv_get "token:$1:$2"; }

# get <token> <caminho> → corpo (stdout); código HTTP em HTTP_STATUS
get() {
  local out
  out="$(curl -sS --max-time 30 -w '\n%{http_code}' -H "Authorization: Bearer $1" "${APP_URL}$2")"
  HTTP_STATUS="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}"
}

[[ "$(readiness_json)" == *'"status":"UP"}' ]] || die "o sandbox não está pronto em ${APP_URL} (rode ./scripts/sandbox/up.sh)"

log INFO "1. login de cada perfil em cada assessoria"
for tenant in aurora boreal; do
  for person in "${PEOPLE[@]}"; do
    email="${person}@${tenant}.exemplo.test"
    response="$(login "${tenant}" "${email}" || true)"
    status="$(jq -r '.status // empty' <<<"${response}" 2>/dev/null || true)"
    if [[ "${status}" == "AUTHENTICATED" ]]; then
      kv_set "token:${tenant}:${person}" "$(jq -r '.accessToken' <<<"${response}")"
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
for tenant in aurora boreal; do
  token="$(token "${tenant}" gestor)"
  [[ -n "${token}" ]] || { fail "${tenant}: sem token do gestor"; continue; }
  creditors="$(get "${token}" '/api/v1/creditors?size=50')"
  ncred="$(jq '.totalElements' <<<"${creditors}")"
  kv_set "creditor_ids:${tenant}" "$(jq -r '[.content[].id] | join(" ")' <<<"${creditors}")"
  portfolios="$(get "${token}" '/api/v1/portfolios?size=50')"
  nport="$(jq '.totalElements' <<<"${portfolios}")"
  kv_set "portfolio_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${portfolios}")"
  per_creditor="$(jq -r '[.content | group_by(.creditorId)[] | length] | map(tostring) | join(",")' <<<"${portfolios}")"
  cases="$(get "${token}" '/api/v1/collection/cases?size=1')"
  ncases="$(jq '.totalElements' <<<"${cases}")"
  kv_set "case_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${cases}")"
  kv_set "debtor_ids:${tenant}" "$(jq -r '.content[0].debtorId' <<<"${cases}")"
  agreements="$(get "${token}" '/api/v1/negotiation/agreements?size=50' | jq -r '[.content[].status] | group_by(.) | map("\(.[0])=\(length)") | join(" ")')"
  fin="$(token "${tenant}" financeiro)"
  payments="$(get "${fin}" '/api/v1/payment/payments?size=50' | jq -r '[.content[].status] | group_by(.) | map("\(.[0])=\(length)") | join(" ")')"
  users="$(get "$(token "${tenant}" admin)" '/api/v1/users?size=50')"
  kv_set "user_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${users}")"
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
  for id in $(kv_get "creditor_ids:${other}"); do
    get "${token}" "/api/v1/creditors/${id}" >/dev/null
    [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou credor de ${other} (HTTP ${HTTP_STATUS})"
  done
  get "${token}" "/api/v1/portfolios/$(kv_get "portfolio_ids:${other}")" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou carteira de ${other} (HTTP ${HTTP_STATUS})"
  get "${token}" "/api/v1/collection/cases/$(kv_get "case_ids:${other}")" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou caso de ${other} (HTTP ${HTTP_STATUS})"
  get "${token}" "/api/v1/debtors/$(kv_get "debtor_ids:${other}")" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou devedor de ${other} (HTTP ${HTTP_STATUS})"
  get "${admin}" "/api/v1/users/$(kv_get "user_ids:${other}")" >/dev/null
  [[ "${HTTP_STATUS}" == 404 ]] || fail "${from} consultou usuário de ${other} (HTTP ${HTTP_STATUS})"
  local seen
  seen="$(get "${token}" '/api/v1/creditors?size=50' | jq -r '[.content[].id] | join(" ")')"
  for id in $(kv_get "creditor_ids:${other}"); do
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

log INFO "5. API pública (tokens de demonstração; ./scripts/sandbox/tokens.sh)"
# papi <método> <caminho> <token|-> [corpo JSON] [Idempotency-Key] → corpo (stdout); código HTTP em HTTP_STATUS
papi() {
  local args=(-sS --max-time 30 -w '\n%{http_code}' -X "$1") out
  [[ "$3" == - ]] || args+=(-H "Authorization: Bearer $3")
  if [[ -n "${4:-}" ]]; then args+=(-H 'Content-Type: application/json' -d "$4"); fi
  if [[ -n "${5:-}" ]]; then args+=(-H "Idempotency-Key: $5"); fi
  out="$(curl "${args[@]}" "${APP_URL}/public-api/v1$2")"
  HTTP_STATUS="${out##*$'\n'}"
  printf '%s' "${out%$'\n'*}"
}
expect() {  # expect <descrição> <códigos aceitos, ex. "200|202">
  if [[ "|$2|" == *"|${HTTP_STATUS}|"* ]]; then ok "$1 → HTTP ${HTTP_STATUS}"; else fail "$1 → HTTP ${HTTP_STATUS} (esperado $2)"; fi
}
token_line() { awk -F'\t' -v t="$1" -v n="$2" '$2 == t && $4 == n {print $6}' <<<"${API_TOKENS}"; }

API_TOKENS="$(sandbox_tokens || true)"
ntokens="$(grep -c . <<<"${API_TOKENS}" || true)"
[[ "${ntokens}" == 8 ]] && ok "8 tokens de demonstração (1 completo por credor + 1 de leitura no 1º credor, por assessoria)" \
  || fail "tokens de demonstração: ${ntokens} (esperado 8)"
FULL="$(token_line aurora sandbox-hzn-completo)"
READ="$(token_line aurora sandbox-hzn-leitura)"
OTHER_CREDITOR="$(token_line aurora sandbox-est-completo)"
OTHER_TENANT="$(token_line boreal sandbox-csr-completo)"

gestor="$(token aurora gestor)"
hzn="$(get "${gestor}" '/api/v1/creditors?size=50' | jq -r '.content[] | select(.externalCode == "HZN") | .id')"
portfolios="$(get "${gestor}" '/api/v1/portfolios?size=50')"
HZN_PORTFOLIO="$(jq -r '.content[] | select(.code == "HZN-CARTAO") | .id' <<<"${portfolios}")"
EST_PORTFOLIO="$(jq -r '.content[] | select(.code == "EST-CREDIARIO") | .id' <<<"${portfolios}")"
BOREAL_PORTFOLIO="$(get "$(token boreal gestor)" '/api/v1/portfolios?size=50' | jq -r '.content[] | select(.code == "CSR-CARTAO") | .id')"

papi GET /portfolios "${FULL}" >/dev/null; expect "listar carteiras com o token do credor" 200
papi GET "/portfolios/${HZN_PORTFOLIO}" "${FULL}" >/dev/null; expect "consultar carteira do próprio credor" 200
papi GET '/titles?limit=5' "${FULL}" >/dev/null; expect "listar títulos" 200
# lote pequeno e fixo: a mesma chave de idempotência e o mesmo conteúdo devolvem o mesmo lote a cada execução
batch_body="$(jq -nc --arg p "${HZN_PORTFOLIO}" '{portfolioId: $p, mode: "IMPORT", records: [
  {documento: "52998224725", nome: "Cliente Demonstração API (fictício)", contrato: "API-DEMO-C1",
   id_titulo: "API-DEMO-0001", parcela: 1, vencimento: "2026-01-15", valor_principal: 350.75},
  {documento: "52998224725", nome: "Cliente Demonstração API (fictício)", contrato: "API-DEMO-C1",
   id_titulo: "API-DEMO-0002", parcela: 2, vencimento: "2026-02-15", valor_principal: 420.00}]}')"
batch="$(papi POST /imports "${FULL}" "${batch_body}" sandbox-verify-api-demo-v1)"
expect "importar lote JSON com Idempotency-Key" "200|201|202"
batch_id="$(jq -r '.id // empty' <<<"${batch}" 2>/dev/null || true)"
if [[ -n "${batch_id}" ]]; then
  status=""
  for _ in $(seq 1 30); do
    status="$(papi GET "/imports/${batch_id}" "${FULL}" | jq -r '.status')"
    [[ "${status}" == COMPLETED* || "${status}" == FAILED || "${status}" == CANCELLED ]] && break
    sleep 2
  done
  [[ "${status}" == COMPLETED ]] && ok "consultar o lote → ${status}" || fail "lote importado pela API terminou em ${status}"
fi
papi GET /portfolios - >/dev/null; expect "sem token" 401
papi GET /portfolios "gck_invalido00000.segredo-invalido-0000000000000000000000000" >/dev/null; expect "token inválido" 401
papi GET "/portfolios/${EST_PORTFOLIO}" "${FULL}" >/dev/null; expect "carteira de outro credor da mesma assessoria" "403|404"
papi GET "/portfolios/${BOREAL_PORTFOLIO}" "${FULL}" >/dev/null; expect "carteira de outra assessoria" "403|404"
papi POST /imports "${OTHER_CREDITOR}" "${batch_body}" sandbox-verify-api-demo-other >/dev/null
expect "token de outro credor importando na carteira do 1º credor" "403|404"
papi POST /imports "${OTHER_TENANT}" "${batch_body}" sandbox-verify-api-demo-tenant >/dev/null
expect "token de outra assessoria importando na carteira" "403|404"
papi GET /portfolios "${READ}" >/dev/null; expect "token só de leitura lendo carteiras" 200
papi POST /imports "${READ}" "${batch_body}" sandbox-verify-api-demo-read >/dev/null; expect "token só de leitura num endpoint de escrita" 403
for path in /docs /docs/changelog /versions /changelog /v1/openapi.json; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' "${APP_URL}/public-api${path}")"
  [[ "${code}" == 200 ]] && ok "público sem token: /public-api${path}" || fail "/public-api${path} → HTTP ${code}"
done
# consumo gravado em lote a cada minuto: a tela interna de consumo (gestor) passa a mostrar as chamadas
today="$(TZ=America/Sao_Paulo date +%F)"
requests=0
for _ in $(seq 1 18); do
  requests="$(get "${gestor}" "/api/v1/public-api/usage?from=${today}&to=${today}&creditorId=${hzn}" \
    | jq -r '.totals.requests // 0')"
  (( requests > 0 )) && break
  sleep 10
done
(( requests > 0 )) && ok "consumo do 1º credor na tela interna: ${requests} requisições hoje" \
  || fail "consumo da API pública não apareceu em /api/v1/public-api/usage em 3 min"

log INFO "6. documentação da API interna aberta no navegador (só no sandbox, decisão 643)"
for path in /swagger-ui/index.html /v3/api-docs/swagger-config /v3/api-docs/interna /v3/api-docs/public-v1; do
  code="$(curl -sS --max-time 60 -o /dev/null -w '%{http_code}' "${APP_URL}${path}")"
  [[ "${code}" == 200 ]] && ok "${path} sem login: 200" || fail "${path} sem login: esperado 200, veio ${code}"
done
code="$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' "${APP_URL}/api/v1/me")"
[[ "${code}" == 401 ]] && ok "a API interna continua exigindo login (/api/v1/me: 401)" \
  || fail "/api/v1/me sem login: esperado 401, veio ${code}"

log INFO "7. posição do credor (repasse e faturamento da API, calculados agora)"
financeiro="$(token aurora financeiro)"
TMP_BODY="$(mktemp "${TMPDIR:-/tmp}/gc-verify.XXXXXX")"
trap 'rm -f "${TMP_BODY}"' EXIT
# has_fields <descrição> <filtro jq que precisa dar true> ← corpo em stdin; código HTTP em HTTP_STATUS
has_fields() {
  if [[ "${HTTP_STATUS}" == 200 ]] && jq -e "$2" >/dev/null 2>&1; then ok "$1 → HTTP 200 com os campos principais"
  else fail "$1 → HTTP ${HTTP_STATUS} ou campos principais ausentes"; fi
}
get "${financeiro}" "/api/v1/settlement/position?creditorId=${hzn}" > "${TMP_BODY}"
has_fields "posição do repasse (financeiro, tela interna)" \
  'has("periodStart") and has("totals") and has("direction") and has("calculatedAt")' < "${TMP_BODY}"
get "${financeiro}" "/api/v1/public-api/position?creditorId=${hzn}" > "${TMP_BODY}"
has_fields "posição do faturamento da API (financeiro, tela interna)" \
  'has("periodStart") and has("periodEnd") and has("amountSoFar") and has("currency")' < "${TMP_BODY}"
papi GET /settlement/position "${FULL}" > "${TMP_BODY}"
has_fields "API pública: posição do repasse com o token completo" 'has("periodStart") and has("totals")' < "${TMP_BODY}"
papi GET /billing/position "${FULL}" > "${TMP_BODY}"
has_fields "API pública: posição do faturamento com o token completo" 'has("periodStart") and has("amountSoFar")' < "${TMP_BODY}"
papi GET /settlement/position "${READ}" >/dev/null; expect "token só de leitura (sem settlement:read) na posição do repasse" 403
papi GET /billing/position "${READ}" >/dev/null; expect "token só de leitura (sem billing:read) na posição do faturamento" 403

if (( FAILURES > 0 )); then
  die "${FAILURES} verificação(ões) falharam"
fi
log INFO "todas as verificações passaram"
