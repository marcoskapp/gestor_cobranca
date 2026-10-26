# Desenho — métricas operacionais e remuneração variável dos negociadores (módulo `incentive`)

Escopo: indicadores individuais e de equipe dos negociadores humanos, planos de comissionamento parametrizáveis e versionados, atribuição justa de crédito quando o assistente de IA participa da negociação, apuração, extrato, contestação e aprovação. Schema `incentive`, migrations V1800–V1899.

> **Diagnóstico (22/10/2026).** O escopo funcional cobre a **comissão da assessoria junto ao credor** (RB08, F17, módulo `settlement`) e cita indicadores de produtividade em M08/§7 (casos trabalhados, contatos efetivos, tempo de atendimento, acordos por hora). **Não havia** catálogo de métricas por negociador, regras de atribuição de crédito, plano de comissão do negociador, nem tratamento da participação do assistente na remuneração. Este documento preenche a lacuna.
>
> Os dois conceitos são distintos e não se misturam: `settlement` calcula quanto a **assessoria** recebe do **credor**; `incentive` calcula quanto o **negociador** recebe da **assessoria**. A base de ambos é a mesma (recuperação confirmada), o que garante coerência entre o que a assessoria fatura e o que paga.

## 1. Princípios

1. **Paga-se por dinheiro que entrou, não por promessa.** Comissão é *prevista* no acordo formalizado e só é *apurada* com pagamento confirmado/conciliado (RB06, RB08). Acordo não pago não remunera.
2. **Crédito para quem gerou o resultado.** A participação de cada ator (humano, assistente, portal, campanha) é registrada no momento do acordo e não é reconstruída depois.
3. **A IA não "rouba" comissão nem "dá" comissão de graça.** O que o assistente fecha sozinho não remunera o negociador individual; o que o humano fecha usando o assistente como ferramenta (copiloto) é integralmente do humano; o que o assistente prepara e o humano apenas confirma tem peso reduzido e configurável.
4. **Qualidade pesa tanto quanto volume.** Desconto desnecessário, quebra precoce, reclamações e violações de compliance reduzem ou zeram a remuneração variável — sem isso o plano incentiva acordos ruins.
5. **Comparação justa.** Carteiras têm dificuldades diferentes (faixa de atraso, valor, credor). Metas e taxas são segmentadas e há multiplicador de dificuldade, para que o negociador da carteira de 360+ dias não seja comparado ao da carteira de 30 dias.
6. **Transparência e reprodutibilidade.** O negociador vê em tempo real o próprio desempenho e a comissão prevista/apurada, com memória de cálculo. Todo extrato é reproduzível pela versão do plano e pelos fatos usados.
7. **Prefira não pagar a pagar e estornar.** Por cautela trabalhista, o desenho padrão evita descontar comissão já paga: usa carência antes de apurar (ver §5.2). Estorno é opcional e exige validação jurídica.

## 2. Catálogo de métricas do negociador

Todas por negociador, equipe, credor, carteira, faixa de atraso, canal e período. As fórmulas são contrato com painéis e planos — não mudar sem versionar.

### 2.1 Esforço e produtividade

| Métrica | Fórmula / definição | Fonte |
| --- | --- | --- |
| Tempo logado | soma das sessões ativas no período | identity (sessões) + presença do operador |
| Ocupação | tempo em atendimento ÷ tempo logado disponível | collection, telephony, communication |
| Casos trabalhados | casos com ≥ 1 interação registrada pelo negociador | collection |
| Tentativas | interações de saída registradas | collection |
| Contatos efetivos (CPC) | interações com tabulação `effectiveContact` (pessoa certa) | collection |
| Taxa de contato efetivo | CPC ÷ tentativas | collection |
| TMA (tempo médio de atendimento) | duração média das chamadas/conversas atendidas | telephony, communication |
| Tempo de resposta em conversa | mediana entre mensagem do devedor e resposta do negociador | communication |
| Retornos cumpridos | callbacks realizados até o horário ÷ agendados (detalhe em §10.6) | collection |
| Conversas recebidas do assistente | transferências (handoff) atendidas | communication / assistant |

### 2.2 Conversão

| Métrica | Fórmula |
| --- | --- |
| Contato → proposta | propostas criadas ÷ CPC |
| Proposta → acordo | acordos formalizados ÷ propostas |
| Contato → acordo | acordos ÷ CPC |
| Promessas cumpridas | promessas KEPT ÷ promessas vencidas (detalhe em §10.6) |
| Acordos por hora logada | acordos ÷ horas logadas |
| Resolução de handoff | handoffs que viraram acordo ÷ handoffs recebidos |

### 2.3 Resultado financeiro

| Métrica | Fórmula |
| --- | --- |
| Valor negociado | soma dos acordos formalizados (bruto da renegociação, ver §3.4) |
| **Valor recuperado efetivo** | pagamentos confirmados/conciliados atribuídos ao negociador, líquidos de estornos |
| Taxa de recuperação | recuperado ÷ saldo elegível atribuído no início do período (denominador explícito, conforme §7 do escopo) |
| Recuperado por hora | recuperado ÷ horas logadas |
| Ticket médio | recuperado ÷ acordos com 1ª parcela paga |
| Desconto médio concedido | desconto ÷ saldo atualizado dos acordos |
| **Uso do envelope** | desconto concedido ÷ desconto máximo permitido pela política (0–100%) |
| Nível médio da escada | média do nível de concessão em que os acordos fecharam |
| Entrada média | entrada ÷ valor do acordo |

### 2.4 Qualidade e risco

| Métrica | Fórmula |
| --- | --- |
| Quebra precoce | acordos quebrados em até 30/60/90 dias ÷ acordos formalizados no período de safra |
| 1ª parcela paga | acordos com 1ª parcela paga ÷ acordos formalizados (safra) |
| Renegociação | acordos que viraram renegociação ÷ acordos |
| Estornos | valor estornado ÷ recuperado |
| Contestações originadas | contestações procedentes em casos do negociador |
| Reclamações | reclamações registradas (ouvidoria/Procon/consumidor.gov) atribuídas |
| Nota de monitoria (QA) | média das avaliações de qualidade de atendimento (formulário configurável) |
| Violações de compliance | negações do porteiro de contato contornadas, contato fora de horário, ocorrências de auditoria |

