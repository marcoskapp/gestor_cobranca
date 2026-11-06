# Desenho do módulo debt — correção de valor do título

Desenho técnico da correção manual do valor do título. As regras de negócio estão em
[negocio/correcao-de-valor.md](../../negocio/correcao-de-valor.md) (decisões 802–813, 864, 865, 872 e as revisões do
responsável 9101–9105, numeração provisória). Este documento cobre, por enquanto, só a correção de valor; o restante do
módulo (devedores, títulos, razão, garantias, prescrição) segue descrito no código e nas decisões.

## 1. Agregados e tabelas

| Agregado | Tabela | Observações |
| --- | --- | --- |
| `TitleAmountAdjustment` | `debt.title_amount_adjustment` + `title_amount_adjustment_line` (`V0304`) | append-only (gatilho); desfecho gravado uma vez; `approval_mode` `WITHIN_LIMIT`/`DUAL_CONTROL`; segregação por `check` |
| `AmountAdjustmentSettings` | `debt.amount_adjustment_settings` (`V0304`, proposta em `V0305`) | uma linha por assessoria (RLS); `approval_threshold` e a proposta de aumento (`proposed_threshold`, `proposed_by`, `proposed_by_name`, `proposed_at`) |
| `TitleAmountDivergence` | `debt.title_amount_divergence` (`V0304`) | carga do credor diferente do valor corrigido |

Sem migration nova nesta mudança: a soma do limite acumulado usa o índice existente
`ix_amount_adjustment_title (tenant_id, title_id, requested_at)`, e a validade da proposta é calculada a partir de
`proposed_at`.

## 2. Alçada (RB06) e limite acumulado por título

`TitleAmountAdjustment.isPureReductionWithin(increases, decreases, recentReductions, threshold)`: dentro do limite só
quando nenhum componente aumenta e `decreases + recentReductions <= threshold`.

- `recentReductions` = `TitleAmountAdjustmentRepository.sumWithinLimitReductionsSince(titleId, now - janela)`: soma
  `previous_balance - new_balance` das linhas que reduziram, nos pedidos `WITHIN_LIMIT` e `APPLIED` do título com
  `requested_at >= now - janela`. Pedidos `DUAL_CONTROL` não entram (já tiveram segunda pessoa).
- Janela: `app.debt.amount-adjustment.cumulative-window` (padrão `P30D`). Teto: o próprio `approval_threshold` (sem
  coluna própria; teto separado fica para decisão futura do responsável).
- **Concorrência**: o pedido trava o título (`findByIdForUpdate`) antes de ler a soma; dois pedidos no mesmo título se
  serializam no lock e o segundo, em `READ COMMITTED`, enxerga o primeiro já confirmado (teste em `TitleAmountLimitIT`:
  dois pedidos simultâneos em componentes diferentes, cada um dentro do limite sozinho → um `WITHIN_LIMIT` e o outro
  recusado com `title_amount.evidence_required`).
- Janela e validade da proposta só em **dias inteiros** (`DebtProperties` recusa subir com outro valor), porque telas,
  manual e tour falam em dias; o manual, o tour e as novidades citam os padrões de 30 e 15 dias.
- O pedido guarda em memória (`@Transient`) se foi o acumulado que levou a `DUAL_CONTROL` (`isCumulativeLimitReached`):
  mensagem própria em `title_amount.evidence_required`, `cumulativeLimit` na auditoria `title_amount.requested` e no
  evento `title.amount_adjustment_requested`, métrica `debt.title_amount.cumulative_limit`.
- **Prévia** (`GET /titles/{id}/amount-adjustment-context`): `recentReductions` e `cumulativeWindowDays` no
  `AdjustmentContext`; o `adjustmentPreview` da tela aplica a mesma regra e devolve `cumulative` para a mensagem
  "Somada às reduções de R$ X já aplicadas neste título nos últimos N dias…". A prévia lê sem lock; vale o que o pedido
  calcula com o título travado.

