# Desenho — painéis e relatórios (módulo `analytics`)

Escopo: M08, F11 (painel operacional básico) e F18 (relatórios de recuperação e produtividade). Schema `analytics`, migrations V1600–V1699 (+ uma repetível). Definições de negócio de cada indicador — fórmula e denominador — em [`docs/negocio/indicadores.md`](../../negocio/indicadores.md), que é o contrato com a operação.

## 1. Princípios

1. **Lê o banco por views próprias**, não por casos de uso de outros módulos (grafo: `analytics → organization`; nenhuma outra dependência Java). As views são o contrato de leitura e quebram um teste, não o painel, quando o produtor muda.
2. **Isolamento por tenant inalterado**: toda view é `security_invoker` (roda com o RLS de quem consulta — `app_runtime` com `app.tenant_id` da transação). Nada de materialized view do PostgreSQL (ver §4).
3. **Denominador explícito** em todo indicador (`Ratio` = valor, numerador, denominador, motivo de indisponibilidade).
4. **Recuperado = pago confirmado, líquido de estornos**; acordo não pago nunca entra. Fonte: recebimentos do `settlement` (a mesma base do demonstrativo ao credor), garantindo que painel e prestação de contas batem.
5. **Reutiliza os fatos do incentive** (produtividade) em vez de recalcular a partir de eventos.

## 2. Mapa

| Peça | Onde |
| --- | --- |
| Funções auxiliares e snapshots | `db/migration/analytics/V1600__analytics_snapshots.sql` |
| Views de contrato + funções de reconstrução | `db/migration/analytics/R__analytics_contract.sql` (repetível) |
| Casos de uso | `application/port/in/AnalyticsQueries`, `AnalyticsExportUseCases`, `AnalyticsRefreshUseCases` |
| Fórmulas puras | `domain/service/Indicators`, `SpreadsheetSafety`; filtros `domain/model/ReportFilter`, `Dimension`, `Ratio` |
| Escopo | `application/service/AnalyticsAccess` (credores via `AccessScope`; pessoas com a regra do incentive) |
| Leitura | `infrastructure/persistence/JdbcAnalyticsReader` (+ `SqlWhere`), `JdbcSnapshotStore` |
| Atualização | `AnalyticsRefreshService` + `AnalyticsJobs` (ShedLock `analytics.refresh`) |
| Exportação | `AnalyticsExportService` + `ReportTable` (formato longo) |
| REST | `web/AnalyticsController` — `/api/v1/analytics/...` |
| Frontend | `frontend/src/app/features/analytics` (rota `/paineis`) e resumo na página inicial |

## 3. Contrato de leitura (views `analytics.src_*`)

### 3.1 Colunas usadas por módulo