### 2.5 Normalização para comparação justa

- **Segmentos de dificuldade**: faixa de atraso × faixa de valor × credor/carteira. Metas e taxas podem ser definidas por segmento.
- **Multiplicador de dificuldade** por segmento (ex.: 1,0 para 31–90 dias; 1,4 para 181–360; 1,8 para 360+), configurável no plano.
- **Recuperação esperada** (`financial.expectedRecoveryRatio`, disponível desde 24/10/2026 — §10.9): recuperado ÷ esperado pelo score da carteira atribuída — o indicador mais justo, porque desconta a qualidade da carteira recebida.
- **Safra**: indicadores de quebra e 1ª parcela são medidos pela safra de formalização, não pelo mês do pagamento.

## 3. Atribuição de crédito (quem gerou o acordo)

No momento da formalização o módulo `negotiation` registra os **participantes** do acordo, publicados no evento `AgreementAttributionRecorded` (linguagem publicada). `incentive` nunca reconstrói a atribuição a partir de logs.

### 3.1 Papéis

| Papel | Quem |
| --- | --- |
| `CLOSER` | ator que formalizou a proposta aceita pelo devedor |
| `ORIGINATOR` | ator que criou a proposta (pode ser diferente do closer) |
| `PRE_NEGOTIATOR` | assistente que conduziu a negociação antes do handoff |
| `APPROVER` | supervisor/gestor que aprovou acima da alçada |
| `INFLUENCER` | último negociador humano com contato efetivo dentro da janela de influência, quando o fechamento foi pelo portal ou por campanha |

### 3.2 Origem do acordo e peso de crédito padrão

| Origem | Situação | Crédito individual padrão |
| --- | --- | --- |
| `HUMAN` | humano negociou e fechou | 100% para o closer |
| `HUMAN_COPILOT` | humano fechou usando sugestões do assistente (modo copiloto) | 100% para o closer — o assistente é ferramenta; não penalizar quem usa |
| `ASSISTANT_HANDOFF_NEGOTIATED` | assistente transferiu e o humano negociou de fato (alterou condição, contornou objeção, subiu nível com justificativa) | 100% para o closer |
| `ASSISTANT_HANDOFF_CONFIRMED` | assistente deixou proposta pronta e o humano só confirmou/aprovou sem alterar | 30% para o closer |
| `ASSISTANT_AUTONOMOUS` | assistente fechou sozinho | 0% individual; entra no **pool da equipe/supervisor** responsável pela fila do assistente (§5.1) |
| `PORTAL_SELF_SERVICE` | devedor fechou no portal | 50% para o `INFLUENCER` se houve contato efetivo humano nos últimos 7 dias; senão 0% |
| `CAMPAIGN` | fechamento por régua automatizada (link de pagamento) | igual ao portal |

Todos os pesos e a janela de influência são parâmetros do plano. A diferença entre *negociou* e *confirmou* é objetiva: o servidor compara a proposta formalizada com a sugestão recebida no handoff (mesmas condições ⇒ `CONFIRMED`).

### 3.3 Mudança de carteira e desligamento

- **Modelo originador (padrão)**: parcelas pagas depois continuam creditadas a quem fechou o acordo, mesmo que o caso tenha sido redistribuído.
- **Modelo carteira**: parcelas creditadas ao responsável atual do caso. Útil em operações com rotatividade alta.
- Desligado: parcelas pagas após o desligamento seguem a política do plano (padrão: creditadas até a data de desligamento + carência; depois, não remuneram). Validar com RH/jurídico.

### 3.4 Renegociação

Só conta o **ganho incremental**: o valor já creditado em acordos anteriores do mesmo débito não é creditado de novo (RB05). Renegociação de acordo do próprio negociador tem peso configurável (padrão 50%), para não premiar quebra seguida de refazer.

## 4. Plano de comissionamento (parametrizável e versionado)

`CommissionPlan` por tenant, com estados `DRAFT → PUBLISHED → RETIRED` e versões imutáveis com vigência. Apuração sempre usa a versão vigente na data do fato.

### 4.1 Elegibilidade

Perfis/papéis, equipes, credores e carteiras cobertos; data de início; exigência mínima de dias trabalhados no período.

### 4.2 Componentes (combináveis)

| Componente | Parâmetros |
| --- | --- |
| Percentual sobre recuperado | taxa por segmento (credor/carteira/faixa de atraso); modo **escalonado progressivo** (cada faixa paga a sua taxa) ou **por faixa atingida** (a taxa da faixa vale para todo o volume) |
| Valor fixo por acordo | por acordo com 1ª parcela paga (não por acordo formalizado) |
| Meta | meta individual ou de equipe (recuperado, taxa de recuperação ou recuperado vs. esperado); **gatilho** mínimo (ex.: 80%), **acelerador** acima de 100%, **teto** |
| Multiplicador de dificuldade | por segmento (§2.5) |
| Modificadores de qualidade | deflator por quebra precoce acima do limite; deflator por uso do envelope acima de X%; **portão** de nota de monitoria mínima; **zerador** por violação grave de compliance |
| Pesos de participação da IA | tabela da §3.2 |
| Pool de equipe | % do recuperado da equipe (inclusive autônomo do assistente) para supervisores |
| Limites | teto por período por negociador; piso não existe (salário fixo não é escopo) |
| Carência e estorno | dias de carência após confirmação; estorno habilitado ou não (§5.2) |

### 4.3 Exemplo (plano semeado `PADRAO_MERCADO`, ajustável)

