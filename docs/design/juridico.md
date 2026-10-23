# Desenho — garantias e workflow jurídico

Especificação de produto: [`documentação/workflow-juridico.md`](../../documentação/workflow-juridico.md).

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
| `status` | `ACTIVE`, `UNDER_ENFORCEMENT`, `SEIZED`, `CONSOLIDATED`, `AUCTIONED`, `RELEASED`, `WRITTEN_OFF` com transições validadas e auditadas |
| `CollateralAppraisal` | histórico (append-only): valor (`Money`), data, fonte (`CREDITOR`, `APPRAISER`, `FIPE`, `MARKET`, `AUCTION`), observação |

### A.2 `ObligationParty` (partes da obrigação)
Por contrato: `debtorId` (o mesmo cadastro de devedor, consolidado por documento — RB01), `role` (`PRINCIPAL`, `GUARANTOR_AVAL`, `GUARANTOR_SURETY`, `CO_DEBTOR`, `THIRD_PARTY_COLLATERAL_PROVIDER`), `sinceDate`. O devedor principal do título continua em `DebtTitle.debtorId`; coobrigados passam a ser visíveis na ficha e elegíveis à cobrança conforme política do credor.

### A.3 Natureza do título e prescrição
- `OriginContract.instrumentType`: `CONTRACT`, `CCB`, `PROMISSORY_NOTE`, `DUPLICATE`, `CHECK`, `DEBT_CONFESSION`, `OTHER`; `executiveTitle` (boolean).
- `TitlePrescription` (1:1 com o título, recalculável): `baseDate` (vencimento por padrão), `termYears` (definido por regra configurável por natureza do título — tabela `prescription_rule` por tenant, sem prazos fixos em código), `estimatedDate`, histórico de `PrescriptionInterruption` (tipo: `PROTEST`, `CITATION`, `ACKNOWLEDGEMENT`, `OTHER`; data; referência; evidência). Uma interrupção reinicia a contagem conforme a regra.
- Job diário que sinaliza títulos com prescrição próxima (janela configurável) via evento de integração `TitlePrescriptionApproaching` e business event.

### A.4 Importação
O layout canônico do `ingestion` ganha campos opcionais de garantia, avaliação, coobrigados (documento, nome, papel) e natureza do título.

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