| View | Origem | Colunas |
| --- | --- | --- |
| `src_creditor` | `organization.creditor` | `tenant_id`, `id`, `trade_name` |
| `src_portfolio` | `organization.portfolio` | `tenant_id`, `id`, `creditor_id`, `code`, `name` |
| `src_user` | `identity.app_user` | `tenant_id`, `id`, `name` (nunca e-mail, credenciais ou sessões) |
| `src_team`, `src_team_member` | `identity.team`, `identity.team_member` | `id`, `name`, `supervisor_id`, `active`; `team_id`, `user_id` |
| `src_title` | `debt.debt_title` | `creditor_id`, `portfolio_id`, `due_date`, `status`, `open_balance` |
| `src_case` | `collection.collection_case` | `creditor_id`, `portfolio_id`, `status`, `eligible`, `open_balance`, `eligible_balance`, `oldest_due_date`, `owner_type`, `queue_id`, `assignee_user_id`, `assignee_team_id` |
| `src_dispute` | `collection.dispute` | `dispute_id`, `creditor_id`, `portfolio_id`, `type`, `origin`, `status`, `outcome`, `opened_at`, `resolved_at`, `response_due_date`, `internal_due_date`, `assignee_id`, `awaiting_creditor_since`, `adjustment_status` (nunca descrição, parecer ou devedor) |
| `src_dispute_creditor_request` | `collection.dispute_creditor_request` + `dispute` | `request_id`, `dispute_id`, `creditor_id`, `portfolio_id`, `type`, `origin`, `status`, `requested_due_date`, `overdue`, `sent_at`, `answered_at` (nunca destinatários nem texto) |
| `src_interaction` | `collection.interaction` | `case_id`, `creditor_id`, `portfolio_id`, `channel`, `direction`, `effective_contact`, `operator_id`, `actor_type`, `started_at` |
| `src_queue` | `collection.queue` | `id`, `code`, `name`, `creditor_id`, `active` |
| `src_proposal` | `negotiation.proposal` | `creditor_id`, `portfolio_id`, `team_id`, `channel`, `actor_type`, `actor_id`, `status`, `created_at` |
| `src_agreement` | `negotiation.agreement` | `creditor_id`, `portfolio_id`, `case_id`, `team_id`, `channel`, `origin`, `closer_type`, `closer_id`, `status`, `formalized_at`, `ended_at`, `renegotiation_number`, `total_amount`, `negotiated_amount`, `discount_amount`, `installment_count` |
| `src_installment` | `negotiation.agreement_installment` | `agreement_id`, `number`, `due_date`, `amount`, `paid_amount`, `status`, `paid_at` |
| `src_receipt` | `settlement.receipt` | `kind`, `payment_id`, `agreement_id`, `creditor_id`, `portfolio_id`, `amount`, `days_past_due`, `paid_on`, `competence_date` |
| `src_payment_instruction` | `payment.payment_instruction` | `creditor_id`, `type`, `status`, `failure_code`, `created_at`, `updated_at` |
| `src_divergence` | `payment.divergence` | `creditor_id`, `type`, `status`, `amount`, `created_at`, `resolved_at` |
| `src_statement_import`, `src_statement_line` | `payment.statement_import`, `payment.statement_line` | `creditor_id`, `status`, `failure_code`, `created_at`, `started_at`, `finished_at`, `total_lines`; `import_id`, `status`, `created_at` |
| `src_message` | `communication.message` (+ `conversation.creditor_id`) | `channel`, `direction`, `status`, `failure_code`, `queued_at`, `failed_at`, `created_at` |
| `src_call` | `telephony.phone_call` | `creditor_id`, `status`, `failure_code`, `created_at` |
| `src_import_batch` | `ingestion.import_batch` | `creditor_id`, `portfolio_id`, `source`, `mode`, `status`, `created_at`, `started_at`, `finished_at`, `rows_read`, `rows_with_error` |
| `src_processing_job` | `shared.processing_job` (sem RLS: filtrada por `shared.current_tenant_id()` na própria view) | `tenant_id`, `type`, `status`, `attempts`, `created_at`, `finished_at` |
| `src_business_event` | `shared.business_event`, só `conversation.sla_breached` | `event_type`, `occurred_at`, `creditor_id`, `portfolio_id`, `channel`, `attributes->>'owner'` |
| `src_performance_fact` | `incentive.performance_fact` | `fact_date`, `user_id`, `creditor_id`, `portfolio_id`, `channel`, `attempts`, `effective_contacts`, `answered_calls`, `talk_seconds` |
| `src_worked_case` | `incentive.worked_case_day` | `fact_date`, `user_id`, `case_id`, `creditor_id`, `portfolio_id`, `channel` |
| `src_logged_time` | `incentive.logged_time_daily` | `fact_date`, `user_id`, `seconds` |
| `src_case_snapshot` | `incentive.case_snapshot` | `case_id`, `effective_at`, `creditor_id`, `portfolio_id`, `assignee_id`, `team_id`, `eligible_balance` |
| `src_commission_statement` | `incentive.commission_statement` | `period_start`, `beneficiary_id`, `status`, `payable_amount` |

Nenhuma view expõe nome, documento ou contato do devedor (conferido em teste).

### 3.2 Como o contrato protege a produção

