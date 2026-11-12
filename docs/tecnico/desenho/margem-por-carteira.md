# Desenho — Margem e custo por credor e carteira (proposta de 12/11/2026)

Jornada de gestão pedida pelo responsável em 12/11/2026: saber quanto cada credor e cada carteira **rende** para a
assessoria depois do custo operacional da recuperação — pessoas, IA, telefonia, mensagens, jurídico e custos fixos —
com um painel operacional e um relatório de análise fina.

Status: **fase 1 aprovada** pelo responsável em 12/11/2026, com as respostas do §9 (faixa por cargo, ociosidade à
parte, rateio por horas). Decisões 1233 em diante. Backend da fase 1 implementado — ver §11. Fase 2, conciliação do
custo de canal com a fatura real, implementada — ver §13 (decisões 1300–1314).

## 1. O que já existe e o que falta

| Componente | Fonte hoje | Granularidade | Valor em R$? |
| --- | --- | --- | --- |
| Receita da assessoria | `settlement.commission_entry` (comissão, valor fixo, honorários da assessoria) e `settlement.receipt` (carteira) | recebimento × credor × carteira | sim |
| IA — assistente | `assistant.assistant_turn.cost_amount` | turno × credor × carteira × caso | **USD** (estimado pela tabela de preços do Bedrock) |
| IA — Importação PDF | `ingestion.pdf_import.ai_cost` | importação × credor × carteira | **USD** |
| IA — chat de suporte | `helpdesk.chat_message.cost_amount` | usuário | USD, sem credor (custo indireto) |
| Telefonia | `telephony.phone_call` (`talk/ring_seconds`) + `provider_account.rate_per_minute` | chamada × credor × carteira | só a tarifa por conta; custo por chamada não é gravado |
| Mensagens | `communication.message` (canal, categoria do template) | mensagem × conversa × credor × carteira | **não** (sem tarifa) |
| Tempo humano | `collection.interaction.duration_seconds`, `phone_call.wrap_up_seconds`, `incentive.logged_time_daily` | interação × credor × carteira; tempo logado por pessoa/dia sem credor | **não** (sem valor-hora) |
| Comissão dos negociadores | `incentive.commission_ledger_entry` | acordo × credor × carteira | sim |
| Custas jurídicas | `legal.legal_expense` (`advanced_by`, `reimbursable`) | processo × credor | sim |
| Custos fixos (licenças, infraestrutura, aluguel, gestão) | — | — | não existe |
| Tarifas de Pix/boleto, armazenamento, consultas externas | — | — | não existe |

Faltam, portanto, **cadastros de custo** (valor-hora, tarifas, custos fixos, câmbio) e um **apurador** que transforme o
uso em custo por credor e carteira.

## 2. Módulo

Módulo novo **`costing`** (faixa Flyway V2500–V2599), com `domain/application/infrastructure/web`:

* guarda os cadastros (o `analytics` é só leitura);
* lê o uso pelas views de contrato `analytics.src_*`, ampliadas com as colunas que faltam (durações, categoria do
  template, custo de IA). É o mesmo padrão do analytics: nenhuma dependência Java nos módulos de origem;
* publica os fatos de custo diários (`costing.cost_daily`), lidos pelo painel e pelo relatório.

Alternativa descartada: estender o `analytics` — ele não tem onde guardar cadastros e passaria a ter regra de negócio
de rateio.

## 3. Cadastros (com vigência; faixas, tarifas e câmbio nunca apagados, só encerrados; custo fixo pode ser excluído — auditoria `AuditEntry`)

### 3.1 Custo de pessoas ❓ (§9, Q1)

Três opções:

| Opção | Como | Prós | Contras |
| --- | --- | --- | --- |
| **A. Faixa de custo** (recomendada) | Cadastro de faixas ("Negociador júnior — R$ 38,00/h", "Supervisor — R$ 62,00/h") com vigência; cada usuário aponta para uma faixa | Minimiza dado pessoal (LGPD): ninguém vê o salário de uma pessoa; manutenção simples | Menos preciso que o valor individual |
| B. Valor-hora individual | Campo novo no cadastro do usuário, com vigência | Precisão máxima | Remuneração individual é dado pessoal sensível no contexto trabalhista: exige permissão própria, log de acesso e cuidado em relatórios com uma pessoa só |
| C. A + exceção individual | Faixa por padrão; valor individual opcional para quem a gestão quiser | Equilíbrio | Duas regras para explicar |

Em todas: o valor é **custo-empresa por hora** (salário + encargos + benefícios ÷ horas contratadas), informado pela
gestão — o sistema não guarda salário nem a composição. Permissão nova `COST_RATE_MANAGE` (só ADMIN e FINANCEIRO por
padrão); quem vê o painel nunca vê o valor de uma pessoa, só o custo agregado.

### 3.2 Tarifas de canal

`costing.channel_tariff` com vigência:

* **WhatsApp**: por conversa iniciada pela empresa, por categoria do template (utilidade, marketing, autenticação) —
  é como a Meta cobra; resposta do devedor dentro da janela de 24 h não gera nova cobrança;
* **SMS**: por mensagem (quando houver provedor);
* **E-mail**: por mensagem enviada ao provedor (normalmente desprezível; a tarifa pode ficar zerada — a tela aceita zero);
* **Telefonia**: reaproveita `telephony.provider_account.rate_per_minute`, com **minuto tarifado** (arredondado para
  cima, por chamada atendida; tarifa opcional por tentativa não atendida).

### 3.3 Câmbio da IA

O custo de IA vem em dólar. `costing.fx_rate` mensal (USD→BRL) informado pela gestão. A consulta automática da PTAX
do Banco Central é integração externa: vira **issue**, não código.

### 3.4 Custos fixos e indiretos

`costing.overhead` mensal (ex.: licenças, infraestrutura, aluguel, gestão, chat de suporte interno) com a **regra de
rateio** ❓ (§9, Q3):

* por horas humanas (recomendado para custos de equipe e espaço);
* por valor recuperado;
* por casos trabalhados;
* direto a um credor (ex.: equipe dedicada).

### 3.5 Ajuste pela fatura real (fase 2 — implementado, §13)

O custo de canal é **estimado** pelo uso × tarifa. Na fase 2, a gestão informa a fatura do mês **por item** de custo
de canal (telefonia, WhatsApp, SMS, e-mail e IA — em R$; a de IA convertida pela gestão); a diferença entre a fatura e
a estimativa do item no mês é distribuída **na proporção do custo estimado do item** em cada dia × credor × carteira
(sem estimativa, pela quantidade de uso; sem uso, fica sem credor) e o painel passa a mostrar a base "conciliado com a
fatura". O ajuste é um fato à parte: a estimativa continua guardada e o painel mostra as duas bases.

## 4. Apuração (job diário, `costing.refresh`, ShedLock; reprocessa os últimos 35 dias)

`costing.cost_daily`: dia × credor × carteira × **categoria** × subcategoria, com `amount`, `quantity`, `unit` e
`basis` (estimado, fatura, lançado). Categorias:

