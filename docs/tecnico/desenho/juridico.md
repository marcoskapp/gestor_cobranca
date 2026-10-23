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

## Parte B — módulo `legal` (implementado)

Schema `legal`, migrations `V1700__legal_schema_and_tables.sql` (tabelas, RLS, privilégios) e `V1701__legal_permissions_and_roles.sql` (permissões e perfis para assessorias existentes). Dependências declaradas: `shared`, `organization`, `identity`, `debt`, `collection`, `compliance` (calendário de feriados). O `negotiation` **não** é dependência de código: acordos são referenciados por id e acompanhados pelo evento `AgreementStatusChanged` (a parte 2 do negotiation é desenvolvida em paralelo).

### B.1 Agregados
| Agregado | Responsabilidade | Tabela |
| --- | --- | --- |
| `LegalEligibilityRule` | regras por credor (e carteira opcional): atraso mínimo, saldo mínimo, garantia, título executivo com pretensão executiva vigente, acordos quebrados, prescrição próxima (dias), régua extrajudicial esgotada. Critérios combinados por **E**; regras somam-se por **OU** | `eligibility_rule` |
| `CadenceExhaustion` | régua de campanha com finalidade `EXTRAJUDICIAL_COLLECTION` esgotada num caso de cobrança, conhecida pelo evento `CadenceExhausted` do campaign (inscrição, campanha, versão da régua, etapas executadas, mensagens enviadas, instante); uma linha por inscrição, append-only | `cadence_exhaustion` |
| `LegalMatter` | caso jurídico de um caso de cobrança (devedor × carteira): títulos, sinalizações, fase, fase guardada (acordo judicial), via principal, responsável (`NONE`/`INTERNAL`/`LAW_FIRM`), dívida, custas previstas, valor recuperável estimado, orçamento autorizado, despesas, valor recuperado, acordo vinculado, retenção no collection. Um único caso jurídico não final por caso de cobrança (índice único parcial) | `legal_matter` (+ `_title`, `_flag`, `matter_stage_history`) |
| `ViabilityAssessment` | análise append-only: fotografia da dívida, garantias e prescrição (jsonb), premissas, resultado do cálculo (§B.6), checklist documental, conclusão, vias recomendadas, pesquisa patrimonial e parecer (criptografados) | `viability_assessment`, `viability_checklist_item` |
| `CreditorAuthorization` | solicitação (vias, orçamento) → decisão do credor (concedida/negada, data, meio, representante, evidência obrigatória na concessão); concessão nova substitui a anterior (`SUPERSEDED`); uma solicitação pendente e uma concessão vigente por vez (índices parciais) | `creditor_authorization` |
| `ExtrajudicialMeasure` | `NOTICE`, `PROTEST`, `CREDIT_BUREAU_LISTING`, `COLLATERAL_ENFORCEMENT`: títulos, garantia, valor, cartório/birô, protocolo, datas, prazo, situação `PLANNED → SUBMITTED → EFFECTIVE → REMOVED` (ou `FAILED`/`CANCELLED`), provedor | `extrajudicial_measure` (+ `_title`) |
| `Lawsuit` | número CNJ validado (dígito verificador mod 97, segmento, ano = ano da distribuição; único na assessoria), tribunal `J.TR` derivado, órgão, via judicial, classe, polo do credor, polo contrário (devedor ou coobrigados vigentes), valor da causa, distribuição, situação, provedor, última captura | `lawsuit` (+ `lawsuit_party`) |
| `CaseMovement` | andamento append-only: data, natureza (`MovementType`), descrição criptografada, origem `MANUAL`/`PROVIDER` (idempotente por `provider` + `external_id`), valor, garantia | `case_movement` |
| `LegalDeadline` | prazo (natureza, início, fato inicial, dias, contagem, data fixa, tribunal, vencimento, responsável, situação `PENDING`/`MET`/`MISSED`/`CANCELLED`, último nível de alerta) | `legal_deadline` |
| `CourtSuspension` | suspensão forense cadastrável (período, tribunal `J.TR` ou todos, motivo) | `court_suspension` |
| `Hearing` | audiência: tipo, data/hora, forma, local ou link HTTPS, participantes internos (usuários) e externos (criptografado), resultado e observações (criptografadas) | `hearing` (+ `hearing_participant`) |
| `LegalExpense` | custa/despesa: tipo, valor, data, quem adiantou, reembolsável, comprovante, acima do orçamento, situação `RECORDED`/`REIMBURSED`/`CANCELLED` (colunas financeiras imutáveis por privilégio de coluna) | `legal_expense` |
| `LawFirm` / `LawFirmMember` | escritório parceiro (CNPJ único) e vínculo usuário × escritório (no máximo um ativo por usuário) | `law_firm`, `law_firm_member` |
| `LegalDocument` | documento/evidência no `FileStorage` (categoria `EVIDENCE`, tipo conferido pela assinatura do conteúdo — PDF/PNG/JPEG —, SHA-256), vínculo opcional a autorização, medida, processo, andamento, audiência ou despesa; append-only | `legal_document` |
| `AgreementRecord` | acordo do negotiation conhecido por evento: situação, se já foi quebrado, caso jurídico vinculado, judicial ou não | `agreement_record` |
| `LegalSettings` | parâmetros da assessoria (§B.7) | `legal_settings` |

