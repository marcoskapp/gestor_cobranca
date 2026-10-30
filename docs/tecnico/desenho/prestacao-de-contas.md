# Prestação de contas e comissão da assessoria (módulo `settlement`)

F17 (demonstrativo e comissão por credor), F22 (prestação de contas automatizada), RB08 (recuperação, comissão prevista × apurada). Código em `backend/src/main/java/br/com/gestorcobranca/settlement`; migrations `V0900__settlement_tables.sql` (schema `settlement`, RLS forçado em todas as tabelas) e `identity/V0204__identity_settlement_permissions.sql`. Layout do arquivo para o credor: [negocio/layout-demonstrativo-prestacao-contas.md](../../negocio/layout-demonstrativo-prestacao-contas.md).

> **Não confundir com o `incentive`.** Aqui se calcula quanto a **assessoria** recebe do **credor** (comissão, valores fixos, honorários) e quanto ela repassa; no `incentive`, quanto o **negociador** recebe da assessoria ([remuneracao-variavel.md](remuneracao-variavel.md)). A base dos dois é a mesma: recuperação confirmada, por alocação do payment (`paymentId`), líquida de estornos.

Dependências: `payment :: usecases` (pagamento dono da alocação: origem, situação de conciliação; divergências abertas), `negotiation :: usecases` (distribuição do pagamento no acordo — `AgreementUseCases#paymentDistribution`, novo), `organization :: usecases` (contratos, carteiras, credor). Os demais fatos chegam por eventos de integração.

## 1. Conceitos (RB08)

| Conceito | Onde | Observação |
| --- | --- | --- |
| Valor negociado | `agreement_fact.agreement_amount` (por `AgreementAttributionRecorded`/`AgreementStatusChanged`) | soma dos acordos formalizados no período; **não é recuperação** |
| Valor pago (recebido) | `receipt` `PAYMENT` | título avulso: valor alocado (`PaymentConfirmed` sem acordo); acordo: **valor aplicado** pelo negotiation (`AgreementPaymentRecorded` `APPLIED`) — acordo encerrado ou excedente não aplicado não é recuperação |
| Valor conciliado | situação do pagamento no payment (`RECONCILED`) lida no cálculo | com `reconciliationRequired`, recebimento na conta da assessoria só entra no demonstrativo conciliado; recebido diretamente pelo credor não depende de conciliação |
| Comissão prevista | `statement.forecast_commission` | saldo não recebido dos acordos em aberto × regras vigentes hoje; informativa |
| Comissão apurada | `commission_entry` (razão) | por recebimento, com a versão de regras vigente na data do pagamento e memória de cálculo |

## 2. Regras de comissionamento (versionadas por contrato de serviço)

O `ServiceContract` (organization) continua com vigência e dia de fechamento (`settlementDay`); as regras ficam no settlement, como o próprio contrato já documentava ("regras de comissão versionadas no settlement, referenciando este contrato"). `CommissionRuleSet`: uma linha por versão (`rule_version` sequencial por contrato), `DRAFT → PUBLISHED` (ou `DISCARDED`), regras tipadas (`CommissionRules`) em jsonb com `rules_schema_version`, validadas em toda escrita e leitura.

| Componente | Campo | Efeito |
| --- | --- | --- |
| Percentual padrão | `defaultRate` | sobre a base de comissão quando nenhuma regra específica casa |
| Regras específicas | `rateRules[]` (`code`, `priority`, `portfolioId`, `minDaysPastDue`/`maxDaysPastDue`, `paymentType` `LUMP_SUM`/`INSTALLMENTS`, `receivedBy` `AGENCY`/`CREDITOR`, `renegotiation`, `rate`) | por carteira, faixa de atraso (inclusive), à vista × parcelado, quem recebeu, renegociação; vence a maior `priority`, depois a mais específica, depois o código; regra sem critério é recusada |
| Valor fixo por acordo | `fixedFeePerAgreement` | devido no primeiro recebimento do acordo; estornado só se esse recebimento for estornado por inteiro (e então devido de novo no próximo) |
| Honorários do devedor | `honorarium.ownership` `AGENCY`/`CREDITOR`, `agencySharePercent` | parte dos honorários cobrados do devedor (componente `HONORARIUM` do razão do título) que é da assessoria — retida e fora da base; o restante é repassado ao credor |
| Conciliação | `reconciliationRequired` | ver §1 |