| Categoria | Regra |
| --- | --- |
| `HUMAN_DIRECT` | Tempo atribuído ao credor × custo-hora vigente da pessoa no dia: duração das interações + pós-atendimento das chamadas + tempo de conversa no chat |
| `HUMAN_IDLE` ❓ (Q2) | Tempo logado sem atribuição (espera, pausa) × custo-hora, distribuído na proporção do tempo direto de cada credor naquele dia, para a mesma pessoa. Opcional: mostrar separado ("ociosidade") |
| `AI` | Custo do assistente e da Importação PDF × câmbio do mês |
| `TELEPHONY` | Minutos tarifados × tarifa da conta usada na chamada |
| `MESSAGING` | Conversas de WhatsApp (por categoria) e mensagens de SMS/e-mail × tarifa |
| `VARIABLE_COMMISSION` | Comissão variável dos negociadores (`commission_ledger_entry`) |
| `LEGAL` | Custas adiantadas pela assessoria e **não reembolsáveis** (as reembolsáveis voltam na prestação de contas) |
| `OVERHEAD` | Custos fixos rateados pela regra do item |

Sem dado pessoal nos fatos: nem usuário, nem devedor. A ligação com a pessoa fica só no cálculo, em memória.

### 4.1 Atendimentos simultâneos: a mesma hora não é cobrada duas vezes (fase 2, decisões 1297–1299)

Um operador pode atender vários devedores ao mesmo tempo — conversas de credores diferentes, chat em paralelo com
ligação —, se a distribuição das filas permitir. Somar as durações de cada atendimento contaria a mesma hora várias vezes
(três conversas paralelas de 20 min virariam 60 min de custo) e ainda esconderia a ociosidade (logado − direto ficaria
menor que o real). Por isso o tempo direto é apurado por **linha do tempo da pessoa** (`WorkTimeline`):

* cada atendimento vira um **trecho com início e fim**: interação com duração (`started_at` + `duration_seconds`),
  pós-atendimento da chamada (`ended_at` + `wrap_up_seconds`) e **resposta escrita no chat** (mensagem enviada pela
  pessoa: do evento anterior da conversa — mensagem do devedor, do assistente ou da própria pessoa — até o registro do
  envio, limitado a **3 minutos**, `app.costing.chat-reply-window`); a resposta do assistente não é tempo de pessoa;
* em cada instante, o tempo da pessoa é **dividido em partes iguais** entre os atendimentos abertos naquele instante;
  cada credor/carteira recebe a sua fração. O total da pessoa no dia é a **união** dos trechos — nunca passa do relógio;
* trechos que atravessam a meia-noite (fuso de operação) ficam em cada dia; a leitura vai até 2 dias antes do período;
* a ociosidade continua `max(0, logado − direto)`, agora com o direto sem duplicidade.

Exemplo: ligação do credor A das 9h às 10h e, no meio dela, respostas no chat do credor B que somam 5 min → A recebe
57,5 min, B 2,5 min, total 1 h (antes: 65 min). Teste: `CostRulesTest` e `CostingMarginIT.atendimentosSimultaneos…`.
A mudança sobe `RULES_VERSION` para 2. Com a versão do estado menor que a das regras, o job diário refaz **uma vez** os
6 meses da janela da tendência (gatilho `rules_upgrade`; a versão gravada só sobe quando a apuração cobre essa janela),
para a série do painel não misturar regras (decisão 1328). Meses anteriores mudam só com "Reapurar o período".

## 5. Indicadores (catálogo de informação contextual, ux.md §8.6)

* **Receita** = comissão + valor fixo + honorários da assessoria (sobre o recebido no período).
* **Custo direto** = pessoas (direto) + IA + telefonia + mensagens + comissão variável + jurídico.
* **Margem de contribuição** = receita − custo direto (R$ e %).
* **Margem líquida** = margem de contribuição − ociosidade − rateio de fixos.
* **ROI operacional** = margem líquida ÷ custo total.
* **Custo por real recuperado** = custo total ÷ recuperado (substitui a versão atual, que só tinha a comissão
  variável — decisão 159).
* **Custo por acordo cumprido** = custo total ÷ acordos quitados no período (critério de sucesso do projeto).
* **Custo por caso trabalhado** e **horas por acordo**.
* **Participação da IA** = custo de IA ÷ custo direto, ao lado das horas humanas evitadas (acordos e conversas
  concluídos pelo assistente sem humano × tempo médio humano do mesmo tipo). Estimativa, com o aviso.

Regra de exibição: todo número com o denominador e a base ("estimado" ou "conciliado"); cortes com menos de 30 dias de
dados ou receita zero mostram "—" com o motivo.

## 6. Telas

### 6.1 Painel "Margem por carteira" (`/paineis/margem`)

* Filtros na URL: período (mês fechado por padrão), credor, carteira, base (estimado ou conciliado).
* Linha de indicadores: receita, custo total, margem de contribuição, margem líquida, custo por real recuperado, custo
  por acordo cumprido.
* **Cascata** receita → cada categoria de custo → margem.
* **Tabela por credor** (com expansão por carteira): recuperado, receita, custo por categoria, margem (R$ e %), ROI e
  tendência dos últimos 6 meses; ordenável; margem negativa destacada.
* **Mapa de dispersão** carteira × margem % × volume recuperado (quadrantes: "rende e escala", "rende pouco",
  "consome", "revisar contrato").
* Evolução mensal da margem e do mix de custo.
* Cada número leva à lista que o forma quando o perfil abre essa lista (premissa de navegabilidade).

### 6.2 Relatório "Análise de carteiras e credores"

* Por credor ou carteira e período: DRE gerencial (receita, custos por categoria, margens), indicadores de eficiência,
  comparação com o período anterior e com a média da assessoria, e a explicação de cada número.
* Exportação XLSX (abas: resumo, por credor, por carteira, por mês, premissas — tarifas, câmbio, faixas e regras de
  rateio vigentes) e CSV no formato longo, pela exportação existente (`REPORT_EXPORT`, `DATA_EXPORTED`). PDF fica
  para a fase 2 (o analytics ainda não gera PDF).

### 6.3 Configuração "Custos operacionais" (`/organizacao/custos`)

Abas: faixas de custo de pessoas, tarifas de canal, câmbio, custos fixos e rateio. Cada valor com vigência e histórico.
Atalho `app-config-link` a partir do painel.

## 7. Permissões

* `COST_VIEW` — painel e relatório de margem (GESTOR, FINANCEIRO, ADMIN; AUDITOR leitura).
* `COST_MANAGE` — tarifas, câmbio, custos fixos e rateio (FINANCEIRO, ADMIN).
* `COST_RATE_MANAGE` — faixas e valores de custo de pessoas (FINANCEIRO, ADMIN).
* Escopo por credor como no analytics: quem não vê todos os credores não vê rateio de fixos nem totais da assessoria.

## 8. Fases

1. **Fase 1 (MVP):** cadastros, apuração diária estimada, painel e exportação XLSX/CSV. A tendência de 6 meses por
   credor e o mapa de dispersão do §6.1, adiados na primeira entrega (decisão 1253), foram feitos depois — ver §14.
2. **Fase 2:** fatura real e conciliação (**feito**, §13), atendimentos simultâneos e tempo de chat no custo de pessoas
   (**feito**, §4.1), relatório em PDF, custo por campanha e por régua, PTAX automática (issue).
3. **Fase 3:** simulação "e se" (ex.: preço do contrato, troca de canal, mais automação) e alerta de carteira com
   margem negativa por dois meses.

## 9. Decisões do responsável (12/11/2026)

* **Q1 — custo de pessoas:** **faixa por cargo** (opção A). Sem valor individual.
* **Q2 — tempo ocioso:** **separado como ociosidade**. A margem de contribuição usa só o tempo direto; a ociosidade
  entra na margem líquida, rateada pelo tempo direto de cada credor da mesma pessoa no dia.