### B.2 Fases e transições
`ELIGIBLE → UNDER_REVIEW → AWAITING_AUTHORIZATION → EXTRAJUDICIAL → FILED → IN_PROGRESS → ENFORCEMENT → SETTLED_IN_COURT → CLOSED`, além de `NOT_VIABLE` e `RETURNED_TO_CREDITOR`. Tabela validada em `LegalStage`; pré-condições em `LegalMatter#moveTo`:

| De | Para | Pré-condição / efeito |
| --- | --- | --- |
| `ELIGIBLE` | `UNDER_REVIEW` | aceite da indicação: `LEGAL_AUTHORIZE` |
| `UNDER_REVIEW` | `AWAITING_AUTHORIZATION` | análise de viabilidade registrada e favorável; abre a solicitação ao credor com as vias recomendadas e as custas previstas |
| `UNDER_REVIEW`, `AWAITING_AUTHORIZATION` | `NOT_VIABLE` | análise registrada; `NOT_VIABLE → UNDER_REVIEW` reabre |
| `AWAITING_AUTHORIZATION` | `EXTRAJUDICIAL` | via extrajudicial autorizada; automática ao registrar a primeira medida |
| `AWAITING_AUTHORIZATION`, `EXTRAJUDICIAL` | `FILED` | via judicial autorizada e processo cadastrado; automática ao cadastrar o processo |
| `FILED` | `IN_PROGRESS` | automática na citação (manual ou capturada) |
| `IN_PROGRESS` | `ENFORCEMENT` | manual (penhora, busca e apreensão…) |
| `FILED`, `IN_PROGRESS`, `ENFORCEMENT` | `SETTLED_IN_COURT` | acordo informado (manual ou `AgreementStatusChanged FORMALIZED`); guarda a fase; cria prazo para protocolar a homologação |
| `SETTLED_IN_COURT` | fase guardada | quebra ou cancelamento do acordo (evento) |
| não final | `CLOSED` | motivo (`PAID`, `AGREEMENT_FULFILLED`, `ASSET_RECOVERED`, `UNCOLLECTIBLE`, `PRESCRIBED`, `WITHDRAWN`, `CREDITOR_REQUEST`, `OTHER`); acordo quitado encerra com `AGREEMENT_FULFILLED` |
| antes do ajuizamento | `RETURNED_TO_CREDITOR` | motivo |

