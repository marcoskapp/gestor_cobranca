# Desenho — garantias e workflow jurídico

Especificação de produto: [`docs/negocio/workflow-juridico.md`](../../negocio/workflow-juridico.md).

## Parte A — evolução do módulo `debt` (garantias, partes, natureza do título, prescrição)

Migrations a partir de V0301 (faixa do `debt`), sem alterar a V0300 publicada.

### A.1 `Collateral` (garantia)
Tenant-scoped, ligada ao `OriginContract`, cobrindo seus títulos (ou a títulos específicos via tabela de vínculo quando o contrato tiver garantias por parcela).

| Campo | Observação |
| --- | --- |
| `type` | `VEHICLE_FIDUCIARY_ALIENATION`, `REAL_ESTATE_FIDUCIARY_ALIENATION`, `MORTGAGE`, `PLEDGE`, `RECEIVABLES_FIDUCIARY_ASSIGNMENT`, `SURETY_INSURANCE`, `OTHER` |
| `description` | texto livre |
| `assetIdentification` | estruturado por tipo: veículo (placa, RENAVAM, chassi, ano/modelo); imóvel (matrícula, cartório, município/UF); outros (descrição). Dados que identificam pessoa física não entram aqui |
| `registration` | órgão/cartório, número do registro ou do gravame, data |
| `status` | `ACTIVE`, `UNDER_ENFORCEMENT`, `SEIZED`, `CONSOLIDATED`, `AUCTIONED`, `RELEASED`, `WRITTEN_OFF` com transições validadas e auditadas: `ACTIVE → UNDER_ENFORCEMENT/RELEASED/WRITTEN_OFF`; `UNDER_ENFORCEMENT → ACTIVE/SEIZED/CONSOLIDATED/RELEASED/WRITTEN_OFF`; `SEIZED → AUCTIONED/RELEASED/WRITTEN_OFF`; `CONSOLIDATED → AUCTIONED/WRITTEN_OFF` (só alienação fiduciária de imóvel); demais finais |
| `CollateralAppraisal` | histórico (append-only): valor (`Money`), data, fonte (`CREDITOR`, `APPRAISER`, `FIPE`, `MARKET`, `AUCTION`), observação |

### A.2 `ObligationParty` (partes da obrigação)
Por contrato: `debtorId` (o mesmo cadastro de devedor, consolidado por documento — RB01), `role` (`PRINCIPAL`, `GUARANTOR_AVAL`, `GUARANTOR_SURETY`, `CO_DEBTOR`, `THIRD_PARTY_COLLATERAL_PROVIDER`), `sinceDate`. O devedor principal do título continua em `DebtTitle.debtorId`; coobrigados passam a ser visíveis na ficha e elegíveis à cobrança conforme política do credor.