- 1,5% do recuperado até R$ 50 mil no mês; 2,0% de R$ 50–100 mil; 2,5% acima (escalonado).
- Multiplicador 1,0 (até 90 dias), 1,2 (91–180), 1,4 (181–360), 1,8 (> 360).
- Gatilho de meta 80% (abaixo: paga 50% do variável calculado); acelerador 1,2× acima de 110%; teto de 3× a meta.
- Deflator −20% se quebra precoce 60 dias > 35% da safra; −10% se uso médio do envelope > 85%.
- Portão de monitoria: nota ≥ 7,0 (abaixo: variável suspenso até reavaliação).
- Violação grave de compliance comprovada: variável do período zerado (com contestação).
- Supervisor: 0,3% do recuperado da equipe, incluindo autônomo do assistente da sua fila.

Os números são referências usuais de mercado para assessorias e **devem ser calibrados** pela operação.

## 5. Ciclo de apuração

1. **Atribuição** registrada no acordo (`AgreementAttributionRecorded`).
2. **Comissão prevista** (`commission.forecast`): no acordo formalizado, pelo valor das parcelas × crédito × taxa — só informativa.
3. **Fato comissionável**: pagamento confirmado/conciliado e alocado a parcela do acordo (`PaymentConfirmed`) → lançamento no razão de comissão (`CommissionLedgerEntry`, append-only) com memória de cálculo. Estorno (`PaymentReversed`) gera lançamento compensatório.
4. **Carência**: o lançamento fica `PENDING` até D+N (padrão 5 dias úteis) para absorver estornos e conciliação.
5. **Fechamento do período** (mensal, configurável): aplica metas, aceleradores, deflatores e tetos sobre os lançamentos `CLEARED`; gera **extrato** por negociador (`CommissionStatement`) em `CALCULATED`.
6. **Revisão e contestação**: negociador vê o extrato e pode contestar item a item com justificativa; gestor resolve (ajuste manual exige motivo e é auditado, com dupla aprovação acima de limite).
7. **Aprovação** pelo gestor e pelo financeiro (segregação de funções) → `APPROVED` → exportação para folha (CSV/XLSX) → `EXPORTED`. Extrato aprovado é imutável; correções entram no período seguinte como ajuste.

### 5.1 Pool do assistente

O recuperado em acordos `ASSISTANT_AUTONOMOUS` é atribuído à **equipe dona da fila do assistente** e entra só no componente de pool (supervisores e, opcionalmente, toda a equipe em partes iguais). Isso remunera quem configura, monitora e trata as exceções do assistente sem dar a um indivíduo um resultado que ele não gerou.

### 5.2 Carência × estorno (atenção trabalhista)

A comissão integra a remuneração (CLT, art. 457) e o estorno de comissão já paga é restrito (Lei 3.207/1957, art. 7º, e jurisprudência). Por isso o padrão é **carência antes de apurar** e **sem estorno após aprovação**; estorno posterior só por plano configurado explicitamente. **Validar com o jurídico/RH da assessoria** antes de habilitar estorno ou o modelo de desligamento.

## 7. Permissões, privacidade e auditoria

| Permissão | Uso |
| --- | --- |
| `PERFORMANCE_VIEW_OWN` | negociador vê os próprios indicadores e extratos |
| `PERFORMANCE_VIEW_TEAM` | supervisor vê a equipe |
| `PERFORMANCE_VIEW_ALL` | gestor vê o tenant |
| `COMMISSION_PLAN_MANAGE` | criar/publicar planos |
| `COMMISSION_STATEMENT_MANAGE` | fechar período, resolver contestações, ajustar |
| `COMMISSION_STATEMENT_APPROVE` | aprovação financeira (segregada de quem fecha) |
| `QUALITY_EVALUATE` | registrar monitoria |

Remuneração é dado pessoal sensível do ponto de vista trabalhista: nunca aparece em logs técnicos; eventos de negócio levam ids e faixas, nunca o valor individual da comissão; toda consulta a extrato de terceiro gera log de acesso. Ajustes, publicações de plano e aprovações vão para a trilha de auditoria.

## 8. Eventos de negócio

`attribution.recorded`, `commission.forecast`, `commission.entry_posted`, `commission.entry_cleared`, `commission.entry_reversed`, `commission.period_closed`, `commission.statement_disputed`, `commission.statement_adjusted`, `commission.statement_approved`, `commission.statement_exported`, `quality.evaluated`.

Insumos de inteligência: custo de comissão por real recuperado, por credor e por origem (humano × assistente), elasticidade do resultado ao plano (antes/depois de cada versão), correlação entre uso do envelope e quebra.

## 9. Dependências e integração

`incentive` → `organization`, `identity` (equipes, usuários, tempo logado) e `compliance` (calendário de feriados, para a carência em dias úteis). Os demais fatos chegam **somente por eventos de integração**: `AgreementAttributionRecorded`, `AgreementStatusChanged`, `InstallmentStatusChanged`, `PaymentConfirmed`, `PaymentReversed`, `InteractionRecorded`, `CaseStatusChanged`, `CaseAssignmentChanged`, `CaseBalanceChanged`, `CallbackStatusChanged`, `PromiseStatusChanged`, `CallCompleted`, `MessageStatusChanged`, e os de handoff do assistente. Os indicadores do §2 são materializados em tabelas de fatos próprias (`performance_fact` diário por negociador/segmento), que o `analytics` pode ler por views.

## 10. Implementação (22/10/2026)

Código em `backend/src/main/java/br/com/gestorcobranca/incentive`, migrations `V1800`–`V1804` (schema `incentive`, RLS forçado em todas as tabelas) e `identity/V0202` (instante de desativação do usuário e permissões nos perfis existentes). API em `/api/v1/incentive/...` (OpenAPI gerado pelo springdoc).

### 10.1 Mapa

