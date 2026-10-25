# Desenho — módulo `scoring` (score de propensão à recuperação e priorização)

Escopo: F23 — score de propensão "avaliado com métricas e validação", usado **somente para priorização operacional**.
Regra de negócio, ética e LGPD: [docs/negocio/score-propensao.md](../../negocio/score-propensao.md).
Schema `scoring`, migrations V1400–V1499 (`V1400` tabelas). Dependências declaradas: `shared`,
`collection :: usecases` (posição dos casos, varredura `CaseUseCases#listActiveAfter`), `debt :: usecases` (carteira do
título de um pagamento sem carteira). O grafo também permite `payment` e `organization`, não usados: os fatos de
pagamento chegam por eventos. Nenhum módulo depende do `scoring`; os consumidores recebem o score por eventos.

## 1. Visão geral

```
 eventos (InteractionRecorded, MessageStatusChanged, CallCompleted, PromiseStatusChanged,
          AgreementStatusChanged, PaymentConfirmed, PaymentReversed, DebtorAnonymized)
        │  FactRecorder (só com base legal registrada)
        ▼
 scoring.case_fact ──┐          CaseUseCases (posição do caso)
                     ▼                 │
              FeatureCalculator ◄──────┘   (sem vazamento: só fatos conhecidos até o instante)
                     │
         campeão (scorecard ou regressão) ── desafiante em sombra nos snapshots
                     │
   scoring.case_score (vigente + explicação)   scoring.feature_snapshot (por data de referência)
                     │                                   │  desfecho apurado após N dias
          CaseScoreChanged ──► collection (fila HIGHEST_SCORE, faixa de score)
                           └─► campaign (filtro de score dos segmentos)
                                                         │
                       treino (regressão L2), monitoramento (PSI, AUC realizada),
                       recuperado × esperado ──ExpectedRecoveryMeasured──► incentive
```

## 2. Features

Catálogo fechado em `domain.model.Feature` (17 features, lista e o que nunca entra em `score-propensao.md` §4); nada
configurável por tenant. Cálculo puro em `FeatureCalculator#compute(caso, fatos, instante, taxaDaCarteira)`, o mesmo na
escoragem e nos snapshots de treino:

| Grupo | Features | Fonte |
| --- | --- | --- |
| Posição | `DAYS_OVERDUE` (teto 3650), `OPEN_BALANCE`, `OPEN_TITLES`, `CASE_AGE_DAYS`, `ACTIVE_AGREEMENT`, `PENDING_PROMISE` (estágio ou estágio guardado) | `CaseUseCases.CaseView` |
| Contato (caso) | `CONTACT_ATTEMPTS_90D`, `EFFECTIVE_CONTACTS_90D`, `DAYS_SINCE_EFFECTIVE_CONTACT` | `InteractionRecorded`; última data do caso |
| Resposta | `RESPONSIVE_CHANNELS_90D` (canais distintos com resposta do devedor: interação de entrada, mensagem recebida, ligação atendida; do caso ou sem caso no mesmo credor) | `InteractionRecorded`, `MessageStatusChanged`, `CallCompleted` |
| Compromissos | `PROMISES_KEPT`, `PROMISES_BROKEN` (situação mais recente de cada promessa do caso) | `PromiseStatusChanged` |
| Relacionamento com o credor | `PRIOR_AGREEMENTS`, `BROKEN_AGREEMENTS` | `AgreementStatusChanged` |
| Pagamentos (carteira do caso, líquidos de estorno) | `PARTIAL_PAYMENTS_365D`, `DAYS_SINCE_LAST_PAYMENT` | `PaymentConfirmed`, `PaymentReversed` |
| Carteira | `PORTFOLIO_RECOVERY_RATE` (taxa de pagamento das safras apuradas dos últimos 180 dias, ≥ 30 observações; senão a da assessoria) | snapshots |