**Vigência e reprodutibilidade:** publicar exige outra pessoa além de quem editou por último (`commission_rule_set.self_publish`; `check` no banco), início dentro da vigência do contrato, depois do início da versão vigente (que termina na véspera) e **depois do último pagamento já apurado no contrato** e do último demonstrativo aprovado do credor (`commission_rule_set.retroactive`) — recebimento apurado nunca muda de regra. A versão publicada é imutável (gatilho `rule_set_guard`). Recebimento sem contrato resolvível ou sem versão vigente na data fica `PENDING_RULES` (`NO_CONTRACT`/`NO_RULES`, evento `settlement.receipt_pending_rules`) e é apurado na publicação (ou por `POST /receipts/rate-pending`).

Contrato do recebimento: o da carteira (`PortfolioView.contractId`); sem carteira única, o único contrato do credor vigente na data do pagamento.

## 3. Cálculo (puro, `CommissionCalculator`)

```
honorários da assessoria = honorários × fração da assessoria         (0 se pertencem ao credor)
base de comissão         = recuperado − honorários da assessoria
comissão                 = base × percentual da regra
valor fixo               = fixedFeePerAgreement no primeiro recebimento do acordo
efeito no repasse        = (recebido pela assessoria ? recuperado : 0) − honorários da assessoria − comissão − valor fixo
```

Centavos HALF_EVEN em cada linha; a memória (`calculation` jsonb do lançamento) reproduz o valor. Estorno: proporcional ao saldo ainda não estornado do recebimento; o estorno que zera o recebimento leva exatamente o saldo de cada componente (sem resíduo de centavos), inclusive o valor fixo.

Exemplos verificados à mão (`CommissionCalculatorTest`): 1.100,00 com 100,00 de honorários da assessoria, 120 dias, parcelado (12%), primeiro recebimento (fixo 25,00) → base 1.000,00, comissão 120,00, repasse **855,00**; estorno de 550,00 → −50,00/−60,00/fixo mantido/−440,00; estorno do restante → −50,00/−60,00/−25,00/−415,00 (soma zero). Recebido pelo credor: o mesmo recebimento a 8% gera efeito **−205,00** (o credor deve 100,00 + 80,00 + 25,00).

## 4. Recebimentos e razão (append-only)

| Evento | Efeito |
| --- | --- |
| `PaymentConfirmed` sem `agreementId` | um `receipt` `PAYMENT` por título (valor, honorários, atraso e `titleReference` vêm da alocação — campos novos em `PaymentConfirmed.Allocation`, preenchidos pelo payment) |
| `PaymentConfirmed` com acordo | ignorado (a recuperação do acordo é o aplicado, abaixo) |
| `AgreementPaymentRecorded` `APPLIED` | um `receipt` `PAYMENT` do `appliedAmount`; parcelas, títulos (com `titleReference`) e componentes lidos de `AgreementUseCases#paymentDistribution`; à vista = acordo de 1 parcela; atraso = maior atraso na formalização; renegociação = `renegotiationNumber > 0` |
| `PaymentReversed` (estorno, devolução, `REALLOCATED`) | `reversal_notice` (único por `reversalId`) + `receipt` `REVERSAL` negativos repartidos entre os recebimentos da alocação pelo líquido (maiores restos), competência = data do estorno; estorno antes do recebimento fica guardado e é aplicado quando ele chega; realocação de valor que o negotiation não aplicou não tem recebimento e fica sem efeito |
| `AgreementAttributionRecorded`, `AgreementStatusChanged` | `agreement_fact` (valor negociado, comissão prevista) |
| `LegalExpenseChanged` (**novo**, publicado pelo legal) | `reimbursable_expense` (§6) |

Origem do pagamento (`PaymentView.externalOrigin`): `CREDITOR_DIRECT`/`CREDITOR_REPORT` → recebido pelo credor; demais → conta da assessoria.

Serialização: lock consultivo por alocação (eventos da mesma alocação), por credor (apuração × cálculo do demonstrativo) e por acordo (valor fixo devido uma vez); índices únicos `(alocação, título)` e `(estorno, recebimento)`; `shared.consumed_event`. Recebimentos: colunas de fato imutáveis (gatilho `receipt_guard` + privilégio de coluna); `commission_entry` e `statement_export`: só `INSERT`.

## 5. Demonstrativo (ciclo e cálculo)

