# Desenho — API pública de integração dos credores (módulo `publicapi`)

Documento de negócio para o PO e para os terceiros: [`docs/negocio/api-publica.md`](../../negocio/api-publica.md). Publicação e versionamento: [`docs/tecnico/operacao/api-publica-publicacao.md`](../operacao/api-publica-publicacao.md). Decisões: 600–629 em [`decisoes-autonomas.md`](../../negocio/decisoes-autonomas.md).

## 1. Objetivo e escopo

Permitir que o **credor** administre, de sistema a sistema, a carteira de cobrança mantida na assessoria: carteiras, importação de títulos (JSON, CSV, XLSX), ciclo de vida dos títulos, contatos dos devedores, acompanhamento da cobrança (casos, acordos e parcelas, pagamentos — somente leitura), pagamento recebido diretamente pelo credor e contestações. A API **chama os casos de uso dos módulos donos** (`organization`, `ingestion`, `debt`, `collection`, `negotiation`, `payment`) — nenhuma regra de negócio é duplicada; o módulo `publicapi` só cuida de credencial, contrato HTTP público, medição e faturamento.

Fora do escopo desta entrega (fase seguinte, issue a abrir): **webhooks de saída** assinados com HMAC (lote processado, acordo formalizado, pagamento conciliado) — ver §11.

## 2. Por que um módulo novo chamado `publicapi`

- **Coesão**: credencial de integração, cadeia de segurança, contrato público, medição e faturamento mudam juntos e pelo mesmo motivo (o produto "API para credores"); não pertencem a nenhum módulo de negócio existente.
- **Baixo acoplamento**: depende só de `shared` e das interfaces `usecases` dos módulos que expõe (`organization`, `debt`, `ingestion`, `collection`, `negotiation`, `payment`); nenhum módulo depende dele. A atribuição assíncrona de registros usa o evento publicado `PortfolioLoaded`.
- **Nome**: `integration` já designa, no produto, os provedores configurados pela assessoria (`INTEGRATION_MANAGE`: WhatsApp, PSP, telefonia). `publicapi` deixa claro que é a fronteira de entrada para terceiros.

## 3. Estrutura do módulo

```
br.com.gestorcobranca.publicapi
├── package-info.java                      @ApplicationModule(allowedDependencies = shared + usecases dos módulos expostos)
├── domain/model/                          IntegrationToken, TokenSecret, TokenScope, TokenStatus/State, PriceTerms (cópia das
│                                          condições do contrato), BillableUsage (MB inteiros), UsageStatement, UsageTotals,
│                                          ApiVersionStatus
├── domain/service/                        TokenHasher (HMAC-SHA256 com chave do servidor), PriceCalculator e BillingCycles
│                                          (funções puras)
├── domain/repository/                     IntegrationTokenRepository, UsageStatementRepository
├── application/PublicApiProperties        app.public-api.*
├── application/port/in/                   (usecases) IssueIntegrationToken, IntegrationTokenUseCases, PublicApiUsageUseCases,
│                                          PublicApiBillingUseCases, ApiVersionUseCases
├── application/port/in/gateway/           (só o adaptador HTTP do módulo) IntegrationAuthentication, PublicApiMetering, PublicImports
├── application/port/out/                  TokenRouteDirectory, UsageStore, UsageDelta, ApiImportBatchStore, ApiCatalog,
│                                          ClientAddressRules, CsvWriter
├── application/service/                   IntegrationTokenService, IntegrationAuthenticationService, TokenRateLimiter,
│                                          PublicApiMeteringService, UsageAccumulator, UsageFlushJob, UsageReportService,
│                                          BillingService, StatementClosingJob, ImportRecordsListener, PublicImportService, …
├── infrastructure/persistence/            JPA (tokens, extratos) e JDBC (rota global, consumo por hora, lotes por token)
├── infrastructure/config/                 PublicApiSecurityConfig (cadeia própria), PublicApiOpenApiConfig (grupo public-v1)
├── infrastructure/{catalog,csv,network}/  versões/changelog do classpath, CSV seguro, faixas CIDR
└── web/
    ├── security/                          PublicApiRequestFilter, CountingStreams, ProblemWriter, UsageRecords
    ├── v1/                                controllers públicos (DTOs próprios, minimizados) + PublicApiExceptionHandler
    ├── admin/                             /api/v1/public-api/** (telas internas, JWT de usuário)
    └── docs/                              /public-api/docs, /changelog, /versions, /v1/openapi.json
```