- **Colunas**: o PostgreSQL recusa `DROP COLUMN`/`ALTER TYPE` de coluna usada por view. A migration do produtor falha e a suíte inteira com ela (`AnalyticsIsolationAndExportIT#contractViewsRunAsInvokerAndProtectSourceColumns` demonstra com `settlement.receipt.competence_date`). Para mudar uma coluna do contrato, a migration do produtor faz `drop view analytics.src_x` e o **mesmo pull request** altera `R__analytics_contract.sql` — a mudança de checksum reexecuta o arquivo, que recria tudo.
- **Domínios de valores** de que as fórmulas dependem (status de recebimento, título, caso, acordo, parcela, proposta, instrução, divergência, extrato, mensagem, chamada, lote, job, extrato de comissão e **todas as origens de acordo** mapeadas em `analytics.origin_group`) são conferidos por `producerDomainsStillContainTheValuesTheFormulasUse`, lendo os `check` do catálogo. Origem nova sem mapeamento cai em `OTHER` e quebra o teste.
- **Views e invocador**: o teste exige `security_invoker=true` em toda view do schema, a lista exata de views e nenhuma materialized view.

### 3.3 Por que migration repetível

As views leem tabelas de módulos com faixa de versão maior (incentive, V18xx). O Flyway executa as repetíveis depois de todas as versionadas, então o contrato sempre enxerga o schema completo — sem depender da ordem das faixas nem obrigar o analytics a ser o último módulo numerado.

## 4. Snapshots (em vez de materialized views)

| Tabela | Conteúdo | Grão |
| --- | --- | --- |
| `recovery_daily` | recebido bruto, estornado e quantidade de recebimentos | dia do fato × credor × carteira × negociador humano (closer) × equipe × canal × origem × faixa de atraso |
| `agreement_cohort` | acordo com valor acordado, pago líquido (todos os recebimentos), 1ª parcela, quebra, renegociação, 1º recebimento | acordo |
| `portfolio_position` | títulos em cobrança, valor e vencido | credor × carteira × faixa |
| `case_position` | casos não encerrados, saldo em aberto e elegível | credor × carteira × faixa × estado × elegível × dono × fila |
| `refresh_state` | carimbo "dados até" (`data_until` = instante em que a leitura do conjunto começou), duração e linhas | assessoria × conjunto |

Materialized view não serve: não aceita RLS (guardaria todas as assessorias num objeto sem isolamento), o `REFRESH` roda como dono e, com `FORCE ROW LEVEL SECURITY` nas origens, não enxergaria linha alguma sem `app.tenant_id`, e só atualiza tudo de uma vez. Os snapshots são tabelas comuns com RLS forçado, reconstruídas **por assessoria** pelas funções `analytics.refresh_*` (executam como o chamador; exigem assessoria na transação), com `delete + insert` numa única transação para os quatro conjuntos (coerentes entre si; leitores veem a versão anterior até o commit). Atualizações simultâneas da mesma assessoria são serializadas por `pg_advisory_xact_lock`; o tempo limite das instruções é ampliado só nessa transação (`set_config('statement_timeout', …, true)`, padrão 10 min). O `app_runtime` não tem `UPDATE` nos snapshots.

Leituras **ao vivo** (sem snapshot): interações, propostas, fatos do incentive, confiabilidade. Os snapshots cobrem o que exige junção cara (recebimentos × acordos, posição de títulos e casos).

Job: `AnalyticsJobs#refreshAll` — `@Scheduled(fixedDelay = app.analytics.refresh-interval, padrão PT15M)` + `@SchedulerLock(name = "analytics.refresh")`, assessoria por assessoria via `TenantExecution`, falha isolada. Pedido manual: `POST /api/v1/analytics/refresh` (intervalo mínimo `app.analytics.manual-refresh-min-interval`, padrão 2 min; 409 antes disso).

**Escala**: a reconstrução completa por assessoria é proporcional ao histórico de recebimentos e acordos. Se passar de alguns minutos, o próximo passo é a reconstrução incremental por janela de datas (recebimentos dos últimos N dias + acordos alterados), mantendo o mesmo contrato de tabelas.

## 5. Escopo e permissões

