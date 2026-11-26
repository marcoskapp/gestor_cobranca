# Desenho do módulo debt — correção de valor do título e busca rápida

Desenho técnico da correção manual do valor do título (§1–§5), da busca rápida de devedor (§6) e do contato novo
recebido na carga (§7, desenho completo em [contatos-recebidos.md](contatos-recebidos.md)). As regras de negócio estão em
[negocio/correcao-de-valor.md](../../negocio/correcao-de-valor.md) (decisões 802–813, 864, 865, 872 e as revisões do
responsável 888–892), além do pago ao credor (§11). Este documento cobre, por enquanto, só esses temas; o restante do
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
  manual e tour falam em dias. O manual, o tour e as novidades **não** escrevem 30 e 15 dias à mão: usam os
  marcadores `{{debt.cumulativeWindow}}` e `{{debt.proposalValidity}}`, resolvidos com os valores que
  `DebtManualParameters` (`shared.manual.ManualParameterSource`) publica em `GET /api/v1/helpdesk/manual-parameters`
  — e, na base do chat de suporte, na subida do backend ([ux.md](ux.md) §8.7). Mudar a configuração muda o texto.
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

## 6. Busca rápida de devedor (`DebtorQuickSearch`, revisão de produto 11/2026 — item 1.3)

Porta `application/port/in/DebtorQuickSearch` (sem endpoint próprio; quem expõe é o collection,
`POST /api/v1/collection/quick-search` — [collection.md](collection.md) §8.4), implementada por
`DebtorQuickSearchService`. O termo é classificado no domínio (`domain/service/QuickSearchTerm`):

| Termo | Critério | Consulta |
| --- | --- | --- |
| CPF/CNPJ completo e válido (com ou sem máscara) | `DOCUMENT` | blind index `debtor.document_hash` |
| Telefone do Brasil completo (DDD + número; `+55` opcional) | `PHONE` | blind index `debtor_contact.value_hash` (`ix_debtor_contact_hash`), com o escopo na consulta; só se o documento não achou ninguém visível (CPF válido de 11 dígitos também pode ser celular) |
| Sem espaço, 3 a 80 caracteres, e que não seja documento nem telefone | `TITLE_CODE` | `debt_title.external_id` exato (`ix_debt_title_external_id`, **V0306**) |
| Com letra e 3+ letras ou dígitos | `NAME` | trecho do nome (`ix_debtor_name_trgm`), pela mesma consulta da lista de devedores |

- Documento ou telefone **incompleto** nunca é buscado como documento, telefone ou nome (nunca por parte de dado
  pessoal); sem espaço, ele só vira busca **exata** pelo código do título no credor (ex.: `071.340` acha o título cujo
  código é exatamente esse). Termo curto ou vazio devolve `criteria` vazio.
- Precedência: o documento é conferido com a visibilidade do ator **antes** de decidir pelo telefone (documento de
  devedor fora do escopo conta como "não achou" e o telefone é buscado).
- Telefone e código do título aplicam o escopo na própria consulta (título no credor ou coobrigação vigente, a regra de
  `DebtorVisibility`), em ordem de id: o limite de candidatos e o "há mais resultados" contam só devedores do escopo;
  `filterVisible` continua como segunda barreira. Credores do resultado em lote (`findDebtorCreditors` e
  `findActiveDebtorCreditors`: duas consultas).
- Escopo: candidatos de documento/telefone/código passam por `DebtorVisibility#filterVisible` (em lote); o nome usa o
  filtro de credores da própria consulta; os credores do resultado são só os do escopo. No máximo 10 devedores, sem
  paginação (`truncated` avisa que há mais).
- Exige `DEBTOR_VIEW` (`AccessScope#requirePermission`, defesa além do `@PreAuthorize`).
- **Log de acesso**: busca por documento ou telefone grava `PERSONAL_DATA_SEARCHED` (`resource=debtor`,
  `purpose=QUICK_SEARCH`, `criteria`, `returned`, `resourceIds` dos devolvidos) — inclusive com zero resultados; o termo
  nunca é gravado (nem em log técnico, evento ou métrica; `QuickSearchTerm#toString` não o contém).
- Testes: `QuickSearchTermTest` (classificação) e `CollectionQuickSearchIT` (escopo, critérios, log de acesso, web).

Lista de títulos por lote: `TitleSearch.importBatchId` (`GET /titles?importBatchId=`) filtra os títulos cuja **última**
carga foi o lote (`debt_title.import_batch_id`, atualizado a cada carga que toca o título; índice parcial
`ix_debt_title_import_batch`). Usado no link "Títulos importados" do lote.

## 7. Recuperação judicial, recuperação extrajudicial e falência (cobrança B2B §7, B6)

Desenho de produto em [cobranca-b2b.md](cobranca-b2b.md) §7. Código em `debt/{domain/model/InsolvencyProceeding,
application/service/InsolvencyService, web/InsolvencyController}`; migration `V0311__debt_insolvency_proceeding.sql`.

