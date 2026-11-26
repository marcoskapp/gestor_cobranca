#!/bin/bash
# =============================================================================
# Verificação de fumaça do sandbox no ar (pela API, como um cliente):
#   1. login de todos os usuários de demonstração (um por perfil em cada assessoria) e do administrador da plataforma;
#   2. aurora com 30 credores e 80 carteiras (os 3 do catálogo principal com 4 carteiras cada) e boreal com 3 × 4,
#      com casos, acordos e pagamentos; na aurora, o histórico de 12 meses (acordos cumpridos e quebrados, pagamentos,
#      negociadores, classificação publicada, margem apurada mês a mês e faturas de canal);
#   3. isolamento entre assessorias: o usuário de uma assessoria não enxerga credores, carteiras, casos, devedores
#      nem usuários da outra (listagens e consulta direta por id → 404);
#   4. escopo por credor: o segundo operador só enxerga o primeiro credor;
#   5. API pública com os tokens de demonstração: leitura, importação de um lote fixo (Idempotency-Key), 401 sem
#      token/token inválido, 403/404 em carteira de outro credor e de outra assessoria, 403 do token só de leitura
#      em escrita, páginas públicas da documentação e consumo aparecendo na tela interna (gravado a cada minuto).
#   6. Swagger e /v3/api-docs de todos os grupos abertos sem login (só no sandbox) e a API interna ainda protegida.
#   7. posição do credor: repasse e faturamento da API pelas telas internas (financeiro) e pela API pública (token
#      completo: 200; token só de leitura, sem billing:read/settlement:read: 403).
#   8. Importação PDF sem IA: PDFs fictícios de demonstração gerados pela carga; o PDF de verificação enviado pela
#      API pública (token completo, Idempotency-Key fixa) é mapeado pelo mapa salvo do credor (TEMPLATE), fica
#      aguardando validação humana e os totais do documento batem.
#   9. menu x API: para cada perfil, as páginas do menu que o perfil vê não recebem 403 na chamada de carregamento.
#  10. portal do credor (fatia 8): pessoas ativas, sem bloqueio, falhas nem convite pendente; login sempre pede o código
#      (MFA_REQUIRED sem token); Início com números no 1º credor e os próprios no 2º; isolamento (demonstrativo,
#      arquivo, contestação e pagamento de outro credor e de outra assessoria → 404 igual ao de id inexistente);
#      fronteira dos tokens; time só com a permissão; período fora da lista → 400; no-store nas leituras. Só leituras
#      (GET) e as recusas esperadas; cada pessoa usa um código num passo novo e a sessão é encerrada ao fim. Nenhum
#      segredo, código, token, cookie ou link é impresso ou gravado (cofre lido para um temporário 600, apagado na saída).
#  11. Mailpit fora do túnel: porta só em 127.0.0.1 e o túnel aponta só para a aplicação; com o túnel no ar, as rotas
#      do Mailpit no endereço público não chegam ao Mailpit.
#
#   ./scripts/sandbox/verify.sh
#
# Requer curl e jq. Sai com código diferente de zero na primeira divergência.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_cmd curl jq openssl xxd
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
      kv_set "perms:${tenant}:${person}" "$(jq -r '.user.permissions | join(",")' <<<"${response}")"
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
  creditors="$(get "${token}" '/api/v1/creditors?size=200')"
  ncred="$(jq '.totalElements' <<<"${creditors}")"
  kv_set "creditor_ids:${tenant}" "$(jq -r '[.content[].id] | join(" ")' <<<"${creditors}")"
  portfolios="$(get "${token}" '/api/v1/portfolios?size=200')"
  nport="$(jq '.totalElements' <<<"${portfolios}")"
  kv_set "portfolio_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${portfolios}")"
  # carteiras dos 3 credores do catálogo principal (os primeiros cadastrados), que os cenários e o audit usam
  main_ids="$(jq -c '[.content[] | select(.externalCode as $c | ["HZN","EST","TVC","CSR","ELT","UMC"] | index($c)) | .id]' <<<"${creditors}")"
  per_creditor="$(jq -r --argjson ids "${main_ids}" '[.content[] | select(.creditorId as $c | $ids | index($c))]
    | group_by(.creditorId) | map(length | tostring) | join(",")' <<<"${portfolios}")"
  cases="$(get "${token}" '/api/v1/collection/cases?size=1')"
  ncases="$(jq '.totalElements' <<<"${cases}")"
  kv_set "case_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${cases}")"
  kv_set "debtor_ids:${tenant}" "$(jq -r '.content[0].debtorId' <<<"${cases}")"
  summary="$(get "${token}" '/api/v1/negotiation/agreements/summary')"
  agreements="$(jq -r '"total=\(.total) em vigor=\(.active) quitados=\(.settled) quebrados=\(.broken) cancelados=\(.cancelled) renegociados=\(.renegotiated)"' <<<"${summary}")"
  kv_set "agreements:${tenant}" "${summary}"
  fin="$(token "${tenant}" financeiro)"
  payments="$(get "${fin}" '/api/v1/payment/payments?size=200' | jq -r '"\(.totalElements) (amostra: \([.content[].status] | group_by(.) | map("\(.[0])=\(length)") | join(" ")))"')"
  kv_set "payments:${tenant}" "$(get "${fin}" '/api/v1/payment/payments?size=1' | jq '.totalElements')"
  users="$(get "$(token "${tenant}" admin)" '/api/v1/users?size=200')"
  kv_set "user_ids:${tenant}" "$(jq -r '.content[0].id' <<<"${users}")"
  active="$(jq '[.content[] | select(.status == "ACTIVE")] | length' <<<"${users}")"
  matters="$(get "$(token "${tenant}" juridico)" '/api/v1/legal/matters?size=20' | jq -r '[.content[].stage] | join(",")')"
  case "${tenant}" in
    aurora) expected_cred=30; expected_port=80; expected_users=34 ;;
    *) expected_cred=3; expected_port=12; expected_users=14 ;;
  esac
  if [[ "${ncred}" == "${expected_cred}" && "${nport}" == "${expected_port}" && "${per_creditor}" == "4,4,4" \
        && "${ncases}" -gt 0 && "${active}" -ge "${expected_users}" ]]; then
    ok "${tenant}: ${ncred} credores, ${nport} carteiras (catálogo principal ${per_creditor}); ${ncases} casos; ${active} usuários ativos"
  else
    fail "${tenant}: esperado ${expected_cred} credores, ${expected_port} carteiras (4,4,4 no catálogo principal) e ${expected_users}+ usuários; obtido ${ncred} credores, ${nport} carteiras (${per_creditor}), ${ncases} casos, ${active} usuários ativos"
  fi
  log INFO "${tenant}: acordos [${agreements}] · pagamentos [${payments}] · jurídico [${matters}]"
