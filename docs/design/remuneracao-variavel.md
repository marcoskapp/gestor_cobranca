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
| Retornos cumpridos | callbacks realizados até o horário ÷ agendados | collection |
| Conversas recebidas do assistente | transferências (handoff) atendidas | communication / assistant |

### 2.2 Conversão

| Métrica | Fórmula |
| --- | --- |
| Contato → proposta | propostas criadas ÷ CPC |
| Proposta → acordo | acordos formalizados ÷ propostas |
| Contato → acordo | acordos ÷ CPC |
| Promessas cumpridas | promessas KEPT ÷ promessas vencidas |
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
- **Recuperação esperada** (quando o módulo `scoring` existir): recuperado ÷ esperado pelo score da carteira atribuída — o indicador mais justo, porque desconta a qualidade da carteira recebida.
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

`incentive` → `organization`, `identity` (equipes, usuários). Os demais fatos chegam **somente por eventos de integração**: `AgreementAttributionRecorded`, `AgreementStatusChanged`, `InstallmentStatusChanged`, `PaymentConfirmed`, `PaymentReversed`, `InteractionRecorded`, `CaseStatusChanged`, `CallCompleted`, `MessageStatusChanged`, e os de handoff do assistente. Os indicadores do §2 são materializados em tabelas de fatos próprias (`performance_fact` diário por negociador/segmento), que o `analytics` pode ler por views.