- **Tabela** `debt.insolvency_proceeding`: `debtor_id`, `kind` (`JUDICIAL_RECOVERY`, `EXTRAJUDICIAL_RECOVERY`,
  `BANKRUPTCY`), `case_number` (20 dígitos CNJ), `court`, `filed_on`, `converted_from`/`converted_on` (convolação),
  `status` (`ACTIVE`, `ENDED`), `ended_on`, `end_reason`, `note`. RLS (`shared.enable_tenant_rls`); `check` em `kind`,
  `status`, formato do número, `(status = 'ENDED') = (ended_on is not null)`, motivo obrigatório no encerramento,
  encerramento depois do pedido e convolação só de recuperação judicial para falência; índice único parcial
  `uk_insolvency_active` (no máximo um `ACTIVE` por devedor). Sem `delete` para `app_runtime` (o histórico fica).
- **Número CNJ**: `shared.domain.CnjNumber` (módulo 97, ISO 7064), o mesmo do jurídico — movido de
  `legal.domain.model` para `shared.domain` para não duplicar a regra; cada módulo informa o prefixo do código de erro
  (`insolvency.cnj_invalid`, `lawsuit.cnj_invalid`). O ano do número deve ser o da data do pedido
  (`insolvency.cnj_year_mismatch`); a data do pedido não pode ser futura.
- **Casos de uso** (`InsolvencyUseCases`): `register` (só PJ — `insolvency.debtor_not_company`, 422; devedor
  anonimizado → 409; já há ativo → `insolvency.already_active`, 409; a **origem da informação** é obrigatória e vira o
  motivo da auditoria), `convertToBankruptcy` (art. 73: o mesmo processo muda de tipo, com data da decretação e motivo),
  `end` (data e motivo), `ofDebtor` (ativo e histórico; devedor visível ao ator) e `isUnderInsolvency` (booleano para a
  elegibilidade do collection, sem escopo — chamado por ator de sistema).
- **Escopo**: registrar, convolar e encerrar exigem escopo sobre **todos** os credores do devedor
  (`DebtorVisibility#requireFullScope`, 403): a suspensão vale para a cobrança em todos eles. A consulta exige o devedor
  visível (404).
- **Web**: `GET /debtors/{id}/insolvency` (`DEBTOR_VIEW` ou `CASE_VIEW` — quem atende o caso precisa saber por que ele
  está suspenso), `POST /debtors/{id}/insolvency`, `POST /insolvency-proceedings/{id}/bankruptcy` e
  `POST /insolvency-proceedings/{id}/end` (`DEBTOR_MANAGE`).
- **Rastreabilidade**: auditoria `insolvency.registered` / `insolvency.converted` / `insolvency.ended` (com motivo);
  eventos de negócio `debtor.insolvency_registered` / `_converted` / `_ended` (tipo e durações, sem texto livre);
  métrica `debt.insolvency.changes{action,kind}` (sem devedor).
- **Integração**: cada mudança publica `DebtorInsolvencyChanged` (`shared.events.debt`: `debtorId`, `active`, `kind`).
  O collection reavalia os casos ativos do devedor em todos os credores e marca `DEBTOR_INSOLVENCY`
  ([collection.md](collection.md) §2); a régua respeita a elegibilidade ([campanhas.md](campanhas.md)). Interações não
  são apagadas e acordo em vigor **não** é cancelado (decisão humana: a dívida pode estar no plano de recuperação);
  acordo **novo** é recusado pela negociação (`CollectionCaseEligibilityAdapter`: `DEBTOR_INSOLVENCY` bloqueia como a
  anonimização). O processo vale para a empresa inteira: `isUnderInsolvency` e a publicação (um evento por unidade)
  consideram matriz e filiais pela raiz do CNPJ (`DebtorRepository#findIdsByDocumentRootHash`); `ofDebtor` traz o
  histórico das unidades, `canManage` e o texto livre só para quem vê o cadastro. Auditoria com motivo fixo (o texto
  livre fica no processo) e `insolvency.anonymized` na anonimização.
- **LGPD**: o número, o juízo e as datas são dados públicos da empresa. A nota e o motivo do encerramento são texto livre
  (podem citar pessoas): a anonimização do devedor os neutraliza (`InsolvencyAnonymizationListener`, consumidor
  `debt.insolvency-anonymized` de `DebtorAnonymized`). A tela orienta a não registrar dado pessoal.
- **Tela**: `app-insolvency-notice` (em `features/debt/components`), na ficha do devedor (com as ações, para quem tem
  `DEBTOR_MANAGE`, fora do painel da estação) e na ficha do caso (só o aviso): "Empresa em recuperação judicial desde
  …", processo, suspensão da cobrança e atalho para o caso jurídico aberto da empresa (um → abre o caso; vários → menu;
  nenhum → orientação de habilitar o crédito). Só para PJ: com pessoa física nada é consultado.