Alterações mínimas fora do módulo (todas sem regra duplicada):

| Onde | O quê |
| --- | --- |
| `shared.web.ApiPaths` | `PUBLIC_API = /public-api`, `PUBLIC_API_V1` |
| `shared.security` | permissões `PUBLIC_API_TOKEN_VIEW/MANAGE`, `PUBLIC_API_USAGE_VIEW`, `PUBLIC_API_BILLING_MANAGE`; `ActorAuthentication.integration(actor, scopes)` gera as authorities `SCOPE_<escopo>` |
| `shared.logging` | `AccessEventType.PUBLIC_API_REQUEST`, `INTEGRATION_TOKEN_REJECTED/CREATED/REVOKED/ROTATED`; tipos `public_api.*` e `contract.integration_pricing_defined` em `BusinessEventTypes` |
| `shared.domain` | `PreconditionFailedException` (412, If-Match) e tratamento no `GlobalExceptionHandler` |
| `shared.paging` | `ChangeQuery`, `ChangeCursor`, `ChangePage`, `ChangeSpecifications` — paginação por cursor (updated_at, id) |
| `shared.config` | grupo OpenAPI `interna` separado; interceptor de MDC também em `/public-api/**`; SPA não captura `/public-api` |
| módulos donos | `changes(credor, ChangeQuery)` em títulos, casos, acordos, pagamentos, carteiras, lotes e contestações; versão e `updatedAt` nas visões de título e carteira; `expectedVersion` (If-Match) opcional nos comandos de título e carteira; `DebtTitleUseCases.findByExternalId` |
| `organization` | condições de cobrança das integrações no contrato do credor (`ContractIntegrationPricing`, versões append-only, `ContractIntegrationPricingUseCases`, `GET/POST /api/v1/contracts/{id}/integration-pricing`) — §7 |
| `identity.DefaultRoles` | permissões novas nos perfis padrão (§4.4) |

## 4. Tokens de integração

### 4.1 Formato e armazenamento

`gck_<prefixo>.<segredo>`: prefixo de 12 caracteres base32 (60 bits, público, único na plataforma); segredo de 32 bytes do `SecureRandom` (**256 bits**) em Base64 URL. O marcador `gck_` permite a varredura automática de vazamentos (ex.: secret scanning de repositórios).

O banco guarda só `secret_hash = HMAC-SHA256(chave do servidor, "v1|prefixo|segredo")` e `hash_key_id` (decisão 601):

- **Por que HMAC e não Argon2/bcrypt**: o segredo é aleatório com 256 bits — força bruta e dicionário são inviáveis mesmo com hash rápido; funções lentas existem para senhas escolhidas por pessoas. Um hash lento por requisição tornaria a API vulnerável a negação de serviço (dezenas de MB e milissegundos de CPU por tentativa).
- **Por que com chave ("pepper")**: um vazamento só do banco não permite sequer conferir um token candidato. A chave fica no cofre (`app/public-api-token-hash-key` → `APP_PUBLIC_API_TOKEN_HASH_KEY`), versionada por id (`t1`, `t2`…) para rotação sem invalidar tokens existentes.
- Comparação em tempo constante (`MessageDigest.isEqual`); prefixo inexistente também calcula um HMAC (tempo equalizado).

### 4.2 Ciclo de vida