| Conceito | Onde |
| --- | --- |
| Fatos de desempenho | `performance_fact` (diário por negociador × credor × carteira × faixa × canal, medidas aditivas, inclusive retornos e promessas — §10.6), `callback_commitment` e `promise_outcome` (situação por compromisso), `worked_case_day` (casos distintos), `response_sample`/`conversation_wait` (mediana de resposta), `logged_time_daily`, `effective_contact` (janela de influência), `case_snapshot` (responsável e saldo elegível, append-only), `agreement_lifecycle` |
| Atribuição | `AgreementAttribution` + participantes + créditos (`CreditPolicy`), resolvidos pelo plano vigente na formalização |
| Planos | `CommissionPlan` (uma linha por versão; regras `PlanRules` tipadas em jsonb com `rules_schema_version`), `GoalTarget`, `StandardPlans.padraoMercado()` |
| Motor | `CommissionEngine` (puro) + `StatementCalculator` (monta as entradas a partir do razão e dos fatos) |
| Razão | `CommissionLedgerEntry` (`ACCRUAL`/`REVERSAL`, `PENDING → CLEARED/WAIVED`), `PaymentFact`, `PaymentReversalFact`, `CommissionPeriod` |
| Extrato | `CommissionStatement` (+ linhas = memória contestável, + JSON da memória completa), `StatementDispute`, `StatementAdjustment`, `StatementExport` |
| Qualidade | `QualityForm` (critérios com peso e criticidade), `QualityEvaluation` (0–10), `ConductOccurrence` (violação de compliance, reclamação, contestação procedente) |
| Indicadores | `PerformanceQueries` (`GET /api/v1/incentive/performance`), fórmulas em `PerformanceQueryService` |

### 10.2 Ordem do motor (memória de cálculo linha a linha; soma das linhas = valor calculado)

1. Elegibilidade (dias trabalhados) → 2. cobertura de credor/carteira → 3. teto da meta (volume × teto ÷ atingimento) → 4. percentual sobre recuperado por tabela de segmento (`PROGRESSIVE` ou `REACHED_TIER`; a comissão da tabela é distribuída aos segmentos pelo volume e multiplicada pela dificuldade) → 5. fixo por acordo com 1ª parcela paga × fração de crédito → 6. meta (gatilho, acelerador `TOTAL` ou `EXCESS`) → 7. deflatores somados (máx. 100%) → 8. pool de equipe → 9. zerador de compliance, depois portão de monitoria (sobre tudo, inclusive pool) → 10. piso zero → 11. teto do período. Centavos HALF_EVEN por linha.

Exemplo verificado (`CommissionEngineTest#standardMarketPlanCombination`): 60.000 (31–90 dias) + 40.000 (> 360 dias) → 1.750 de comissão de tabela (taxa efetiva 1,75%) → 1.050,00 + 1.260,00 (× 1,8) = 2.310,00; meta 80.000 (125%) → × 1,2 = 2.772,00; envelope 88% > 85% → −10% = **2.494,80**.