| Endpoint | Permissão (`@PreAuthorize`) | Regras adicionais |
| --- | --- | --- |
| `GET /analytics/portfolio` | `DASHBOARD_VIEW` ou `REPORT_VIEW` | escopo de credores |
| `GET /analytics/recovery`, `/conversion` | `REPORT_VIEW` | agrupar/filtrar por operador ou equipe exige escopo de pessoas |
| `GET /analytics/productivity` | `PERFORMANCE_VIEW_OWN/TEAM/ALL` ou gestão/aprovação de extratos de comissão (só `REPORT_VIEW` não basta: dados por pessoa) | sempre por pessoa |
| `GET /analytics/reliability` | `DASHBOARD_VIEW` ou `REPORT_VIEW` | processamentos (sem credor) só com todos os credores |
| `GET /analytics/freshness`, `POST /analytics/refresh` | leitura de qualquer painel | — |
| `GET /analytics/{report}/export?format=CSV|XLSX` | `REPORT_EXPORT` + leitura do relatório | `DATA_EXPORTED` com relatório, filtros, linhas e SHA-256 |

- **Credores**: `AccessScope` reduz o filtro pedido ao escopo (pedido inteiramente fora = nenhum dado, sem revelar existência). Registros sem credor só para quem vê todos.
- **Pessoas** (mesma regra do incentive, `IncentiveAccess`): `PERFORMANCE_VIEW_ALL`/`COMMISSION_STATEMENT_MANAGE`/`APPROVE` = todos; `PERFORMANCE_VIEW_TEAM` = a pessoa e os membros das equipes ativas que supervisiona; `PERFORMANCE_VIEW_OWN` = só a pessoa. Filtro por pessoa ou equipe fora do escopo → 403 + `SCOPE_DENIED`; dado por pessoa sem nenhuma dessas permissões → 403 + `ACCESS_DENIED`. Com escopo restrito, os totais do relatório por pessoa são os das pessoas visíveis (`meta.peopleRestricted = true`).
- Permissões usadas já existiam (`DASHBOARD_VIEW`, `REPORT_VIEW`, `REPORT_EXPORT`, `PERFORMANCE_VIEW_*`) e já estão nos perfis padrão (supervisor: painéis e relatórios; gestor e financeiro: também exportação; auditor: leitura; operador: só o próprio desempenho). Não houve migration de permissões.

## 6. API

Filtros comuns por query string (listas repetidas): `from`, `to` (ISO, obrigatórios, até 400 dias), `creditorId`, `portfolioId`, `operatorId`, `teamId`, `channel` (até 200 valores cada), `groupBy` (lista fixa por relatório — nunca vira nome de coluna). Toda resposta traz `meta` com `dataUntil`, `notApplicableFilters`, `peopleRestricted` e `unavailableMetrics`.

| Relatório | `groupBy` aceito |
| --- | --- |
| recovery | `CREDITOR`, `PORTFOLIO`, `OPERATOR`, `TEAM`, `CHANNEL`, `ORIGIN`, `DELAY_BUCKET`, `DAY`, `WEEK`, `MONTH` |
| conversion | os mesmos, exceto `DELAY_BUCKET` |
| productivity | `OPERATOR`, `TEAM` |
| disputes | — (cortes fixos por motivo, origem, credor e fila por responsável; `channel` não se aplica) |

Exportação: formato longo (`secao;chave;rotulo;indicador;valor;numerador;denominador;observacao`), CSV UTF-8 com BOM e `;`, XLSX com números em célula numérica; texto iniciado por `= + - @`, tabulação ou retorno de carro recebe apóstrofo (`SpreadsheetSafety`); célula de texto nunca é fórmula. O arquivo é gerado na hora e **não é guardado** (reproduzível pelos filtros e pelo "dados até"); limite `app.analytics.export-max-rows` (100 mil linhas); cabeçalhos `Cache-Control: no-store` e `X-Content-SHA256`.

## 7. Frontend