* **Q3 — rateio padrão dos fixos:** **por horas humanas** (direto + ociosidade); cada custo fixo pode trocar a regra.
* **Q4 — escopo:** desenho e **fase 1**.

## 10. Contrato da fase 1

### 10.1 Banco (`costing`, V2500+; RLS por assessoria; `check` nos enums)

* `costing.cost_band` (id, name, hourly_cost numeric(12,2) > 0, valid_from date, valid_to date null, created_at/by) —
  um nome pode ter várias vigências sem sobreposição.
* `costing.user_cost_band` (user_id, band_name, valid_from, valid_to) — a pessoa aponta para o **nome** da faixa; o
  valor vem da vigência da faixa no dia.
* `costing.channel_tariff` (channel `WHATSAPP_UTILITY|WHATSAPP_MARKETING|WHATSAPP_AUTHENTICATION|SMS|EMAIL`,
  unit_cost numeric(12,6), valid_from, valid_to).
* `costing.fx_rate` (month date — primeiro dia, usd_brl numeric(12,6)).
* `costing.overhead` (id, month, description, amount, allocation `HOURS|RECOVERED|CASES|CREDITOR`, creditor_id null
  — obrigatório só com `CREDITOR`).
* `costing.cost_daily` (day, creditor_id, portfolio_id null, category, amount numeric(19,6), quantity numeric(19,4),
  unit, basis `ESTIMATED`), chave (day, creditor_id, portfolio_id, category). Categorias: `HUMAN_DIRECT`,
  `HUMAN_IDLE`, `AI`, `TELEPHONY`, `MESSAGING`, `VARIABLE_COMMISSION`, `LEGAL`, `OVERHEAD`.
* `costing.refresh_state` (último dia apurado, versão).

Sem faixa vigente para a pessoa, as horas entram com custo zero e o painel avisa "N horas sem faixa de custo".
Sem câmbio do mês, o custo de IA usa o último câmbio informado e o painel avisa; sem nenhum, a IA fica em zero com aviso.

### 10.2 REST (`/api/v1/costing`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/bands` | `GET`, `POST` (nova faixa ou nova vigência), `POST /{id}/end` | `GET`: `COST_VIEW` ou `COST_RATE_MANAGE` (`hourlyCost` só para quem tem `COST_RATE_MANAGE`; para os demais vem `null` — só o nome); escrita: `COST_RATE_MANAGE` |
| `/user-bands` | `GET` (usuário → faixa vigente), `PUT /{userId}` (faixa a partir de uma data) | `COST_RATE_MANAGE` |
| `/tariffs` · `/fx-rates` · `/overheads` | `GET`, `POST`, `POST /{id}/end` (tarifa) ou `DELETE /{id}?reason=` (custo fixo de qualquer mês; refaz o rateio do mês na hora; motivo opcional, até 500 caracteres, na auditoria — decisão 1247) | `GET`: `COST_VIEW`; escrita: `COST_MANAGE` |
| `/margin` | `GET ?from=&to=&creditorId=&portfolioId=` → totais, por credor (com carteiras), por mês e avisos (até 200 credores e 500 carteiras por pedido; acima, 400) | `COST_VIEW` + escopo por credor |
| `/margin/trend` | `GET ?from=&to=&creditorId=&portfolioId=` → série dos 6 meses até o mês de `to`, por credor e carteira (§14.1) | `COST_VIEW` + escopo por credor |
| `/margin/export` | `GET ?format=xlsx|csv&…` | `COST_VIEW` + `REPORT_EXPORT` (registra `DATA_EXPORTED`) |
| `/refresh` | `POST ?from=&to=` (reapura o período: até ontem, no máximo 93 dias; auditoria `costing.refreshed`). Com outra apuração da assessoria em andamento, **409 `costing.refresh_running`** na hora (não espera) | `COST_MANAGE` ou `COST_RATE_MANAGE` + todos os credores |

Resposta de `/margin`: `{ period, basis, totals: Line, creditors: [ Line & { creditorId, portfolios: [Line & {
portfolioId }] } ], months: [ Line & { month } ], warnings: [ { code, count } ] }`, com
`Line = { recovered, revenue, costs: { HUMAN_DIRECT, HUMAN_IDLE, AI, TELEPHONY, MESSAGING, VARIABLE_COMMISSION, LEGAL,
OVERHEAD }, directCost, totalCost, contributionMargin, netMargin, contributionMarginRate, netMarginRate, roi,
costPerRecovered, settledAgreements, costPerSettledAgreement, directHours, idleHours }` (taxas nulas sem base).
Quem não vê todos os credores recebe `HUMAN_IDLE` e `OVERHEAD` só dos próprios credores e nenhum total da assessoria
além da soma deles.

## 11. Implementação da fase 1 (backend)

Módulo `br.com.gestorcobranca.costing` (`allowedDependencies = shared, organization :: usecases, identity :: usecases`),
migrations em `db/migration/costing/` (faixa V2500–V2599).

### 11.1 Banco

* `V2500__costing_tables.sql`: as tabelas do §10.1 com RLS (`shared.enable_tenant_rls`), `check` nos enums e
  **exclusion constraint** (btree_gist) contra vigências sobrepostas de faixa (por nome), faixa da pessoa (por usuário)
  e tarifa (por canal). `fx_rate` é único por mês. Além do contrato:
  * `costing.activity_daily` (dia × credor × carteira): recuperado líquido, recuperado bruto, receita da assessoria,
    acordos quitados e casos-dia trabalhados — base do painel e dos rateios `RECOVERED` e `CASES`;
  * `costing.cost_warning_daily` (dia × credor × carteira × código): base dos avisos, gravada por credor para respeitar
    o escopo;
  * `cost_daily.unit` (`HOURS`, `MINUTES`, `UNITS`, `USD`, `ENTRIES`, `BRL`) e `refresh_state.version` = versão das
    regras (`CostRefreshService.RULES_VERSION`).
* `R__costing_contract.sql` (repetível): views `costing.src_*` `security_invoker` sobre interação, chamada, conta do
  provedor, tempo logado, comissão variável, turnos do assistente, Importação PDF, mensagem (com a categoria do
  template), custas jurídicas (carteira pelo processo), recebimento, lançamentos de comissão e acordo. Mesmo padrão do
  analytics: para mudar uma coluna usada, a migration do produtor remove a view e o mesmo PR altera o arquivo.
* Credor nulo nos fatos = valor da assessoria sem credor (ver 11.3). Nenhuma linha de fato tem usuário ou devedor.

### 11.2 Apuração (`CostRefreshService`, job `costing.refresh`)

* Job diário às 05:30 (`app.costing.refresh-cron`, fuso de operação), ShedLock, assessoria por assessoria, reprocessa
  os últimos 35 dias até ontem (`app.costing.refresh-days`). `POST /refresh?from=&to=` reapura um período (até
  **ontem** — o dia de hoje ainda não fechou —, no máximo 93 dias, `app.costing.max-refresh-days`). Uma transação por
  assessoria, com lock consultivo por assessoria (`pg_advisory_xact_lock`) e `statement_timeout` de 10 min.