`DRAFT → CALCULATED → REVIEWED → APPROVED → SENT → ACKNOWLEDGED | CONTESTED` (`CONTESTED → ACKNOWLEDGED` após a correção); `DRAFT/CALCULATED/REVIEWED → CANCELLED`. Um demonstrativo em aberto por credor; períodos contíguos e sem sobreposição (`exclude using gist`); período de até 3 meses; só calculado depois de encerrado. Sem datas, o sistema sugere o ciclo seguinte ao último demonstrativo (ou o último ciclo encerrado) pelo `settlementDay` do contrato: ciclo do dia D do mês M−1 ao dia D−1 do mês M.

Cálculo (`SettlementStatementCalculator`, demonstrativo e credor bloqueados): libera o que estava vinculado (recálculo); candidatos = recebimentos apurados e despesas **ainda não prestados com competência até o fim do período** (inclusive de períodos anteriores que chegaram depois do fechamento deles — marcados em `receiptsFromPriorPeriods`); recebimento que exige conciliação e não está conciliado (nem estornado por inteiro no período) fica pendente; compensação entra junto com o original ou se o original já foi prestado; vincula os incluídos. Totais = soma dos lançamentos do razão, despesas deduzidas e ajustes aplicados:

`líquido a repassar = Σ efeito no repasse − despesas reembolsáveis + ajustes` (negativo: o credor deve à assessoria).

A memória (`statement.calculation`) guarda versões de regras usadas, pendências de conciliação e de regra (com ids), divergências de conciliação abertas do credor (payment), dados da comissão prevista e a fórmula.

**Ajustes**: motivo obrigatório (10–2000), categoria, efeito com sinal; acima de `app.settlement.adjustment-dual-approval-threshold` (R$ 1.000,00) ficam `PENDING_SECOND_APPROVAL` até outra pessoa com `SETTLEMENT_APPROVE` decidir; ajuste aplicado devolve o demonstrativo a `CALCULATED` (nova revisão). Correção de demonstrativo aprovado = ajuste `PRIOR_PERIOD_CORRECTION` no seguinte, apontando o corrigido.

**Aprovação** (`SETTLEMENT_APPROVE`): exige `REVIEWED`, sem ajuste pendente; quem calculou ou pediu ajuste aplicado não aprova (inclusive o administrador; `check` no banco para quem calculou). Aprovado é imutável (gatilho `statement_guard`): estornos, despesas canceladas e correções posteriores entram no período seguinte. Publica `SettlementClosed` e o evento de negócio `settlement.closed`.

**Exportação** (`SETTLEMENT_CLOSE`, só aprovado): CSV/XLSX conciliáveis e PDF resumo (PDFBox) no `FileStorage` (`SETTLEMENT_STATEMENT`) com SHA-256; o conteúdo depende só do demonstrativo aprovado. **Envio** registra canal, referência e os arquivos enviados; **aceite/contestação** do credor são registrados pela assessoria (não há portal do credor).

### 5.1 Posição em tempo real (simulação do demonstrativo)

Para acompanhar o repasse sem esperar o fechamento, `SettlementPositionUseCases.position(credor)` (`GET /api/v1/settlement/position?creditorId=`, `SETTLEMENT_VIEW`; na API pública, `GET /public-api/v1/settlement/position`, escopo `settlement:read`) executa **o mesmo núcleo do cálculo** (`SettlementStatementCalculator.compute`) em modo simulação: não libera, não vincula, não grava e não bloqueia nada. `calculate` = liberar + `compute` + vincular; `simulate` = `compute` contando como candidatos os itens vinculados ao demonstrativo em aberto (como se fosse recalculado agora) e somando os ajustes aplicados dele.