### A.3 Natureza do título e prescrição
- `OriginContract.instrumentType`: `CONTRACT`, `CCB`, `PROMISSORY_NOTE`, `DUPLICATE`, `CHECK`, `DEBT_CONFESSION`, `OTHER`; `executiveTitle` (boolean). Contratos anteriores assumem `CONTRACT`, não executivo.
- **Pretensões** (`PrescriptionClaim`): `EXECUTION` (execução do título executivo; só se aplica a contrato com `executiveTitle`) e `COLLECTION` (cobrança/monitória — limite da cobrança). Cada título tem até uma prescrição por pretensão: o cheque, por exemplo, perde a força executiva em 6 meses e continua cobrável por monitória por 5 anos.
- `PrescriptionRule` (tabela `debt.prescription_rule`, por tenant, única por natureza × pretensão): `termMonths` (prazo em meses — o cheque não cabe em anos), `base` (`TITLE_DUE_DATE` ou `CONTRACT_FINAL_DUE_DATE`), `startOffsetDays` (ex.: prazo de apresentação do cheque), `interruptionPolicy` (`FIRST_ONLY` — CC art. 202 — ou `EVERY`), `alertDays` (janela do alerta), `legalBasis`, `reviewStatus` (`PENDING_LEGAL_REVIEW`/`VALIDATED`). Nenhum prazo fica no código.
- **Tabela de mercado** (`debt.prescription_rule_template`, global, mantida por migration) copiada para cada assessoria como `PENDING_LEGAL_REVIEW` — as existentes na V0302, as novas ao `TenantProvisioned` e sob demanda (`apply-market-defaults`). Valores iniciais (validar com o jurídico): contrato/CCB/NP/duplicata/cheque/confissão — cobrança 60 meses (CC 206 §5º I; Súmulas 503 e 504/STJ); execução: contrato e confissão 60 meses (Súmula 150/STF), CCB/NP/duplicata 36 meses (LUG art. 70; Lei 5.474 art. 18), cheque 6 meses após 30 dias de apresentação (Lei 7.357 arts. 33 e 59). Quem altera ou confirma a regra assume a validação (auditado).
- `TitlePrescription` (por título × pretensão, recalculável): `baseDate`, `countingStart`, `termMonths`, `estimatedDate`, `ruleId`, `lastAlertedFor`. Existe enquanto houver regra aplicável; remover a regra remove as linhas (cascata). Recalculada na carga (novo título, vencimento ou contrato alterado), na reclassificação, no registro de interrupção, por job após mudança de regra (`debt.prescription.recalculate`, lotes por cursor) e pela reconciliação diária.
- `PrescriptionInterruption` (append-only; única por título × tipo × data): tipo `PROTEST`, `CITATION`, `ACKNOWLEDGEMENT`, `OTHER`; data (não futura); referência; origem `MANUAL`/`LEGAL`; evidência opcional (S3, categoria `EVIDENCE`, download registra `FILE_DOWNLOADED`). Eficaz se ocorrer entre o início da contagem vigente e a data estimada vigente; reinicia o prazo inteiro (com `FIRST_ONLY`, só a primeira).
- Job diário (`app.debt.prescription.daily-cron`, ShedLock): reconcilia contratos com título em cobrança sem prescrição calculada e alerta, uma vez por data estimada, os títulos em cobrança cuja data entrou na janela da regra — evento de integração `TitlePrescriptionApproaching` (`shared.events.debt`, com `claim` e `ruleValidated`) e business event `title.prescription_approaching`.

### A.4 Importação
O layout canônico do `ingestion` ganha campos opcionais de garantia, avaliação, coobrigados (documento, nome, papel) e natureza do título. **Responsabilidade do módulo `ingestion`.** O `debt` já expõe no `DebtIngestionPort`, idempotentes e por linha: `classifyContract`, `upsertObligationParty` (pessoa pelo mesmo fluxo de `upsertDebtor`), `upsertCollateral` (casada por identificador externo ou pelo bem; bem `OTHER` exige identificador externo; modalidade divergente vira aviso `collateral.type_mismatch`) e `recordCollateralAppraisal` (uma por lote e só se mudar). Todos exigem o contrato gravado antes por `upsertTitle`.

### A.5 API, permissões e integrações (implementado)
| Recurso | Permissão |
| --- | --- |
| `GET /api/v1/debt/contracts/{id}`, `.../parties`, `.../collateral`, `GET /api/v1/debt/collateral/{id}[/appraisals]`, `GET /api/v1/debt/titles/{id}/prescription` | `DEBTOR_VIEW` ou `CASE_VIEW` |
| `POST /api/v1/debt/contracts/{id}/collateral`, `PUT /api/v1/debt/collateral/{id}`, `POST .../status`, `POST .../appraisals`, `POST /api/v1/debt/contracts/{id}/parties[/{partyId}/end]` | `COLLATERAL_MANAGE` |
| `PUT /api/v1/debt/contracts/{id}/nature`, `POST /api/v1/debt/titles/{id}/prescription/interruptions` (JSON ou multipart com evidência), download da evidência | `PRESCRIPTION_MANAGE` |
| `GET /api/v1/debt/prescription-rules[/{id}]` | `POLICY_VIEW` ou `PRESCRIPTION_MANAGE` |
| `POST/PUT/DELETE /api/v1/debt/prescription-rules...`, `POST .../{id}/validate`, `POST .../apply-market-defaults` | `POLICY_MANAGE` + escopo de todos os credores |