`scoring.case_fact` guarda `occurred_at` (quando o fato ficou conhecido — corte das features) e `effective_at` (data do
pagamento — desfecho). Fatos são idempotentes por (evento, tipo). Pagamento sem carteira usa a carteira do primeiro
título alocado (`DebtTitleUseCases#get`); sem nenhuma, é ignorado (`scoring.facts.ignored{reason=no_portfolio}`).

## 3. Modelos versionados

`scoring.model` (definição, cortes de faixa, fração paga típica, período e métricas de treino/validação imutáveis —
gatilho `model_immutable`). Ciclo:

```
 scorecard criado / treino aprovado → CHALLENGER ──promoção manual──► CHAMPION ──nova promoção──► RETIRED
                                         ├── rejeição manual ──► REJECTED
                                         └── desafiante mais novo ──► SUPERSEDED
 treino reprovado → FAILED_VALIDATION (nunca promovível)
```

Índices únicos parciais: no máximo um `CHAMPION` e um `CHALLENGER` por assessoria.

* **Scorecard por pontos** (`ScorecardDefinition`): pontos base + pontos por faixa `[de, até)` de cada feature (+ pontos
  para ausente). Escala PDO: `logit = ln(chanceÂncora) + (pontos − pontosÂncora)·ln2/PDO`. Padrão
  (`ScorecardDefinition.standard()`, pesos em `score-propensao.md` §5): 500 pontos = 1:9 (10%), 20 pontos dobram a
  chance. Semeado como campeão v1 no registro da base legal (ou na primeira escoragem). Scorecards configurados entram
  como desafiantes com retroteste nos últimos 180 dias de snapshots apurados.
* **Regressão logística L2** (`LogisticRegressionTrainer`): transformação por feature (`ln(1+x)`, recência
  `1/(1+dias/30)`, identidade com imputação pela média), padronização com estatísticas do treino, Newton-Raphson/IRLS
  com busca em linha, penalidade `λ‖β‖²/2` sem o intercepto, Cholesky. Features sem variação ficam fora.
  **Implementação própria em Java** (decisão 145): o ajuste tem ~150 linhas, é determinístico e verificado por testes
  de resposta conhecida; uma biblioteca (Smile — GPL/comercial nas versões recentes; Tribuo — Apache 2.0, mas traz um
  framework inteiro; Commons Math 3 — sem logística regularizada pronta e em manutenção) não reduziria risco.
* Alvo: **pagamento confirmado (líquido de estorno) do devedor na carteira do caso em até N dias** após a data de
  referência (`outcomeDays`, padrão 30, por assessoria; fixado em cada versão de modelo).

### 3.1 Treino e validação (`TrainingService`)

Pedido manual (`POST /models/training`, `SCORING_MANAGE`) ou mensal automático (`auto-train`); job
`scoring.training` (um por vez). Dados: snapshots apurados nos últimos `trainingWindowDays` (padrão 365), até
200 mil observações.

1. **Critério mínimo** (`TrainingPolicy`): ≥ `minCases` casos distintos com desfecho (padrão 500) e ≥ `minPositives`
   casos distintos com pagamento (padrão 50). Abaixo: execução `INSUFFICIENT_DATA`, nenhum modelo, assessoria segue no
   campeão (scorecard).
2. **Divisão temporal**: as datas de referência mais recentes, somando ~`validationShare` (30%) das observações, são a
   validação; o treino usa só datas até `outcomeDays` antes do início da validação (embargo — nenhum desfecho do treino
   cai no período validado). Cada partição precisa de ≥ `minPartitionPositives` (10) positivos e negativos.
3. Métricas na validação (`ModelMetrics`): AUC (Mann-Whitney com postos médios), Gini = 2·AUC − 1, KS, Brier,
   probabilidade média × taxa observada, **calibração por faixa** e **lift por decil**; as mesmas métricas do treino e
   do **campeão nas mesmas observações** (comparação campeão × desafiante).
4. Aprovação técnica: convergência e AUC de validação ≥ `minAuc` (0,60) → `CHALLENGER` (o anterior vira
   `SUPERSEDED`); senão `FAILED_VALIDATION` com os motivos. **Nunca promove.**