- **Testes**: `InsolvencyIT` (marcador → casos inelegíveis nos dois credores → a régua não envia → convolação →
  encerrado → elegíveis e a régua volta a enviar; só PJ; escopo total; número CNJ; um ativo; anonimização) e
  `CnjNumberTest`.

Carteiras em que o devedor tem títulos (todas, sem filtro de escopo): `DebtTitleUseCases#portfolioIdsOfDebtor`, usado
pelo compliance para o superendividamento por regime da carteira ([conformidade.md](conformidade.md) §1.5).

## 8. Cobrança B2B: empresa, pessoa de contato e representantes (B1–B3)

Desenho funcional em [cobranca-b2b.md](cobranca-b2b.md) §3 a §5. Migration `V0310__debt_company_profile.sql`.

* **Empresa** (`Debtor`): `trade_name_encrypted` (nome fantasia, `EncryptedStringConverter`, até 200) e
  `document_root_hash` (blind index da raiz do CNPJ, finalidade `debtor.document_root`; nulo para CPF), com `check` de
  que só pessoa jurídica ativa os tem. Raiz e unidade no domínio: `Document#cnpjRoot`, `#cnpjBranch`, `#isHeadOffice`;
  `Debtor#establishmentLabel` ("Matriz" / "Filial 0002"). Pessoa jurídica não tem data de nascimento
  (`debtor.birth_date_company` no cadastro manual; a carga ignora com aviso); nome fantasia só para PJ
  (`debtor.trade_name_individual`). `DebtorView` ganhou `tradeName` e `establishment` (no fim do record).
* **Outras unidades**: `DebtorUseCases#otherUnits(debtorId, page)` → `GET /debtors?sameCompanyAs=<id>` (o filtro leva o
  id do devedor de referência, nunca o CNPJ); mesma raiz, sem o devedor de referência, com o escopo por credor da busca
  (`DebtorJpaRepository#searchByDocumentRoot`, índice parcial `ix_debtor_document_root`). Cadastros anteriores à V0310
  recebem a raiz pela varredura diária `debt.document_root_backfill` (`DebtJobs`, ShedLock, lotes de 500 por
  assessoria; o blind index usa a chave da aplicação e não pode ser calculado pela migration) e pela próxima carga
  (`Debtor#assignDocumentRoot`, auditado como `documentRootAssigned`).
* **Pessoa de contato** (`DebtorContact`): `person_name_encrypted` (até 120), `person_role` (`FINANCE`,
  `ACCOUNTS_PAYABLE`, `OWNER_PARTNER`, `LEGAL`, `OTHER`, com `check`) e `decision_maker`
  (`contact.decision_maker_without_person` sem nome nem papel). `ContactView` traz o nome **mascarado como o valor**
  (`maskedPersonName` = "A***"), o papel e `decisionMaker`; o nome completo vem em `RevealedContact.personName`, na
  mesma revelação auditada do valor (`DEBTOR_SENSITIVE_VIEW` + `SENSITIVE_DATA_VIEWED`). Na edição, `personName` nulo
  mantém o nome (a tela só conhece o mascarado) e vazio remove. Auditoria com o nome mascarado (iniciais).
* **Representantes** (`CompanyRepresentative`, `debt.company_representative`, RLS): fora de `debt.debtor` — nunca em
  listas, buscas, filas, segmentos, score ou réguas. CPF (`document_encrypted` + `document_hash`, finalidade própria
  `debt.representative.document`), nome cifrado, papel (`MANAGING_PARTNER`, `ATTORNEY_IN_FACT`, `OTHER`),
  `can_negotiate`, `valid_until`, `evidence_note` (até 200), `source` (`MANUAL`/`IMPORT`), `ended_at` + `end_reason`;
  único por (empresa, CPF) enquanto vigente (índice parcial). Só devedor PJ (`representative.company_required`); só CPF
  (`representative.cpf_required`); duplicado vigente → 409 `representative.duplicate`; encerrado não muda
  (`representative.ended`). `canNegotiateOn(hoje)` = vigente, dentro da validade e com poder.
  * Caso de uso `CompanyRepresentativeUseCases` (`port/in`): `list`, `add`/`update`/`end` (`DEBTOR_MANAGE`),
    `revealDocument` (`DEBTOR_SENSITIVE_VIEW` + `SENSITIVE_DATA_VIEWED`, `resource=company_representative`), e para
    outros módulos `negotiator(companyId, cpf)` (o blind index é calculado aqui — o portal nunca conhece a finalidade),
    `hasNegotiators` e `get`. REST `/api/v1/debtors/{id}/representatives` (`GET`, `POST`, `PUT /{rid}`,
    `POST /{rid}/end`, `GET /{rid}/document`).
  * Auditoria `company_representative.created` / `.updated` / `.ended` com nome mascarado e CPF mascarado.
  * LGPD: a anonimização da empresa apaga os representantes (`representativesRemoved` na auditoria); a solicitação de
    titular do representante localiza-o pelo CPF pela fonte `RelatedPersonSource` (`shared.privacy`, implementada por
    `RepresentativePrivacySource`: só ids, papel e período, com o escopo por credor da consulta).