### 10.3 Decisões de implementação

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Regras do plano como records Java tipados (`PlanRules`) em jsonb com versão de esquema, validados em toda escrita e leitura | Tabelas normalizadas por componente; JSON livre | Componentes aninhados e opcionais; tipagem + validação sem 10+ tabelas; versão de esquema permite conversão futura |
| 2 | Vigência de versão em meses inteiros (dia 1 ao último dia) | Datas arbitrárias com rateio no mês | Cada período usa exatamente a versão vigente na data dos fatos, sem partir faixas no meio do mês |
| 3 | Sem publicação retroativa: início ≥ mês corrente e após o último período fechado | Permitir retroativo | Extratos fechados e pesos já lançados não mudam por trás |
| 4 | Pesos de crédito fixados na formalização; taxas, metas e deflatores da versão do período de competência; simulação recalcula pesos a partir da base | Tudo pela versão do fechamento | "Registrada no momento do acordo e não reconstruída depois" (§1.2) |
| 5 | Razão guarda o recuperado creditado (base × peso × fator); a comissão em R$ é apurada no fechamento | Comissão por lançamento à taxa marginal | Faixas progressivas, meta e deflatores dependem do total do período |
| 6 | Competência = mês do pagamento; se fechado, o próximo período aberto. Fechamento só após o último dia + maior carência (dias úteis); períodos fechados em ordem; job diário 03:20 e comando manual com a mesma regra | Competência pela liberação | Fecha quando todos os pagamentos do mês já passaram pela carência |
| 7 | Carência D+N dias úteis contada da confirmação; feriados do `compliance` (`HolidayUseCases`) — nova dependência `incentive → compliance :: usecases` | Interface `shared` implementada pelo compliance; seg–sex sem feriados | Porta pública já existente, sem ciclo; feriados por assessoria |
| 8 | Estorno proporcional ao valor do pagamento: na carência anula no mesmo período; liberado e período aberto → mesmo período; período fechado → estorno de comissão só se o plano habilitar (no próximo período), senão `WAIVED` | Sempre estornar | §5.2: padrão sem estorno após fechamento (cautela trabalhista) |
| 9 | Multiplicador de dificuldade aplicado sobre a parcela proporcional da comissão da tabela | Volume ponderado antes das faixas | Não antecipa a progressão das faixas; cálculo verificável à mão |
| 10 | Teto da meta escala o volume remunerado por teto ÷ atingimento; acelerador `TOTAL` por padrão (`EXCESS` opcional) | Teto sobre o valor | Coerente com "teto de 3× a meta" e aplicável a faixas progressivas |
| 11 | Deflatores somados (máx. 100%), com amostra mínima e limite estrito (>); safra da quebra = acordos formalizados no período deslocado pela janela | Multiplicativos | Mais simples de explicar ("−20% e −10% = −30%"); safra já maturada |
| 12 | Portão de monitoria suspende tudo (inclusive pool) com valor retido; libera por recálculo após nova monitoria; sem avaliação o portão passa (configurável) | Zerar | "Suspenso até reavaliação" (§4.3) |
| 13 | Violação grave registrada como `ConductOccurrence` por quem tem `QUALITY_EVALUATE`, dispensável (contestação) por `COMMISSION_STATEMENT_MANAGE` | Evento do compliance | Não há evento de violação comprovada; a comprovação é decisão humana |
| 14 | Influenciador apurado pelo módulo (último contato efetivo humano no caso dentro da janela do plano); o do evento é contingência | Só o do evento | A janela é parâmetro do plano (§3.2) |
| 15 | Pool: equipe dona da fila informada na atribuição (`teamId`); supervisor (ou divisão igual com membros ativos); membros da equipe atuais no fechamento | Histórico de equipes | O identity não guarda histórico de composição |
| 16 | Modelo carteira: beneficiário = responsável pelo caso na data do pagamento (`case_snapshot`) | Responsável no fechamento | Data do fato |
| 17 | Desligamento: `identity.app_user.disabled_at`; lançamento criado com peso 0 e nota `TERMINATED` | Não lançar | Transparência e simulação |
| 18 | Renegociação: valor negociado incremental = máx(0, novo − (substituído − pago)); pagamentos são fatos únicos; renegociação do próprio negociador com fator 50% | Creditar o integral | §3.4 |
| 19 | Catálogo fixo de faixas de atraso (`D0_30`…`D361_PLUS`, `UNKNOWN`) derivado de `daysPastDue` no evento | Faixa textual do produtor | Fatos, metas e multiplicadores comparáveis entre versões |
| 20 | Segregação: quem aprova ≠ beneficiário, ≠ quem fechou o período, ≠ quem requereu/decidiu ajuste ou resolveu contestação; ajuste acima de R$ 500 (configurável) exige segunda pessoa | Duas aprovações sequenciais (gestor + financeiro) | Gestor revisa/fecha/ajusta (`MANAGE`), financeiro aprova (`APPROVE`): uma aprovação segregada |
| 21 | Escopo: `VIEW_ALL`, `MANAGE` ou `APPROVE` = todos; `VIEW_TEAM` = si + membros das equipes que supervisiona; extrato fora do escopo → 404; filtro por pessoa fora do escopo → 403 + `SCOPE_DENIED`; escopo de credor restringe fatos e lançamentos de terceiros | — | §7 e F02 |
| 22 | Eventos de negócio só com faixas; agregados com valor apenas com ≥ 3 extratos | Valor sempre | Agregado de um extrato identifica a pessoa |
| 23 | Plano `PADRAO_MERCADO` semeado pelo evento `TenantProvisioned` (novo, publicado pelo provisionamento) + job diário idempotente para assessorias existentes | Provisionamento chamar o incentive; seed em SQL | Sem dependência do identity para o incentive; regras tipadas só existem em Java |
| 24 | Tempo logado = união das sessões de `startedAt` até a última atividade (limitada pelo encerramento), por dia de operação; sincronização horária (ontem e hoje) para quem tem `CASE_WORK` | Presença/pausa do operador | Não há módulo de presença; aproximação documentada |
| 25 | Ocupação = tempo de conversação de chamadas atendidas ÷ tempo logado; tentativas = interações `OUTBOUND` (chamadas não contam em dobro); TMA = chamadas `ANSWERED` | Somar conversas | `MessageStatusChanged` não tem duração de atendimento |
| 26 | Recuperado = créditos ponderados líquidos de estornos (estorno na data do estorno); taxa de recuperação sobre o saldo elegível dos casos atribuídos no início do período | Estorno na data do pagamento | Histórico de indicadores estável |
| 27 | Ordem de chegada arbitrária: lock consultivo por acordo + chaves naturais; pagamento antes da atribuição fica pendente e é lançado quando ela chega (varredura de contingência a cada 15 min) | Exigir ordem | Eventos são entregues em paralelo |
| 28 | Mais de um plano cobrindo o negociador: vence a maior prioridade; nenhum → extrato `NO_PLAN` com valor zero; membro sem participação no pool não recebe extrato | Erro | Transparência sem ruído |
| 29 | Exportação CSV (`;`, UTF-8, neutraliza fórmulas) e XLSX (streaming), guardada no S3 com SHA-256; inclui aprovados e já exportados (reexportação) | Só gerar em memória | Evidência do que foi à folha |
| 30 | Consumidores em `application/service` (como `CONVENTIONS.md` §1) | `application/listener` | Mantém os serviços package-private |
| 31 | Campos novos em eventos existentes com construtor da assinatura original | Novos eventos | Retrocompatível para produtores em desenvolvimento |

### 10.4 Contratos de eventos alterados ou criados

- **Novo** `shared.events.negotiation.AgreementAttributionRecorded` (linguagem publicada; produtor: `negotiation`, parte 2): ids, `teamId`, `origin`, `channel`, participantes (`actorType`, `actorId`, `role`), `concessionLevel`, `envelopeUsagePercent`, `daysPastDue`, `agreementAmount`, `updatedBalance`, `discountAmount`, `downPaymentAmount`, `replacedAgreementId`, `formalizedAt`.
- **Novo** `shared.events.collection.CaseAssignmentChanged` (produtor: `collection`): distribuição/redistribuição com responsável, equipe e saldo elegível.
- **Novo** `shared.events.identity.TenantProvisioned` (produtor: `identity`, já publicado).
- `InteractionRecorded` + `direction`, `portfolioId`; `CaseStatusChanged` + `assigneeId`, `teamId`, `eligibleBalance`; `MessageStatusChanged` + `senderActorType`, `senderActorId`, `portfolioId`; `CallCompleted` + `portfolioId` (e valores de `result` documentados, `ANSWERED` = atendida).
- `PaymentConfirmed` já tinha a alocação por parcela de acordo (`Allocation.agreementInstallmentId`) e `agreementId`: base comissionável = alocações a parcelas.
- **Novo** `shared.events.collection.CaseBalanceChanged` (produtor: `collection`, 23/10/2026): saldo elegível atribuível mudou sem mudança de status, elegibilidade ou responsável (§10.7). `CaseStatusChanged.eligibleBalance` e `CaseAssignmentChanged.eligibleBalance` documentados como saldo **atribuível** (zero quando inelegível ou encerrado).
- `CallbackStatusChanged` e `PromiseStatusChanged` (produtor: `collection`) consumidos desde 23/10/2026 (§10.6).

### 10.5 Pendências