- Rotas sob demanda em `/paineis` (operacional, `recuperacao`, `conversao`, `produtividade`, `contestacoes`, `confiabilidade`), menu "Painéis" filtrado por permissão, e resumo da operação na página inicial carregado com `@defer (on idle)` (não entra no bundle inicial: +7 kB no inicial — navegação, tour e notas de versão —, 786,9 kB para o aviso de 800 kB).
- **Gráficos em SVG/HTML próprios** (`bar-chart`, `column-chart`, `funnel-chart`), sem biblioteca: ECharts modular custaria ~150–300 kB no chunk dos painéis e Chart.js ~70 kB, ambos com canvas (sem DOM acessível por marca); três formas simples (barras horizontais, colunas por período, funil) cabem em ~400 linhas com acessibilidade completa. Revisitar se surgirem dispersão, mapas ou séries densas.
- Acessibilidade (skill de visualização adotada no projeto): `figure` com título e resumo textual (`aria-describedby`), tabela equivalente em todo gráfico ("Ver tabela"), valor escrito na ponta de cada barra, legenda com 2 séries, cada marca focável com rótulo (`role="img"`, `aria-label`) e dica no foco e no ponteiro, nunca só cor. Cores `--app-chart-1/2` (azul/laranja) validadas com o validador da skill (CVD ΔE ≥ 24, contraste ≥ 3:1 no claro e no escuro); grade e eixo recessivos; colunas ≤ 24 px com ponta arredondada de 4 px.
- Cartões de KPI sempre com a base ("R$ 1.100,00 de R$ 4.000,00"); indicador indisponível mostra o motivo.
- Filtros numa barra única acima dos gráficos, **persistidos na URL** (`de`, `ate`, `credor`, `carteira`, `operador`, `equipe`, `canal`, `agrupar`; valores malformados descartados), preservados ao trocar de painel; seletor de pessoas só para quem vê usuários. Recarga mantém o conteúdo anterior com opacidade reduzida (sem salto de layout).
- Carimbo "Dados até …" com "Atualizar agora"; exportação XLSX/CSV no cabeçalho para quem tem `REPORT_EXPORT`.
- Tour próprio por painel (`analytics.operational`, `analytics.recovery`, `analytics.conversion`, `analytics.productivity`, `analytics.disputes`, `analytics.reliability`, até 6 passos cada, com passos nos cartões e no "i"), que substituiu o tour único `analytics.panels`; notas de versão 6 e 12.
- Informação contextual ("i") em cada cartão, gráfico e coluna de tabela, com os textos do catálogo `analytics.*` (ux.md §8.6).

### 7.1 Drill-down: do número à lista (revisão de produto 11/2026, J6)

O número vira link para a lista que é a **base exata** do indicador, com os recortes do painel, pelos parâmetros de URL
que as listas já leem (ux.md §16). Peças: `analytics/data/drill-down.ts` (destinos e regras, funções puras com teste) e
`app-drill-link` (link na cor do texto, sublinhado pontilhado, destino dito ao leitor de tela). Regras:

1. **Só onde a lista representa a base.** As listas filtram um credor e uma carteira: com mais de um no painel, o número
   fica como texto. Recorte que a lista não tem (operador, equipe, canal no razão; carteira na fila de contestações)
   → sem link. Nada de lista "aproximada".
2. **Permissão:** o link só aparece se o perfil abre a lista (`canOpen` do menu, a mesma regra da rota); sem ela, o
   número continua como texto (nada exibido para depois negar).
3. **"Agora" × período:** indicador de posição atual (contestações em aberto) só tem link quando o período do painel
   chega a hoje; a lista é viva, o painel vale a partir de "Dados até…" (consolidação a cada 15 min) — pequenas
   diferenças entre o número e a lista são esperadas nesse intervalo.

| Painel | Indicador / linha | Lista e parâmetros |
| --- | --- | --- |
| Operacional e início | Casos em aberto; Fila pendente; Casos por estado (linha, casos, elegíveis); Fila pendente por fila | `/cobranca/casos` com `credor`, `carteira`, `situacao`, `elegivel=true`, `atendimento=QUEUE`, `fila` |
| Operacional e início | Recuperado no período / em 30 dias | `/prestacao-de-contas/razao` com `credor`, `carteira`, `pago-de`, `pago-ate` |
| Recuperação | Recuperado líquido, Estornos; "Detalhe por" credor, carteira, mês, semana e dia (linha, recebido, estornado, recuperado, recebimentos) e as barras/colunas | Razão com o período (da linha, recortado pelo do painel), `credor`/`carteira` da linha e `tipo=PAYMENT`/`REVERSAL` |
| Contestações | Em aberto agora; "Em aberto" por credor; fila sem responsável (já existia) | `/cobranca/contestacoes` (situação padrão: abertas e em análise) com `credor`; `responsavel=supervisao` |
| Confiabilidade | Divergências em aberto; abertas no período e por tipo | `/pagamentos/divergencias` com `credor`, `situacao=todas`, `aberta-de`, `aberta-ate`, `tipo` |