* **Carga** (`DebtIngestionPort`): `DebtorRecord.tradeName` e `representatives` (`RepresentativeRecord`),
  `ContactRecord.personName`/`personRole` — construtores antigos mantidos. Representante e pessoa de contato inválidos
  voltam como aviso (`representative.*`, `contact.person_role_invalid`); contato existente sem pessoa recebe a da carga.
  `RepresentativeRecord.role`/`canNegotiate` nulos = não informados: mantêm o papel e o poder cadastrados (na criação,
  `OTHER` e sem poder).
* **Testes**: `CompanyProfileTest` (domínio), `CompanyDebtorIT` (carga idempotente com nome fantasia, pessoa de contato e
  representantes; unidades pela raiz com escopo; raiz preenchida na carga; regras e validade dos representantes; carga sem papel e poder mantém o cadastro manual;
  permissões; fonte do titular; anonimização).

## 9. Contato novo recebido na carga (`V0309`)

Resumo do que o módulo implementa ([contatos-recebidos.md](contatos-recebidos.md) é o desenho completo):

* `debt.debtor_contact` ganha a carga de origem e a última carga que enviou o contato (`first_import_batch_id`,
  `last_import_batch_id`, `last_received_at`), `received_after_registration`, `novelty_until`, `first_attempt_at` e
  `first_effective_at`; `debt.debtor` ganha `created_import_batch_id` (devedor criado por carga). Índices parciais para a
  recálculo (`ix_debtor_contact_novelty`), para a varredura (`ix_debtor_contact_novelty_due`, contatos novos sem tentativa) e para o relatório/filtro da carga (`ix_debtor_contact_first_batch`).
* `DebtIngestionPort.DebtorRecord` leva `importBatchId`: `DebtorRegistry.upsertFromImport` marca como novo o contato
  criado para devedor que já existia antes da carga (`Debtor#existedBefore`) e registra o reenvio dos já existentes
  (`ContactRegistry#receiveFromImport`); publica `DebtorContactNoveltyChanged` quando o fim da novidade do devedor muda
  (`ContactNoveltyTracker`).
* Uso do contato: consumidor `debt.contact-usage` (`DebtEventListeners`) de `InteractionRecorded`, `CallCompleted` e
  `MessageStatusChanged` com `contactId` → `ContactNoveltyService#recordUse`. Contato inválido perde o selo: invalidar ou
  revalidar um contato novo também recalcula a novidade do devedor.
* Varredura diária `debt.contact_novelty_sweep` (`DebtJobs`, 03:40, ShedLock): devedores com janela vencida sem tentativa
  nos últimos `sweep-lookback` (padrão 7 dias) têm a novidade republicada.
* Prazo por assessoria: `ContactNoveltySettings` (`debt.contact_novelty_settings`, uma linha por assessoria, RLS),
  `ContactNoveltyUseCases` e `GET/PUT /api/v1/debtors/contact-novelty-settings` (`DEBTOR_MANAGE`); padrão do sistema
  `app.debt.contact-novelty.window-days` (30).
* Leitura: `ContactView.receipt` / `ContactSummary.receipt` (selo), `ChannelContact.newPending` (ordem do discador),
  `DebtorUseCases.DebtorFilter` (`newContact`, `newContactBatchId`), `DebtIngestionPort#contactReceipt` (contador do
  relatório da carga) e `DebtorSegmentationQueries` (tipo de pessoa e fim da novidade para o collection na abertura do caso).
* Testes: `DebtorContactTest` (domínio) e `ContactReceiptIT` (fluxo entre debt, collection e campaign).

## 10. Anonimização do titular: contato neutralizado no lugar (`V0312`)

`DebtorService.anonymize` não apaga mais os contatos do devedor (SEG-20261124-D01a, decisão 1457): conversas,
interações (imutáveis por gatilho) e chamadas de outros módulos apontam para `debt.debtor_contact`, e a exclusão
barrava a anonimização inteira de todo devedor já contatado. `DebtorContact#anonymize` grava `anonymized_at`, troca o
valor por `anonimizado` e o blind index pelo SHA-256 de `anonymized:<id>` (não reproduzível a partir de um contato
real), deixa o contato inválido, sem WhatsApp, sem nome e papel da pessoa; a tela mostra "Contato removido". O `check`
`ck_debtor_contact_anonymized` garante isso no banco. Depois disso o contato recusa toda alteração (`contact.anonymized`,
422), a revelação é recusada, o uso não é contado e `contactsForChannel` não o oferece. Cada módulo que guarda dado
do titular consome `DebtorAnonymized` e limpa o que é dele ([ADR 0012](../../arquitetura/adr/0012-anonimizacao-do-titular-por-modulo-dono.md)).
Testes: `DebtModuleIT#anonymizationRemovesPersonalDataButKeepsTitles`, `DebtorContactTest#anonymizedContactIsNeutralizedAndRefusesEveryChange`.