| Operação | Regra | Trilha |
| --- | --- | --- |
| Criar | credor ativo no escopo do usuário (403 fora); escopos ⊆ permissões do próprio usuário (prevenção de escalada, decisão 604); limite 1–6.000/min; até 20 faixas CIDR (mín. /8 IPv4, /32 IPv6); expiração opcional futura. Segredo devolvido uma vez (`Cache-Control: no-store`) | auditoria `integration_token.created`, acesso `INTEGRATION_TOKEN_CREATED`, negócio `public_api.token_created` |
| Editar | nome, descrição, limite e IPs (escopos e credor nunca mudam: novo token) | auditoria `integration_token.updated`, acesso `PERMISSION_CHANGED` |
| Rotacionar | novo segredo, mesmo prefixo; o anterior vale até `previous_valid_until` (carência 0–24 h). Nova rotação encerra a carência anterior (no máximo dois segredos válidos) | `integration_token.rotated`, `INTEGRATION_TOKEN_ROTATED`, `public_api.token_rotated` |
| Revogar | imediata e definitiva, com motivo; descarta o segredo em carência | `integration_token.revoked`, `INTEGRATION_TOKEN_REVOKED`, `public_api.token_revoked` |
| Expirar | derivado de `expires_at` (sem job) | recusa `INTEGRATION_TOKEN_REJECTED reason=expired` |

Banco (`V2100`): `unique(prefix)`, checks de formato, `status`/revogação coerentes, gatilho que impede mudar credor, prefixo, criação e expiração e reativar um token revogado; sem `DELETE` para o runtime. `token_route` é **global e sem RLS** (como as rotas de webhook): só prefixo → (assessoria, token), consultada antes de haver tenant; o hash fica no token, sob RLS.

### 4.3 Escopos

| Escopo | Libera | Permissões concedidas ao ator |
| --- | --- | --- |
| `portfolios:read` | credor, contratos, carteiras | `CREDITOR_VIEW`, `PORTFOLIO_VIEW` |
| `portfolios:write` | criar, atualizar, mudar situação de carteira | + `PORTFOLIO_MANAGE` |
| `imports:read` / `imports:write` | acompanhar / enviar e cancelar lotes | `IMPORT_VIEW` / + `IMPORT_EXECUTE` |
| `titles:read` / `titles:write` | consultar e sincronizar / suspender, reativar, cancelar, devolver | `DEBTOR_VIEW` / + `DEBT_TITLE_MANAGE` |
| `debtors:read` / `debtors:write` | devedor e contatos mascarados / incluir e invalidar contato | `DEBTOR_VIEW` / + `DEBTOR_MANAGE` |
| `collection:read` | casos | `CASE_VIEW` |
| `agreements:read` | acordos e parcelas | `AGREEMENT_VIEW` |
| `payments:read` / `payments:write` | pagamentos / informar pagamento direto ao credor | `PAYMENT_VIEW` / + `PAYMENT_REGISTER_EXTERNAL` |
| `disputes:read` / `disputes:write` | contestações / abrir contestação (origem `CREDITOR`) | `CASE_VIEW` |

Nenhum escopo concede `DEBTOR_SENSITIVE_VIEW`, aprovação, estorno, formalização ou gestão (teste `IntegrationTokenTest`). Defesa em profundidade: o endpoint exige `SCOPE_<escopo>` e o caso de uso dono confere a permissão.

### 4.4 Perfis

| Perfil | Permissões novas |
| --- | --- |
| `ADMIN` | todas |
| `GESTOR` | `PUBLIC_API_TOKEN_VIEW`, `PUBLIC_API_TOKEN_MANAGE`, `PUBLIC_API_USAGE_VIEW`, `PUBLIC_API_BILLING_MANAGE` |
| `INTEGRACAO` | `PUBLIC_API_TOKEN_VIEW`, `PUBLIC_API_TOKEN_MANAGE`, `PUBLIC_API_USAGE_VIEW` (só concede escopos de importação e carteiras, que o perfil tem) |
| `FINANCEIRO` | `PUBLIC_API_USAGE_VIEW`, `PUBLIC_API_BILLING_MANAGE` |
| `AUDITOR` | `PUBLIC_API_TOKEN_VIEW`, `PUBLIC_API_USAGE_VIEW` |

A sincronização de perfis padrão (`DefaultRolesSynchronizer`) acrescenta essas permissões uma vez aos perfis de sistema existentes.

## 5. Autenticação e cadeia de segurança