- Produtores: `communication` informar o autor das mensagens. (`telephony` já publica `CallCompleted` com a carteira, o operador que atendeu e `durationSeconds` = conversação — ver `telefonia.md` §6; presença do discador em `telephony.presence_interval`, ainda não consumida aqui.) (`negotiation` já publica `AgreementAttributionRecorded`, `AgreementStatusChanged` e `InstallmentStatusChanged` — ver `negociacao-e-alcadas.md` §10.7–§10.8; a distinção `HANDOFF_CONFIRMED` × `HANDOFF_NEGOTIATED` usa a impressão digital das condições.) (`collection` já preenche os campos novos e publica `CaseAssignmentChanged` — ver `collection.md` §9.)
- Indicadores sem fonte (retornam nulos e listados em `unavailableMetrics`): handoffs recebidos/resolvidos, propostas (contato → proposta, proposta → acordo) — exigem eventos de integração próprios (o negotiation já registra `proposal.*` e `proposal.handoff_requested` no log de negócio e publica `HandoffRequested` (contrato do communication); falta um evento de integração de proposta criada, ou o consumo do handoff, aqui). (Retornos cumpridos e promessas cumpridas: implementados em 23/10/2026, §10.6.)
- Reatribuição de um retorno (`TaskUseCases.reassign`) não publica evento: o compromisso passa ao novo responsável só no próximo evento do retorno (cumprimento, atraso ou cancelamento); até lá, segue no denominador do responsável anterior. Se a operação reatribuir retornos com frequência, o `collection` deve publicar `CallbackStatusChanged` também na reatribuição.
- Retornos e promessas não têm faixa de atraso nem canal nos eventos: as medidas ficam em `delay_bucket = UNKNOWN` e `channel = UNKNOWN` e somem quando o painel filtra por faixa ou canal.
- Histórico de composição de equipes (pool e meta de equipe usam a composição no fechamento).
- ~~Telas do incentive no frontend~~ — implementadas em 25/10/2026 (§11). (Views para o `analytics`: implementadas em 24/10/2026 — `analytics.src_performance_fact`, `src_worked_case`, `src_logged_time`, `src_case_snapshot`, `src_commission_statement`; ver `analytics.md` §3. Mudança de coluna dessas tabelas exige ajustar `R__analytics_contract.sql`.)
- Validar com jurídico/RH antes de habilitar estorno de comissão (`clawbackEnabled`) ou mudar a política de desligamento.

### 10.6 Retornos cumpridos e promessas cumpridas (23/10/2026)

Fonte: `CallbackStatusChanged` e `PromiseStatusChanged` do `collection` (ver `collection.md` §9), consumidos por `CommitmentFactService` (consumidores `incentive.callback-status` e `incentive.promise-status`, idempotentes por evento). Migration `V1804`: sete colunas aditivas em `performance_fact` e as tabelas de situação `callback_commitment` (tarefa × horário combinado) e `promise_outcome` (promessa). A cada evento a situação do compromisso é recalculada (`CommitmentPolicy`, regra pura) e o fato diário recebe a diferença entre a contribuição nova e a anterior — o fato é sempre a soma das situações atuais, e o resultado não depende da ordem de entrega (eventos do mesmo retorno/promessa serializados por advisory lock).

| Indicador (API) | Fórmula | Data e pessoa do fato |
| --- | --- | --- |
| `effort.callbacksKeptRate` — Retornos cumpridos | `callbacksKept ÷ callbacksScheduled` | dia do horário combinado (fuso de operação) × responsável pelo retorno |
| `effort.callbacksScheduled` | agendados = retornos com horário no período, exceto cancelados antes de vencer e horários substituídos por reagendamento (inclui os ainda pendentes) | idem |
| `effort.callbacksKept` | cumpridos até o fim da tolerância (`KEPT`) | idem |
| `effort.callbacksMissed` | atrasados: `MISSED` ou `COMPLETED_LATE` (cumprido depois da tolerância, com ou sem o job ter marcado o atraso) | idem |
| `conversion.promisesKeptRate` — Promessas cumpridas | `promisesKept ÷ promisesDue` | data prometida × quem registrou (`operatorId`) |
| `conversion.promisesDue` | vencidas = prazo encerrado com desfecho: cumpridas + quebradas (pendentes na tolerância e canceladas ficam fora) | idem |
| `conversion.promisesKept` / `promisesBroken` | situação atual `KEPT` / `BROKEN` (estorno devolve a cumprida a pendente e retira a contagem) | idem |
| `conversion.promisesRegistered` | promessas registradas | dia do registro × quem registrou |

Situações de um compromisso de retorno, em graus (só avança): `SCHEDULED` → `SUPERSEDED` (reagendado antes de vencer) → `CANCELLED` → `MISSED` → `COMPLETED_LATE`/`KEPT`. Assim, um cancelamento depois do atraso (ex.: caso encerrado) não apaga o atraso, e o horário original de um reagendamento depois do atraso continua contado como atrasado. Responsável, credor e carteira vêm do evento mais recente do compromisso. Retorno sem responsável humano (fila do caso) e promessa sem operador (automática) não entram nos fatos de ninguém.

Para um período encerrado, `callbacksScheduled = callbacksKept + callbacksMissed`, ou seja, a taxa coincide com `KEPT ÷ (KEPT + MISSED)` sugerida em `collection.md` §9; no período em curso, retornos ainda pendentes já estão no denominador (é a fórmula do §2.1: "÷ agendados").

### 10.7 Conferência dos eventos de caso (23/10/2026)

| Campo esperado pelo incentive | Publicado pelo collection | Situação |
| --- | --- | --- |
| `CaseStatusChanged.assigneeId` / `CaseAssignmentChanged.assigneeId` | responsável humano atual; null quando na fila, com o assistente ou encerrado | confere (modelo carteira e denominador por responsável) |
| `teamId` | equipe dona da fila/atribuição do caso (`assignee_team_id`), mantida quando o caso volta à fila | confere (o pool usa o `teamId` da atribuição do acordo) |
| `eligibleBalance` | saldo elegível **atribuível**: zero quando o caso está inelegível ou encerrado | confere; o consumidor deixou de filtrar o flag `eligible` herdado (podia excluir, por empate de instante entre os dois eventos do mesmo commit, um caso que acabara de ficar elegível) |
| Saldo no início do período | só era publicado em mudança de status, elegibilidade ou responsável | **bug no produtor, corrigido**: pagamentos e cargas que mudam só o saldo deixavam o retrato defasado (taxa de recuperação com denominador errado no período seguinte). `CaseRecorder` agora publica `CaseBalanceChanged` quando só o saldo atribuível muda; consumido como retrato (`incentive.case-balance`). Teste: `CollectionIncentiveIndicatorsIT` (falha sem a correção) |

