# Desenho — módulo `negotiation` (políticas, cálculo, alçadas, propostas e acordos)

Escopo: M06, F08, F09, F16, RB03, RB04, RB05. Schema `negotiation`, migrations V0700–V0799 (parte 1: V0700–V0749; parte 2: V0750–V0799).
Dependências: `shared`, `organization`, `identity`, `debt`, `collection` (interfaces `usecases`).

> **Estado (23/10/2026).** Parte 1 (§1–§4, simulação, `NegotiationGuard`, alçadas — decisões em §9) e parte 2 (propostas e aprovação §5, acordos, parcelas, quebra, renegociação, termo §6, atribuição, elegibilidade sobre o `collection`, LGPD — contratos e decisões em §10) implementadas.

Requisito transversal: **humanos e o assistente de IA passam exatamente pela mesma validação**. Nenhum caminho (tela, API, automação, assistente) aplica condição sem passar por `NegotiationGuard` no servidor.

## 1. Política de cobrança e negociação (versionada)

`CollectionPolicy` por credor, com sobreposições opcionais por carteira e por faixa de atraso. Estados `DRAFT → PUBLISHED → RETIRED`; publicar cria nova versão imutável com `effectiveFrom`. Propostas e acordos guardam a versão usada (RB03: reproduzir propostas antigas).

| Grupo | Parâmetros |
| --- | --- |
| Encargos | juros de mora (% a.m., simples ou compostos), multa (%), correção (índice e regra, quando contratual), encargos autorizados, honorários (% e base de cálculo) |
| Descontos máximos | por componente: principal, juros, multa, encargos, honorários |
| Parcelamento | parcelas máximas, valor mínimo de parcela, entrada mínima (% ou valor), juros do parcelamento, intervalo entre vencimentos |
| Validade | dias de validade da proposta; dia máximo para a 1ª parcela |
| Escada de concessões | níveis ordenados (ver §3) |
| Quebra de acordo | parcelas em atraso / dias de tolerância que caracterizam quebra (RB05) |

## 2. Cálculo (domínio, puro e testável)

`DebtCalculator`: (títulos + versão da política + data de referência) → `DebtComposition` por título e total (principal, juros, multa, encargos, honorários, pagamentos, saldo) com **memória de cálculo** persistida.

`OfferEnvelope`: as concessões máximas permitidas pela política para aquela composição, por forma de pagamento (à vista, parcelado por número de parcelas).

## 3. Escada de concessões

A política define níveis (ex.: nível 0 = sem desconto; nível 1 = desconto em multa e juros; nível 2 = desconto parcial em honorários; nível 3 = limite da política, só à vista). A simulação devolve ofertas **até o nível permitido ao ator**; subir de nível exige justificativa registrada (motivo da concessão). Assim o assistente e os negociadores não abrem o limite de início, e a operação mede em que nível os acordos fecham.

## 4. Perfis de alçada

`AuthorityProfile` (por tenant, opcionalmente restrito a credores) define **quanto do envelope** um ator pode usar sem aprovação:

| Parâmetro | Exemplo |
| --- | --- |
| `maxConcessionLevel` | nível máximo da escada |
| `maxDiscountShare` | fração do desconto máximo da política (ex.: 50%) |
| `maxInstallments` | limite próprio, nunca acima da política |
| `maxAgreementAmount` | valor máximo do acordo |
| `minDownPaymentShare` | entrada mínima própria (≥ política) |
| `canFormalize` | pode formalizar sem aprovação |
| `canApprove` | pode aprovar propostas de outros até o próprio limite |
| `maxRenegotiations` | renegociações de acordo quebrado permitidas |

Perfis semeados no provisionamento (ajustáveis): `JUNIOR`, `PLENO`, `SENIOR`, `SUPERVISOR`, `GESTOR` e `ASSISTENTE_INICIAL`.

Atribuição: usuário → perfil (opcionalmente por credor); assistente → perfil via `AssistantPolicy` (módulo `assistant`). O limite efetivo é sempre **mínimo(envelope da política, perfil do ator)**.

## 5. Propostas e aprovação

`Proposal`: `DRAFT → PENDING_APPROVAL → APPROVED | REJECTED`; `APPROVED/DRAFT dentro da alçada → ACCEPTED (pelo devedor) → FORMALIZED`; `EXPIRED` pela validade; `HANDED_OFF` (sugestão do assistente transferida a humano). Detalhes em §10.2.

- Proposta dentro da alçada do proponente: segue direto para aceite.
- Acima da alçada: `PENDING_APPROVAL`, roteada para a fila de aprovadores com alçada suficiente (F16). Proposta do assistente acima da alçada **não** vai para aprovação: gera transferência do atendimento para humano com a proposta como sugestão.
- Toda proposta registra: ator (tipo e id), versão da política, nível de concessão, memória de cálculo, canal.