* **Concorrência**: o job agendado **espera** o lock (`CostFactStore.lockForRefresh`); as rotas HTTP — reapuração
  manual e o rateio refeito ao criar ou excluir custo fixo — só **tentam** (`tryLockForRefresh`, mesma chave e mesmo
  `statement_timeout`) e, se outra apuração está em andamento, falham na hora com 409 `costing.refresh_running` ("A
  apuração dos custos está em andamento: tente de novo em alguns minutos"), sem prender a requisição nem a conexão. O
  custo fixo recusado não é gravado (a transação é desfeita).
* Regras no domínio (`HumanCost`, `UsageCost`, `WhatsAppConversations`, `OverheadAllocator`, `RateBook`,
  `MarginCalculator`), com testes em `CostRulesTest` e `MarginCalculatorTest`; o SQL só lê e agrega:
  * **HUMAN_DIRECT**: trechos de trabalho da pessoa — interações de pessoa (`actor_type = USER`, com operador e
    duração), pós-atendimento das chamadas com credor e respostas escritas no chat (`costing.src_chat_message`) — pela
    linha do tempo da pessoa (§4.1: tempo simultâneo dividido, sem contar duas vezes; cortado por dia) × valor-hora
    da faixa da pessoa no dia. Sem faixa: custo zero e aviso `HOURS_WITHOUT_BAND` no credor/carteira.
  * **HUMAN_IDLE**: `max(0, logado − direto)` da pessoa no dia, rateado pelo tempo direto dela no dia; sem tempo direto
    no dia, tudo vai para a linha **sem credor**.
  * **AI**: assistente (`started_at`) + Importação PDF (`created_at`) em dólar × câmbio do mês; sem câmbio do mês, o
    último anterior (aviso `MISSING_FX_RATE`); sem nenhum, zero (aviso `NO_FX_RATE`). Valor em `BRL` entra como veio;
    moeda nula é tratada como dólar.
  * **TELEPHONY**: por chamada com conversa, `ceil(talk_seconds/60)` × `rate_per_minute` da conta usada (dia da
    entrada na fila). Conta sem tarifa: zero e aviso `MISSING_TARIFF` (em minutos). Chamada sem credor → sem credor.
  * **MESSAGING**: WhatsApp — template enviado ao provedor (`sent_at`) abre janela de 24 h por conversa e categoria
    (`provider_category`); outro template da mesma categoria dentro da janela não cobra. A leitura começa 2 dias antes
    do período para conhecer as janelas já abertas. SMS e e-mail: por mensagem saída enviada ao provedor (`sent_at`),
    mesmo que a entrega falhe depois. Sem tarifa vigente: zero e aviso `MISSING_TARIFF` (em unidades). Tarifa sempre
    por unidade (e-mail por mensagem, não por mil).
  * **VARIABLE_COMMISSION**: `credited_amount` dos lançamentos não dispensados (`WAIVED`), acúmulo na data do
    pagamento, estorno na data em que foi lançado (pessoa e equipe).
  * **LEGAL**: custas com `advanced_by = AGENCY`, não reembolsáveis e não canceladas, na data em que incorreram.
  * **OVERHEAD**: todo mês que toca o período é rateado de novo por inteiro, depois de gravados os fatos que servem de
    base: `HOURS` = horas diretas + ociosas com credor; `RECOVERED` = recuperado bruto (pagamentos, sem estornos, para
    não ter base negativa); `CASES` = casos-dia com interação (pessoa ou assistente); `CREDITOR` = em partes iguais
    pelos dias do mês, sem carteira. Rateio diário (base do dia × credor × carteira ÷ base do mês), 6 casas, com a
    diferença de arredondamento na maior base. Sem base no mês: valor sem credor no dia 1 e aviso
    `OVERHEAD_WITHOUT_BASIS`. Criar ou excluir um custo fixo refaz o rateio do mês na hora.
* **Receita** (`activity_daily`) = comissão + valor fixo + honorários da assessoria dos lançamentos de cada
  recebimento, na data do fato do recebimento; acordos quitados pela data de quitação (`settled_at`).

### 11.3 REST (`/api/v1/costing`, `CostingController`)

Rotas e corpos do §10.2, com estes detalhes:

* `GET /margin` e `/margin/export`: `from`, `to` (`AAAA-MM-DD`; sem os dois = mês anterior fechado; até 731 dias),
  `creditorId` e `portfolioId` repetidos; export com `format=xlsx|csv`.
* Resposta de `/margin`: `period {from,to}`, `basis = "ESTIMATED"`, `totals`, **`unallocated`** (linha sem credor;
  `null` para escopo restrito ou com filtro de credor/carteira), `creditors[]` (com `name` e `portfolios[]` com
  `name`; `portfolioId` nulo = custos do credor sem carteira), `months[]` (`month` = `AAAA-MM`, um por mês do período),
  `warnings[] {code, count}`, **`computedUntil`** e **`refreshedAt`**. `Line.costs` traz as 8 categorias. Valores com 2
  casas; taxas (`contributionMarginRate`, `netMarginRate`, `roi`, `costPerRecovered`) em fração com 4 casas; `null`
  sem base.
* Avisos: `HOURS_WITHOUT_BAND` (horas, 1 casa), `MISSING_FX_RATE` e `NO_FX_RATE` (meses), `MISSING_TARIFF`
  (minutos/conversas/mensagens), `OVERHEAD_WITHOUT_BASIS` (custos fixos), `NOT_REFRESHED` (dias do período ainda não
  apurados).
* Cadastros: `GET /user-bands` lista as pessoas habilitadas (ativas ou aguardando ativação), com e sem faixa, sem
  valor-hora; `GET /user-bands/{userId}` (histórico) além do §10.2; `PUT /user-bands/{userId}` com `bandName` vazio =
  sem faixa a partir da data; data igual ao início de uma vigência futura corrige a faixa dela. `POST /fx-rates`
  substitui o mês já informado (auditado). `POST /overheads`: `month` `AAAA-MM`, `allocation` padrão `HOURS`.
  `DELETE /overheads/{id}?reason=` vale para qualquer mês (o rateio é refeito na hora e a exclusão fica na auditoria;
  `reason` até 500 caracteres). `POST /refresh` aceita `COST_MANAGE` ou `COST_RATE_MANAGE`.
* Validação dos parâmetros: `MarginParams` com `@Valid` (`creditorId` até 200, `portfolioId` até 500) e `reason` com
  `@Size(max = 500)`; acima do limite, 400 da validação do Spring MVC.
* `GET /bands`: `hourlyCost` só para quem tem `COST_RATE_MANAGE`; quem tem só `COST_VIEW` recebe o nome, a vigência e
  `hourlyCost: null` (o valor-hora é a remuneração de um cargo — LGPD, decisão 1258).
* Escrita dos cadastros exige acesso a **todos os credores** (são da assessoria), exceto custo fixo `CREDITOR` (exige o
  credor). Quem tem escopo restrito vê, em `GET /overheads`, só os custos diretos dos seus credores.
* Auditoria (`AuditEntry`): `costing.band_created|band_ended`, `costing.user_band_assigned|user_band_changed|
  user_band_ended`, `costing.tariff_created|tariff_ended`, `costing.fx_rate_created|fx_rate_changed`,
  `costing.overhead_created|overhead_deleted` e `costing.refreshed` (reapuração manual: período e linhas; entidade
  `cost_refresh` com o id da assessoria).

### 11.4 Exportação

`MarginExportService`: XLSX com as abas **Resumo**, **Por credor** (com "Sem credor" e "Total"), **Por carteira**,
**Por mês** e **Premissas** (faixas pelo nome — o valor-hora só para quem tem `COST_RATE_MANAGE`; para os demais, o
nome e a vigência —, tarifas, câmbio, custos fixos com a regra e as regras de cálculo — nunca a faixa de uma pessoa);
CSV no formato longo `secao;chave;rotulo;indicador;valor` (faixa sem permissão do valor: indicador `inicio_vigencia`).
Avisos com rótulo em português na aba Resumo ("Aviso: Horas de trabalho sem faixa de custo…") e na coluna `rotulo` do
CSV; o código (`HOURS_WITHOUT_BAND` etc.) fica só na coluna `chave`. `generatedOn` do log de acesso na data de negócio
(`TimeConfig.BUSINESS_ZONE`). Proteção contra
fórmulas pela `shared.export.SpreadsheetSafety` (movida do analytics para o shared), log `DATA_EXPORTED`
(`resource = costing_margin`, filtros, linhas, SHA-256). Exige `COST_VIEW` e `REPORT_EXPORT`.

### 11.5 Sandbox

`CostingSeeder` (etapa "custos operacionais e margem"): faixas fictícias (Negociador júnior R$ 38/h, pleno R$ 46/h,
Supervisor de equipe R$ 62/h), faixa dos usuários de demonstração, tarifas de canal, câmbio dos últimos 3 meses, dois
custos fixos do mês anterior (por horas e direto ao 1º credor) e a apuração dos últimos 60 dias.

### 11.6 Pendências da fase 1

* ~~Tempo de conversa no chat~~: entra na fase 2 pelas respostas escritas no chat (§4.1).
* Tarifa por tentativa de chamada não atendida (§3.2) não foi implementada (não está no contrato §10.1).
* Custo do chat de suporte interno (helpdesk) não entra: sem credor, fica para um custo fixo informado pela gestão.
* Mudança de cadastro (faixa, tarifa, câmbio) só vale para dias já apurados depois de uma reapuração (`POST /refresh`)
  ou quando o job diário alcança o período.

## 12. Telas da fase 1 (implementação)

Frontend em `frontend/src/app/features/costing` (`data/` com modelos do §10.2, API, rótulos e regras puras;
`components/waterfall-chart.ts`; `pages/margin-panel.*`; `settings/cost-settings.ts` e `cost-dialogs.ts`).

### 12.1 Permissões, rotas e menu

| Tela | Rota | Permissão (rota e menu) | Menu |
| --- | --- | --- | --- |
| Margem por carteira | `/paineis/margem` (em `analytics.routes.ts`, componente do `costing`) | `COST_VIEW` | Painéis → "Margem por carteira" (ícone `pie-chart`) |
| Custos operacionais | `/organizacao/custos` (em `organization.routes.ts`) | `COST_MANAGE` ou `COST_RATE_MANAGE` | Organização → "Custos operacionais" (ícone `receipt-cutoff`) |

`COST_VIEW`, `COST_MANAGE` e `COST_RATE_MANAGE` entraram em `core/auth/permission.ts` (grupo "Custos e margem"). Perfis
padrão simulados em `navigation.spec.ts`: `COST_VIEW` para GESTOR, FINANCEIRO, ADMIN e AUDITOR; `COST_MANAGE` e
`COST_RATE_MANAGE` para FINANCEIRO e ADMIN (menu: ADMIN 99, GESTOR 81, FINANCEIRO 44, AUDITOR 52 itens).

### 12.2 Painel "Margem por carteira"

* **Filtros na URL** (`margin-url-state.ts`): `de`, `ate`, `credor`, `carteira` (os mesmos nomes dos painéis, para
  links entre eles) e `ordem` (coluna da tabela; `-` = decrescente). Período padrão: **último mês fechado**. Diferente
  dos painéis do analytics, o filtro não é herdado do último painel aberto (a margem é mensal). A barra de filtros é a
  dos painéis (`app-analytics-filters` com período, credor e carteira); a API recebe listas repetidas
  (`?creditorId=a&creditorId=b`), como o analytics.
* **"Estimado"**: selo ao lado dos filtros com a explicação (uso × faixas, tarifas e câmbio), o "i"
  `analytics.margin.estimated` e o atalho `app-config-link` para Custos operacionais.
* **Avisos** (`warnings`) traduzidos em `warningView` com o efeito no número e o próximo passo, e o atalho para a aba
  certa da configuração (só para quem tem a permissão da aba): `HOURS_WITHOUT_BAND` → `?aba=faixas`
  (`COST_RATE_MANAGE`), `MISSING_FX_RATE` → `?aba=cambio` e `MISSING_TARIFF` → `?aba=tarifas` (`COST_MANAGE`). Código
  desconhecido vira texto genérico (sem expor o código); contagem zero não aparece.
* **Cartões** (6): receita, custo total, margem de contribuição, margem líquida (com ROI), custo por real recuperado e
  custo por acordo cumprido; cada um com o denominador no detalhe e o "i" do catálogo (`analytics.margin.*`, arquivo
  `manual/indicators/content/paineis-margem.ts`). Margem negativa: tom vermelho **e** "−"/"(negativa)" no texto.
* **Cascata** (`app-waterfall-chart`): receita → seis custos diretos → margem de contribuição → ociosidade e custos
  fixos → margem líquida; barras flutuantes sobre um eixo com zero (aceita negativo), valor escrito em cada linha,
  barra focável com rótulo acessível e tabela equivalente ("Ver tabela"). O mesmo componente mostra a **margem líquida
  por mês** (subtotais com sinal; a observação traz receita, custo e % do mês).
* **Tabela por credor** com linhas expansíveis por carteira (`aria-expanded`/`aria-controls`), cabeçalhos ordenáveis
  (`aria-sort`; padrão: menor margem líquida primeiro; taxa sem base sempre no fim), linha de total, margem negativa com
  marca lateral e texto "(negativa)". Colunas: recuperado, receita, custo direto, custo total, margem de contribuição
  (R$ e %), margem líquida (R$ e %) e ROI; **custo por categoria** (8 colunas) sob demanda pelo seletor "Mostrar o custo
  por categoria", para a tabela padrão caber sem rolagem lateral. Carteira nula = "Sem carteira (custos do credor)".
* **Drill-down**: recuperado e receita (cartão, linhas de credor e de carteira) levam ao Razão de comissão com o mesmo
  credor, carteira e período (`ledgerTarget` + `drillAccess` do analytics); sem a permissão do razão ou com mais de um
  credor/carteira no filtro, ficam como texto. Custos, margens e acordos cumpridos não têm lista que represente a base
  (a lista de acordos não filtra por data de quitação), então ficam como texto.
* **Ações**: "Exportar" (XLSX/CSV, `GET /costing/margin/export?format=xlsx|csv&…`) só com `REPORT_EXPORT`; "Reapurar o
  período" (`POST /costing/refresh?from=&to=`) só com `COST_MANAGE` ou `COST_RATE_MANAGE` **e todos os credores** (o
  servidor exige escopo total). O período enviado é o do filtro cortado em ontem; período sem dia fechado ou acima de 93
  dias avisa na tela sem chamar o servidor (`refreshPeriod` em `margin.rules.ts`). Quem não vê todos os credores recebe
  um aviso de que ociosidade, fixos e totais são só dos próprios credores.