done

log INFO "2b. histórico de 12 meses (aurora)"
gestor_aurora="$(token aurora gestor)"
summary="$(kv_get agreements:aurora)"
total="$(jq '.total' <<<"${summary}")"; settled="$(jq '.settled' <<<"${summary}")"; broken="$(jq '.broken' <<<"${summary}")"
if [[ "${total}" -ge 150 && "${settled}" -ge 40 && "${broken}" -ge 10 && "$(kv_get payments:aurora)" -ge 200 ]]; then
  ok "aurora: ${total} acordos (${settled} quitados, ${broken} quebrados) e $(kv_get payments:aurora) pagamentos no histórico"
else
  fail "aurora: histórico de acordos e pagamentos abaixo do esperado (${total} acordos, ${settled} quitados, ${broken} quebrados, $(kv_get payments:aurora) pagamentos)"
fi
month_end="$(date -u -v-1m +%Y-%m-01 2>/dev/null || date -u -d "$(date +%Y-%m-01) -1 month" +%Y-%m-%d)"
month_last="$(date -u -v1d -v-1d +%Y-%m-%d 2>/dev/null || date -u -d "$(date +%Y-%m-01) -1 day" +%Y-%m-%d)"
trend="$(get "${gestor_aurora}" "/api/v1/costing/margin/trend?from=${month_end}&to=${month_last}")"
complete="$(jq '[.months[] | select(.status == "COMPLETE")] | length' <<<"${trend}" 2>/dev/null || echo 0)"
with_points="$(jq '[.creditors[] | select([.points[] | select(.netMargin != null)] | length >= 5)] | length' <<<"${trend}" 2>/dev/null || echo 0)"
if [[ "${complete}" -ge 5 && "${with_points}" -ge 10 ]]; then
  ok "margem: ${complete} meses apurados na tendência de 6 meses; ${with_points} credores com 5+ pontos"
else
  fail "margem: tendência com ${complete} meses apurados e ${with_points} credores com 5+ pontos"