`PublicApiSecurityConfig` registra uma `SecurityFilterChain` própria com `securityMatcher("/public-api/**")` e precedência sobre a cadeia dos usuários: **o JWT de usuário e o token do portal não são aceitos**, e o token de integração não é aceito em `/api/**` (testado). Sem sessão, sem cookies, sem CORS (servidor a servidor), cabeçalhos HSTS/CSP/nosniff/Referrer-Policy.

`PublicApiRequestFilter`, a cada requisição:

1. **Versão**: `/public-api/v<n>/…` consulta o registro de versões — inexistente 404 (`public_api.version_unknown`), desligada 410, depreciada acrescenta `Deprecation: @<epoch>` (RFC 9745), `Sunset: <data HTTP>` (RFC 8594) e `Link: <guia>; rel="deprecation"`.
2. **Rotas públicas** (sem token): `/public-api/docs/**`, `/versions`, `/changelog`, `/v<n>/openapi.json`.
3. **Autenticação** (`IntegrationAuthentication`, sem cache — revogação vale na requisição seguinte): formato → rota global → token sob RLS → HMAC → situação → assessoria ativa → credor ativo → IP permitido (IP do `TrustedProxyFilter`, o mesmo de toda a plataforma). Recusa: `401` genérico (`auth.unauthenticated`, `WWW-Authenticate: Bearer`) ou `403` (IP fora da lista, credor inativo), sempre com `INTEGRATION_TOKEN_REJECTED` e o **motivo real** no log de acesso (`missing`, `malformed`, `unknown`, `secret_mismatch`, `revoked`, `expired`, `ip_not_allowed`, `tenant_inactive`, `creditor_inactive`).
4. **Contexto**: ator `INTEGRATION` com `id` = token, assessoria do token, escopo de credores = **somente** o credor do token, permissões dos escopos e authorities `SCOPE_*`. O `TenantAwareJpaTransactionManager` aplica o RLS da assessoria e o `AccessScope` dos módulos donos isola o credor; os controllers ainda conferem que o recurso é do credor do token (404 sem revelar).
5. **Limite de taxa** por token (janela fixa de 1 min, por instância — mesmo padrão dos limitadores existentes): `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` em toda resposta; acima → `429 rate_limited` com `Retry-After`. Com N instâncias o teto efetivo é até N× o configurado (limite global exato: pendência §12).
6. **Medição** (§6).

Erros: `ProblemWriter` (filtros), `PublicApiExceptionHandler` (requisição ilegível, validação, parâmetro ausente, mídia, método — restrito aos controllers v1) e `GlobalExceptionHandler` (domínio) produzem o mesmo RFC 9457: `type`, `title`, `status`, `detail`, `code` estável, `requestId`, `errors`/`details` quando houver.

## 6. Medição de consumo e métricas

Por requisição autenticada o filtro mede **bytes reais do corpo** (`CountingStreams` envolve os streams; entrada = maior entre o lido e o `Content-Length`; saída antes da compressão HTTP do conector, independente da negociação do cliente), latência, status, rota (padrão, sem ids) e **registros processados** informados pelo endpoint (`UsageRecords`). Três destinos separados:

| Fluxo | Destino | Conteúdo |
| --- | --- | --- |
| Acesso (rastreabilidade) | `AccessLogger` → `shared.access_log` + `access.log` | `PUBLIC_API_REQUEST`: token (ator e prefixo), credor, assessoria, rota, método, status, bytes in/out, latência, registros; IP, user-agent e `X-Request-Id` do contexto |
| Técnico | Micrometer → CloudWatch | `publicapi.requests{method,route,status,outcome}` (timer com histograma), `publicapi.bytes{direction,route}`, `publicapi.records{route}`, `publicapi.auth{outcome}`, `publicapi.tokens.lifecycle{operation}`, `publicapi.usage.pending_keys`, `publicapi.usage.flushed_rows{outcome}`, `publicapi.usage.flush.duration`, `publicapi.statements.closed{trigger}`, `publicapi.statements.close_failures` — **sem** credor/token nas tags (cardinalidade) |
| Consumo (faturamento) | `UsageAccumulator` (memória) → `UsageFlushJob` → `publicapi.usage_hourly` | soma por (assessoria, credor, token, hora, método, rota) |