**Sem drill-down (e por quê)** — registrado para a próxima rodada, sem lista inventada:

| Indicador | Motivo |
| --- | --- |
| Saldo elegível (cartão e coluna) | É a soma do saldo elegível **por título** dos casos abertos (um caso inelegível pode ter títulos elegíveis e vice-versa); o filtro `elegivel` da lista de casos é a elegibilidade do **caso inteiro** — outra base, então não há link |
| Valor da carteira, saldo vencido, carteira por credor e por faixa de atraso | São de **títulos** em cobrança (`OPEN` + `SUSPENDED`, saldo > 0); a lista de títulos filtra uma situação só e não tem a faixa 31–90 nem "vencidos" (lista da frente D: acrescentar "Em cobrança" e as faixas do painel) |
| Taxa de recuperação; tempo até o 1º recebimento; comissão por real | O denominador (saldo elegível no início, retratos do incentive), a amostra de acordos e o custo não têm lista |
| Recuperação por operador, equipe, canal, origem, faixa de atraso; qualquer recuperação com filtro de pessoa ou canal | O razão não tem essas dimensões (vêm do acordo) |
| Conversão (contatos, propostas, acordos, 1ª parcela, valores, quebras, renegociações) | Interações não têm lista; propostas e acordos não filtram período (criação/formalização) nem carteira (listas das frentes E e D) |
| Produtividade (todas as colunas) | Os fatos do incentive (casos trabalhados, tentativas, TMA, horas) não têm lista; acordos por pessoa precisariam do filtro "fechado por" nos acordos |
| Contestações abertas, resolvidas, no prazo, procedência, retorno do credor, por motivo e origem | A fila não filtra período de abertura/resolução nem carteira; "aguardando o credor" do painel conta pedidos, a fila conta contestações |
| Confiabilidade: instruções, chamadas, mensagens, extratos com falha, linhas duplicadas, SLA, processamentos, importações | Instruções e extratos não filtram período de criação; chamadas filtram resultado e data de fila (o painel usa a situação e a criação); mensagens, SLA e processamentos não têm lista; lotes são da frente D |

## 8. Logs e métricas

Sem eventos de negócio novos (o módulo só lê). Log técnico: ciclo de atualização (`INFO`, assessorias, falhas, linhas, duração), falha por assessoria (`ERROR`, alarme por `analytics.refresh.failures`), consulta lenta (`WARN` com relatório, agrupamento e duração). Exportação no log de acesso.

| Métrica | Uso |
| --- | --- |
| `analytics.query.duration{report,outcome}` (p50/p95/p99) | latência das consultas dos painéis |
| `analytics.queries.slow{report}` | consultas acima de `app.analytics.slow-query-threshold` (padrão 2 s) |
| `analytics.refresh.duration{dataset,outcome}`, `analytics.refresh.rows{dataset}` | reconstrução de cada snapshot |
| `analytics.refresh.cycle.duration{trigger}`, `analytics.refresh.tenants{trigger,outcome}` | ciclo agendado |
| `analytics.refresh.failures{trigger}` | falhas de atualização (alarme) |
| `analytics.exports{report,format}`, `analytics.export.rows{report}` | exportações |

## 9. Testes