fi
rounds="$(get "${gestor_aurora}" '/api/v1/incentive/tiers/rounds' | jq '[.[] | select(.status == "PUBLISHED")] | length' 2>/dev/null || echo 0)"
[[ "${rounds}" -ge 1 ]] && ok "classificação por desempenho: ${rounds} rodada(s) publicada(s)" \
  || fail "classificação por desempenho: nenhuma rodada publicada"

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
  seen="$(get "${token}" '/api/v1/creditors?size=200' | jq -r '[.content[].id] | join(" ")')"
  for id in $(kv_get "creditor_ids:${other}"); do
    [[ " ${seen} " != *" ${id} "* ]] || fail "${from} lista credor de ${other}"
  done
  ok "${from} não enxerga credores, carteira, caso, devedor nem usuário de ${other}"
}
check_isolation aurora boreal
check_isolation boreal aurora

log INFO "4. escopo por credor (segundo operador, só o primeiro credor)"
for tenant in aurora boreal; do
  n="$(get "$(token "${tenant}" operador2)" '/api/v1/creditors?size=200' | jq '.totalElements')"
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
hzn="$(get "${gestor}" '/api/v1/creditors?size=200' | jq -r '.content[] | select(.externalCode == "HZN") | .id')"
portfolios="$(get "${gestor}" '/api/v1/portfolios?size=200')"
HZN_PORTFOLIO="$(jq -r '.content[] | select(.code == "HZN-CARTAO") | .id' <<<"${portfolios}")"
EST_PORTFOLIO="$(jq -r '.content[] | select(.code == "EST-CREDIARIO") | .id' <<<"${portfolios}")"
BOREAL_PORTFOLIO="$(get "$(token boreal gestor)" '/api/v1/portfolios?size=200' | jq -r '.content[] | select(.code == "CSR-CARTAO") | .id')"

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

log INFO "8. Importação PDF (mapa salvo do 1º credor, sem IA)"
PDF_INDEX="$(sandbox_pdf_index || true)"
npdfs="$(grep -c . <<<"${PDF_INDEX}" || true)"
[[ "${npdfs}" == 10 ]] && ok "10 PDFs fictícios de demonstração (5 por assessoria; ./scripts/sandbox/pdfs.sh)" \
  || fail "PDFs de demonstração no índice: ${npdfs} (esperado 10)"
PDF_FILE="$(awk -F'\t' '$1 == "aurora" && $4 ~ /^verificacao-api-/ {print $4}' <<<"${PDF_INDEX}")"
TMP_PDF="$(mktemp "${TMPDIR:-/tmp}/gc-verify-pdf.XXXXXX")"
trap 'rm -f "${TMP_BODY}" "${TMP_PDF}"' EXIT
if [[ -z "${PDF_FILE}" ]] || ! sandbox_pdf aurora "${PDF_FILE}" > "${TMP_PDF}" || [[ ! -s "${TMP_PDF}" ]]; then
  fail "PDF de verificação da Importação PDF indisponível no volume do sandbox"
else
  # mesmo arquivo e mesma chave a cada execução → a mesma importação (a API recusa o mesmo PDF com outra chave: 409)
  out="$(curl -sS --max-time 60 -w '\n%{http_code}' -H "Authorization: Bearer ${FULL}" \
    -H 'Idempotency-Key: sandbox-verify-pdf-v1' -F "file=@${TMP_PDF};type=application/pdf;filename=${PDF_FILE}" \
    "${APP_URL}/public-api/v1/imports/pdf?portfolioId=${HZN_PORTFOLIO}")"
  HTTP_STATUS="${out##*$'\n'}"
  expect "enviar o PDF de demonstração pela API pública (token completo)" "200|202"
  pdf_id="$(jq -r '.id // empty' <<<"${out%$'\n'*}" 2>/dev/null || true)"
  if [[ -n "${pdf_id}" ]]; then
    pdf=""
    status=""
    for _ in $(seq 1 30); do
      pdf="$(papi GET "/imports/pdf/${pdf_id}" "${FULL}")"
      status="$(jq -r '.status' <<<"${pdf}")"
      [[ "${status}" != RECEIVED && "${status}" != MAPPING ]] && break
      sleep 2
    done
    summary="$(jq -r '"\(.status) \(.mapOrigin) \(.records) \(.totalsMatch) \(.awaitingHumanValidation)"' <<<"${pdf}")"
    [[ "${summary}" == "AWAITING_VALIDATION TEMPLATE 3 true true" ]] \
      && ok "PDF mapeado pelo mapa salvo, 3 títulos, totais batendo, aguardando validação humana" \
      || fail "Importação PDF pela API: status mapa títulos totais validação = ${summary} (esperado AWAITING_VALIDATION TEMPLATE 3 true true)"
  fi