## 3. Proposta de aumento do limite com validade

- `AmountAdjustmentSettings.isProposalExpired(now, validity)` / `proposalExpiresAt(validity)`: vence em
  `proposed_at + app.debt.amount-adjustment.proposal-validity` (padrão `P15D`).
- `confirmProposal(by, reviewed, now, validity)`: proposta vencida → `ConflictException`
  `title_amount.proposal_expired` (409), antes da conferência do valor revisado.
- `SettingsView` traz `proposalExpiresAt`, `proposalExpired` e `cumulativeWindowDays`; a aba Limite mostra a data em que
  a proposta vence e, vencida, esconde "Confirmar aumento".
- Pendência `debt.amount_threshold_proposal`: a consulta só conta propostas com `proposed_at > now - validade`.
- Job `DebtJobs.expireThresholdProposals` (`@Scheduled` com `app.debt.amount-adjustment.proposal-expiry-cron`, padrão
  03:50 no fuso de operação; ShedLock `debt.amount-threshold-proposal-expiry`): por assessoria ativa, numa transação
  própria como ator de sistema (`TenantExecution`), chama `TitleAmountAdjustmentService.discardExpiredProposal`, que
  limpa a proposta e registra `title_amount.settings_proposal_expired`. Falha numa assessoria →
  `debt.jobs.failures{job=debt.amount_threshold_proposal_expiry}` e ERROR técnico; as demais seguem. Concorrência com
  uma confirmação: a versão otimista da linha faz um dos dois falhar.

## 4. Pedido sem transação longa (evidência fora da transação)

`TitleAmountAdjustmentService.request` roda com `@Transactional(propagation = NOT_SUPPORTED)` (sobrepõe o
`@Transactional` da classe; a chamada vem pelo proxy, via `TitleAmountAdjustmentUseCases`, sem métodos `default`), no
padrão do envio de PDF (decisão 870):

1. sem evidência: só a transação curta de registro;
2. com evidência: transação curta somente leitura confere o escopo por projeção (`findCreditorIdById`, sem carregar nem
   travar o título) e já recusa `title_amount.pending_exists` antes do envio (o registro confere de novo sob o lock);
3. o arquivo é validado pelo conteúdo e gravado no armazenamento **sem transação aberta**;
4. transação curta (`TransactionTemplate`, `REQUIRES_NEW`): trava o título, lê os saldos e a soma do limite acumulado,
   aplica a regra e grava;
5. `EvidenceFileCleanup.registerOrDiscard` acompanha o desfecho da transação do passo 4 por
   `TransactionSynchronization.afterCompletion`: se ela não começou ou foi desfeita (`STATUS_ROLLED_BACK`: regra recusa,
   pedido pendente, falha no banco), o arquivo é removido (falha na remoção → ERROR técnico com a chave); se o desfecho é
   incerto (`STATUS_UNKNOWN`, ex.: conexão caiu no commit), o arquivo é **mantido** e fica um WARN técnico só com a
   `storageKey` — apagar poderia deixar um pedido confirmado sem a evidência.

Testes: `TitleAmountEvidenceUploadIT` (nenhuma transação ativa durante o envio; pagamento no mesmo título conclui sem
esperar; pedido pendente recusado antes do envio; arquivo removido quando a regra recusa depois do envio) e
`EvidenceFileCleanupTest` (desfeita ou não iniciada remove; incerta ou confirmada mantém).

## 5. Testes

- Domínio e configuração: `TitleAmountAdjustmentTest` (acumulado), `AmountAdjustmentSettingsTest` (validade da
  proposta), `DebtPropertiesTest` (dias inteiros), `EvidenceFileCleanupTest`.
- Integração: `TitleAmountAdjustmentIT`, `TitleAmountLimitIT` (acumulado na janela e fora dela; concorrência no lock
  do título; proposta vencida com o
  relógio de teste `support/MutableClock`, pendência e job), `TitleAmountEvidenceUploadIT`.