## 11. Pago ao credor: quem recebeu o dinheiro (`V0313`, H07)

Decisões 1466 e 1521–1526. O pagamento feito diretamente ao credor (origem `CREDITOR_DIRECT` ou `CREDITOR_REPORT` no
payment) é destacado nos títulos.

- **Razão**: `title_ledger_entry.received_by` (`AGENCY` ou `CREDITOR`) só em `PAYMENT` e `REVERSAL` (`check`
  `ck_title_ledger_received_by`); `TitleLedger.applyPayment` exige o receptor (`DebtLedgerPort.PaymentApplication
  .receivedBy`, padrão `AGENCY`) e `reversePayment` repete o do pagamento estornado. Lançamentos anteriores ficam
  nulos (valem assessoria; sem carga retroativa no razão append-only).
- **Cache no título**: `debt_title.creditor_paid_total`, parte de `paid_total` recebida pelo credor, líquida dos
  estornos (`TitleBalance.creditorPaidTotal`, gravada por `DebtTitle.recordPosting` na mesma transação; `check`
  `0 <= creditor_paid_total <= paid_total`). Índice parcial `ix_debt_title_creditor_paid` (`creditor_paid_total > 0`, o mesmo predicado do filtro).
- **Leitura**: `TitleView.creditorPaidTotal`, `TitleSummary.creditorPaidTotal` (ficha do devedor),
  `LedgerEntryView.receivedBy` e o filtro `TitleSearch.paidToCreditor` (`GET /api/v1/titles?paidToCreditor=true`).
  A API pública não expõe o campo (decisão 1524).
- **Telas**: selo "Pago ao credor" na lista de títulos (filtro "Recebimento", `?recebido=credor`) e na ficha do
  devedor e na estação (aba Títulos, `CaseUseCases.TitleInfo.creditorPaidTotal`); no detalhe, "ao credor: R$ …" no total pago e o aviso com "Ver no razão"; no razão, "Pago ao credor" ou "Estorno de pago ao credor".
- **Auditoria**: o lançamento grava `receivedBy` nos metadados de `debt_title.payment_applied` e `payment_reversed`.
- **Testes**: `TitleLedgerTest` (receptor, estorno e obrigatoriedade) e
  `PaymentExternalIT#paymentReceivedByTheCreditorMarksTitleAndAgreementUntilReversed` (título avulso, acordo, filtro
  e estorno).

## 12. Referências conservadas do titular anonimizado (`AnonymizedSubjectQueries`, D01h)

Os títulos e contratos de origem do titular anonimizado ficam (LGPD art. 16 I e II), com o identificador do credor.
São a única referência que sobra dele no debt, e servem para três coisas (SEG-20261124-D01h, decisões 1469–1472):

- **Recusa na carga:** `DebtIngestionService#requireSubjectNotAnonymized` recusa com `title.subject_anonymized`
  (`DebtIngestionPort.SUBJECT_ANONYMIZED`) a linha cujo contrato de origem ou título, no credor, é de devedor
  anonimizado, antes de gravá-los; a transação da linha desfaz o devedor criado para ela. Teste:
  `DebtModuleIT#importOfTitleOfAnonymizedDebtorIsRejectedWithoutCreatingDebtor`.
- **Recusa em lote** (ingestion, por bloco): `anonymizedKeys(creditorId, títulos, contratos)` devolve os
  identificadores de título e de contrato do credor que são de devedor anonimizado; o chamador
  manda no máximo um bloco; exige `IMPORT_EXECUTE` e acesso ao credor. Usa `uk_debt_title_external` e `uk_origin_contract_external` (sem índice novo).
- **Referências do titular** (compliance e ingestion, na anonimização): `referencesOf(debtorId)` devolve os títulos do
  devedor anonimizado (vazio se não estiver), com `displayReference` mascarado por `Document.maskIfDocument` (CPF/CNPJ
  inteiro ou embutido, decisão 1472); exige `DATA_SUBJECT_REQUEST_MANAGE` e todos os credores (o sistema tem os dois).
  Em vez do identificador, o record leva a `titleKey` (chave HMAC, a mesma das linhas da carga): o identificador cru
  nunca sai do debt (decisão 1476; o `toString` omite a chave).