**Sem gargalo no caminho da requisição** (decisão 606): a requisição só incrementa `LongAdder`s num mapa concorrente, sob a trava de leitura compartilhada de um `ReentrantReadWriteLock` (sem contenção entre requisições). A cada minuto (e no desligamento) cada instância troca o mapa sob a trava de escrita e grava o acumulado **em lote**, uma instrução `insert … on conflict do update set x = x + excluded.x` por assessoria, em ordem estável de chave (sem deadlock entre instâncias). Falha de gravação devolve os deltas ao acumulador. Em queda abrupta perde-se no máximo o último minuto de consumo — o log de acesso por requisição permanece completo e permite reconstituí-lo. `last_used_at`/`last_used_ip` do token são atualizados no mesmo lote (só avançam). Teste de concorrência: `UsageAccumulatorTest` (40.000 somas simultâneas à drenagem, nenhuma perdida).

Requisições contam: `requests` (todas as autenticadas), `throttled` (429), `client_errors` (4xx exceto 429), `server_errors` (5xx). **Cobráveis** = requisições com resposta 2xx/3xx = `requests − throttled − client_errors − server_errors` (decisão 607, do responsável). Além de `bytes_in`/`bytes_out` (todo o tráfego, medição técnica), `usage_hourly` guarda `billable_bytes_in`/`billable_bytes_out` — só o tráfego das requisições cobráveis, base da cobrança em MB. Registros de processamento assíncrono (títulos criados/atualizados pela importação) chegam pelo evento `PortfolioLoaded` (`ImportRecordsListener`, idempotente por evento) e são somados na rota `ASYNC import.records` do token que enviou o lote (`publicapi.api_import_batch`).

`usage_hourly` só soma (gatilho recusa diminuir contadores) e não tem `DELETE`.

## 7. Faturamento

O preço da API fica **no contrato de prestação de serviço do credor** (decisão 608, do responsável); o `publicapi` não tem tabela de preços.