- **Período**: início do demonstrativo em aberto; sem ele, o dia seguinte ao último demonstrativo fechado; sem nenhum, o início do ciclo corrente do contrato (`settlementDay`) — até **hoje**. Fechamento previsto pelo `settlementDay`.
- **Resultado**: os mesmos totais do demonstrativo (recuperado, recebido pela assessoria e direto pelo credor, honorários, comissão, valores fixos, despesas, ajustes, líquido estimado com direção pelo sinal); pendências de conciliação e de regra **fora do líquido**, à parte; acumulado diário (recuperado e líquido; competência anterior ao início conta no dia 1, ajustes no último dia) comparado ao último demonstrativo fechado no mesmo dia do período; demonstrativos aprovados com saldo a repassar/receber (`balances`).
- **Garantia**: `SettlementPositionIT` calcula a posição, fecha o demonstrativo do mesmo período no dia seguinte e compara todos os totais; repete com o demonstrativo em aberto e confere que nada foi desvinculado.
- **Desempenho**: cache por (assessoria, credor) de 60 s servido só enquanto a marca de alterações do credor (`SettlementPositionFingerprints`: uma instrução com agregados sobre os índices parciais de itens em aberto e os índices por credor) não muda — dado próprio novo recalcula na hora em qualquer instância; os 60 s cobrem a situação de conciliação (payment) e a virada do dia. Métricas `settlement.position.duration{cache}` (`hit` ou `miss`), `settlement.position.requests{cache}`; log técnico DEBUG com `durationMs` e `receipts` (WARN acima de 2 s).
- **API pública**: `GET /public-api/v1/settlement/statements` lista só os demonstrativos enviados ao credor (`SENT`, `ACKNOWLEDGED`, `CONTESTED`), com totais e datas de envio/resposta — sem memória de cálculo nem autores.

## 6. Custas e despesas do jurídico

Contrato novo `shared.events.legal.LegalExpenseChanged` (publicado pelo `LegalExpenseService` no lançamento, cancelamento e reembolso; sem descrição livre). Reembolsável adiantada pela assessoria ou por escritório parceiro → `DEDUCT` (deduzida do repasse: o credor reembolsa no demonstrativo); paga pelo credor ou não reembolsável → `INFORMATIVE`. Cancelamento antes de prestar → sai; depois → lançamento `CANCELLATION` negativo no demonstrativo seguinte. Reembolso registrado no legal antes de prestar → sai (`REIMBURSED_OUTSIDE`); depois → WARN + métrica `settlement.events.ignored{reason=reimbursed_after_statement}` (possível reembolso em duplicidade). `SettlementClosed.reimbursedLegalExpenseIds` permite ao legal marcar as despesas como reembolsadas (pendência no legal).

## 7. Repasse

`CreditorTransfer` por demonstrativo aprovado: direção pelo sinal do líquido (`AGENCY_TO_CREDITOR` ou `CREDITOR_TO_AGENCY`), valor até o saldo (bloqueio pessimista do demonstrativo — concorrência testada), data não futura e não anterior ao fim do período, referência bancária, **comprovante obrigatório** (PDF/PNG/JPEG conferido pela assinatura, `FileStorage` `EVIDENCE`, SHA-256, removido se a transação for desfeita), `Idempotency-Key` (escopo `settlement.transfer`, hash do comando + comprovante). Cancelamento com motivo (nunca exclusão). Saldo a repassar / a receber por credor: `GET /settlement/balances`.

## 8. Permissões, escopo e auditoria

| Ação | Permissão | Perfis padrão |
| --- | --- | --- |
| consultar regras, razão e simulação | `COMMISSION_VIEW` (ou `COMMISSION_MANAGE`) | gestor, financeiro, auditor |
| editar/publicar regras, reapurar pendentes | `COMMISSION_MANAGE` (quem edita não publica) | financeiro |
| consultar demonstrativos, exportações, repasses, saldos e a posição em tempo real | `SETTLEMENT_VIEW` | gestor, financeiro, auditor |
| criar, calcular, revisar, ajustar, exportar, enviar, registrar resposta do credor, cancelar | `SETTLEMENT_CLOSE` | financeiro |
| aprovar demonstrativo e segunda aprovação de ajuste | `SETTLEMENT_APPROVE` (**nova**) | gestor |
| registrar/cancelar repasse | `SETTLEMENT_TRANSFER` (**nova**) | financeiro |

Administrador tem todas, mas a segregação vale para ele. Escopo por credor em tudo (404 em leitura, 403 em comando; listagens filtradas). Auditoria: `commission_rule_set.*`, `settlement_statement.*` (criado, calculado, revisado, aprovado, enviado, aceito, contestado, cancelado, exportado), `settlement_adjustment.*`, `settlement_transfer.*`. Log de acesso: `DATA_EXPORTED` na geração, `FILE_DOWNLOADED` no download de exportação e comprovante, `SCOPE_DENIED`/`ACCESS_DENIED`.