- **Identificador com CPF/CNPJ trocado na anonimização** (decisão 1476, decisões 1610–1621; `V0316`): no
  `DebtorService#anonymize`, depois de gravar o devedor, o `ExternalIdAnonymizer` troca o `external_id` dos títulos e
  dos contratos de que o titular é o devedor, quando traz documento (`Document.maskIfDocument` diferente do valor), por
  `anon:` + HMAC (`shared.crypto.ExternalIdDigest`: assessoria, finalidade de título ou de contrato, credor e
  identificador sem espaços nas pontas) e guarda a forma mascarada em `external_id_display` (até 1.440). O contrato de
  outro devedor em que ele era coobrigado fica como está. Telas, views e textos usam `displayExternalId()`.
  - **Banco:** o gatilho `debt.external_id_anonymize_only` aceita só a troca de ida (para `anon:<64 hex>`, com o devedor
    anonimizado e a forma de exibição junto); `check` reserva o prefixo `anon:` e amarra a forma de exibição à troca.
  - **Carga:** `ExternalIdLookup` compara sempre pelas duas formas (crua e anonimizada) no upsert do título (com
    trava) e do contrato e nas consultas de anonimizados; o título trocado continua recusado e não volta com o
    identificador cru. Quem só consulta (busca rápida, `by-external-id` da API pública, pagamento do credor por número
    do título) busca pelo cru e não acha o título trocado (decisão 1611); o prefixo reservado devolve "não encontrado".
  - **Anteriores à versão:** job `debt.external-id-anonymization`, enfileirado pela `V0316` uma vez por assessoria
    (cursor por devedor anonimizado, idempotente, auditoria `debtor.external_ids_anonymized` só com quantidades).
  - **Rotação da chave do índice cego:** exige manter a chave anterior só para verificação (o cru não existe mais;
    `anon:<versão>:` reservado, decisão 1621, SEG-20261126-E10).

## 13. Titular eliminado a pedido não volta (SPI `ErasedSubjects`, decisão 1471)

- **SPI** `debt.application.port.spi.ErasedSubjects` (decisão 1600), implementada só pelo compliance, dono do pedido do
  titular; injeção obrigatória. O debt consulta em dois lugares (`ArchitectureTest#erasedSubjectsAreCheckedOnlyByTheirOwners`):
  `DebtorRegistry.create` (todo devedor novo: carga, cadastro manual e coobrigado manual) e
  `AnonymizedSubjectQueries#erasedSubjectsAmong` (recusa por bloco da carga; `IMPORT_EXECUTE` e o credor).
- `DebtorRegistry.create` grava com `saveAndFlush` e confere depois (decisão 1601); reconhecido, recusa com
  `DebtIngestionPort.SUBJECT_NOT_ACCEPTED` (`row.not_accepted`) e texto neutro (decisão 1498), e a criação é desfeita.
- **Trava compartilhada** `DebtorRepository#refreshForShare` (decisão 1602): `flush` e `refresh` com `PESSIMISTIC_READ`
  antes de criar título (`DebtIngestionService#upsertTitle`), coobrigação (`#upsertObligationParty`,
  `ObligationPartyService#add`) e no estorno (`DebtLedgerService`, que lê o devedor do título sem trava e o trava antes
  do título). Ordem devedor → contrato → título em todos os caminhos. Se a eliminação terminou entre a busca e a trava,
  recusa neutra `row.not_accepted` (`DebtorRegistry#requireNotErasedMeanwhile`).
- **Estorno** que deixaria aberto título quitado de titular eliminado: o título fica `SETTLED` com o saldo e o motivo
  `DebtTitle.ERASED_HOLDER_REASON` (`keepSettledAfterErasure`), auditoria `debt_title.reversal_kept_settled`
  (decisão 1603).
- `DebtorUseCases#anonymize` devolve `Anonymization` (o cadastro e o documento eliminado, `toString` mascarado), só para
  o portão do compliance gravar o índice do titular no pedido.
- `AnonymizedSubjectQueries#coObligorContractsOf`: contratos em que o titular era coobrigado, para o aviso ao credor
  (decisão 1605).

Testes: `ErasedSubjectReturnIT`, `DebtTitleTest#reopenedTitleOfErasedHolderStaysSettled`.

## 14. O que a carga do credor fez com o título (`V0314`, H07 parte 2b)

Decisões 1576–1581 e 1632–1633. Resposta 2 do responsável (25/11/2026): a carga pode encerrar o título ao reduzir o principal.

- **Saldo zerado pela carga**: `debt_title.settled_by_import_batch_id` + `settled_by_import_at`, gravados por
  `DebtTitle#markSettledByImport` só no caminho da carga (`DebtIngestionService#updateTitle`, logo depois do
  `TitleLedger.adjustPrincipal`), com o lote em processamento, na mesma transação, e só quando o ajuste levou o título a
  `SETTLED` (auditoria `debt_title.settled_by_import`). Toda mudança de status limpa as colunas (`DebtTitle#moveTo`); a
  reabertura pelo razão registra a mudança de `settledByImportBatchId` na auditoria do lançamento
  (`TitleLedger.Posting.previousSettledByImportBatchId`). Reprocessamento (outro lote) não sobrescreve: o título
  quitado não é alterado pela carga. Linha recusada por titular anonimizado (D01h) não grava nada (a recusa vem antes).