`NegotiationGuard.validate(actor, proposal)` aplica: política vigente, envelope, perfil de alçada do ator, elegibilidade dos títulos (sem contestação impeditiva, carteira operável), escopo de credor.

## 6. Acordos (RB05)

`Agreement`: `FORMALIZED → IN_PAYMENT → SETTLED | BROKEN | CANCELLED`; `RENEGOTIATED` quando substituído (o histórico é preservado e as obrigações antigas não contam como nova recuperação). Detalhes em §10.3–§10.6.

- Formalização exige **evidência de aceite**: canal, instante, identificador da mensagem ou sessão do portal, IP quando houver, e hash do termo (PDF gerado e armazenado como `AGREEMENT_TERM`).
- `AgreementInstallment` com vencimento, valor, status (`OPEN, PAID, PARTIALLY_PAID, OVERDUE, CANCELLED`).
- Quebra conforme a regra da política; atraso isolado dentro da tolerância não quebra.
- Lançamentos no razão do `debt`: encargos reconhecidos (`applyCharges`) na medida em que são pagos, pagamentos por componente (`applyPayment`) e, quando o título fica integralmente pago, os encargos brutos restantes e o desconto concedido (`applyDiscount`) — §10.5.
- Eventos `AgreementStatusChanged` / `InstallmentStatusChanged`; eventos de negócio com atributo `actorType` (USER ou ASSISTANT) e `concessionLevel`, para comparar desempenho de humanos e assistente.

## 7. Copiloto para negociadores humanos

Os mesmos cálculos alimentam a tela de negociação com intensidade definida pelo perfil do operador: júniores recebem a próxima oferta sugerida e bloqueio imediato de oferta inválida; seniores veem o envelope completo e o histórico. A sugestão textual vem do módulo `assistant` em modo copiloto.

## Atribuição do acordo (insumo da remuneração variável)

Na formalização, `negotiation` registra os participantes do acordo e publica `shared.events.negotiation.AgreementAttributionRecorded` com: `agreementId`, `caseId`, `creditorId`, `portfolioId`, `origin` (`HUMAN`, `HUMAN_COPILOT`, `ASSISTANT_HANDOFF_NEGOTIATED`, `ASSISTANT_HANDOFF_CONFIRMED`, `ASSISTANT_AUTONOMOUS`, `PORTAL_SELF_SERVICE`, `CAMPAIGN`), lista de participantes (`actorType`, `actorId`, `role` = `CLOSER`/`ORIGINATOR`/`PRE_NEGOTIATOR`/`APPROVER`/`INFLUENCER`), nível de concessão, uso do envelope (%), faixa de atraso e `replacedAgreementId`. `HANDOFF_CONFIRMED` × `HANDOFF_NEGOTIATED` é decidido no servidor comparando a proposta formalizada com a sugestão do handoff. Regras de uso em `docs/tecnico/desenho/remuneracao-variavel.md`.

## 9. Implementação — parte 1 (decisões e contratos)

### 9.1 Componentes

| Componente | Onde | Papel |
| --- | --- | --- |
| `CollectionPolicy` + `PolicyTerms`/`PolicyRules`/`PolicyOverride` | `domain.model.policy` | versão por credor; termos tipados em jsonb (esquema v1) |
| `MonetaryIndex`/`MonetaryIndexRate` + `MonetaryIndexProvider` | `domain.model.index`, `port.out`, `ManualMonetaryIndexProvider` | tabela manual de índices por tenant |
| `DebtCalculator` → `DebtComposition` | `domain.service` | cálculo puro com memória (`CalculationLine`) — `ENGINE_VERSION = 1` |
| `OfferCalculator` | `domain.service` | descontos por componente e cronograma (Price) |
| `TermsEvaluator` | `domain.service` | regras de validação puras: POLICY × AUTHORITY |
| `AuthorityProfile`/`AuthorityAssignment` + `AuthorityResolver` | `domain.model.authority`, `application.service` | perfis de alçada e resolução por ator/credor |
| `NegotiationGuard` | `application.service` | validação única (escopo, política, títulos, elegibilidade, cálculo, alçada) |
| `NegotiationSimulation` | `domain.model.simulation` | registro append-only com a memória completa |
| `CaseEligibilityPort` + `DebtBasedCaseEligibilityAdapter` | `port.out`, `infrastructure.eligibility` | elegibilidade provisória (debt + organization) |

REST (`/api/v1/negotiation`): `policies` (CRUD de rascunho, `/{id}/versions`, `/{id}/publish`, `/{id}/retire`, `/effective`, `/template`), `indexes` (`/{code}/rates`), `authority-profiles`, `authority-assignments`, `authority/effective`, `authority/me`, `simulations`, `validations`.

### 9.2 Política versionada