fi

log INFO "9. menu x API: para cada perfil, as páginas que o menu mostra não recebem 403 ao carregar"
# página | permissões do item de menu (frontend/src/app/core/layout/navigation.ts) | chamada de carregamento
# (o teste navigation-routes.spec.ts garante menu = rota; aqui, rota/menu = API, nos perfis reais do sandbox)
MENU_PAGES="Painel operacional|DASHBOARD_VIEW,REPORT_VIEW|/api/v1/analytics/portfolio
Recuperação|REPORT_VIEW|/api/v1/analytics/recovery
Conversão|REPORT_VIEW|/api/v1/analytics/conversion
Produtividade|PERFORMANCE_VIEW_OWN,PERFORMANCE_VIEW_TEAM,PERFORMANCE_VIEW_ALL,COMMISSION_STATEMENT_MANAGE,COMMISSION_STATEMENT_APPROVE|/api/v1/analytics/productivity
Confiabilidade|DASHBOARD_VIEW,REPORT_VIEW|/api/v1/analytics/reliability
Casos|CASE_VIEW|/api/v1/collection/cases
Devedores|DEBTOR_VIEW|/api/v1/debtors
Títulos|DEBTOR_VIEW,CASE_VIEW|/api/v1/titles
Pagamentos|PAYMENT_VIEW|/api/v1/payment/payments
Credores|CREDITOR_VIEW|/api/v1/creditors
Carteiras|PORTFOLIO_VIEW|/api/v1/portfolios
Importação de carteiras|IMPORT_VIEW|/api/v1/ingestion/batches
Painel jurídico|LEGAL_VIEW|/api/v1/legal/dashboard
Casos jurídicos|LEGAL_VIEW|/api/v1/legal/matters"
menu_checked=0
for person in "${PEOPLE[@]}"; do
  token="$(token aurora "${person}")"
  perms=",$(kv_get "perms:aurora:${person}"),"
  if [[ -z "${token}" ]]; then continue; fi
  while IFS='|' read -r page required endpoint; do
    shown=false
    for p in ${required//,/ }; do
      if [[ "${perms}" == *",${p},"* ]]; then shown=true; fi
    done
    if [[ "${shown}" != true ]]; then continue; fi
    menu_checked=$((menu_checked + 1))
    get "${token}" "${endpoint}" >/dev/null
    if [[ "${HTTP_STATUS}" == 403 ]]; then
      fail "aurora/${person}: o menu mostra \"${page}\", mas ${endpoint} responde 403"
    fi
  done <<<"${MENU_PAGES}"
done
ok "menu x API: ${menu_checked} combinações perfil x página do menu sem 403 no carregamento"

log INFO "10. portal do credor (pessoas do credor, isolamento e fronteira dos tokens)"
CREDITOR_VAULT="$(umask 077 && mktemp "${TMPDIR:-/tmp}/gc-verify-credor.XXXXXX")"
trap 'rm -f "${TMP_BODY}" "${TMP_PDF}" "${CREDITOR_VAULT}"' EXIT
sandbox_creditor_mfa > "${CREDITOR_VAULT}" 2>/dev/null || true
CREDITOR_FIRST_STEP="$(totp_step)"

# creditor_get <token> <caminho> → BODY, HTTP_STATUS e CACHE_CONTROL (sem subshell: os valores ficam visíveis)
creditor_get() {
  local out headers
  headers="$(mktemp "${TMPDIR:-/tmp}/gc-verify-headers.XXXXXX")"
  out="$(curl -sS --max-time 30 -D "${headers}" -w '\n%{http_code}' -H "Authorization: Bearer $1" "${APP_URL}$2")"
  HTTP_STATUS="${out##*$'\n'}"
  CACHE_CONTROL="$(tr -d '\r' < "${headers}" | awk -F': ' 'tolower($1) == "cache-control" {print $2}' | tail -1)"
  rm -f "${headers}"
  BODY="${out%$'\n'*}"
}

# creditor_post <caminho> <json> → BODY, HTTP_STATUS e RETRY_AFTER (rotas de autenticação: Origin obrigatório)
creditor_post() {
  local out headers
  headers="$(mktemp "${TMPDIR:-/tmp}/gc-verify-headers.XXXXXX")"
  out="$(curl -sS --max-time 30 -D "${headers}" -w '\n%{http_code}' -H 'Content-Type: application/json' \
    -H "Origin: ${APP_URL}" -d "$2" "${APP_URL}$1")"
  HTTP_STATUS="${out##*$'\n'}"
  RETRY_AFTER="$(tr -d '\r' < "${headers}" | awk -F': ' 'tolower($1) == "retry-after" {print $2}' | tail -1)"
  rm -f "${headers}"
  BODY="${out%$'\n'*}"
}

# fresh_step <passo> → passo TOTP atual, posterior a <passo> e ao início desta verificação (espera a virada)
fresh_step() {
  local floor="$1" step
  (( floor < CREDITOR_FIRST_STEP )) && floor="${CREDITOR_FIRST_STEP}"
  while step="$(totp_step)"; (( step <= floor )); do sleep "$(totp_seconds_left)"; done
  printf '%s' "${step}"
}

# creditor_login <assessoria> <pessoa> → token em kv "ctoken:<assessoria>:<pessoa>". Senha → MFA_REQUIRED sem token
# (RS-85) → código num passo novo. 429 espera o Retry-After; código recusado nunca é repetido (uma nova tentativa,
# com outro passo, RS-89).
creditor_login() {
  local tenant="$1" person="$2" hex body step code attempt last=0 token tries=0
  hex="$(awk -F'\t' -v t="${tenant}" -v e="${person}@credor.exemplo.test" '$1 == t && $2 == e {print $5}' "${CREDITOR_VAULT}")"
  [[ -n "${hex}" ]] || { fail "${tenant}/${person}: pessoa do credor sem segredo no cofre do sandbox"; return 1; }
  for attempt in 1 2 3 4; do
    creditor_post /api/creditor/v1/auth/login "$(jq -nc --arg t "${tenant}" \
      --arg e "${person}@credor.exemplo.test" --arg p "${PASSWORD}" '{tenant: $t, email: $e, password: $p}')"
    body="${BODY}"
    if [[ "${HTTP_STATUS}" == 429 ]]; then
      log INFO "portal do credor pediu para esperar (429); aguardando ${RETRY_AFTER:-60} s"
      sleep "$(( ${RETRY_AFTER:-60} + 1 ))"
      continue
    fi
    if [[ "$(jq -r '.status // empty' <<<"${body}")" != MFA_REQUIRED || -n "$(jq -r '.accessToken // empty' <<<"${body}")" ]]; then
      fail "${tenant}/${person}: a 1ª etapa do login do credor não respondeu MFA_REQUIRED sem token (HTTP ${HTTP_STATUS})"
      return 1
    fi
    step="$(fresh_step "${last}")"
    last="${step}"
    code="$(totp_code "${hex}" "${step}")"
    creditor_post /api/creditor/v1/auth/mfa "$(jq -nc --arg s "$(jq -r .stepToken <<<"${body}")" \
      --arg c "${code}" '{stepToken: $s, code: $c}')"
    body="${BODY}"
    code=""
    if [[ "${HTTP_STATUS}" == 429 ]]; then
      sleep "$(( ${RETRY_AFTER:-60} + 1 ))"
      continue
    fi
    token="$(jq -r 'select(.status == "AUTHENTICATED") | .accessToken // empty' <<<"${body}" 2>/dev/null || true)"
    if [[ -n "${token}" ]]; then
      kv_set "ctoken:${tenant}:${person}" "${token}"
      return 0
    fi
    tries=$((tries + 1))
    (( tries >= 2 )) && break
  done
  fail "${tenant}/${person}: entrada no portal do credor não concluída"
  return 1
}

# normalized <regex de ids> → corpo do 404 sem requestId/instance e com os ids trocados (comparação com o inexistente)
normalized() { jq -cS 'del(.requestId, .instance, .timestamp)' 2>/dev/null | sed -E "s/$1/:id/g"; }

CREDITOR_PEOPLE=(aurora:hzn-gestao aurora:hzn-consulta aurora:est-consulta boreal:csr-gestao)
if [[ ! -s "${CREDITOR_VAULT}" ]]; then
  fail "cofre do portal do credor indisponível no volume do sandbox (/sandbox-data/creditor-mfa.txt)"
else
  # pessoas do credor: todas ativas, nenhuma bloqueada nem com falhas, nenhum convite pendente (RS-84, RS-87)
  stuck="$(compose exec -T postgres psql -U postgres -d cobranca -At -c "select count(*) from identity.creditor_user
    where status = 'INVITED' or locked_until > now() or failed_attempts > 0" </dev/null 2>/dev/null | tr -d '[:space:]')"
  active="$(compose exec -T postgres psql -U postgres -d cobranca -At -c "select count(*) from identity.creditor_user
    where status = 'ACTIVE'" </dev/null 2>/dev/null | tr -d '[:space:]')"
  if [[ "${stuck}" == 0 && "${active:-0}" -ge 11 ]]; then
    ok "pessoas do credor: ${active} ativas, nenhuma bloqueada, com falhas ou com convite pendente"
  else
    fail "pessoas do credor: ${active:-?} ativas e ${stuck:-?} com convite pendente, bloqueio ou falhas (esperado 11+ e 0)"
  fi

  for who in "${CREDITOR_PEOPLE[@]}"; do
    creditor_login "${who%%:*}" "${who#*:}" || true
  done
  HZN="$(kv_get ctoken:aurora:hzn-gestao)"
  HZN_READ="$(kv_get ctoken:aurora:hzn-consulta)"
  EST="$(kv_get ctoken:aurora:est-consulta)"
  CSR="$(kv_get ctoken:boreal:csr-gestao)"
  if [[ -n "${HZN}" && -n "${EST}" && -n "${CSR}" && -n "${HZN_READ}" ]]; then
    ok "pessoas do credor entraram com senha e código do autenticador (1ª etapa MFA_REQUIRED sem token)"

    # (g) leituras com no-store; Início com números no 1º credor e os próprios números no 2º
    creditor_get "${HZN}" '/api/creditor/v1/overview?period=THIS_YEAR'
    overview="${BODY}"
    [[ "${HTTP_STATUS}" == 200 && "${CACHE_CONTROL}" == *no-store* ]] \
      || fail "Início do credor: HTTP ${HTTP_STATUS}, Cache-Control '${CACHE_CONTROL}'"
    summary="$(jq -r '"recuperado=\(.recovery.recovered) saldo=\(.balance.amount) acordos=\(.agreements.active) indisponíveis=\(.unavailable | length)"' <<<"${overview}" 2>/dev/null || true)"
    if jq -e '(.unavailable | length) == 0 and .recovery.recovered != null and .balance.amount > 0 and (.monthly | length) > 0' \
        <<<"${overview}" >/dev/null 2>&1; then
      ok "Início do Banco Horizonte com números (${summary})"
    else
      fail "Início do Banco Horizonte sem números ou com fonte indisponível (${summary})"
    fi
    creditor_get "${HZN}" /api/creditor/v1/awaiting
    awaiting="${BODY}"
    [[ "${HTTP_STATUS}" == 200 && "${CACHE_CONTROL}" == *no-store* ]] \
      || fail "Aguardando você: HTTP ${HTTP_STATUS}, Cache-Control '${CACHE_CONTROL}'"
    counts="$(jq -r '"contestações=\(.counts.disputes) demonstrativos=\(.counts.statements) recusados=\(.counts.rejectedPayments) vencidos=\(.overdue)"' <<<"${awaiting}" 2>/dev/null || true)"
    if jq -e '.counts.disputes >= 1 and .counts.statements >= 1 and .counts.rejectedPayments >= 1 and .overdue >= 1' \
        <<<"${awaiting}" >/dev/null 2>&1; then
      ok "Aguardando você do Banco Horizonte: ${counts}"
    else
      fail "Aguardando você do Banco Horizonte incompleto: ${counts} (esperado ≥ 1 em cada e 1 vencido)"
    fi
    creditor_get "${HZN}" '/api/creditor/v1/activity?scope=team'
    activity="${BODY}"
    [[ "${HTTP_STATUS}" == 200 && "${CACHE_CONTROL}" == *no-store* ]] \
      || fail "atividade do time: HTTP ${HTTP_STATUS}, Cache-Control '${CACHE_CONTROL}'"
    nact="$(jq '.items | length' <<<"${activity}" 2>/dev/null || echo 0)"
    if (( nact >= 3 )); then
      ok "Minha atividade do time do Banco Horizonte: ${nact} ações"
    else
      fail "Minha atividade do time do Banco Horizonte com ${nact} ações (esperado ≥ 3: aceite, resposta e informe)"
    fi
    creditor_get "${EST}" /api/creditor/v1/awaiting
    est_statements="$(jq -r '.counts.statements' <<<"${BODY}" 2>/dev/null || true)"
    creditor_get "${EST}" '/api/creditor/v1/overview?period=THIS_YEAR'
    est_balance="$(jq -r '.balance.amount' <<<"${BODY}" 2>/dev/null || true)"
    hzn_balance="$(jq -r '.balance.amount' <<<"${overview}" 2>/dev/null || true)"
    if [[ "${est_statements}" == 0 && -n "${est_balance}" && "${est_balance}" != "${hzn_balance}" ]]; then
      ok "Início do Varejo Estrela com os próprios números (saldo ${est_balance} × ${hzn_balance}; nenhum demonstrativo aguardando)"
    else
      fail "Início do Varejo Estrela igual ao do Banco Horizonte ou com demonstrativo de outro credor"
    fi

    # (f) período fora da lista; (e) time só com a permissão
    creditor_get "${HZN}" '/api/creditor/v1/overview?period=ULTIMA_DECADA'
    expect "Início com período fora da lista" 400
    creditor_get "${HZN_READ}" '/api/creditor/v1/activity?scope=team'
    expect "perfil Consulta na atividade do time" 403

    # (a)/(b) registros do Banco Horizonte vistos por outro credor e por outra assessoria: 404 igual ao de id inexistente
    creditor_get "${HZN}" /api/creditor/v1/statements
    statement="$(jq -r '.[0].id // empty' <<<"${BODY}" 2>/dev/null || true)"
    creditor_get "${HZN}" "/api/creditor/v1/statements/${statement}"
    file="$(jq -r '.files[0].id // empty' <<<"${BODY}" 2>/dev/null || true)"
    creditor_get "${HZN}" /api/creditor/v1/disputes
    dispute="$(jq -r '.[0].id // empty' <<<"${BODY}" 2>/dev/null || true)"
    creditor_get "${HZN}" /api/creditor/v1/payments
    payments_json="${BODY}"
    payment="$(jq -r '.[0].id // empty' <<<"${payments_json}" 2>/dev/null || true)"
    title="$(jq -r '.[0].titleNumbers[0] // empty' <<<"${payments_json}" 2>/dev/null || true)"
    missing="00000000-0000-7000-8000-000000000000"
    if [[ -z "${statement}" || -z "${file}" || -z "${dispute}" || -z "${payment}" || -z "${title}" ]]; then
      fail "Banco Horizonte sem demonstrativo, arquivo, contestação ou pagamento informado para o teste de isolamento"
    else
      ids="${statement}|${file}|${dispute}|${missing}"
      for probe in "statements/${statement}|statements/${missing}|demonstrativo" \
          "statements/${statement}/files/${file}/content|statements/${missing}/files/${missing}/content|arquivo do demonstrativo" \
          "disputes/${dispute}|disputes/${missing}|contestação"; do
        IFS='|' read -r path ghost label <<<"${probe}"
        for other in EST CSR; do
          case "${other}" in EST) who="Varejo Estrela (mesma assessoria)" ;; *) who="Coop Sertão (outra assessoria)" ;; esac
          creditor_get "${!other}" "/api/creditor/v1/${path}"
          real="${BODY}"; real_status="${HTTP_STATUS}"
          creditor_get "${!other}" "/api/creditor/v1/${ghost}"
          fake="${BODY}"; fake_status="${HTTP_STATUS}"
          if [[ "${real_status}" == 404 && "${fake_status}" == 404 \
                && "$(normalized "${ids}" <<<"${real}")" == "$(normalized "${ids}" <<<"${fake}")" ]]; then
            ok "${label} do Banco Horizonte pelo ${who}: 404 igual ao de id inexistente"
          else
            fail "${label} do Banco Horizonte pelo ${who}: HTTP ${real_status} (inexistente: ${fake_status}) ou corpo diferente"
          fi
        done
      done
      creditor_get "${EST}" /api/creditor/v1/payments
      if [[ "${HTTP_STATUS}" == 200 && "$(jq --arg p "${payment}" '[.[] | select(.id == $p)] | length' <<<"${BODY}" 2>/dev/null)" == 0 ]]; then
        ok "pagamento informado do Banco Horizonte fora da lista do Varejo Estrela"
      else
        fail "pagamento informado do Banco Horizonte aparece (ou a lista falhou) para o Varejo Estrela"
      fi
      creditor_get "${CSR}" "/api/creditor/v1/payments/titles?number=${title}"
      other_title="${BODY}"
      creditor_get "${CSR}" '/api/creditor/v1/payments/titles?number=SBX-INEXISTENTE-0'
      ghost_title="${BODY}"
      if [[ "${other_title}" == "[]" && "${ghost_title}" == "[]" ]]; then
        ok "título do Banco Horizonte pela Coop Sertão: vazio, igual ao de número inexistente"
      else
        fail "busca de título de outra assessoria não respondeu vazio"
      fi
    fi

    # (c) token do credor fora do portal; (d) token interno no portal
    get "${HZN}" /api/v1/creditors >/dev/null
    expect "token do credor na API interna" 403
    papi GET /creditor "${HZN}" >/dev/null
    expect "token do credor na API pública" 401
    creditor_get "$(token aurora gestor)" /api/creditor/v1/overview
    expect "token interno no portal do credor" "401|403"
  fi

  # encerra as sessões abertas por esta verificação
  for who in "${CREDITOR_PEOPLE[@]}"; do
    tok="$(kv_get "ctoken:${who%%:*}:${who#*:}")"
    if [[ -n "${tok}" ]]; then
      curl -sS --max-time 10 -o /dev/null -X POST -H "Authorization: Bearer ${tok}" \
        "${APP_URL}/api/creditor/v1/session/logout" || true
    fi
  done
fi

log INFO "11. Mailpit fora do túnel"
mail_port="$(compose port mailpit 8025 2>/dev/null || true)"
if [[ "${mail_port}" == 127.0.0.1:* ]] && compose --profile tunnel config 2>/dev/null | grep -q 'http://app:8080'; then
  ok "Mailpit publicado só em ${mail_port}; o túnel aponta só para a aplicação (app:8080)"
else
  fail "Mailpit publicado em '${mail_port}' ou o túnel não aponta só para a aplicação"
fi

# Túnel registrado: precisa estar no ar, com o endereço que publicou por último, e a aplicação montando os links dos
# e-mails com ele (o envio de link exige https). Sem registro, a aplicação não pode ter ficado com um endereço antigo.
registered="$(public_url)"
base="$(compose exec -T app printenv APP_BASE_URL 2>/dev/null || true)"
if [[ -n "${registered}" ]]; then
  current="$(tunnel_logged_url)"
  if ! tunnel_running; then
    fail "túnel registrado em ${registered}, mas o contêiner do túnel não está rodando (./scripts/sandbox/tunnel.sh)"
  elif [[ "${current}" != "${registered}" ]]; then
    fail "túnel publicou ${current:-nenhum endereço}, mas o registro diz ${registered} (./scripts/sandbox/tunnel.sh)"
  elif [[ "${base}" != "${registered}" ]]; then
    fail "túnel em ${registered}, mas a aplicação monta os links com '${base}' (./scripts/sandbox/tunnel.sh)"
  else
    ok "túnel: links dos e-mails em ${base}"
  fi
  # RS-92: rotas do Mailpit no endereço público caem na aplicação, nunca no Mailpit
  mp_api="$(curl -sS --max-time 20 -o "${TMP_BODY}" -w '%{http_code}' "${registered}/api/v1/info" || true)"
  mp_view="$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' "${registered}/view/latest.html" || true)"
  if [[ "${mp_api}" != 200 && "${mp_view}" != 200 ]] && ! grep -qiE 'mailpit|"Database"' "${TMP_BODY}" 2>/dev/null; then
    ok "túnel: rotas do Mailpit não chegam ao Mailpit pelo endereço público (${mp_api}/${mp_view})"
  else
    fail "túnel: rota do Mailpit respondeu ${mp_api}/${mp_view} pelo endereço público"
  fi
elif [[ -n "${base}" && "${base}" != "${APP_URL}" ]]; then
  fail "sem túnel registrado, mas a aplicação monta os links com '${base}' (./scripts/sandbox/up.sh --no-build)"
else
  ok "sem túnel: links dos e-mails em ${APP_URL}"
fi

if (( FAILURES > 0 )); then
  die "${FAILURES} verificação(ões) falharam"
fi
log INFO "todas as verificações passaram"