5. Distribuição de referência (decis do score e de cada feature no treino) gravada para o PSI.

### 3.2 Promoção manual (`ModelGovernanceService`)

`POST /models/{id}/promote` e `/reject` (`SCORING_MANAGE`, todos os credores), com justificativa (10–1000 caracteres).
Quem pediu o treino ou criou o scorecard **não promove** (`409 scoring.model.self_promotion`). Registra
`scoring.model_decision` (append-only) com a comparação vista por quem decidiu (validação do desafiante, campeão nas
mesmas observações e o desempenho realizado do último monitoramento), auditoria (`scoring_model.promoted`/`retired`/
`rejected`) e `score.model_promoted`/`score.model_rejected`. A troca é imediata para cálculos novos; a escoragem em
andamento termina com o campeão fixado na execução.

## 4. Escoragem

* **Lote diário** (`ScoringJobs.daily`, 02:30, ShedLock): uma execução `scoring.run` por data de referência (índice único;
  manual `POST /runs` devolve a do dia). Campeão, desafiante e taxas por carteira fixados na execução. Páginas de 200
  casos ativos (`CaseUseCases#listActiveAfter`, cursor por id) em jobs encadeados `scoring.batch_page` (fila
  `shared.processing_job` com `SKIP LOCKED` → distribuída entre instâncias). Casos `LEGAL`, encerrados e de devedor
  anonimizado não são escorados. Lock consultivo por caso evita colisão com cálculos sob demanda; um cálculo mais recente
  que a referência do lote não é sobrescrito (o lote só grava o snapshot).
* **Snapshot**: em dias múltiplos de `snapshotIntervalDays` (padrão 7, `epochDay mod N = 0`, determinístico) a execução
  grava `scoring.feature_snapshot` (features, campeão, desafiante em sombra, saldo, responsável, `outcome_due_at`).
  Ao concluir, enfileira `scoring.monitoring`.
* **Sob demanda**: `POST /cases/{id}/recalculate` (`SCORING_VIEW` + `CASE_WORK`); **por evento**: contato efetivo,
  promessa, acordo, pagamento e estorno enfileiram `scoring.rescore_case` com atraso de 5 min e deduplicação por caso.
* **Faixas** A–E pelos cortes da versão do modelo (padrão A ≥ 400 > B ≥ 250 > C ≥ 150 > D ≥ 75 > E; score = probabilidade
  × 1000).
* **Publicação**: `CaseScoreChanged` no primeiro cálculo, na mudança de faixa ou com variação ≥ `publishMinDelta` (10
  pontos) — evita ruído de eventos. Mudança de faixa é auditada (`case_score.band_changed`).

## 5. Monitoramento

`MonitoringService` após cada snapshot → `scoring.monitoring_report` (append-only):

* **PSI** do score e de cada feature contra a referência do campeão (treino; para scorecard, a primeira população que
  ele escorou), decis + faixa de ausentes, ε = 1e-4. Atenção ≥ `psiWarning` (0,10), alerta ≥ `psiAlert` (0,25).
* **Desempenho realizado por safra** (últimos 180 dias apurados): AUC realizada do campeão e do desafiante (sombra),
  probabilidade média × taxa observada e taxa de pagamento por faixa. AUC realizada abaixo da mínima → alerta.
* Situação `STABLE`/`WARNING`/`ALERT`; fora de `STABLE`: `score.drift_detected`, WARN técnico e
  `scoring.monitoring.alerts{level,kind}` (alarme sugerido: qualquer `ALERT`).

**Apuração dos desfechos** (`OutcomeService`, 04:15): snapshots com janela encerrada há ≥ 5 dias (carência para
confirmações tardias) recebem `outcome_paid`/`outcome_amount` (pagamentos com `effective_at` em (referência, fim] sem
estorno conhecido), em lotes `FOR UPDATE SKIP LOCKED`.

## 6. Consumidores do score