- Uma linha do tempo de versões por credor; um rascunho por credor (índice parcial único). `newVersion` copia os termos.
- Publicação a partir de hoje ou de data futura (nunca retroativa); a versão anterior tem a vigência encerrada no dia anterior (`supersede`); versão agendada substituída antes de começar fica sem vigência. Aposentar aceita fim de vigência a partir de ontem (retirada imediata).
- Versão vigente numa data = maior `policyVersion` entre as publicadas/aposentadas cuja vigência alcança a data.
- Imutabilidade reforçada por gatilho: termos, credor, versão, início e esquema não mudam após publicar, e a vigência só pode ser encurtada.
- Sobreposições substituem **grupos inteiros** (encargos, faixas de desconto, parcelamento, validade, escada, quebra). Especificidade: carteira + faixa (3) > carteira (2) > faixa (1); sobreposições de mesma especificidade e mesma carteira não podem ter faixas que se interceptem. Cada combinação base + sobreposição é validada por inteiro.
- Na negociação, a carteira usada na resolução é a carteira comum dos títulos (nula se houver mais de uma) e a faixa é o **maior atraso** entre eles.
- Desconto máximo por forma de pagamento: lista de faixas `upToInstallments` (1 = à vista); acima da maior faixa não há desconto.
- A contagem de parcelas **inclui a entrada** (à vista = 1 pagamento).
- O `POLICY_PUBLISHED` (`policy.published`) é reutilizado com `policyKind = collection` (a política de contato do compliance usa `contact`).

### 9.3 Motor de cálculo (`DebtCalculator`, contrato versão 1)

1. Dias de atraso = dias corridos (vencimento, referência]; título não vencido não recebe mora.
2. Mora incide **somente sobre o principal em aberto** do razão; encargos já reconhecidos no razão (juros, multa, encargos, honorários em aberto — ex.: de acordo anterior) entram como estão.
3. Correção: fator Π (1 + rₘ)^(kₘ/nₘ) por mês (pro rata die composto); competências após a última publicada valem 0% com aviso (métrica `negotiation.calculation.index_pending`); lacuna antes dela é erro (`negotiation.index_rate_missing`); deflação acumulada não reduz o principal.
4. Juros de mora sobre a base corrigida, mês comercial de 30 dias: simples B·i·d/30; compostos B·((1+i)^(d/30) − 1).
5. Multa uma vez sobre a base corrigida. Encargos autorizados: fixos por título vencido ou % do principal.
6. Honorários = reconhecidos + base × h, base `PRINCIPAL`, `UPDATED_PRINCIPAL` ou `UPDATED_DEBT` (padrão). Nas ofertas, os honorários calculados são **recalculados sobre a base descontada** antes do desconto próprio.
7. Arredondamento: fatores com 34 dígitos (`MathContext.DECIMAL128`, sem `double`); cada componente de cada título arredondado **uma vez** para centavos com `HALF_EVEN`; totais = soma dos valores arredondados por título. Entradas mínimas percentuais arredondam **para cima** (nunca abaixo do mínimo).
8. Parcelamento: com entrada (informada ou mínima), saldo financiado pela tabela Price à taxa do período; sem entrada exigida, parcelas iguais em série antecipada; juros de cada parcela arredondados e resíduo de centavos na última parcela; sem juros, divisão com os centavos nas primeiras parcelas. Periodicidade `MONTHLY` (mesmo dia, ajustado ao fim do mês) ou `EVERY_N_DAYS` com taxa equivalente (1+i)^(N/30) − 1.

### 9.4 Envelope, escada e limite efetivo

- `envelope(n)` = faixa de desconto da política para n pagamentos.
- Nível ℓ da escada: `envelopeShare` (fatia do envelope por componente) e `maxInstallments` opcional (1 = só à vista). Níveis contíguos a partir de 0 e não decrescentes em todos os componentes.
- Limite da política no nível = envelope(n) × fatia do nível. Limite do ator = mínimo(limite da política no nível, envelope(n) × `maxDiscountShare` do perfil). Truncamento na 4ª casa (`DOWN`) para nunca exceder.
- Parcelas máximas = mínimo(política, nível, perfil); entrada mínima = máximo(política, perfil); nível máximo = mínimo(topo da escada, perfil); `maxAgreementAmount` compara a **soma dos pagamentos**.
- Violação **POLICY** (fora da política: ninguém concede) × **AUTHORITY** (dentro da política, acima da alçada: aprovação na parte 2; para o assistente, transferência a humano) × **ELIGIBILITY**. Resultado `WITHIN_AUTHORITY`, `REQUIRES_APPROVAL` ou `REJECTED`.
- Subir acima do nível 0 exige justificativa: motivo codificado (`ConcessionReason`: `PAYMENT_CAPACITY`, `INCOME_LOSS`, `HEALTH_OR_FAMILY`, `COMPETING_OFFER`, `LOW_RECOVERY_PROSPECT`, `IMMEDIATE_PAYMENT`, `OTHER` + observação obrigatória). O código vai para auditoria, evento de negócio e métrica; a observação livre fica só na tabela da simulação.

### 9.5 Alçadas