- **Suspensão por "credor informou pagamento/cancelamento"**: `suspension_reason_code`
  (`CREDITOR_REPORTED_PAID`/`CREDITOR_REPORTED_CANCELLED`, `check`), `suspension_import_batch_id` e `suspended_at`
  (`check`: motivo codificado exige lote e instante), gravados por `DebtTitleUseCases#suspendReportedByCreditor`
  (só ator `SYSTEM` com `IMPORT_EXECUTE` — os demais atores são recusados com `denyActorType`; chamado pelo `RowWriter`
  com o lote em processamento; uma única auditoria `debt_title.suspended_by_creditor_report` com a mudança de status, o
  motivo codificado e o lote). Vai para o `DebtIngestionPort` quando o `DebtIngestionService` for liberado pela frente
  do D01h (decisão 1580). A saída da suspensão — manual (`transition`) ou pelo razão
  (`TitleLedger.Posting.previousSuspensionImportBatchId`, registrado pelo `LedgerRecorder`) — registra a mudança de
  `suspensionImportBatchId` na auditoria. O texto livre do motivo nunca é lido: sem tratamento de títulos suspensos antes
  da `V0314` (o sistema não está em produção e o sandbox é recriado; decisão 1579).
- **Expand/contract**: a `V0314` (expand) só acrescenta colunas anuláveis e `check`s que a versão anterior não viola; a
  coerência com o status (lote só em `SETTLED`, motivo só em `SUSPENDED`) fica para a migration de **contract** (pendência
  registrada). A leitura sempre filtra pelo status.
  - **Gatilho**: só depois que todas as instâncias rodarem a versão com a `V0314` (troca concluída, nenhuma instância
    antiga no balanceador). Antes disso, uma instância antiga que muda o status do título não limpa as colunas e teria a
    transação recusada pelo `check` novo.
  - **Caso RC-07** (revisão de código): durante a troca, uma instância antiga pode reabrir, reativar ou cancelar um título
    sem limpar o lote ou o motivo. A migration de contract limpa esses restos **antes** dos `check`s (o status vale; a
    leitura já os ignorava), e os `check`s entram `not valid` e depois `validate` (a verificação das linhas existentes
    não bloqueia as escritas):

    ```sql
    -- contract da V0314 (faixa do debt; número definido quando for escrita)
    update debt.debt_title set settled_by_import_batch_id = null, settled_by_import_at = null
     where settled_by_import_batch_id is not null and status <> 'SETTLED';
    update debt.debt_title set suspension_reason_code = null, suspension_import_batch_id = null, suspended_at = null
     where suspension_reason_code is not null and status <> 'SUSPENDED';
    alter table debt.debt_title
        add constraint ck_debt_title_settled_by_import_status
            check (settled_by_import_batch_id is null or status = 'SETTLED') not valid,
        add constraint ck_debt_title_suspension_reason_status
            check (suspension_reason_code is null or status = 'SUSPENDED') not valid;
    alter table debt.debt_title validate constraint ck_debt_title_settled_by_import_status;
    alter table debt.debt_title validate constraint ck_debt_title_suspension_reason_status;
    ```
  - **Rollback**: o rollback da aplicação não reverte o banco (as migrations só avançam). Voltar para a versão anterior à
    `V0314` funciona com as colunas presentes (ela as ignora). Depois da contract, voltar para uma versão anterior a ela
    exige antes uma migration nova que remova os dois `check`s (`alter table debt.debt_title drop constraint ...`), porque
    a versão antiga não limpa as colunas ao mudar o status.
- **Lista** (`CreditorReportedTitlesQueries`, `GET /api/v1/titles/creditor-reported`, `PAYMENT_VIEW`; aba "Pela carga do
  credor" de "Baixas informadas pelo credor", [pagamentos.md](pagamentos.md) §5.0): duas partes (zerado; suspenso com
  motivo codificado e lote) com o escopo por credor na SQL (`JdbcCreditorReportedTitlesReader`), período obrigatório até
  366 dias, `size` ≤ 100, `sort` `occurredAt`/`amount`; o filtro por lote só vale com `IMPORT_VIEW` (sem a permissão, é
  ignorado). Referência do título só com `DEBTOR_VIEW` ou `CASE_VIEW` (nula para titular anonimizado; o link para o
  título continua), nome só com `DEBTOR_VIEW`, lote só com `IMPORT_VIEW`; não lê o schema do ingestion — o rótulo do
  lote (data, layout, origem; nunca o arquivo) vem de `ImportBatchUseCases#labels` (teste `ImportBatchLabelsIT`).
  Índices `ix_debt_title_settled_by_import` e `ix_debt_title_creditor_report`. Métrica `debt.creditor_reported.queries`.
- **Testes**: `DebtTitleTest` (domínio), `CreditorReportedIsolationIT#importSettlementIsRecordedOnlyBySettlingImportAndClearedWhenTheTitleReopens`,
  `IngestionModuleIT` (suspensão com motivo codificado e lote).

## 15. Estorno em conferência: retenção da cobrança após estorno de pagamento recebido pelo credor (`V0315`, H07 parte 3)