| Consumidor | Reação | Ordem |
| --- | --- | --- |
| `collection.case-score` | `CollectionCase#applyScore` (coluna `score` existente + `score_calculated_at`, `V0602`) e reavalia só a fila: o critério de faixa de score pode reencaminhar o caso (mudança de dono registrada no histórico). O "próximo caso" da fila `HIGHEST_SCORE` já ordenava por `c.score desc nulls last` | cálculo mais antigo que o aplicado é ignorado |
| `campaign.case-score` | `AudienceStore#applyScore` (projeção `audience_case`, `score_calculated_at` em `V1101`); casos fora da projeção são projetados antes. Depois do primeiro score por evento, o refresh a partir do `CaseView` não altera o score | idem |

Testes: `CollectionScoreOrderingIT` (ordem do próximo caso, critério de faixa, fora de ordem), `ScoringFlowIT`.

## 7. Recuperado × esperado (incentive)

Quando todos os snapshots de uma execução estão apurados, `OutcomeService#measureCohorts` agrega por credor × carteira ×
responsável no momento do cálculo × modelo: casos, pagantes esperados (Σp), pagantes realizados, **esperado** =
Σ(p × saldo) × fração paga típica do modelo (média de pago ÷ saldo dos pagantes no treino; padrão 0,30) e **realizado**
(valor confirmado na janela). Grava `scoring.cohort_outcome` (id determinístico) e publica
`ExpectedRecoveryMeasured` por grupo. O incentive grava `incentive.expected_recovery_fact` (`V1805`, idempotente pela
safra) e calcula `financial.expectedRecoveryRatio = Σ realizado ÷ Σ esperado` das safras com data no período, pelo
responsável na data do score (deixou de constar em `unavailableMetrics`). Meta de plano por essa métrica continua
recusada (`plan.rules.goal_metric_unavailable`, mensagem atualizada): o motor de comissão não a apura.

`GET /expected-recovery` expõe o mesmo indicador por `NONE`/`CREDITOR`/`PORTFOLIO`/`ASSIGNEE`, filtrado pelo escopo.

## 8. Explicabilidade, LGPD e retenção

* Cada `case_score` guarda features e **contribuições por feature** (log-odds; pontos no scorecard). `GET /cases/{id}`
  devolve todos os fatores, os 5 principais com texto ("Contatos efetivos…: 0 — reduz a propensão de pagamento"), a
  referência das contribuições e o aviso de finalidade e base legal.
* Base legal registrada em `scoring.settings` (`PUT /settings/legal-basis`): `LEGITIMATE_INTEREST` (art. 7º IX, com a
  referência da avaliação de legítimo interesse) ou `CREDIT_PROTECTION` (art. 7º X). **Sem base legal nenhum fato é
  coletado e nenhum score é calculado** (`409 scoring.legal_basis_missing`).
* **Direito de revisão** (art. 20): `POST /cases/{id}/reviews` (`SCORING_MANAGE`) recalcula com os dados atuais e grava
  `scoring.score_review` (append-only: anterior, novo, modelo, origem, referência da solicitação do titular), auditoria
  e `score.reviewed`.
* `DebtorAnonymized`: apaga fatos e scores vigentes do devedor, apaga snapshots não apurados e desvincula o devedor dos
  apurados (estatística preservada, sem identificação).
* Retenção: fatos 1095 dias, snapshots apurados 730 dias (`app.scoring.*`).

## 9. Segurança, escopo e auditoria

* Permissões existentes do catálogo: `SCORING_VIEW` (operador, supervisor, gestor, auditor) e `SCORING_MANAGE` (gestor,
  admin). Sem migration de identity.
* Score do caso: caso lido pelo `collection` com o escopo do ator (404 fora do escopo); recálculo/revisão exigem o credor
  no escopo (403). Modelos, execuções, monitoramento e parâmetros usam dados de todos os credores: exigem escopo completo
  (403 + `SCOPE_DENIED`). Recuperado × esperado: filtrado pelo escopo.