- Perfis semeados por `TenantProvisioned` (consumidor `negotiation.tenant-provisioned`) e por job diário idempotente (`negotiation.default-profiles`, ShedLock) para assessorias existentes. Parâmetros em `StandardAuthorityProfiles`; nível 99 = "topo da escada".
- `ASSISTENTE_INICIAL`: nível 1, 50% do envelope, 6 pagamentos, R$ 3.000, entrada 10%, **não formaliza** (modo com aprovação humana até a operação validar o desempenho).
- Resolução: atribuição do sujeito para o credor → atribuição geral → padrão do tipo de ator (`JUNIOR` para usuário, `ASSISTENTE_INICIAL` para o assistente); o perfil precisa valer para o credor. Outros tipos de ator (sistema, integração, devedor) não têm alçada nesta parte (`authority.actor_not_supported`) — portal e campanhas receberão sujeitos próprios.
- Assistente: `Actor.assistant(policyId, …)`; o sujeito da atribuição é `ASSISTANT_POLICY` com o id da `AssistantPolicy`. O módulo assistant (futuro) chama `AuthorityUseCases.assign` ao publicar a política e usa `NegotiationSimulationUseCases` como ferramenta.
- Segurança: ninguém atribui a própria alçada; atribuição geral exige escopo de todos os credores; perfis sem restrição de credor só são criados/alterados por quem tem todos os credores; mudanças auditadas e registradas no log de acesso (`PERMISSION_CHANGED`, `change = negotiation_authority`). Usuário ainda não ativado pode receber alçada.
- Copiloto/minimização: o envelope completo só é devolvido a humanos cujo perfil alcança o topo da escada; **nunca** ao assistente, que também só relê as próprias simulações.

### 9.6 Simulação e `negotiation.simulated`

- Títulos: os informados ou todos `OPEN` do devedor no credor (até 200). Inelegíveis ficam fora e são listados (`excludedTitles`); sem nenhum elegível, 422. Na validação (`/validations`, e na proposta da parte 2) títulos pedidos e inelegíveis **reprovam** as condições.
- Data-base entre hoje e hoje + `maxDaysToFirstPayment`; 1º pagamento entre a data-base e o mesmo limite.
- Ofertas: níveis 0..mínimo(pedido, alçada), cada um com as formas pedidas (padrão: à vista e o maior parcelamento permitido no nível), no limite do ator; cada oferta passa pelo mesmo `TermsEvaluator`.
- Registro em `negotiation.simulation` (append-only por privilégio e gatilho), com a memória completa em jsonb e `engine_version`.
- Evento de negócio `negotiation.simulated` (ids, sem dados pessoais): `actorType`, `profileCode`, `policyVersion`, `override`, `requestedLevel`, `grantedLevel`, `levelCapped`, `escalationReason`, `titles`, `excludedTitles`, `offers`, `validOffers`, `daysOverdue`, `bestOfferAmount`; `amount` = dívida atualizada. Escalada de nível também gera `negotiation.concession_escalated` na auditoria.

### 9.7 Elegibilidade (substituída na parte 2)

O adaptador provisório `DebtBasedCaseEligibilityAdapter` foi removido; ver §10.1 (`CollectionCaseEligibilityAdapter`). Contrato e códigos mantidos, com `case.not_eligible` acrescentado.

### 9.8 Pendências da parte 1

Todas atendidas na parte 2 (§10).

## 10. Implementação — parte 2 (propostas, acordos, parcelas, quebra, termo)

Código em `negotiation/{domain/model/proposal,domain/model/agreement,application/service,infrastructure/{eligibility,term,persistence},web}`; migrations `V0750` (propostas, acordos, parcelas, títulos, componentes, pagamentos) e `V0751` (anonimização da simulação). Nenhuma permissão nova: o catálogo já tinha `NEGOTIATION_PROPOSE`, `NEGOTIATION_APPROVE`, `AGREEMENT_VIEW`, `AGREEMENT_FORMALIZE` e `AGREEMENT_CANCEL` nos perfis padrão (operador propõe/aceita/formaliza; supervisor e gestor aprovam e cancelam; auditor e financeiro consultam).

### 10.1 Componentes