As leituras de pagamento e acordo são feitas como processo do sistema (`SystemSettlementSources`), depois de o settlement aplicar o escopo por credor: o cálculo do demonstrativo não depende de quem calcula ter `PAYMENT_VIEW`. Nos casos de uso, cada leitura vai numa transação própria (a exceção de "não encontrado" de outro módulo não desfaz a do settlement); nos consumidores de evento, os fatos são lidos **antes** da transação do consumidor (`SourceSnapshot`), para que cada consumidor use uma conexão por vez (16 consumidores simultâneos × 2 conexões esgotariam o pool de 30).

## 9. Eventos, logs e métricas

Integração: consome `PaymentConfirmed`, `PaymentReversed`, `AgreementPaymentRecorded`, `AgreementAttributionRecorded`, `AgreementStatusChanged`, `LegalExpenseChanged`; publica `SettlementClosed` (novo, `shared.events.settlement`).

Negócio (atributos em [logs-e-metricas.md](../logs-e-metricas.md) §3.8): `commission.accrued`, `commission.reversed`, `commission.rules_published`, `settlement.receipt_pending_rules`, `settlement.calculated`, `settlement.adjusted`, `settlement.closed`, `settlement.exported`, `settlement.sent`, `settlement.acknowledged`, `settlement.contested`, `settlement.transfer_recorded`. Comissão da assessoria não é dado pessoal: os eventos levam valores; nunca nome ou documento do devedor.

Métricas: `settlement.position.duration{cache}`, `settlement.position.requests{cache}`, `settlement.receipts{kind,outcome}`, `settlement.ratings{kind,outcome}`, `settlement.events.ignored{type,reason}`, `settlement.expenses{kind,effect}`, `settlement.statements.transitions{status}`, `settlement.statement.calculate.duration`, `settlement.statement.receipts`, `settlement.adjustments{status}`, `settlement.exports{format}`, `settlement.transfers{direction,outcome}`, `settlement.rules.published`.

## 10. REST (`/api/v1/settlement`)

| Recurso | Métodos |
| --- | --- |
| `/rule-sets` · `/rule-sets/{id}` · `/{id}/publish` · `/{id}/discard` · `/rule-sets/simulate` | `GET`/`POST` · `GET`/`PUT` · `POST` · `POST` · `POST` |
| `/receipts` · `/receipts/{id}` · `/receipts/rate-pending?creditorId=` | `GET` · `GET` · `POST` |
| `/statements` · `/statements/period-suggestion` · `/statements/{id}` · `/{id}/receipts` · `/{id}/expenses` | `GET`/`POST` · `GET` · `GET` · `GET` · `GET` |
| `/statements/{id}/calculate` · `/review` · `/approve` · `/send` · `/acknowledge` · `/contest` · `/cancel` | `POST` |
| `/statements/{id}/adjustments` · `/adjustments/{id}/approve` · `/adjustments/{id}/reject` | `GET`/`POST` · `POST` · `POST` |
| `/statements/{id}/exports` · `/exports/{id}/content` (cabeçalho `X-Content-SHA256`) | `GET`/`POST` · `GET` |
| `/position?creditorId=` (posição do período em aberto, simulação) | `GET` |
| `/transfers` (multipart `transfer` + `evidence`, `Idempotency-Key`) · `/transfers/{id}` · `/{id}/cancel` · `/{id}/evidence` · `/balances` | `POST`/`GET` · `GET` · `POST` · `GET` · `GET` |

## 11. Contratos alterados em outros módulos

- `PaymentConfirmed.Allocation` + `titleReference`, `daysPastDue`, `honorariumAmount` (construtor da assinatura original mantido); o payment preenche nas alocações a títulos (atraso na data do pagamento; honorários = parte distribuída ao componente `HONORARIUM`).
- `AgreementUseCases#paymentDistribution(agreementId, paymentId)` (negotiation): parcelas, títulos (com identificação no credor) e componentes de um pagamento aplicado, número de parcelas, maior atraso e renegociação.
- `LegalExpenseChanged` publicado pelo legal; `SettlementClosed` publicado pelo settlement.