`COLLATERAL_MANAGE` e `PRESCRIPTION_MANAGE` são concedidas a ADMIN e GESTOR (V0303 para assessorias existentes; `DefaultRoles` para novas). Para o módulo `legal`: `CollateralUseCases.changeStatus` (situação da garantia na execução) e `PrescriptionInterruptionPort.register` (protesto/citação; idempotente; evidência já gravada no `FileStorage` do tenant). Coobrigados vigentes tornam o devedor visível a quem tem escopo sobre o credor do contrato e aparecem na ficha (`DebtorSummary.coObligations` e `collateral`); coobrigado de contrato em cobrança não pode ser anonimizado.

## Parte B — módulo `legal`

Schema `legal`, migrations V1700–V1799 (nova faixa). Dependências: `shared`, `organization`, `identity`, `debt`, `collection`, `negotiation`, `compliance`.

### B.1 Agregados
| Agregado | Responsabilidade |
| --- | --- |
| `LegalEligibilityRule` | regras por credor/carteira para indicar casos ao jurídico |
| `LegalMatter` | o caso jurídico: devedor, títulos, garantias, credor, fase (§B.2), via escolhida, responsável (usuário ou escritório), valores (dívida, custas previstas, valor recuperável estimado) |
| `ViabilityAssessment` | checklist documental, prescrição, patrimônio, garantia, custo × benefício, recomendação e parecer |
| `CreditorAuthorization` | autorização formal do credor, orçamento aprovado, evidência |
| `ExtrajudicialMeasure` | notificação, protesto, negativação, execução extrajudicial de garantia — tipo, datas, protocolo, situação, prazo, evidências |
| `LawsuitCase` | processo judicial: número CNJ (validado), tribunal, órgão, classe, polos, valor da causa, data de distribuição, situação |
| `CaseMovement` | andamentos (manuais ou capturados), append-only |
| `LegalDeadline` | prazo com contagem em dias úteis (calendário de feriados do `compliance` + suspensões forenses cadastráveis), responsável, alertas escalonados, cumprimento |
| `Hearing` | audiências: tipo, data, local/link, participantes, resultado |
| `LegalExpense` | custas e despesas: tipo, valor, quem adiantou, reembolsável, comprovante |
| `LawFirm` | escritório parceiro; usuários do escritório com perfil próprio e escopo restrito aos casos atribuídos |

### B.2 Fases e transições
`ELIGIBLE → UNDER_REVIEW → AWAITING_AUTHORIZATION → EXTRAJUDICIAL → FILED → IN_PROGRESS → ENFORCEMENT → SETTLED_IN_COURT → CLOSED`, além de `NOT_VIABLE` e `RETURNED_TO_CREDITOR`. Transições validadas no domínio, com motivo, auditoria e evento `LegalMatterStageChanged` (novo, em `shared.events.legal`).

### B.3 Reações em outros módulos
- `collection`: ao entrar em fase jurídica, caso marcado como jurídico; réguas incompatíveis pausadas; representação por advogado direciona o contato.
- `negotiation`: política para casos judicializados (custas e honorários na composição), acordo judicial vinculado ao `LegalMatter`, quebra retoma a fase processual.
- `payment`: origem `JUDICIAL_DEPOSIT`.
- `settlement`: despesas reembolsáveis e honorários no demonstrativo.
- `debt`: interrupções de prescrição (protesto, citação) e mudança de situação da garantia.

### B.4 Integrações (portas)
`CourtDataProvider` (adaptador DataJud), `ProtestProvider`, `CreditBureauProvider`, `RealEstateRegistryProvider`, `VehicleLienProvider`, `PublicationsProvider` — adaptadores após escolha/convênio.

### B.5 Segurança
Permissões novas: `LEGAL_VIEW`, `LEGAL_MANAGE`, `LEGAL_AUTHORIZE` (aprovar indicação), `LEGAL_EXPENSE_MANAGE`. Usuários de escritório parceiro: escopo por caso atribuído, além do escopo por credor.