| Componente | Onde | Papel |
| --- | --- | --- |
| `Proposal` (+ `proposal_title`) | `domain.model.proposal` | condições, resultado do guard, memória (jsonb), aprovação, evidência de aceite, vínculo com o acordo |
| `Agreement`, `AgreementInstallment`, `AgreementTitle`, `AgreementBucket`, `AgreementPayment`, `AcceptanceEvidence` | `domain.model.agreement` | agregado do acordo: cronograma, títulos ativos, distribuição por título × componente do razão, pagamentos aplicados |
| `ProportionalSplit`, `AttributionRules`, `ProposalTerms` | `domain.service` | rateio por maiores restos; origem/participantes; impressão digital das condições, uso do envelope, exigência de alçada, ganho incremental |
| `ProposalEvaluator` | `application.service` | revalidação de proposta sobre o `NegotiationGuard` (+ `proposal.multiple_portfolios`, `title.under_agreement`, `authority.renegotiations_exceeded`) |
| `ProposalService`, `AgreementService` | `application.service` | casos de uso `ProposalUseCases`, `AgreementUseCases` |
| `AgreementRecorder` | `application.service` | formalização, renegociação e publicação de eventos/auditoria/negócio/métricas |
| `AgreementPaymentService`, `LedgerPostingJob` | `application.service` | baixa por `PaymentConfirmed`/`PaymentReversed`; lançamentos no razão (job `negotiation.ledger_posting`) |
| `NegotiationMaintenance` + `NegotiationJobs.daily` | `application.service` | expiração de propostas; parcelas vencidas; quebra (00:20, fuso de operação, ShedLock `negotiation.daily`) |
| `NegotiationAnonymizer`, `NegotiationReactions` | `application.service` | consumidores `negotiation.payment-confirmed`, `negotiation.payment-reversed`, `negotiation.debtor-anonymized` |
| `TermIssuer` + `AgreementTermRenderer` / `PdfAgreementTermRenderer` | `application.service`, `port.out`, `infrastructure.term` | termo em PDF (PDFBox), `FileStorage` (`AGREEMENT_TERM`) com SHA-256 |
| `CollectionCaseEligibilityAdapter` | `infrastructure.eligibility` | elegibilidade sobre `CaseUseCases`/`DisputeUseCases` (collection), `DebtTitleUseCases`, `PortfolioUseCases` |
| `NegotiationLocks` / `PostgresNegotiationLocks` | `port.out`, `infrastructure.persistence` | `pg_advisory_xact_lock` por devedor na formalização |

### 10.2 Proposta

- Criada a partir de uma **simulação do próprio ator** (credor, devedor, títulos, data-base, justificativa, canal); o nível vai até o pedido (e justificado) na simulação; descontos explícitos ou, se omitidos, o limite do ator no nível e forma de pagamento. Uma proposta cobre os títulos de **uma carteira** (um caso de cobrança).
- Resultado do `ProposalEvaluator`: `REJECTED` → 422 `proposal.conditions_rejected` (com os códigos), nada gravado; `WITHIN_AUTHORITY` → `DRAFT`; `REQUIRES_APPROVAL` → `PENDING_APPROVAL` (humano) ou `HANDED_OFF` (assistente).
- Validade: `hoje + proposalValidityDays`, limitada ao 1º vencimento (`validUntil`); job diário expira as abertas (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `ACCEPTED`).
- **Fila de aprovação (F16)**: `GET /proposals/approval-queue` lista as pendentes nos credores do escopo cujas condições cabem na alçada do aprovador (`ProposalTerms.AuthorityRequirement` × perfil com `canApprove`), exceto as próprias. A aprovação revalida pelo guard **com o aprovador como ator** (mesma versão de política, data-base original): fora da alçada do aprovador → 422 `proposal.approver_authority_insufficient`; inválida → 409 `proposal.no_longer_valid`; posição dos títulos mudou → 409 `proposal.conditions_changed`. Ninguém decide a própria proposta; o assistente nunca aprova.
- **Handoff do assistente**: proposta do assistente acima da alçada fica `HANDED_OFF`, **não** entra na fila, e publica o contrato do communication `shared.events.communication.HandoffRequested` (`comunicacao.md` §3.1) com `reasonCode = REQUIRES_APPROVAL`, `referenceType = proposal`/`referenceId` = proposta, caso, devedor, credor, `requestedByType = ASSISTANT`/política, `conversationId` nulo (todas as conversas abertas do caso) e um resumo sem dados pessoais (nível, pagamentos, valores, 1º vencimento, códigos das violações e o caminho de adoção). O communication (`communication.handoff-requested`) transfere a conversa para a fila e o caso volta aos humanos. O humano assume com `POST /proposals/{id}/adopt` (mesmas condições, revalidadas com a alçada dele; aprovação se exceder) ou negocia outra proposta com `handoffProposalId`.
- **Aceite** (`POST /proposals/{id}/accept`, `AGREEMENT_FORMALIZE`): revalida, gera o termo (PDF), guarda no `FileStorage` e registra `AcceptanceEvidence`: canal, instante, autor (tipo/id), referência externa (mensagem/sessão/chamada), IP e agente de usuário (da requisição HTTP; integrações informam os do devedor) e o arquivo com SHA-256.
- **Formalização** (`POST /proposals/{id}/formalize`): lock consultivo por devedor; perfil de quem formaliza com `canFormalize` (o `ASSISTENTE_INICIAL` não formaliza → humano); revalidação; acima da alçada de quem formaliza só se a proposta foi aprovada.

### 10.3 Acordo e parcelas