## 12. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Regras no settlement, versionadas por `ServiceContract` (uma linha por versão, jsonb tipado) | Colunas no contrato do organization; tabelas por componente | O contrato já delegava ao settlement; componentes opcionais e combináveis; mesmo padrão do incentive |
| 2 | Comissão apurada por recebimento, no lançamento, com a versão vigente na data do pagamento | Calcular só no fechamento | Percentuais por recebimento (sem progressão por volume); memória por lançamento; demonstrativo é soma |
| 3 | Sem publicação que alcance pagamento já apurado; pendentes são apurados na publicação | Recalcular lançamentos | Reprodutibilidade (RB08): lançamento nunca muda |
| 4 | Recuperação de acordo = valor aplicado pelo negotiation (`AgreementPaymentRecorded`), detalhe por `paymentDistribution` | Valor do `PaymentConfirmed` | Valor não aplicado (acordo encerrado/excedente) não é recuperação e pode ser realocado |
| 5 | Detalhe do título avulso no próprio `PaymentConfirmed` (campos novos) | Settlement → debt | Grafo não permite; o payment já calcula a distribuição |
| 6 | Honorários da assessoria fora da base; fração configurável; padrão 100% assessoria | Comissão sobre o total | Prática usual: honorários cobrados do devedor remuneram a assessoria, comissão incide sobre o que é do credor |
| 7 | Valor fixo no primeiro recebimento do acordo; estornado só no estorno integral desse recebimento | Por acordo formalizado | "Acordo não pago não remunera" (RB08) |
| 8 | Conciliação exigida por regra para dinheiro na conta da assessoria; pagamento ao credor dispensa | Sempre exigir; nunca exigir | Prestação de contas "conciliada" sem travar o recebido fora da conta da assessoria |
| 9 | Demonstrativo inclui o ainda não prestado com competência até o fim do período (inclui atrasados de períodos fechados) | Só competência dentro do período | Nada se perde; aprovado imutável; correção no período seguinte |
| 10 | Um demonstrativo em aberto por credor; períodos contíguos | Vários abertos | Cada item em exatamente um demonstrativo; recálculo simples |
| 11 | Estorno com competência na data do estorno; proporcional; último estorno leva o saldo exato | Competência do pagamento | Período aprovado não muda; soma zero sem resíduo |
| 12 | Revisão pode ser de quem calcula; aprovação segregada (gestor); segunda aprovação de ajuste acima de R$ 1.000,00 | Revisor ≠ calculista | Quatro olhos na aprovação, que é o ato financeiro; limite parametrizável |
| 13 | Publicação de regras por outra pessoa (quem editou não publica) | Sem dupla checagem | Transcrição de contrato é ponto de fraude/erro |
| 14 | Leituras de payment/negotiation como sistema, após o escopo do settlement | Exigir `PAYMENT_VIEW` de quem calcula | Prestação de contas não deve depender de permissões de outro módulo |
| 15 | Resposta do credor registrada pela assessoria | Portal do credor | Não há acesso do credor no produto (pendência) |
| 16 | Custas pelo contrato `LegalExpenseChanged` (publicado agora pelo legal); despesas manuais fora do jurídico via ajuste `EXPENSE_REIMBURSEMENT` | Tela de despesas no settlement | Uma fonte de verdade para custas; ajuste tem motivo, auditoria e segunda aprovação |
| 17 | Repasse ligado a demonstrativo aprovado, parcial permitido, comprovante obrigatório, idempotente | Repasse avulso por credor | Saldo conciliável por demonstrativo |
| 18 | Limite de ajuste e tamanho do comprovante por propriedade da aplicação | Parâmetro por assessoria | Sem tela de parâmetros; pode virar `settlement_settings` se a operação pedir |
| 19 | Consumidores leem os fatos de outros módulos antes de abrir a própria transação | Transação de leitura aninhada | Uma conexão por consumidor; sem risco de esgotar o pool com o executor de eventos cheio |

## 13. Pendências

- **legal**: consumir `SettlementClosed` para marcar como reembolsadas as despesas deduzidas (`reimbursedLegalExpenseIds`).
- **payment**: honorários de título avulso dependem da distribuição por componente (já publicada); consulta em lote da situação de conciliação (hoje uma leitura por alocação candidata no cálculo).
- **analytics**: views sobre `receipt`/`commission_entry`/`statement` (custo de comissão por real recuperado, prazo de fechamento e de repasse).
- **produto**: acesso do credor (portal/API) para aceite e contestação; envio automático por e-mail com o arquivo; configuração do limite de ajuste por assessoria.
- ~~**frontend**~~ (24/10/2026): telas de regras, razão, demonstrativo (ciclo, ajustes, custas, exportação com conferência do SHA-256 no download, envio, resposta do credor) e repasses em `/prestacao-de-contas`.
- Depósito judicial como origem de pagamento (payment) entra automaticamente como recebimento quando existir.