* RLS forçado em todas as tabelas; `model_decision`, `score_review` e `monitoring_report` append-only (gatilho e
  privilégio); `feature_snapshot` só com as colunas do desfecho (e o devedor) atualizáveis; definição de modelo imutável.
* Auditoria: base legal, parâmetros, modelo semeado/treinado/criado/promovido/aposentado/rejeitado, mudança de faixa,
  revisão.

## 10. Eventos, logs e métricas

* Integração publicados: `CaseScoreChanged`, `ExpectedRecoveryMeasured` (`shared.events.scoring`).
* Negócio: `score.calculated`, `score.batch_completed`, `score.training_completed`, `score.model_promoted`,
  `score.model_rejected`, `score.drift_detected`, `score.cohort_measured`, `score.reviewed` — atributos em
  `logs-e-metricas.md` §3.8. Nunca dado pessoal; o valor é o saldo em aberto.
* Métricas `scoring.*` em `logs-e-metricas.md` §5.

## 11. API REST (`/api/v1/scoring`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/cases/{caseId}` · `/history` · `/reviews` | `GET` | `SCORING_VIEW` |
| `/cases/{caseId}/recalculate` | `POST` | `SCORING_VIEW` + `CASE_WORK` |
| `/cases/{caseId}/reviews` | `POST` | `SCORING_MANAGE` |
| `/models` · `/models/{id}` · `/decisions` · `/features` | `GET` | `SCORING_VIEW` |
| `/models/training` (202) · `/models/scorecards` (201) · `/models/{id}/promote|reject` | `POST` | `SCORING_MANAGE` |
| `/settings` | `GET` / `PUT` | `SCORING_VIEW` / `SCORING_MANAGE` |
| `/settings/legal-basis` | `PUT` | `SCORING_MANAGE` |
| `/runs` | `GET` / `POST` (202) | `SCORING_VIEW` / `SCORING_MANAGE` |
| `/runs/{id}` · `/monitoring` · `/expected-recovery` | `GET` | `SCORING_VIEW` |

## 12. Telas (`frontend/src/app/features/scoring`)

Rotas em `/score` (`scoring.routes.ts`), carregadas sob demanda pelo shell; menu "Score de propensão". Toda tela exige
`SCORING_VIEW` (guard e menu, espelho do `@PreAuthorize` das consultas); as ações de gestão aparecem só com
`SCORING_MANAGE`. Modelos, execuções, monitoramento de estabilidade, decisões e alteração de parâmetros exigem todos os
credores: quem tem escopo restrito vê a explicação (`app-scoring-scope-notice`) e a tela não chama a API (evita 403).

| Tela | Rota | Conteúdo | Ações |
| --- | --- | --- | --- |
| Modelos do score | `/score/modelos` | Campeão em uso e desafiante em destaque; versões com tipo, situação e AUC de validação | Pedir treino (confirmação; abre a execução), criar scorecard (diálogo com regras por variável do catálogo, faixas `[de, até)`, pontos para ausente, escala PDO e chance da âncora em %, cópia de um scorecard existente; validação de faixa vazia/sobreposta, variável repetida e limites) |
| Modelo | `/score/modelos/:id` | Validação (AUC, Gini, KS, Brier, prevista × observada), comparação com o campeão nas mesmas observações, calibração por faixa e lift por decil (`app-bar-chart` do analytics), regras do scorecard ou coeficientes da regressão, períodos e autores | Promover (justificativa 10–1000 e a palavra `PROMOVER`) e rejeitar (justificativa), no `DecisionDialog` com a comparação que fica registrada; quem pediu o treino ou criou o scorecard vê a explicação da segregação e só pode rejeitar |
| Execuções | `/score/execucoes`, `/score/execucoes/:id` | Escoragens e treinos com resumo (escorados, ignorados, mudanças de faixa; histórico insuficiente com os mínimos; modelo reprovado com os motivos), ficha com contagens, motivos, links para os modelos e para o monitoramento; atualiza a cada 5 s enquanto `RUNNING` | Escorar agora (confirmação; devolve a do dia) |
| Monitoramento | `/score/monitoramento` | Aba **Estabilidade e calibração**: último retrato (situação, população, PSI do score, alertas em texto), PSI do score por retrato e AUC realizada por safra (`app-column-chart` com marcas de 2 casas), PSI por variável e calibração da última safra apurada, tabela dos relatórios (26 últimos). Aba **Recuperado × esperado** (filtrada pelo escopo; padrão para quem não tem todos os credores): período (até 400 dias, padrão 180), agrupamento por credor/carteira/responsável e credor; indicadores, gráfico esperado × recuperado e tabela | — |
| Decisões e variáveis | `/score/transparencia` | Finalidade e limites (LGPD art. 20), variáveis permitidas com a transformação da regressão, o que nunca é usado, decisões de promoção/rejeição com quem decidiu, justificativa e a comparação vista (`DetailDialog`) | — |
| Parâmetros do score | `/score/parametros` | Base legal (situação, referência, quem registrou) e os parâmetros em três grupos (desfecho e faixas com a chance de cada corte; treino; escoragem e monitoramento), com as regras cruzadas do domínio (pagantes ≤ casos, janela de treino ≥ 2× desfecho, PSI de atenção < alerta, cortes decrescentes) | Registrar/alterar a base legal (diálogo); salvar parâmetros com o resumo só do que mudou |