- Cronograma = o da oferta revalidada; regra de quebra (`BreakRule`) **fixada na formalização**. Parcela: `OPEN`, `PAID`, `PARTIALLY_PAID`, `OVERDUE` (vencida e não paga integralmente, inclusive parcial), `CANCELLED` (acordo encerrado).
- `AgreementTitle` ativo por título; índice único parcial `uk_agreement_title_active` garante um único acordo em vigor por título. Proposta com título coberto por outro acordo em vigor (não o substituído) → `title.under_agreement`.
- Transições: 1º pagamento → `IN_PAYMENT`; todas pagas → `SETTLED`; estorno de acordo quitado → `IN_PAYMENT`; `AGREEMENT_CANCEL` com motivo → `CANCELLED`; job diário → `BROKEN`; formalização de renegociação → `RENEGOTIATED` (o anterior aponta `replacedByAgreementId`).

### 10.4 Baixa de parcelas (`PaymentConfirmed` / `PaymentReversed`)

- Contrato com o módulo payment (futuro): o evento já tinha `agreementId` e `Allocation.agreementInstallmentId`; `Allocation.titleId` passou a ser **opcional** nas alocações a parcelas (javadoc). **O payment não lança no razão as alocações de parcelas de acordo**: quem distribui entre títulos e componentes e lança é o negotiation (§10.5).
- Aplicação (consumidor, acordo bloqueado `FOR UPDATE`): valor indicado por parcela e, o excedente, às parcelas seguintes em ordem; acima do saldo do acordo → não aplicado. Acordo encerrado (quebrado, cancelado, renegociado, quitado) → registro `UNAPPLIED` (WARN + métrica): cabe ao payment realocar ou devolver.
- Idempotência: `shared.consumed_event` + único (acordo, pagamento). Estorno sem acordo informado procura o pagamento em todos os acordos.

### 10.5 Lançamentos no razão do título (`DebtLedgerPort`)

- Cada pagamento aplicado é distribuído entre os componentes (`AgreementBucket`: título × PRINCIPAL/INTEREST/FINE/FEES/HONORARIUM) proporcionalmente ao saldo de cada um (`ProportionalSplit`, maiores restos — a soma é exata). Correção monetária → `FEES`; juros do parcelamento → `INTEREST` (rateados pelos títulos pelo líquido de cada um).
- **Encargos reconhecidos na medida do pagamento**: antes de lançar o pagamento de juros/multa/encargos/honorários, reconhece-se (`applyCharges`, referência `agreement_payment`/pagamento) apenas o necessário para cobri-lo. Assim um acordo quebrado não deixa encargo reconhecido e não pago no título: a nova negociação recalcula a mora sobre o principal restante sem dupla contagem.
- **Quitação do título**: o pagamento que zera os componentes de um título leva também, na ordem, os encargos brutos ainda não reconhecidos (`applyCharges`, referência `agreement`) e o desconto concedido (`applyDiscount`, `DISCOUNT`) — o razão termina com cobrado = bruto, descontado = desconto, pago = líquido, e o título passa a `SETTLED` pelo próprio debt.
- Execução pelo job `negotiation.ledger_posting` (deduplicado por acordo, acordo bloqueado), em transação própria: a recusa do razão não desfaz a baixa da parcela; erro de negócio é permanente (job `DEAD` + `negotiation.ledger.postings{outcome=FAILED}` + ERROR). Estorno de pagamento já lançado → `reversePayment` por título (espelho); estorno antes do lançamento → nada é lançado e o reconhecimento planejado é desfeito.
- Limite conhecido: o razão não tem estorno de reconhecimento de encargo. Estornado um pagamento já lançado, o encargo reconhecido permanece em aberto no título (é reaproveitado se o acordo voltar a ser pago); se o acordo quebrar sem novo pagamento, esse encargo entra "como está" numa nova negociação. Pendência no debt: operação compensatória de reconhecimento.

### 10.6 Quebra e renegociação

- Job diário: parcelas vencidas → `OVERDUE` (`installment.overdue`); acordo com `overdueInstallments` parcelas não pagas vencidas há mais de `toleranceDays` dias → `BROKEN` (parcelas em aberto `CANCELLED`, títulos liberados). Atraso isolado dentro da tolerância não quebra.
- Renegociação = proposta com `replacedAgreementId` (acordo em vigor ou quebrado, mesmo devedor e credor), composição recalculada do razão atual (pagamentos já abatidos). `renegotiationNumber` = posição na cadeia; acima de `maxRenegotiations` do perfil → violação de alçada `authority.renegotiations_exceeded` (aprovação de quem tem mais). Ganho incremental `máx(0, novo − (substituído − pago))` em `agreement.incremental_amount` e no `agreement.formalized`; o já recebido nunca é recontado (o `incentive` aplica a mesma regra a partir do `replacedAgreementId`).

### 10.7 Atribuição do acordo