- **Condições de cobrança das integrações** (`organization.contract_integration_pricing`, tabela filha de `service_contract`, migração `organization/V2101`): dia de fechamento do consumo (1–31), mensalidade, franquias por ciclo e preço do excedente por chamada cobrável, por MB recebido, por MB enviado e por registro (até 6 casas; mensalidade com 2). Cada alteração é uma **nova versão append-only** (gatilho `forbid_mutation` + privilégio revogado), com `registered_at` do servidor e motivo opcional, auditada (`contract.integration_pricing_defined`) e com evento de negócio. Editada na ficha do credor (contratos → integrações), com `CONTRACT_MANAGE`. Endpoints internos: `GET/POST /api/v1/contracts/{id}/integration-pricing`.
- **Não retroatividade**: a versão que vale para um ciclo é a mais recente **registrada antes das 00:00 do primeiro dia do ciclo** (fuso de negócio), no contrato do credor vigente nessa data (havendo mais de um, o de início mais recente). Alteração feita durante um ciclo vale a partir do próximo, por construção — não há data de vigência digitada.
- **Leitura pelo publicapi**: `ContractIntegrationPricingUseCases.applicable(creditorId, inícioDoCiclo)` (interface `usecases` do organization); o organization não conhece o publicapi.
- **Ciclo de consumo** (`BillingCycles`, função pura): fecha no fim do dia de fechamento e o seguinte começa às 00:00 do dia seguinte em `TimeConfig.BUSINESS_ZONE`. Meses sem o dia informado fecham no **último dia do mês** (31 → 28/29 em fevereiro, 30 em abril; 30 → 28/29 em fevereiro). Sem condições no contrato: dia 31 (último dia do mês) e extrato com valor zero.
- **Sequência dos ciclos** (decisão 621): o próximo ciclo começa no dia seguinte ao fim do último ciclo fechado e fecha no primeiro dia de fechamento (das condições que valem no seu início) igual ou posterior. Ciclo encerrado **sem consumo** não gera extrato; o próximo extrato é o do ciclo (no calendário do dia de fechamento) que contém o primeiro consumo posterior, nunca antes do fim do último ciclo. Mudar o dia de fechamento vale do próximo ciclo e pode gerar um ciclo de transição diferente de um mês.
- **Quantidades faturáveis** (`BillableUsage`, decisão 607): requisições 2xx/3xx; tráfego em **MB binário (1 MB = 1.048.576 bytes)**, inteiro, arredondado para cima, com mínimo de 1 MB quando há tráfego. O arredondamento é feito **no total do ciclo por token e por direção** (recebido/enviado) e a quantidade do credor é a soma dos tokens — não por requisição (inflaria integrações com muitas chamadas pequenas) nem só no total do credor (o detalhamento por token não fecharia com o extrato). Exemplos: 1 byte → 1 MB; 1.048.576 → 1 MB; 1.048.577 → 2 MB. Registros: soma simples.
- **Cálculo** (`PriceCalculator`, função pura sobre quantidades inteiras): por medida, `cobrável = max(0, quantidade − franquia)`, `valor = cobrável × preço` arredondado a centavos (HALF_EVEN) por linha; total = mensalidade + soma das linhas.
- **Extrato** (`publicapi.usage_statement`, migração `publicapi/V2102`): por credor e ciclo (`period_start`, `period_end` inclusivos, `usage_closing_day`), com contrato e versão das condições aplicadas (chave estrangeira), quantidades faturáveis inteiras (`billable_requests`, `billable_mb_in`, `billable_mb_out`, `records`), linhas, detalhamento por token (MB arredondados no token) e por rota (chamadas e registros) e `usage_hash` = SHA-256 do consumo agregado. **Append-only** (privilégio revogado + gatilho); novo fechamento do mesmo ciclo exige motivo e gera nova revisão com o mesmo período e as mesmas condições — a anterior permanece. Reprodutível: prévia e revisões sobre o mesmo consumo têm o mesmo hash (testado).
- **Fechamento**: `StatementClosingJob` roda **todo dia às 02h20** (Brasília, ShedLock) — depois da virada do ciclo às 00:00 e da última gravação em lote — e fecha, em ordem, os ciclos encerrados ainda sem extrato de cada credor com token (`closeEndedCycles`); falha de um credor não interrompe os demais (métrica `publicapi.statements.close_failures`). Substitui o fechamento fixo do dia 1. Fechamento manual pela tela (próximo ciclo encerrado) e revisão com motivo; filtro da listagem pelo mês do fechamento (`period_end`).
- Não há cobrança: o extrato e a exportação CSV são o insumo para o faturamento (fora do sistema por ora).

## 8. Endpoints v1

Todos sob `/public-api/v1`, JSON, token obrigatório, escopo indicado. Listas usam **paginação por cursor** (`limit` até 500, `cursor`, `updatedSince`) com envelope `{data, nextCursor, hasMore}` em ordem crescente de (`updatedAt`, `id`); a consulta exclui o último minuto (`sync-stabilization-window`) para não pular alterações de transações ainda não confirmadas.