Toda transição grava a linha do tempo (`matter_stage_history`, append-only), auditoria `legal_matter.stage_changed`, evento de negócio `legal.stage_changed` e o evento de integração `LegalMatterStageChanged` (`shared.events.legal`). Encerramento: prazos pendentes cancelados (exceto baixa de restrição), `legal.matter_closed` e, com motivo de pagamento, prazo de baixa de cada protesto/negativação efetivo.

### B.3 Reações e integrações com outros módulos
- **collection** (porta pública já existente, sem operação nova): `CaseUseCases.sendToLegal/returnFromLegal` colocam/retiram a retenção LEGAL do caso de cobrança na mesma transação do caso jurídico (`CollectionHold`). Momento configurável (`collectionHoldStage`: `AUTHORIZATION` — padrão, a partir de EXTRAJUDICIAL/ajuizado — ou `REVIEW`); retirada em `CLOSED`, `RETURNED_TO_CREDITOR` e `NOT_VIABLE`. Caso de cobrança já encerrado não é alterado. A pausa de réguas e o desvio do assistente seguem o status LEGAL do collection e o evento `LegalMatterStageChanged`.
- **debt**: `PrescriptionInterruptionPort.register` (idempotente) no protesto lavrado (`PROTEST`, com o instrumento anexado à medida como evidência) e na citação (`CITATION`), para todos os títulos do caso jurídico; `CollateralUseCases.changeStatus` na execução extrajudicial (`UNDER_ENFORCEMENT` no envio; `CONSOLIDATED` para alienação fiduciária de imóvel ou `SEIZED` para veículo na efetivação; volta a `ACTIVE` se falhar/cancelar) e por andamento com `collateralStatus`. Consumo de `TitleStatusChanged` (todos os títulos fora de cobrança → sinalização `TITLES_SETTLED` e prazos de baixa) e de `TitlePrescriptionApproaching` (sinaliza o caso aberto ou avalia indicação pelas regras com critério de prescrição).
- **negotiation** (por evento, sem dependência): `AgreementStatusChanged` — `FORMALIZED` sobre caso ajuizado → `SETTLED_IN_COURT`; antes do ajuizamento, o acordo fica acompanhado; `SETTLED` → `CLOSED/AGREEMENT_FULFILLED`; `BROKEN`/`CANCELLED` → retomada; renegociação (`replacedAgreementId`) transfere o vínculo. Quebras alimentam o critério "acordos quebrados". A política de negociação para judicializados (custas e honorários na composição) é responsabilidade da parte 2 do negotiation, que pode consumir `LegalMatterStageChanged`.
- **campaign** (por evento, sem dependência): `CadenceExhausted` (contrato em `campanhas.md` §4.5) — somente `campaignPurpose = EXTRAJUDICIAL_COLLECTION` (demais finalidades são ignoradas, métrica `legal.events.consumed{type=cadence_exhausted_ignored}`). Consumidor idempotente `legal.cadence-exhausted` registra a régua esgotada do caso (também único por inscrição), audita `legal.cadence_exhausted` e, sem caso jurídico aberto, avalia as regras ativas do credor/carteira com o critério "régua extrajudicial esgotada" (origem `CADENCE_EXHAUSTED`, mesmas travas da indicação automática: status do caso, janela após encerramento). A varredura diária também considera o critério (o fato fica gravado).
- **compliance**: `HolidayUseCases.list` compõe o calendário forense.
- **settlement** (despesas reembolsáveis no demonstrativo): o legal publica `shared.events.legal.LegalExpenseChanged` no lançamento, cancelamento e reembolso de despesa (sem descrição livre); o settlement deduz do repasse as reembolsáveis adiantadas pela assessoria/escritório e mostra as pagas pelo credor ([prestacao-de-contas.md](prestacao-de-contas.md) §6). Pendente: consumir `SettlementClosed.reimbursedLegalExpenseIds` para marcar as despesas reembolsadas no demonstrativo.
- **payment** (`JUDICIAL_DEPOSIT`): pendente; os dados já existem (andamento `FUNDS_RELEASED`).