* **Estados**: esqueleto ao abrir, barra de progresso ao trocar filtro, erro com "Tentar novamente" e vazio ("Sem
  receita nem custo no período") com o próximo passo.
* Nomes de credor e carteira resolvidos pelo cadastro (`CreditorNames`/`PortfolioNames`, só com `CREDITOR_VIEW`/
  `PORTFOLIO_VIEW`): a resposta traz só ids.

### 12.3 Configuração "Custos operacionais"

Abas com `urlTab` (`?aba=faixas|tarifas|cambio|fixos`); aba sem permissão não existe (nem na URL):

| Aba | Visível com | Escrita com | Conteúdo |
| --- | --- | --- | --- |
| Faixas de custo de pessoas | `COST_RATE_MANAGE` | `COST_RATE_MANAGE` | Faixas (nome, custo por hora, vigência, situação; "Nova vigência" e "Encerrar") e "Pessoas e faixas" (pessoa → **nome** da faixa vigente e desde quando; "Trocar faixa"; "Atribuir faixa" com o seletor de usuários do cadastro, só com `USER_VIEW`). Quem está sem faixa vem primeiro, em destaque |
| Tarifas de canal | `COST_MANAGE` ou `COST_VIEW` | `COST_MANAGE` | Canal (com a unidade cobrada), tarifa com até 4 casas, vigência; nova vigência e encerrar |
| Câmbio da IA | `COST_MANAGE` ou `COST_VIEW` | `COST_MANAGE` | Mês e "US$ 1 = R$ x"; informar ou corrigir o mês |
| Custos fixos | `COST_MANAGE` ou `COST_VIEW` | `COST_MANAGE` | Mês, descrição, valor e rateio (padrão por horas; direto a um credor exige o credor); excluir em qualquer mês, com confirmação que avisa que o rateio do mês é refeito e a margem muda, e motivo opcional (vai como `reason`) |

* **Escopo**: a escrita dos cadastros exige **todos os credores** (`auth.user().allCreditors`), como no servidor. Com
  escopo restrito, as abas continuam pela permissão, sem "Nova faixa", "Trocar faixa", "Atribuir faixa", tarifas e
  câmbio; o custo fixo continua para `COST_MANAGE`, mas o diálogo oferece só a regra "Direto a um credor" (credor do
  próprio escopo) e a exclusão aparece só nos custos diretos a um credor.
* Tarifa de canal aceita **zero** (`[min]="0"`; e-mail costuma ser desprezível). E-mail é cobrado **por mensagem**,
  como o servidor calcula (`CHANNEL_UNITS.EMAIL`).
* Valores digitados com `appMoney` (tarifa e câmbio com `[decimals]="4"`). Datas com o `ngbDatepicker`; mês por lista
  (24 meses para trás e 1 à frente; padrão: último mês fechado).
* **LGPD**: o valor-hora aparece só na lista de faixas; a lista de pessoas e o diálogo de atribuição mostram só o nome
  da faixa (teste em `cost-settings.spec.ts`). Nomes das pessoas pelo serviço de nomes de exibição
  (`/users/display-names`, só id e nome), ou `userName` quando o servidor mandar.
* Encerrar vigência pede o último dia (a partir do início da vigência); faixa, tarifa e câmbio não são apagados.
* `hourlyCost` anulável no modelo (`CostBand`): sem o valor, a coluna mostra "—" (a aba de faixas já é só de
  `COST_RATE_MANAGE`, que recebe o valor).

### 12.4 Onboarding e textos

Telas `analytics.margin` e `organization.costs` (versão 1) em `content/screens.ts`, tours curtos (`analytics.margin`,
6 passos; `organization.costs`, 3 passos), manual rápido em `paineis.ts` e `administracao.ts`, termos novos no
glossário (`margem-de-contribuicao`, `margem-liquida`, `faixa-de-custo`, `rateio`, `custo-estimado`), 20 indicadores
`analytics.margin.*` e a nota de versão 30 ("Margem por carteira e credor"). Base do chat regenerada (`npm run knowledge`).

### 12.5 Suposições sobre o contrato (a confirmar com o backend)

* `GET /margin` aceita `creditorId` e `portfolioId` **repetidos** (lista), como o analytics.
* `months[].month` vem como `AAAA-MM` ou `AAAA-MM-01` (os dois são aceitos); `period` = `{ from, to }`.
* `warnings[].count`: horas (pode ter decimal) em `HOURS_WITHOUT_BAND`, meses em `MISSING_FX_RATE` e envios em
  `MISSING_TARIFF`.
* `POST /bands/{id}/end` e `POST /tariffs/{id}/end` recebem `{ "validTo": "AAAA-MM-DD" }`; `POST /bands` recebe
  `{ name, hourlyCost, validFrom }`; `PUT /user-bands/{userId}` recebe `{ bandName, validFrom }`; `POST /tariffs`
  recebe `{ channel, unitCost, validFrom }`; `POST /fx-rates` recebe `{ month, usdBrl }` e substitui o mês já
  informado; `POST /overheads` recebe `{ month, description, amount, allocation, creditorId }`.
* `GET /user-bands` devolve `{ userId, userName?, bandName, validFrom, validTo }` e, de preferência, também as
  pessoas ativas **sem** faixa (`bandName: null`), para quem não tem `USER_VIEW` (o FINANCEIRO padrão) conseguir
  atribuir a primeira faixa pela lista.
* `GET /tariffs`, `/fx-rates` e `/overheads` aceitam `COST_MANAGE` além de `COST_VIEW` (a aba aparece para quem
  gerencia).
* `POST /refresh?from=&to=` reapura o período informado.

Fora desta entrega (fase 1 do desenho, a fazer): tendência dos últimos 6 meses por credor (a resposta não traz meses por
credor) e o mapa de dispersão carteira × margem × volume.

## 13. Fase 2 — conciliação com a fatura

Implementação do §3.5 (decisões 1300–1314). O custo de canal continua estimado pela apuração diária; a fatura entra
como **ajuste** separado, rateado pela estimativa, e o painel escolhe a base.

### 13.1 Banco (`V2501__costing_channel_invoice.sql`)

* `costing.cost_daily.item` (`TELEPHONY`, `WHATSAPP`, `SMS`, `EMAIL`, `AI`): item de custo de canal faturado. A
  apuração grava `MESSAGING` com uma linha por item (WhatsApp, SMS, e-mail); `TELEPHONY` e `AI` têm o item da própria
  categoria; as demais categorias ficam sem item. Um `check` amarra item e categoria. Fatos antigos: `TELEPHONY` e
  `AI` recebem o item na migration; `MESSAGING` fica com item nulo até a próxima apuração do dia (o job refaz 35 dias;
  meses mais antigos pedem "Reapurar o período" antes da fatura de mensagens — 409
  `costing.invoice_month_not_itemized`).
* `costing.cost_daily.basis` ganha `INVOICE_ADJUSTMENT`: o ajuste pela fatura (só nas categorias de canal, sempre com
  item, valor em R$, quantidade zero, unidade `BRL`). A chave única passa a incluir item e base. A estimativa
  (`ESTIMATED`) nunca é alterada pela fatura: `replaceFacts` só substitui a base estimada e as bases do rateio de fixos
  e da margem estimada filtram `basis = 'ESTIMATED'`.
* `costing.channel_invoice` (id, month — primeiro dia, item, amount `numeric(19,2)` ≥ 0, supplier até 120 opcional,
  note até 500 opcional, metadados de criação e alteração da `BaseEntity`), **única por mês × item**, RLS
  (`shared.enable_tenant_rls`), `check` no item, no mês e no valor.
* Aviso novo `INVOICE_WITHOUT_USAGE` em `cost_warning_daily` (fatura de item sem uso no mês).

### 13.2 Regra (`InvoiceReconciler`, domínio)

Para cada fatura do mês: **ajuste = fatura − estimativa do item no mês** (assessoria inteira, inclusive as linhas sem
credor). O ajuste é rateado na proporção do **custo estimado** do item em cada dia × credor × carteira do mês — a linha
sem credor (ex.: chamada sem credor) recebe a parte dela. Estimativa zero no mês (ex.: sem tarifa): rateio pela
**quantidade de uso** do item (minutos, conversas/mensagens, dólares de IA). Sem uso nenhum: o valor inteiro fica
**sem credor** no primeiro dia do mês, com aviso `INVOICE_WITHOUT_USAGE`. Partes com 6 casas e a diferença de
arredondamento na maior base (como o rateio de fixos): estimativa + ajuste = fatura, exatamente. Fatura igual à
estimativa não gera fato. Testes em `InvoiceReconcilerTest`.

* O ajuste é refeito **por mês inteiro**: na apuração (job diário e `POST /refresh`), em cada mês tocado, depois da
  estimativa e do rateio dos fixos; e ao informar, substituir ou excluir uma fatura
  (`CostRefreshService.reconcileInvoices`), com o mesmo `tryLockForRefresh` das rotas HTTP — com outra apuração em
  andamento, **409 `costing.refresh_running`** e a gravação é desfeita.
* O rateio dos custos fixos por horas, recuperado ou casos **não muda** (as bases não leem o ajuste).
* Sem dado pessoal nos fatos nem na fatura (fornecedor e observação são texto da assessoria).

### 13.3 REST (`/api/v1/costing`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/invoices` | `GET ?from=&to=` (meses `AAAA-MM`; sem os dois = últimos 24 meses; até 36 meses) → `[{ id, month, item, amount, supplier, note, estimated, adjustment, updatedAt }]` (`estimated` = estimativa do item no mês na assessoria inteira; `adjustment` = fatura − estimativa) | `COST_VIEW` ou `COST_MANAGE` + **todos os credores** |
| `/invoices/estimates` | `GET ?from=&to=` → um item por mês × canal: `{ month, item, estimated, quantity, unit (MINUTES/UNITS/USD), invoiceId, invoiced }` — a estimativa para comparar ao informar | idem |
| `/invoices` | `POST { month, item, amount, supplier?, note? }` → cria ou **substitui** a fatura do mês × item (200, a view acima); mês futuro → 422 `costing.invoice_future_month`; refaz o ajuste do mês na hora | `COST_MANAGE` + todos os credores |
| `/invoices/{id}` | `DELETE ?reason=` (até 500, na auditoria) → 204; refaz o ajuste do mês | idem |

Auditoria (`AuditEntry`, entidade `channel_invoice`): `costing.invoice_created` (mês, item, valor, fornecedor,
observação), `costing.invoice_changed` (antes e depois de valor, fornecedor e observação; mês e item em metadados) e
`costing.invoice_deleted` (com o motivo).

`GET /margin` e `/margin/export` aceitam **`basis=ESTIMATED|RECONCILED`** (padrão `ESTIMATED`, compatível com a fase
1; valor inválido → 400). Em `RECONCILED`, `Line.costs.TELEPHONY|MESSAGING|AI` (e os totais, margens e taxas
derivados) incluem o ajuste, e `basis` na resposta vem `RECONCILED`. A resposta ganha, nas duas bases:

```
reconciliation: {
  status: ESTIMATED | PARTIAL | RECONCILED,          // do período (todos os meses conciliados = RECONCILED)
  months: [ { month: "AAAA-MM", status, invoiced: [item], pending: [item] } ],
  adjustments: { TELEPHONY, MESSAGING, AI },          // ajuste no recorte visível (já nos custos em RECONCILED)
  invoices: [ { month, item, estimated, invoiced, adjustment } ] | null
}
```

* Situação do mês: `ESTIMATED` = nenhuma fatura; `RECONCILED` = todos os itens com uso estimado (valor ou quantidade)
  têm fatura (ou o mês não tem uso e tem fatura); `PARTIAL` = os demais. `pending` = itens com uso sem fatura.
* **Escopo** (regra do §10.2/§11.3): `adjustments` soma o mesmo recorte das linhas visíveis — quem tem escopo restrito
  vê só o ajuste rateado aos próprios credores, nunca a linha sem credor; `invoices` (valores da fatura, que são da
  assessoria) só vem para quem vê todos os credores **e** sem filtro de credor ou carteira (o mesmo critério de
  `unallocated`); nos demais casos, `null`. A situação dos meses (sem valores) vem para todos.
* Aviso `INVOICE_WITHOUT_USAGE` (faturas) só em `RECONCILED` e só para quem vê a linha sem credor.
* Exportação: a aba **Resumo** traz a base ("Estimada" ou "Conciliada com a fatura — situação") e, em `RECONCILED`, o
  ajuste por categoria; a aba **Premissas** lista as faturas dos meses do período (com estimativa e ajuste) só para
  escopo total. CSV: linhas `base;<basis>;<rótulo>;situacao_conciliacao;<situação>`,
  `ajuste_fatura;<categoria>;<rótulo>;ajuste;<valor>` e
  `premissas_faturas;<AAAA-MM>/<item>;<rótulo>;fatura|estimativa;<valor>`. O log `DATA_EXPORTED` ganha `basis`.

### 13.4 Telas

* **Custos operacionais → aba "Faturas"** (`?aba=faturas&mes=AAAA-MM-01`; mês padrão = último mês fechado; lista de
  24 meses sem mês futuro): visível com `COST_MANAGE` ou `COST_VIEW` **e todos os credores** (sem escopo total, a aba
  não existe nem pela URL); escrita só com `COST_MANAGE`. Tabela dos cinco canais do mês: uso na unidade cobrada,
  estimativa, fatura, diferença com sinal e ações (informar, corrigir, excluir com motivo opcional). Diálogo com mês,
  canal, valor (`appMoney`, zero vale), fornecedor e observação, mostrando ao lado a estimativa do mês do canal e a
  diferença que será rateada (ou o aviso de que, sem uso, o valor fica em "Sem credor"); mês × canal já faturado avisa
  que salvar substitui. Atalho "Ver a margem conciliada do mês" (só com `COST_VIEW`).
* **Painel de margem**: seletor "Estimado | Conciliado com a fatura" (grupo de rádio acessível), guardado na URL
  (`base=conciliado`; ausente = estimado). O selo vira "Conciliado" ou "Parcialmente conciliado" conforme
  `reconciliation.status` (sem fatura no período, segue "Estimado" com a explicação). Na base conciliada: quadro "Ajuste
  pela fatura" com "Telefonia: estimado R$ x, com a fatura R$ y (ajuste ±R$ z)" por categoria, canais com uso ainda sem
  fatura por mês (com atalho `app-config-link` para a aba Faturas, só com `COST_MANAGE` e escopo total) e, na visão da
  assessoria inteira, a tabela das faturas ao lado da estimativa; o cartão "Custo total" e a cascata citam o ajuste. Na
  base estimada, com faturas no período, a tela sugere a base conciliada. A exportação usa a base escolhida.
* Indicadores novos no catálogo: `analytics.margin.reconciled` e `analytics.margin.invoice-adjustment`; termo
  `custo-conciliado` no glossário; manual rápido de `analytics.margin` e `organization.costs` atualizado; tour
  `organization.costs` com o texto da aba nova (sem passo novo nem versão nova).

### 13.5 Sandbox e pendências

* `CostingSeeder` informa faturas fictícias de telefonia (R$ 180) e WhatsApp (R$ 95) do mês anterior, depois da
  apuração: a base conciliada do mês anterior abre como "Parcialmente conciliado".
* Pendências: versão das telas em `content/screens.ts`, nota de versão e base do chat (`npm run knowledge`) ficam para
  a integração; a apuração antiga de mensagens sem item (antes da V2501) só concilia depois de reapurada; a fatura em
  dólar da IA não é convertida pelo sistema (a gestão informa em reais; a PTAX automática segue como issue).

## 14. Fase 2 — tendência e mapa de dispersão

Itens do §6.1 que ficaram fora da primeira entrega (decisão 1253). Decisões 1315 a 1327.

### 14.1 Tendência dos últimos 6 meses (backend)

* **Rota própria** `GET /api/v1/costing/margin/trend` (`CostingController.marginTrend`, `MarginQueries.trend`), com os
  mesmos parâmetros de `/margin` (`MarginParams`: `from`, `to`, `creditorId` e `portfolioId` repetidos, `basis`, mesmos
  limites e a mesma validação de período) e `COST_VIEW`. Na base conciliada, soma o ajuste pela fatura como o painel
  (decisão 1327). A tela pede em paralelo à margem, para não pesar a resposta principal
  nem a exportação (decisão 1315).
* **Janela**: os 6 meses que terminam no mês de `to` (o `from` não muda a janela; sem os dois, o mês anterior
  fechado, como em `/margin`). Regra no domínio, `MarginTrendSeries` (teste `MarginTrendSeriesTest`).
* **Escopo**: o recorte é o de `/margin` (`MarginQueryService.resolve`): quem não vê todos os credores recebe só os
  próprios credores, com a ociosidade e os custos fixos já rateados a eles; pedido fora do escopo = lista vazia. A
  linha sem credor não entra (a tendência é por credor).
* **Desempenho**: as duas leituras agrupadas do painel (`MarginReader.costs` e `activity`, por credor × carteira × mês
  sobre `cost_daily` e `activity_daily`) com a janela de 6 meses — sem SQL novo e sem consulta por credor (até 200
  credores e 500 carteiras). Métrica técnica `costing.margin.trend.duration{outcome}`.
* **Resposta**:

  ```
  { period: {from, to},
    months: [ {month: "AAAA-MM", status: "COMPLETE" | "PARTIAL" | "NOT_REFRESHED"} ×6 ],
    computedUntil: "AAAA-MM-DD" | null,
    creditors: [ { creditorId, points: [Point ×6],
                   portfolios: [ {portfolioId | null, points: [Point ×6]} ] } ] }
  Point = { month, recovered, revenue, netMargin, netMarginRate }
  ```

  Credores ordenados pelo id (a tela junta pela chave com `/margin`); só os que têm algum fato na janela.
* **Situação do mês** (pelo último dia apurado): nenhum dia do mês apurado = `NOT_REFRESHED`; apurado até antes do
  último dia = `PARTIAL` (o mês corrente, normalmente); senão `COMPLETE`.
* **Ponto vazio, nunca zero**: mês `NOT_REFRESHED` ou sem nenhum fato do corte → todos os valores `null`. Mês só com
  custo → receita zero, margem em R$ (negativa) e `netMarginRate` nulo (as fórmulas do §5, `MarginCalculator`).
* Testes: `CostingMarginIT.tendenciaDeSeisMesesPorCredorComMesSemDadoEEscopo` (série de 6 meses com setembro só com
  receita e outubro só com custo, meses sem fato vazios, outubro igual ao painel, mês não apurado, escopo restrito,
  filtro de carteira, permissão, contrato JSON e 403 por HTTP).

### 14.2 Telas (frontend)

* **Coluna "Tendência"** na tabela por credor e carteira (`components/trend-sparkline.ts`, regras em
  `data/margin-trend.rules.ts`): minigráfico SVG de 72 × 22 px da **margem líquida em % da receita** dos 6 meses —
  linha interrompida no mês sem receita ou não apurado, ponto em cada mês com base, ponto final maior, linha do zero
  tracejada quando a série fica negativa e pontos negativos em vermelho. Texto acessível (`role="img"`) com a frase da
  tendência: "Margem líquida de mai. a out.: subiu de 8,0% para 12,0% da receita. 1 mês sem receita." (compara o
  primeiro e o último mês com receita; menos de 1 ponto percentual = "estável"; negativo com "(negativa)"; mês não
  apurado e parcial citados). Dica no ponteiro e no foco com o valor de cada mês (posição fixa, sem ser cortada pela
  rolagem da tabela). Estados: "Carregando…", "Indisponível" (falha só da tendência; o painel segue) e "sem base".
  A coluna é estreita: a tabela padrão continua sem rolagem lateral. "i": `analytics.margin.trend`.
* **Mapa de dispersão** "Carteiras: margem × volume recuperado" (`components/margin-scatter.ts`, regras em
  `data/margin-scatter.rules.ts`), cartão entre os gráficos e a tabela (`data-tour="margin-scatter"`):
  * cada carteira do período é um ponto: x = recuperado (**escala logarítmica**, marcas em potências de 10), y =
    margem líquida % da receita (piso de −100%: abaixo fica na borda, com aviso na legenda), área pela receita;
  * **cortes** (escritos na tela e no "i" `analytics.margin.scatter`): volume = mediana do recuperado das carteiras do
    mapa; margem = margem líquida % do conjunto do mapa (soma das margens ÷ soma das receitas), ou zero quando ela é
    negativa. Volume ≥ corte = alto; margem ≥ corte **e** positiva = alta. Quadrantes: **Rende e escala** (margem e
    volume altos), **Rende pouco** (margem alta, volume baixo), **Consome** (volume alto, margem baixa) e **Revisar
    contrato** (volume e margem baixos);
  * fora do mapa, com a contagem "N carteiras sem base": carteira sem receita (taxa nula) ou sem valor recuperado; a
    linha "Sem carteira (custos do credor)" não é carteira e não conta;
  * margem negativa em vermelho **e** losango, com "(negativa)" no rótulo, na dica e na tabela;
  * pontos focáveis (`role="link"`) com rótulo completo (carteira, credor, recuperado, receita, margem e quadrante) e
    dica no ponteiro e no foco; clique ou Enter abre o painel filtrado pelo credor e pela carteira do ponto, mantendo
    período e ordem, como **passo novo do histórico** (`marginUrlState().open`, sem `replaceUrl`) — o "voltar" retorna
    ao mapa;
  * "Ver tabela": carteira (link para o mesmo filtro), credor, recuperado, receita, margem e quadrante, ordenada por
    quadrante.
* Textos: indicadores `analytics.margin.trend` e `analytics.margin.scatter` (`paineis-margem.ts`), manual rápido
  (`paineis.ts`) e tour `analytics.margin` (passo "Margem × volume" no lugar de "Custos incompletos" — decisão 1324).
  A `version` da tela, a nota de versão e a base do chat (`npm run knowledge`) ficam para a integração.
* Testes: `margin-trend.rules.spec.ts` (frase, lacunas, parcial, não apurado, geometria), `margin-scatter.rules.spec.ts`
  (mediana, cortes, quadrantes, sem base, escala) e `margin-panel.spec.ts` (coluna com a frase por credor e carteira,
  falha só da tendência, mapa com cortes, contagem, losango, Enter → filtro com histórico novo e tabela com links).

### 14.3 Pendências

* ~~Base da tendência~~: feito na integração — `/margin/trend` aceita `basis` e a tela envia a base do seletor
  (decisão 1327).
* A situação do mês só conhece o último dia apurado: meses anteriores ao início da apuração aparecem como completos e
  vazios (decisão 1317).
* "Evolução mensal do mix de custo" (§6.1) continua fora: o gráfico mensal mostra só a margem líquida.