`AgreementAttributionRecorded` na transação da formalização (`AttributionRules`): `CLOSER` (quem formalizou), `ORIGINATOR` (quem criou), `APPROVER` (se aprovada), `PRE_NEGOTIATOR` (assistente do handoff ou autor da proposta confirmada). Origem: assistente formalizou → `ASSISTANT_AUTONOMOUS`; humano com handoff → `ASSISTANT_HANDOFF_CONFIRMED` se a **impressão digital** das condições (títulos, desconto por componente, número de pagamentos, entrada pedida) é a da sugestão, senão `ASSISTANT_HANDOFF_NEGOTIATED`; humano formalizando proposta criada pelo assistente → `ASSISTANT_HANDOFF_CONFIRMED`; proposta marcada `copilot` → `HUMAN_COPILOT`; demais → `HUMAN`; devedor/sistema → `PORTAL_SELF_SERVICE`/`CAMPAIGN` (para os módulos futuros). `teamId` = equipe do caso; `daysPastDue` = maior atraso; `envelopeUsagePercent` = desconto ÷ desconto máximo da política para a forma de pagamento.

### 10.8 Eventos de integração

| Evento | Quando |
| --- | --- |
| `AgreementStatusChanged` | formalização (`previousStatus` nulo), `IN_PAYMENT`, `SETTLED`, `BROKEN`, `CANCELLED`, `RENEGOTIATED`, reabertura por estorno |
| `InstallmentStatusChanged` | toda mudança de situação de parcela |
| `AgreementAttributionRecorded` | formalização |
| `HandoffRequested` (contrato do `communication`, `shared.events.communication`) | proposta do assistente acima da alçada |
| `AgreementPaymentRecorded` (**novo**, para o payment) | toda baixa de `PaymentConfirmed` com acordo: aplicado, não aplicado (acordo encerrado/excedente) ou ignorado (acordo desconhecido) |
| consumidos: `PaymentConfirmed`, `PaymentReversed`, `DebtorAnonymized` (**novo**, publicado pelo debt na anonimização), `TenantProvisioned` | — |

### 10.9 LGPD

O compliance aciona a anonimização chamando `DebtorUseCases#anonymize` (debt); o debt passou a publicar `shared.events.debt.DebtorAnonymized` na mesma transação, e o negotiation (que não pode ser chamado pelo compliance, pelo grafo) consome: apaga `simulation.justification_note` e a observação na memória JSON (o gatilho `simulation_anonymize_only` só permite isso numa tabela append-only), a observação livre da proposta e da decisão, IP/agente dos aceites, o motivo livre de cancelamento e remove o PDF do termo após o commit (fica o hash). Outros módulos com texto livre do devedor (collection) podem consumir o mesmo evento.

### 10.10 REST (`/api/v1/negotiation`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/proposals` | `POST` (criar), `GET` (filtros: credor, devedor, caso, status, tipo de ator) | `NEGOTIATION_PROPOSE` / leitura: `AGREEMENT_VIEW`, `NEGOTIATION_PROPOSE` ou `NEGOTIATION_APPROVE` |
| `/proposals/approval-queue` | `GET` | `NEGOTIATION_APPROVE` |
| `/proposals/{id}` · `/proposals/{id}/term` | `GET` | leitura |
| `/proposals/{id}/approve` · `/reject` | `POST` | `NEGOTIATION_APPROVE` |
| `/proposals/{id}/adopt` | `POST` | `NEGOTIATION_PROPOSE` |
| `/proposals/{id}/accept` · `/formalize` | `POST` | `AGREEMENT_FORMALIZE` |
| `/agreements` · `/agreements/{id}` · `/installments` · `/payments` · `/term` | `GET` | `AGREEMENT_VIEW` |

Porta pública adicional (23/10/2026, para a prestação de contas — [prestacao-de-contas.md](prestacao-de-contas.md)): `AgreementUseCases#paymentDistribution(agreementId, paymentId)` devolve a distribuição de um pagamento aplicado (parcelas com número, títulos com a identificação no credor e componentes do razão), número de parcelas, maior atraso na formalização e número da renegociação. Sem endpoint REST; `AGREEMENT_VIEW` e escopo por credor.
| `/agreements/{id}/cancel` | `POST` | `AGREEMENT_CANCEL` |