Decisões do responsável de 25/11 e 26/11/2026 (decisão 1644) e decisões 1645–1670. O estorno de um pagamento recebido
pelo credor (origem `CREDITOR_DIRECT`/`CREDITOR_REPORT`) **não retoma a cobrança sozinho**: o payment cria, na mesma
transação do estorno e antes de publicar os `PaymentReversed`, uma retenção `debt.reversal_hold` (porta
`ReversalHoldPlacement#place` na interface `debt :: reversal-hold`, só do payment — regra ArchUnit
`onlyPaymentPlacesReversalHolds`; `MANDATORY`; erro desfaz o estorno inteiro — RS-108).

- **Registro** (um por estorno, empilha): credor, devedor, pagamento, estorno (único por tenant), motivo do
  estorno, quem estornou (`USER`/`SYSTEM`), quem informou (só usuário interno), situação `PENDING | RELEASED | ENDED`,
  liberação (quem, quando, motivo codificado, justificativa ≤ 500) e encerramento. Itens em `debt.reversal_hold_item`
  (`TITLE`, `AGREEMENT` — um ou mais acordos, decisão 1678 —, `INSTALLMENT`, `ALLOCATION`). RLS (a tabela filha pela linha-mãe), sem `DELETE` para `app_runtime`, itens
  append-only e gatilho `reversal_hold_guard` (campos da criação imutáveis; retenção encerrada não muda de situação; a
  justificativa só pode ser limpa) — RS-123. Check de segregação no banco.
- **Independente do status do título** (RS-109): nada no título a encerra; só a liberação humana e a anonimização do
  titular (`ReversalHoldAnonymizationListener`, consumidor de `DebtorAnonymized`: pendente → `ENDED`, justificativa
  limpa). Titular eliminado (decisão 1603) não recebe retenção.
- **Onde vale**: porteiro do compliance (`isHeld`: devedor principal ou coobrigado ativo de título retido; nega
  `COLLECTION` com `PAYMENT_REVERSAL_HOLD`), elegibilidade do collection (`PAYMENT_REVERSAL_HOLD`), negociação
  (`title.reversal_hold` em todos os títulos do par), quebra do acordo (`breakExemptInstallmentIds`: retida ou liberada
  há menos de `grace-days`), instrução de pagamento (`heldAmong`, `pendingInstallmentIds`), portal do devedor
  (`PortalDebts.titlesInPortal` sem os `heldTitleIds`), score (`createdForAllocation`). A ligação devedor–credor do
  registro externo (RS-124) é outra porta, `DebtorCreditorLinkQueries#linked` (consulta escalar antes das travas).
  Colocação, liberação e encerramento notificam os títulos retidos e os títulos próprios, no mesmo credor, dos
  coobrigados ativos (decisão 1673).
- **Liberação** (`release`, via `/api/v1/payment/reversal-holds/{id}/release`): só `USER` com `PAYMENT_REVIEW`; 404 igual
  fora do escopo, de outra assessoria ou inexistente antes de qualquer regra (consulta escalar de credor/devedor);
  trava compartilhada do devedor → `FOR UPDATE` da retenção; versão (409), situação (409), segregação (422
  `reversal_hold.self_release`, Admin incluído), motivo e justificativa (422), confirmação (409). `AuditEntry`
  `reversal_hold.released` com o estado anterior/novo, pagamento, estorno, credor e o tamanho da justificativa (nunca
  o texto). Colocação e liberação publicam `TitleStatusChanged` dos títulos retidos (gatilho interno `REVERSAL_HOLD`)
  para o collection reavaliar.
- **Telas**: aba "Estornos em conferência" (payment), selo "Estorno em conferência" (`GET /api/v1/titles/reversal-holds
  ?debtorId=`, `DEBTOR_VIEW` ou `CASE_VIEW`; pagamento só com `PAYMENT_VIEW`) e pendência "Estornos de pagamento ao
  credor para conferir" (`DebtPendingWork`, sem as do próprio autor/informante; vencida depois de
  `app.debt.reversal-hold.overdue-after`, padrão 5 dias). Nunca liberação automática por prazo (RS-113).
- **Credor**: não estorna, não libera nem vê a retenção (RS-119); a API pública omite o motivo `PAYMENT_REVERSAL_HOLD`.
- **Roteiro de rollback** (decisão 1654, [revisar]): voltar para uma versão sem a parte 3 **não** desfaz a V0315 nem a
  V0815 (expand); a versão anterior ignora as retenções pendentes — o contato de cobrança e as ofertas voltam para os
  pares retidos — e não lê casos com o motivo `PAYMENT_REVERSAL_HOLD` (a tolerância a valor desconhecido só existe a
  partir desta versão). Antes de um rollback: listar as retenções `PENDING` na aba "Estornos em conferência", avisar o
  financeiro e, se o rollback for inevitável, suspender manualmente o contato desses pares (restrição de contato do
  credor). Antes da produção, reavaliar um deploy anterior só com a tolerância.