- Unitários: `IndicatorsTest` (fórmulas, arredondamento, indisponível ≠ zero, neutralização de fórmulas, validação de filtros e dimensões), `AnalyticsRefreshServiceTest` (ordem: bloqueio → carimbo; intervalo mínimo), `AnalyticsExportFormatTest` (CSV/XLSX), `AnalyticsControllerTest` (vínculo dos filtros e cabeçalhos).
- Integração (PostgreSQL real, papéis de produção): `AnalyticsIndicatorsIT` — cada indicador com dados conhecidos e conta à mão nos comentários (carteira, recuperação líquida e taxa com denominador, tempo até recebimento, funil, safra e quebras, produtividade por pessoa/equipe e escopo, confiabilidade, permissões); `AnalyticsIsolationAndExportIT` — RLS em todas as views e snapshots (outra assessoria, sem assessoria, gravação cruzada, função sem assessoria, sem `UPDATE`), contrato (invocador, lista de views, sem dado pessoal, coluna protegida, domínios dos produtores) e exportação (BOM, neutralização, XLSX sem fórmula, `DATA_EXPORTED` com SHA-256, permissões).
- Frontend: filtros na URL (ida e volta, descarte de valores inválidos), formatação, gráficos (rótulos acessíveis, tabela, eixo), painéis (parâmetros da API, denominadores, escopo do operador e do supervisor, exportação por permissão), drill-down (`drill-down.spec.ts`: parâmetros de cada destino, um credor/carteira, pessoa e canal sem link, período da linha temporal, permissão; `panels.spec.ts`: links do painel de recuperação e de contestações; `drill-targets.spec.ts`: as listas de destino repassam os parâmetros ao servidor).

## 10. Pendências

- Custos de canal (tarifas por mensagem/minuto) não existem no produto: o custo por real recuperado considera só a comissão variável dos negociadores.
- Taxa de recuperação depende dos retratos de saldo do incentive (`case_snapshot`), que começam quando o incentive passou a consumir os eventos de caso; períodos anteriores ficam sem denominador.
- Mensagens duplicadas do provedor só existem como métrica técnica.
- Origens `portal` e `assistant` dependem dos módulos em desenvolvimento paralelo: entram automaticamente pela origem do acordo; se criarem novas origens, o teste de domínios aponta.
- Reconstrução incremental quando o volume exigir (§4).

## Saúde das políticas (decisão 1087)

`GET /api/v1/analytics/policy-health?creditorId=&from=&to=&valueBand=` (`POLICY_MANAGE` ou `REPORT_VIEW`, credor no
escopo; `PolicyHealthQueries`, `PolicyHealthService`). Período padrão de 180 dias (máximo 400), sobre a safra
(`agreement_cohort.formalized_on`).

| Fonte | Uso |
| --- | --- |
| `agreement_cohort` ⋈ `src_policy_agreement` | acordos por carteira × `delay_bucket(max_days_overdue)`: maduros (≥ 60 dias em `asOf`), quebrados até 60 dias, acordado, pago líquido, uso do desconto máximo (`envelope_usage`, %) |
| `src_portal_simulation` | evento `portal.simulated`: consultas e consultas com `offers = 0`, por faixa (`daysOverdue`) |
| `src_portal_service_request` | pedidos `CANNOT_AFFORD` por carteira (faixa nula: "todas as faixas") |

As três views novas excluem devedor com `compliance.vulnerability_marker` **ativo** dentro da própria view (o marcador
não é exposto) e não têm dado pessoal. Vale o estado **atual** do marcador (registrar ou retirar muda os números de
períodos passados). Por diferença com o relatório de conversão daria para inferir a **existência** de um marcador num
recorte mínimo — sem a categoria —, informação que os perfis com acesso já veem pelo selo na ficha (`DEBTOR_VIEW`/
`CASE_VIEW`); um perfil personalizado com `REPORT_VIEW` e sem visão de caso ou devedor exigiria mínimo de células. O uso do desconto só conta onde havia desconto a conceder (`agreement.max_discount_amount` > 0, decisão 1091; nulo nos
acordos anteriores ao dado conta como antes). Faixas de valor do acordo: `V0_1K`, `V1K_5K`, `V5K_20K`, `V20K_PLUS` (não se
aplicam ao portal). Regras de sugestão em `PolicyHealthRules` (domínio, puro; limites na decisão 1087), comparando o
recorte com o resto do credor; sem amostra madura no resto, vale só o limite absoluto. Testes: `PolicyHealthRulesTest`
e `PolicyHealthIT`. Tela: `/negociacao/politicas/saude` (`features/negotiation/policies/policy-health.ts`).
