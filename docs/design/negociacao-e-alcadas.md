# Desenho — módulo `negotiation` (políticas, cálculo, alçadas, propostas e acordos)

Escopo: M06, F08, F09, F16, RB03, RB04, RB05. Schema `negotiation`, migrations V0700–V0799.
Dependências: `shared`, `organization`, `identity`, `debt`, `collection` (interfaces `usecases`).

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