**Ficha do caso** (`app-case-score-card`, em `features/scoring/case`, inserido em `collection/station/case-sheet-panel`
entre a posição do caso e as abas — única mudança no `collection`): só com `SCORING_VIEW`. Pontos, faixa
(`app-score-band`: letra + descrição + régua decorativa, em tom neutro — a faixa ordena, não julga), chance em N dias,
modelo e gatilho do cálculo, os 3 fatores de maior efeito (seta + texto do servidor + pontos ou log-odds) e o aviso de
finalidade. Sem score (404) o cartão diz que não há score. **Recalcular** (`SCORING_VIEW` + `CASE_WORK`), **Ver
explicação** (diálogo com todos os fatores, histórico dos retratos com o desfecho e revisões) e **Revisar score**
(`SCORING_MANAGE`): origem (pedido do titular, da operação ou verificação interna) e, para pedido do titular, a
solicitação "revisão de decisão automatizada" aberta do devedor no compliance (lista com
`DATA_SUBJECT_REQUEST_MANAGE`; sem ela, o identificador opcional). Erros de regra (`scoring.legal_basis_missing`,
`scoring.case_not_scorable`, `scoring.model.self_promotion`…) com texto próprio (`SCORING_ERROR_LABELS`).

Componentes compartilhados reaproveitados: `app-page-header`, `app-data-table`, `app-empty-state`, `app-status-chip`
(domínios `scoringModel`, `scoringRun`, `scoringMonitoring` em `shared/status`), `app-field`/`appInput`,
`DialogService`, `ConfirmService`, `DetailDialog`, `app-kpi-card`/`app-bar-chart`/`app-column-chart` do analytics
(o `app-column-chart` ganhou a entrada opcional `axisFractionDigits`, padrão 1 — retrocompatível). Onboarding: telas
`scoring.*` em `screens.ts` e tours `scoring.models`, `scoring.model`, `scoring.monitoring`, `scoring.settings`.

## 13. Pendências

* `telephony` publicar `CallCompleted` com `ANSWERED` para alimentar "canal com resposta" por voz (consumidor pronto).
* Meta de comissão por recuperado × esperado no motor do incentive (hoje só indicador).
* Alarmes CloudWatch para `scoring.monitoring.alerts{level=ALERT}` e `scoring.jobs.failures` no Terraform.
* Revogar a base legal (desabilitar o score e parar a coleta de fatos): não há operação no backend; a tela só registra ou
  substitui. Exige definir o que acontece com scores vigentes e filas "maior score".
* Nota de versão na central de novidades e novidade na tela da estação (cartão do score) ficaram para a integração na
  master, para não colidir a numeração com outras frentes em paralelo.