Denominador da taxa de recuperação: soma, nos casos cujo último retrato até o início do período (00:00 do fuso de operação) tem o negociador como responsável, do último saldo elegível atribuível informado.

### 10.8 Decisões (23/10/2026)

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 32 | Retornos por dia do horário combinado e responsável; denominador = agendados não cancelados nem substituídos (inclui pendentes) | `KEPT ÷ (KEPT + MISSED)` | Fórmula do §2.1 ("÷ agendados"); coincide com a alternativa em período encerrado |
| 33 | `COMPLETED_LATE` e cancelamento após atraso contam como atrasado | Cancelamento remove do denominador | Não permitir "limpar" atraso cancelando o retorno |
| 34 | Reagendamento antes de vencer substitui o compromisso (`SUPERSEDED`), sem contar como atraso | Contar cada horário | O produtor trata reagendamento como novo compromisso; o horário anterior não foi descumprido |
| 35 | Promessas por data prometida (safra de vencimento) e por quem registrou; vencida = cumprida + quebrada | Dia da resolução (sugestão do collection) | "Promessas vencidas no período" é a leitura usual; cumprimento antecipado fica na safra do vencimento |
| 36 | Situação por compromisso em tabela própria + deltas no fato diário | Recalcular o fato por consulta às tabelas de situação | Mantém `performance_fact` aditivo e legível pelo `analytics`; ordem de chegada arbitrária |
| 37 | Compensações por `update` antes do `insert ... on conflict` | Remover o check de não-negatividade | O check vale para a linha proposta do insert; manter o check protege a coerência das medidas |
| 38 | Novo evento `CaseBalanceChanged` em vez de publicar `CaseStatusChanged` sem mudança de status | Reusar `CaseStatusChanged` | Outros consumidores reagem a transições de status; evento novo não muda a semântica existente |
| 39 | Teste ponta a ponta no pacote de testes do `collection` | Duplicar o cenário do collection no pacote do incentive | Reaproveita a carga de carteira, relógio deslocável e ganchos dos jobs do módulo produtor |

### 10.9 Recuperado × esperado pelo score (24/10/2026)

Fonte: `ExpectedRecoveryMeasured` do módulo `scoring` (ver `scoring.md` §7), publicado quando a janela de desfecho de uma
safra de score termina, por credor × carteira × responsável na data do score. Consumidor `incentive.expected-recovery`
grava `incentive.expected_recovery_fact` (`V1805`; uma linha por safra/grupo, republicação substitui).

| Indicador (API) | Fórmula | Data e pessoa |
| --- | --- | --- |
| `financial.expectedRecoveryRatio` | Σ valor confirmado na janela ÷ Σ valor esperado (probabilidade × saldo em aberto × fração paga típica do modelo) | data de referência do score (safra) × responsável no cálculo; nulo nas dimensões que não são por pessoa ou sem safra apurada |

O indicador saiu de `unavailableMetrics`. Meta de plano `RECOVERED_VS_EXPECTED` continua recusada: o motor de comissão
não apura metas por essa métrica (pendência).

### 10.10 Unidade da meta do período (26/10/2026)

A meta por período (`GoalTarget`) guarda a unidade escolhida por quem a cadastra (`unit`: `CURRENCY` = R$, `PERCENT` = %; `V1806`). Cada métrica tem uma única unidade (`GoalMetric.unit()`): "valor recuperado" é em R$, "taxa de recuperação" (e "recuperado × esperado", ainda recusada) é em %. Decisão do responsável (decisão 387); detalhes nas decisões 500–507.

| Regra | Onde |
| --- | --- |
| Unidade aceita = a da métrica da meta do plano que vai usar a meta. Negociador: plano vigente de maior prioridade que o cobre, se a meta for individual. Equipe: planos dos membros e do supervisor com meta de equipe. Sem plano que use a meta (`NO_PLAN`, `PLAN_WITHOUT_GOAL`) ou com membros em planos de unidades diferentes (`MIXED`), as duas são aceitas. | `GoalUnitPolicy`; `GET /incentive/goals/unit-options` |
| `PUT /goals` exige `unit`; recusa a unidade não aceita (`goal.unit_not_allowed`), negociador/equipe inexistente (`goal.subject_not_found`), mais de 2 casas (`goal.target_scale_invalid`) e % fora de 0,01–100 (`goal.percent_out_of_range`). A unidade entra na auditoria (`commission_goal.defined`/`removed`, com `unitBasis`). | `CommissionPlanService.setGoal`, `GoalTarget.define` |
| No cálculo (fechamento, prévia, simulação), meta em unidade diferente da métrica é desconsiderada: vale a meta padrão do plano, ou nenhuma. A linha "Meta" da memória explica ("meta do período em % desconsiderada: a meta do plano é em R$; vale a meta padrão do plano"). O número nunca é reinterpretado. | `StatementCalculator.goal`, `GoalInput.disregardedUnit`, `CommissionEngine` |
| Metas anteriores à V1806 recebem a unidade quando todos os planos vigentes no período, com meta do mesmo escopo, concordam (para %, só valor ≤ 100). As demais ficam com unidade nula, que segue a métrica do plano como antes, até serem alteradas. | `V1806__incentive_goal_unit.sql` |

Testes: `GoalTargetTest`, `CommissionEngineTest` (meta desconsiderada com e sem meta padrão), `GoalUnitIT` (plano padrão só R$, plano de taxa da equipe só %, limites, sujeito inexistente, meta em outra unidade desconsiderada na prévia, meta sem unidade segue o plano).

