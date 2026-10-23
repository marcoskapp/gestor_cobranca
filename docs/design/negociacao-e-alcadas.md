# Desenho — módulo `negotiation` (políticas, cálculo, alçadas, propostas e acordos)

Escopo: M06, F08, F09, F16, RB03, RB04, RB05. Schema `negotiation`, migrations V0700–V0799 (parte 1: V0700–V0749; parte 2: V0750–V0799).
Dependências: `shared`, `organization`, `identity`, `debt`, `collection` (interfaces `usecases`). A parte 1 ainda não depende de `collection` (ver §9).

> **Estado (23/10/2026).** Parte 1 implementada: §1–§4, simulação, `NegotiationGuard` e alçadas (humanos e assistente). Parte 2 pendente: propostas e aprovação (§5), acordos, parcelas, quebra, termo (§6) e `AgreementAttributionRecorded`. Decisões da parte 1 em §9.

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

`Proposal`: `DRAFT → PENDING_APPROVAL → APPROVED | REJECTED`; `APPROVED/DRAFT dentro da alçada → ACCEPTED (pelo devedor) → FORMALIZED`; `EXPIRED` pela validade.

- Proposta dentro da alçada do proponente: segue direto para aceite.
- Acima da alçada: `PENDING_APPROVAL`, roteada para a fila de aprovadores com alçada suficiente (F16). Proposta do assistente acima da alçada **não** vai para aprovação: gera transferência do atendimento para humano com a proposta como sugestão.
- Toda proposta registra: ator (tipo e id), versão da política, nível de concessão, memória de cálculo, canal.

`NegotiationGuard.validate(actor, proposal)` aplica: política vigente, envelope, perfil de alçada do ator, elegibilidade dos títulos (sem contestação impeditiva, carteira operável), escopo de credor.

## 6. Acordos (RB05)

`Agreement`: `FORMALIZED → IN_PAYMENT → SETTLED | BROKEN | CANCELLED`; `RENEGOTIATED` quando substituído (o histórico é preservado e as obrigações antigas não contam como nova recuperação).

- Formalização exige **evidência de aceite**: canal, instante, identificador da mensagem ou sessão do portal, IP quando houver, e hash do termo (PDF gerado e armazenado como `AGREEMENT_TERM`).
- `AgreementInstallment` com vencimento, valor, status (`OPEN, PAID, PARTIALLY_PAID, OVERDUE, CANCELLED`).
- Quebra conforme a regra da política; atraso isolado dentro da tolerância não quebra.
- Na quitação: lança no razão do `debt` os encargos reconhecidos (`applyCharges`) e o desconto concedido (`applyDiscount`).
- Eventos `AgreementStatusChanged` / `InstallmentStatusChanged`; eventos de negócio com atributo `actorType` (USER ou ASSISTANT) e `concessionLevel`, para comparar desempenho de humanos e assistente.

## 7. Copiloto para negociadores humanos

Os mesmos cálculos alimentam a tela de negociação com intensidade definida pelo perfil do operador: júniores recebem a próxima oferta sugerida e bloqueio imediato de oferta inválida; seniores veem o envelope completo e o histórico. A sugestão textual vem do módulo `assistant` em modo copiloto.

## Atribuição do acordo (insumo da remuneração variável)

Na formalização, `negotiation` registra os participantes do acordo e publica `shared.events.negotiation.AgreementAttributionRecorded` com: `agreementId`, `caseId`, `creditorId`, `portfolioId`, `origin` (`HUMAN`, `HUMAN_COPILOT`, `ASSISTANT_HANDOFF_NEGOTIATED`, `ASSISTANT_HANDOFF_CONFIRMED`, `ASSISTANT_AUTONOMOUS`, `PORTAL_SELF_SERVICE`, `CAMPAIGN`), lista de participantes (`actorType`, `actorId`, `role` = `CLOSER`/`ORIGINATOR`/`PRE_NEGOTIATOR`/`APPROVER`/`INFLUENCER`), nível de concessão, uso do envelope (%), faixa de atraso e `replacedAgreementId`. `HANDOFF_CONFIRMED` × `HANDOFF_NEGOTIATED` é decidido no servidor comparando a proposta formalizada com a sugestão do handoff. Regras de uso em `docs/design/remuneracao-variavel.md`.

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

### 9.7 Elegibilidade provisória (substituir na parte 2)

`CaseEligibilityPort` com `DebtBasedCaseEligibilityAdapter`: `title.not_found`, `title.not_open`, `title.no_balance`, `title.dispute_blocked`, `portfolio.not_operable`. Na parte 2, um adaptador do `collection` (caso elegível, contestações do caso, `case.not_eligible`) substitui o bean e este arquivo é removido, mantendo contrato e códigos. A dependência `collection :: usecases` entra no `package-info` nesse momento.

### 9.8 Pendências para a parte 2

Propostas (referenciando `simulationId` e versão de política), aprovação por fila de aprovadores com `canApprove` e alçada suficiente, transferência do assistente acima da alçada, acordos, parcelas, quebra pela `BreakRule`, termo (PDF + hash), lançamentos no razão (`applyCharges` com correção como `FEES`, `applyDiscount`), `AgreementAttributionRecorded` e uso de `maxRenegotiations`. Anonimização LGPD deve alcançar `simulation.justification_note`.