### B.4 Integrações (portas) e como plugar um adaptador real
Portas em `legal.application.port.out` (base `LegalProvider`: `code()`, `automatic()`): `CourtDataProvider` (DataJud), `PublicationsProvider`, `ProtestProvider`, `CreditBureauProvider`, `RealEstateRegistryProvider`, `VehicleLienProvider`. Adaptadores ativos: **manual** (`infrastructure/provider/manual`), escolhidos por `app.legal.providers.<porta>` (padrão `manual`). O adaptador manual não simula provedor: `automatic() = false`, e os casos de uso recusam a operação automática com `409 legal.provider_manual` (métrica `legal.providers.manual_requests`) — protocolo, situação e andamentos são lançados pela API.

Para plugar um adaptador real (após aprovação — issues #13 e #14):
1. Criar `infrastructure/provider/<provedor>/<Provedor><Porta>.java` implementando a porta, com `@ConditionalOnProperty(prefix = "app.legal.providers", name = "<porta>", havingValue = "<codigo>")`, `RestClient` com timeouts (3 s/10 s), `@Retryable` só para erros transitórios, métrica `integration.calls{provider,operation,outcome}` e credenciais por tenant criptografadas (convenções §8). Dados do devedor enviados ao provedor não vão para log.
2. Configurar `app.legal.providers.<porta>: <codigo>` no ambiente.
3. Nada muda nos casos de uso: `POST /lawsuits/{id}/sync` e a varredura diária passam a capturar andamentos (idempotentes por `external_id`, mesmos efeitos do lançamento manual); `POST /measures/{id}/dispatch` passa a enviar a medida (exige `DEBTOR_SENSITIVE_VIEW`; o documento revelado gera `SENSITIVE_DATA_VIEWED`). O teste `LegalProviderPluggingIT` mostra o encaixe com um provedor de dados processuais automático.

### B.5 Segurança
- Permissões: `LEGAL_VIEW`, `LEGAL_MANAGE`, `LEGAL_AUTHORIZE` (aceitar indicação e registrar a decisão do credor), `LEGAL_EXPENSE_MANAGE`. Perfis padrão: ADMIN (todas), GESTOR (todas), SUPERVISOR/AUDITOR (`LEGAL_VIEW`), FINANCEIRO (`LEGAL_VIEW`, `LEGAL_EXPENSE_MANAGE`), **JURIDICO** (novo: jurídico interno — legal completo, consulta da dívida, garantias e prescrição) e **ADVOGADO_PARCEIRO** (novo: somente `LEGAL_VIEW`, `LEGAL_MANAGE`, `LEGAL_EXPENSE_MANAGE`). Ambos exigem MFA.
- Escopo: credor do caso jurídico (404 na leitura, 403 nos comandos) **e**, para membro de escritório parceiro, somente casos atribuídos ao escritório ativo (404 + `SCOPE_DENIED` com `scope=law_firm`; listagens filtradas). Parâmetros, regras, escritórios e suspensões: só usuários internos; parâmetros, escritórios e suspensões exigem também todos os credores.
- Dados: parecer, pesquisa patrimonial, teor de andamentos, participantes externos e resultado de audiência criptografados; logs de negócio só com ids, enums, datas e valores; download de documento registra `FILE_DOWNLOADED`; vínculos e desativação de escritório registram `SCOPE_CHANGED`.
- Banco: RLS forçado em todas as tabelas (filhas pela linha pai); históricos, andamentos, análises e documentos append-only (privilégio + gatilho); despesas sem `DELETE` e só colunas de situação atualizáveis; demais registros sem `DELETE`.

### B.6 Viabilidade (cálculo)
`ViabilityCalculator` (puro): dívida cobrável = saldo dos títulos cuja pretensão de cobrança não prescreveu; garantia recuperável = Σ avaliações das garantias exequíveis (ativa, em execução, apreendida, consolidada) × taxa de realização (padrão 70%); outros bens = valor localizado na pesquisa patrimonial × taxa de realização; base = garantia + outros bens (ou a dívida cobrável sem patrimônio identificado); **valor recuperável estimado = mín(dívida cobrável, base) × probabilidade de êxito** (padrão 50%); benefício líquido = recuperável − custas previstas. Vias disponíveis: protesto, negativação, monitória e cobrança exigem dívida cobrável; execução exige título executivo com pretensão executiva vigente; busca e apreensão exige alienação fiduciária de veículo ativa; execução extrajudicial da garantia exige alienação fiduciária (imóvel ou veículo) ou hipoteca ativa. A conclusão favorável exige vias disponíveis, checklist sem item obrigatório faltante e, com custo × benefício negativo, parecer; a inviabilidade exige parecer.

### B.7 Parâmetros (`LegalSettings`, `PUT /api/v1/legal/settings`) e padrões
| Parâmetro | Padrão | Observação |
| --- | --- | --- |
| `conductModel` | `INTERNAL_ONLY` | **issue #14** — quem conduz (`INTERNAL_ONLY`, `LAW_FIRMS_ONLY`, `BOTH`) |
| `allowedExpenseAdvancers` | `CREDITOR` | **issue #14** — quem adianta custas (`CREDITOR`, `AGENCY`, `LAW_FIRM`) |
| `prescribedTitlePolicy` | `EXCLUDE` | **issue #14** — título com pretensão de cobrança prescrita não entra no jurídico e é sinalizado (`FLAG` o mantém, sem via disponível) |
| `budgetEnforcement` | `BLOCK` | despesa exige autorização vigente e respeita o orçamento aprovado (`WARN` aceita e sinaliza) |
| `collectionHoldStage` | `AUTHORIZATION` | ver §B.3 |
| `deadlineAlertLevels` | 5, 2, 0 | dias úteis restantes que disparam alerta |
| `yearEndRecess` | ligado | CPC art. 220 (20/12 a 20/01) nos prazos em dias úteis |
| `removalDeadlineBusinessDays` | 5 | baixa de protesto/negativação após pagamento (Súmula 548/STJ) |
| `homologationDeadlineBusinessDays` | 5 | protocolar acordo para homologação |
| `creditBureauNoticeDays` | 10 | antecedência mínima da comunicação prévia à negativação (CDC art. 43 §2º) |
| `prescriptionWarningDays` | 180 | sinalização de prescrição próxima |
| `referralCooldownDays` | 180 | sem nova indicação automática do mesmo caso após encerramento |

### B.8 Prazos
`DeadlineCalculator` (puro): dias úteis (exclui o início, inclui o vencimento; sem fins de semana, feriados do compliance, suspensões cadastradas do tribunal ou gerais e, se ligado, o recesso de fim de ano); dias corridos (prorroga o vencimento para o primeiro dia com expediente; recesso não suspende); data fixa (prorrogada da mesma forma). Fato inicial: intimação (conta do dia útil seguinte) ou disponibilização no DJe (publicação no dia útil seguinte; conta a partir do próximo — CPC art. 224 §§ 2º e 3º). Suspensão cadastrada/removida e a varredura diária recalculam os vencimentos pendentes (auditoria `legal_deadline.rescheduled`). A varredura diária marca prazos vencidos como `MISSED` e alerta uma vez por nível (`legal.deadline_approaching`/`legal.deadline_missed` e evento `LegalDeadlineApproaching` para notificação); cumprimento ocorrido até o vencimento pode ser registrado depois (corrige `MISSED`).

### B.9 API (`/api/v1/legal`)
| Recurso | Permissão |
| --- | --- |
| `GET/PUT /settings` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `GET/POST /eligibility-rules`, `GET/PUT /eligibility-rules/{id}`, `POST …/activate`, `…/deactivate`, `GET /eligibility/cases/{caseId}` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `POST/GET /matters`, `GET /matters/{id}`, `…/sheet`, `…/history`, `…/agreements`, `…/collaterals`, `…/hearings`, `GET /dashboard` | `LEGAL_MANAGE` (indicação) / `LEGAL_VIEW` |
| `GET /me` (vínculo do usuário com escritório parceiro: `external`, `lawFirmId`, `lawFirmName`, `firmActive`) | `LEGAL_VIEW` |
| `POST /matters/{id}/transitions`, `PUT /matters/{id}/responsible` | `LEGAL_MANAGE` (aceite da indicação também `LEGAL_AUTHORIZE`) |
| `POST /matters/{id}/viability/preview`, `POST/GET /matters/{id}/viability` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `GET/POST /matters/{id}/authorizations`, `POST /authorizations/{id}/decision` | `LEGAL_VIEW` / `LEGAL_MANAGE` / `LEGAL_AUTHORIZE` |
| `GET/POST /matters/{id}/measures`, `GET /measures/{id}`, `POST …/submission`, `…/status`, `…/dispatch` | `LEGAL_VIEW` / `LEGAL_MANAGE` (dispatch + `DEBTOR_SENSITIVE_VIEW`) |
| `GET/POST /matters/{id}/lawsuits`, `GET/PUT /lawsuits/{id}`, `POST …/status`, `GET/POST …/movements`, `POST …/sync`, `GET/POST …/hearings`, `PUT /hearings/{id}`, `POST …/result`, `…/cancel` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `GET/POST /matters/{id}/deadlines`, `GET /deadlines` (agenda, `mine=true`), `GET /deadlines/due-date`, `POST /deadlines/{id}/complete`, `…/cancel`, `GET/POST /court-suspensions`, `DELETE /court-suspensions/{id}` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `GET/POST /matters/{id}/expenses`, `POST /expenses/{id}/cancel`, `…/reimburse` | `LEGAL_VIEW` / `LEGAL_EXPENSE_MANAGE` |
| `GET/POST /law-firms`, `GET/PUT /law-firms/{id}`, `…/activate`, `…/deactivate`, `GET/POST …/members`, `POST …/members/{userId}/remove` | `LEGAL_VIEW` / `LEGAL_MANAGE` |
| `GET/POST /matters/{id}/documents` (multipart `document` + `file`), `GET /documents/{id}/content` | `LEGAL_VIEW` / `LEGAL_MANAGE` ou `LEGAL_EXPENSE_MANAGE` |

**Ficha do caso jurídico** (`GET /matters/{id}/sheet`): além de títulos, viabilidade, autorizações, medidas, processos, prazos, custas, documentos e linha do tempo, traz `collaterals` — garantias que cobrem os títulos do caso, lidas do debt pelo contrato de origem (`CollateralUseCases#listByContract`, mesmas regras da viabilidade), sem repetição, com o bem resumido (`assetSummary`), situação, valor estimado e os títulos do caso cobertos — e `hearings` — audiências de todos os processos do caso em ordem cronológica. Os mesmos dados ficam em `GET /matters/{id}/collaterals` e `GET /matters/{id}/hearings`, com o escopo do caso (credor e escritório).

**Vínculo com escritório** (`GET /me`): o vínculo usuário × escritório é do legal (`LawFirmMember`), não do identity — por isso o legal expõe a consulta (o identity não pode depender do legal pelo grafo). A interface usa este endpoint para a visão restrita, em vez de inferir pelo código do perfil `ADVOGADO_PARCEIRO`.

### B.10 Pendências
- Captura de publicações automática e envio automático de medidas: só após adaptadores aprovados.
- Depósito judicial como origem de pagamento (payment); marcar despesas reembolsadas a partir de `SettlementClosed` (settlement já deduz as despesas no demonstrativo).
- Representação do devedor por advogado (direcionamento do contato) depende do communication.