## 11. Telas (frontend, 25/10/2026)

Feature `frontend/src/app/features/incentive`, rotas em `/remuneracao` (seção "Remuneração variável" do menu). Bootstrap 5.3 + ng-bootstrap com os componentes compartilhados (`app-page-header`, `app-data-table`, `app-field`/`appInput`, `app-status-chip`, `app-amount`, `app-empty-state`, `DialogService`, `ConfirmService`); estados de carregamento, vazio e erro em todas as telas; tours curtos em `core/onboarding/content`. Decisões 340–354 em `docs/negocio/decisoes-autonomas.md`.

| Tela | Rota | Permissão (menu e guarda) | API |
| --- | --- | --- | --- |
| Desempenho (próprio, equipe ou todos pela maior permissão) | `/remuneracao/desempenho` | `PERFORMANCE_VIEW_OWN/TEAM/ALL` | `GET /performance` (total + agrupamento) |
| Extratos de comissão | `/remuneracao/extratos` | `PERFORMANCE_VIEW_*`, `COMMISSION_STATEMENT_MANAGE`, `COMMISSION_STATEMENT_APPROVE` | `GET /statements` |
| Prévia do período atual | `/remuneracao/extratos/previa` | idem | `GET /statements/preview`, `GET /ledger` |
| Extrato (memória contestável, contestações, ajustes, lançamentos, aprovação) | `/remuneracao/extratos/:id` | idem | `GET /statements/{id}`, `POST …/recalculate`, `…/disputes`, `…/disputes/{id}/resolve`, `…/adjustments`, `…/adjustments/{id}/decision`, `…/approve`, `GET /ledger` |
| Lançamentos de comissão | `/remuneracao/lancamentos` | idem | `GET /ledger` |
| Planos de remuneração | `/remuneracao/planos` | `COMMISSION_PLAN_MANAGE` | `GET/POST /plans` |
| Plano (regras, versões, simular, publicar, nova versão, aposentar, descartar rascunho) | `/remuneracao/planos/:id` | idem | `GET /plans/{id}`, `POST …/simulations`, `…/publish`, `…/versions`, `…/retire`, `DELETE /plans/{id}` |
| Regras do rascunho | `/remuneracao/planos/:id/regras` | idem | `PUT /plans/{id}` |
| Metas por período (unidade R$ ou %) | `/remuneracao/metas` | `COMMISSION_PLAN_MANAGE` | `GET/PUT /goals`, `GET /goals/unit-options`, `DELETE /goals/{id}` |
| Períodos de apuração | `/remuneracao/periodos` | `COMMISSION_STATEMENT_MANAGE` | `GET /periods`, `POST /periods/{p}/close` |
| Exportação para a folha | `/remuneracao/exportacao` | `COMMISSION_STATEMENT_APPROVE` | `GET/POST /exports`, `GET /exports/{id}/file` |
| Monitoria, ocorrências e formulários | `/remuneracao/qualidade/{avaliacoes,ocorrencias,formularios}` | `PERFORMANCE_VIEW_*`, `QUALITY_EVALUATE` (registro); dispensa de ocorrência com `COMMISSION_STATEMENT_MANAGE` | `/quality/forms`, `/quality/evaluations`, `/quality/occurrences` (+ `dismiss`) |
| Atribuição de crédito do acordo (link no detalhe do acordo) | `/remuneracao/atribuicoes/:agreementId` | `PERFORMANCE_VIEW_*`, `AGREEMENT_VIEW` | `GET /attributions/{agreementId}` |

Regras de tela (o servidor continua decidindo):

- **Segregação** (`statementActions`): o beneficiário contesta e não aprova; quem pediu/decidiu ajuste ou resolveu contestação não aprova; contestação aberta ou ajuste pendente bloqueiam a aprovação com o motivo escrito; quem fechou o período recebe a recusa traduzida. Resolução de contestação e segunda aprovação de ajuste só aparecem para outra pessoa da gestão. Ajuste acima de R$ 500 (padrão de `app.incentive.dual-approval-threshold`) avisa a segunda aprovação.
- **Confirmação forte**: aprovar extrato (`APROVAR`), fechar período (`FECHAR`), publicar (`PUBLICAR`) e aposentar (`APOSENTAR`) mostram o impacto antes; motivos com 10 a 2000 caracteres como no servidor.
- **Vigência**: publicação só no dia 1º do mês corrente em diante; aposentadoria no último dia de um mês, nunca antes da véspera do início. A simulação só oferece meses encerrados.
- **Editor de regras**: validação espelhada de `PlanRules.validate()` (`data/plan-rules.ts`), normalização (perfis em maiúsculas, faixas ordenadas, vazios como nulos), meta "recuperado × esperado" fora das opções, aviso trabalhista ao habilitar estorno.
- **Metas**: botões "Valor (R$)" e "Percentual (%)" (`btn-check`) antes do valor; a unidade aceita vem de `/goals/unit-options` (a única aceita vem marcada e a outra desabilitada, com a explicação). Em R$ o campo usa `appMoney`; em %, número de 0,01 a 100 com até 2 casas e "%" ao lado. Trocar a unidade limpa o valor. A lista mostra a unidade e a meta formatada (§10.10).
- **Exportação**: o arquivo baixado de novo é conferido com o SHA-256 registrado.
- **Desempenho**: reaproveita `app-kpi-card` e `app-bar-chart` do `analytics` sem alterá-los; taxas sempre com a base; indicadores sem fonte listados (`unavailableMetrics`).

Testes: `data/incentive.rules.spec.ts`, `data/plan-rules.spec.ts`, `statements/statements.spec.ts`, `plans/plans.spec.ts`, `incentive-pages.spec.ts` (períodos, exportação, metas, ocorrências, monitoria, desempenho, atribuição), `goals/goal-dialog.spec.ts` (unidade da meta); backend `PlanDraftAndGoalRemovalIT`, `GoalUnitIT`.