### 10.11 Decisões da parte 2

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Proposta nasce de uma simulação do próprio ator e é revalidada inteira (guard) na criação, aprovação, aceite e formalização | Confiar na simulação | Nenhum caminho aplica condição sem o guard; a posição do título pode mudar |
| 2 | Proposta cobre uma única carteira (um caso) | Várias carteiras | Caso de cobrança, filas, atribuição e `AgreementStatusChanged` são por carteira |
| 3 | Revalidação usa a data-base original (valor congelado) dentro da validade; mudança de valor → 409 `proposal.conditions_changed` | Recalcular na data da aprovação | O devedor aceita um valor; a validade (≤ 1º vencimento) limita o congelamento |
| 4 | Fila de aprovação filtrada em memória pela exigência de alçada (até 1.000 pendentes por consulta), aprovação revalidando com o aprovador | Tabela de roteamento | Perfil é resolvido por credor; volume de pendentes é baixo; a revalidação é a garantia |
| 5 | Assistente acima da alçada → `HANDED_OFF` + `HandoffRequested` (contrato já definido pelo communication, sem campos novos), sem fila; humano adota (`/adopt`) | Evento próprio do negotiation; proposta pendente de aprovação | §5 do desenho; um único contrato de transferência; a conversa precisa de humano, não só de aprovação |
| 6 | HANDOFF_CONFIRMED × NEGOTIATED por impressão digital de títulos, descontos, parcelas e entrada pedida (sem datas e valores acumulados) | Comparar valor total | Juros até a data-base mudam com o tempo sem negociação |
| 7 | Termo gerado no aceite (o hash do termo aceito vai à evidência), modelo padrão em PDFBox com nome e documento mascarado | Gerar na formalização; modelo por credor | O devedor aceita um documento concreto; modelo versionado por credor fica pendente (jurídico) |
| 8 | IP/agente do aceite = os da requisição HTTP; integrações (portal/comunicação) informam os do devedor no comando | Sempre do cliente | O cliente HTTP não pode forjar a evidência; a API web não aceita IP informado |
| 9 | Regra de quebra fixada na formalização; quebra por N parcelas vencidas há mais de D dias; parcela parcial vencida conta | Regra vigente na data da quebra | O acordo é um contrato: a regra aceita não muda depois |
| 10 | Pagamento distribuído proporcionalmente ao saldo de cada componente, encargos reconhecidos só quando pagos; quitação do título com encargo bruto + desconto | Reconhecer tudo na formalização; principal primeiro | Sem dupla contagem de mora após quebra; neutro entre componentes; razão final completo |
| 11 | Lançamentos no razão por job (transação própria) | No consumidor | Recusa do razão não desfaz a baixa; o job é idempotente e alarmável |
| 12 | O payment não lança no razão alocações a parcelas de acordo; `Allocation.titleId` opcional nesse caso | Payment distribuir por título | Só o acordo conhece a composição por título e componente |
| 13 | Pagamento para acordo encerrado ou excedente → não aplicado (registro + WARN + métrica) | Aplicar ao novo acordo/ao título | Realocação é decisão do payment/operação; evita baixa no acordo errado |
| 14 | Renegociação permitida de acordo em vigor ou quebrado; limite `maxRenegotiations` do perfil como violação de alçada | Só quebrado; limite como violação de política | Mercado renegocia acordos em risco; acima do limite o gestor decide |
| 15 | Elegibilidade sobre o collection: caso ativo fora de suspensão/jurídico/encerramento, contestação impeditiva aberta ou em análise; caso `DISPUTED` não bloqueia títulos não contestados; restrição de contato não impede negociar | Exigir caso elegível (inclui restrição de contato) | Parte incontroversa pode ser paga; o porteiro de contato vale para contatos ativos |
| 16 | Permissões existentes (`NEGOTIATION_PROPOSE`, `NEGOTIATION_APPROVE`, `AGREEMENT_*`) | `PROPOSAL_CREATE`/`PROPOSAL_APPROVE`/`AGREEMENT_MANAGE` novas | Já estavam no catálogo e nos perfis padrão; sem migration de identity |
| 17 | Anonimização via `DebtorAnonymized` publicado pelo debt | Compliance chamar o negotiation | O grafo não permite compliance → negotiation; o evento serve aos demais módulos |
| 18 | Remoção do PDF do termo na anonimização (hash mantido) | Manter o termo | O termo contém o nome; a anonimização só é aceita sem títulos em cobrança |
| 19 | Lock consultivo por devedor na formalização + índice único parcial de títulos ativos + `@Version` na proposta | Só lock otimista | Formalizações concorrentes de propostas diferentes sobre os mesmos títulos |
| 20 | Estorno de acordo quitado reabre `IN_PAYMENT`; pagamento total estornado mantém `IN_PAYMENT` | Voltar a `FORMALIZED` | Estados não regridem além do necessário; a parcela volta a aberta/vencida |

### 10.12 Pendências

- ~~**payment**: produzir `PaymentConfirmed` com `agreementId` e `agreementInstallmentId` sem lançar essas alocações no razão; realocar pagamentos `UNAPPLIED`.~~ Feito (ver [pagamentos.md](pagamentos.md)); o `paymentId` recebido é o id da alocação no payment.
- ~~**assistant**: encerrar o turno com a mensagem de transferência quando a ferramenta de proposta devolver `HANDED_OFF`.~~ Feito (ver [assistente.md](assistente.md) §5): o devedor é avisado antes do registro da proposta e o turno termina.
- **collection**: consumir `DebtorAnonymized` para observações próprias; opcionalmente reagir ao handoff.
- **debt**: operação compensatória de reconhecimento de encargo (estorno após lançamento, §10.5).
- **jurídico/produto**: modelo de termo versionado por credor (cláusulas, foro, identificação completa).
- **frontend**: telas de proposta, fila de aprovação e acordo.
