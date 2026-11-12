# Desenho do módulo debt — correção de valor do título e busca rápida

Desenho técnico da correção manual do valor do título (§1–§5), da busca rápida de devedor (§6) e do contato novo
recebido na carga (§7, desenho completo em [contatos-recebidos.md](contatos-recebidos.md)). As regras de negócio estão em
[negocio/correcao-de-valor.md](../../negocio/correcao-de-valor.md) (decisões 802–813, 864, 865, 872 e as revisões do
responsável 888–892). Este documento cobre, por enquanto, só a correção de valor; o restante do
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