| Método e caminho | Escopo | Caso de uso dono |
| --- | --- | --- |
| `GET /creditor`, `GET /contracts` | `portfolios:read` | `CreditorUseCases.get`, `ServiceContractUseCases.listByCreditor` |
| `GET /portfolios`, `GET /portfolios/{id}` (ETag) | `portfolios:read` | `PortfolioUseCases.changes/get` |
| `POST /portfolios`, `PUT /portfolios/{id}`, `POST /portfolios/{id}/status` (If-Match) | `portfolios:write` | `PortfolioUseCases.create/update/changeStatus` (regras do organization: contrato vigente, ciclo DRAFT→ACTIVE→…, motivo) |
| `POST /imports` (JSON, Idempotency-Key) | `imports:write` | `ImportBatchUseCases.importRecords` |
| `POST /imports/files` (CSV/XLSX multipart, Idempotency-Key) | `imports:write` | `ImportBatchUseCases.uploadFile` (+ idempotência por token) |
| `GET /imports`, `/imports/{id}`, `/imports/{id}/issues`, `/imports/{id}/issues/report` (CSV) | `imports:read` | `changes/get/issues/exportIssues` |
| `POST /imports/{id}/cancel` | `imports:write` | `cancel` |
| `GET /titles`, `/titles/{id}`, `/titles/by-external-id/{externalId}` (ETag) | `titles:read` | `DebtTitleUseCases.changes/get/findByExternalId` |
| `POST /titles/{id}/suspend` · `/reactivate` · `/cancel` · `/return` (If-Match) | `titles:write` | `DebtTitleUseCases.suspend/reactivate/cancel/returnToCreditor` |
| `POST /debtors/search` (documento no corpo), `GET /debtors/{id}`, `/debtors/{id}/contacts` | `debtors:read` | `DebtorUseCases.findByDocument/get`, `DebtorContactUseCases.list` |
| `GET /debtors/{id}/titles` | `titles:read` | `DebtTitleUseCases.listByDebtor` (filtrado pelo credor) |
| `POST /debtors/{id}/contacts`, `/contacts/{cid}/invalidate` | `debtors:write` | `DebtorContactUseCases.add/markInvalid` |
| `GET /collection-cases`, `/collection-cases/{id}` | `collection:read` | `CaseUseCases.changes/get` |
| `GET /agreements`, `/agreements/{id}` | `agreements:read` | `AgreementUseCases.changes/get` |
| `GET /payments`, `/payments/{id}` | `payments:read` | `PaymentUseCases.changes/get` |
| `POST /payments` (multipart com evidência, Idempotency-Key) | `payments:write` | `PaymentUseCases.registerExternal` (origem `CREDITOR_DIRECT`; revisão por segunda pessoa acima do limite) |
| `GET /disputes`, `/disputes/{id}` | `disputes:read` | `DisputeUseCases.changes/get` |
| `POST /disputes` | `disputes:write` | `DisputeUseCases.open` (origem `CREDITOR`) |

**Idempotência**: `Idempotency-Key` (8–80 caracteres) obrigatório nos comandos que criam lotes e pagamentos, **isolado por token** (`tok:<token>:<chave>`; o `IdempotencyService` é por assessoria). Mesma chave e conteúdo → mesmo recurso; conteúdo diferente → `409 idempotency.key_reused`.

**Concorrência otimista**: leituras de carteira e título trazem `ETag: "<versão>"`; comandos aceitam `If-Match` (forte ou fraco); diferente → `412 <recurso>.version_mismatch`. A conferência é feita **dentro do caso de uso dono**, sob o lock do título (debt) ou antes do `@Version` (organization). A resposta do comando traz o ETag da versão confirmada.

**LGPD (minimização)**: DTOs próprios (`PublicApiModels`), separados das visões internas — devedor com nome e documento mascarado; contatos mascarados; nada de observações internas, operadores, IP/agente de aceite ou dados de outros credores; documento de busca só no corpo (`POST /debtors/search`), nunca na URL.

Baixa de título por pagamento **não** é endpoint: segue a regra RB06 (pagamento com evidência e confirmação) via `POST /payments`.

## 9. Documentação, contrato e publicação

- Grupo springdoc `public-v1` (`/public-api/v1/**`), separado do grupo `interna` (`/api/**`). `PublicApiOpenApiConfig` acrescenta informações, guia (autenticação, escopos, limites, erros, sincronização, ETag, idempotência, LGPD, versões), esquema `integrationToken` (HTTP bearer), `Problem` (RFC 9457), cabeçalhos `X-Request-Id`, `RateLimit-*`, `Retry-After`, `ETag`, `Deprecation`, `Sunset` e as respostas 400/401/403/404/409/412/422/429/500 em cada operação.
- **Contrato versionado**: `docs/api/public-api/v1/openapi.json` (forma canônica: chaves ordenadas). `PublicApiContractIT` gera o contrato e **falha se diferir do arquivo** (controle de mudança). Atualização consciente: `-Dpublicapi.contract.update=true` + entrada no changelog.
- O build copia o arquivo para o classpath; `/public-api/v1/openapi.json` serve **o contrato publicado** (não o gerado em tempo de execução). Em produção o `/v3/api-docs` continua desligado.
- Página pública `/public-api/docs`: Swagger UI do webjar já presente (sem CDN, CSP `script-src 'self'`, "Try it out" desligado). `/public-api/docs/changelog`: versões e changelog renderizados de `publicapi/versions.json` e `publicapi/changelog.json` (arquivos versionados).
- Diff entre versões: perfil Maven `public-api-diff` com `org.openapitools.openapidiff:openapi-diff-maven` (Java), que gera `changes.md`/`changes.json` e falha em mudança incompatível. Procedimento em [`api-publica-publicacao.md`](../operacao/api-publica-publicacao.md).

## 10. Versionamento

- Versão na URL (`/public-api/v1`); `PUBLIC_API_V1` em `ApiPaths`. **Tudo fica na 1.0 até a produção; o versionamento efetivo só começa quando o responsável avisar** (decisão 615).
- Registro de versões (`publicapi/versions.json`): `CURRENT` (uma), `DEPRECATED` (exige `deprecated` e `sunset`), `RETIRED` (410). Validado na subida.
- Política de compatibilidade: em [`docs/negocio/api-publica.md`](../../negocio/api-publica.md) §7.

## 11. Fase seguinte: webhooks de saída

Desenho proposto (não implementado nesta entrega — issue a abrir): cadastro de endpoint HTTPS por token (URL validada como as do PSP — só HTTPS, host público, resolvido a cada envio), segredo HMAC exibido uma vez, assinatura `X-GC-Signature: t=<epoch>,v1=<hmac-sha256(t.corpo)>`, eventos `import.batch_finished` (de `PortfolioLoaded`/fim de lote), `agreement.formalized` (`AgreementStatusChanged`), `payment.reconciled` (`PaymentConfirmed`), entrega pela fila de jobs com tentativas exponenciais, desligamento automático após falhas seguidas, idempotência por `event-id` e registro de entregas. Motivo para adiar: superfície de SSRF e operação de reentrega merecem revisão de segurança própria.

## 12. Pendências que dependem do responsável

- **Limite de taxa global** entre instâncias e proteção de borda (AWS WAF com regra por cabeçalho/token ou API Gateway com usage plans) — serviço AWS adicional.
- Endereço público (domínio) da documentação da API e eventual portal de desenvolvedores.
- Valores iniciais das condições de cobrança em cada contrato, forma de faturar e comunicação ao credor.
- Webhooks de saída (§11).

## 13. Testes

| Teste | Cobre |
| --- | --- |
| `TokenSecretAndHasherTest`, `IntegrationTokenTest` | formato, entropia, HMAC com chave versionada, tempo constante, rotação com carência, revogação, expiração, escopos sem permissões sensíveis |
| `PriceCalculatorTest` | franquias, excedente, 4xx/429/5xx não cobrados, MB binário arredondado para cima (1 byte → 1 MB; 1.048.576 → 1 MB; 1.048.577 → 2 MB), arredondamento por token e direção, reprodutibilidade |
| `BillingCyclesTest` | dia 31 em fevereiro (e anos bissextos) e meses de 30 dias, virada do ciclo às 00:00 no fuso de negócio, dia inválido |
| `UsageAccumulatorTest` | agregação por hora/rota, classificação de status, tráfego cobrável só de 2xx/3xx, concorrência com drenagem, devolução após falha |
| `CidrClientAddressRulesTest` | CIDR IPv4/IPv6, faixas amplas recusadas |
| `PublicApiIT` | HTTP real + PostgreSQL/RLS: segredo uma vez, cadeias separadas, recusas no log, revogação/rotação, isolamento por credor, escopos, If-Match, 429, IPs, escalada, versões, erros RFC 9457, gatilhos |
| `PublicApiUsageIT` | medição e lote (4xx sem tráfego cobrável), trilha por requisição, importação idempotente por token e atribuição assíncrona, cursor; condições no contrato versionadas, append-only e não retroativas; extrato por ciclo com preço do contrato, MB por token, 4xx não cobrado, revisão com motivo, imutável; job diário fechando fevereiro com dia 31 em 28/02 e virada à meia-noite |
| `PublicApiContractIT` | contrato gerado = arquivo versionado |
| `ArchitectureTest`, `ModularityTest` | camadas e fronteiras do módulo |
