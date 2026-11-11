# Desenho — conformidade da operação (vulnerabilidade, contestação do portal e reclamações)

Frente G da revisão de produto de novembro/2026 ([README](../../negocio/revisao-produto-2026-11/README.md) §3.2 I5, I6,
I14; §6 D10; §7 Onda 5). Três entregas:

1. **Marcador de vulnerabilidade e superendividamento** no devedor (compliance) — D10 adotada: entra na versão inicial.
2. **Contestação aberta pelo portal → contestação no collection** (portal + collection).
3. **Gestão de reclamações** (módulo novo `complaint`) — "logo após", nesta mesma rodada.

Base legal e de produto: CDC art. 4º I (vulnerabilidade), 39 IV (hipervulnerável), 42 (cobrança sem constrangimento),
54-A e 104-A (Lei 14.181/2021 — superendividamento, mínimo existencial e repactuação); LGPD art. 6º III
(minimização), 7º, 11 (dado sensível) e 18; Procon, consumidor.gov.br e Banco Central (RDR/ouvidoria).

## 1. Marcador de vulnerabilidade (compliance)

### 1.1 Modelo

`compliance.vulnerability_marker` (`VulnerabilityMarker`) — um por devedor e categoria ativa (índice único parcial):

| Campo | Regra |
| --- | --- |
| `category` | `SUPER_INDEBTED`, `ELDERLY`, `HEALTH` (dado sensível), `LOW_LITERACY`, `OTHER` |
| `legal_basis` | `LEGAL_OBLIGATION`, `REGULAR_EXERCISE_OF_RIGHTS`, `CONSENT`, `LEGITIMATE_INTEREST` — legítimo interesse **não** vale para `HEALTH` (art. 11; `check` no banco e regra no domínio) |
| `source` | `DEBTOR_DECLARATION`, `OPERATOR_OBSERVATION`, `CREDITOR`, `COURT`, `ASSISTANT_SUGGESTION` (só ao confirmar a sugestão) |
| `payment_capacity` | opcional e só para `SUPER_INDEBTED`: parcela mensal que o devedor pode comprometer preservando o mínimo existencial (declaração ou plano de repactuação). **Não é renda** e o sistema não calcula o mínimo existencial |
| `status` | `ACTIVE` → `RETIRED` (motivo em código: `SITUATION_ENDED`, `REGISTERED_BY_MISTAKE`); por `CONSENT_REVOKED` ou `DATA_SUBJECT_REQUEST` o marcador é **apagado** (auditoria `vulnerability.erased`, categoria mascarada) |

**Sem texto livre** em nenhum campo (decisão 1046). O marcador vale para todos os credores do devedor (condição da
pessoa). Na anonimização do devedor (`DebtorAnonymized`), marcadores e sugestões são apagados
(`compliance.vulnerability-anonymized`).

`compliance.vulnerability_suggestion` (`VulnerabilitySuggestion`): sugestão do assistente — devedor, credor da conversa
(escopo), origem `ASSISTANT` e referência opaca (`assistant_conversation` + id). Uma pendente por devedor; com marcador
ativo, a detecção é ignorada. Nunca vira marcador sozinha.

`compliance.vulnerability_settings` (`VulnerabilitySettings`, uma linha por assessoria; ausente = tudo desligado):
`offer_guard_enabled` e `automated_offers_blocked` (§1.4).

### 1.2 Permissões, escopo e minimização

| Permissão | Perfis padrão | O que libera |
| --- | --- | --- |
| `VULNERABILITY_REGISTER` | Operador (e todos que herdam), Compliance | registrar o marcador, ver o histórico (sem categoria), confirmar ou dispensar sugestões |
| `VULNERABILITY_MANAGE` | Supervisor, Gestor, Compliance, Administrador | o anterior, mais retirar, alterar a capacidade, listar todos os marcadores e configurar os efeitos (lista e configuração exigem acesso a todos os credores) |
| `VULNERABILITY_SENSITIVE_VIEW` | Gestor, Compliance, Administrador | ver a **categoria** do marcador (inclui saúde, dado sensível); exibir saúde registra `SENSITIVE_DATA_VIEWED` com os devedores exibidos |

- O selo (`GET /api/v1/compliance/vulnerability/debtors/{id}`) é de quem vê o devedor (`DEBTOR_VIEW`/`CASE_VIEW`). Sem
  `VULNERABILITY_SENSITIVE_VIEW`, devolve só "há atenção especial" e a capacidade de pagamento, **sem nenhuma categoria**
  (`categories` vazio; `MarkerView.category` e `sensitive` nulos); o filtro por categoria na lista também exige a
  permissão (decisão 1061 [revisar]). O supervisor retira e configura, mas não vê a categoria.
- Escopo: o devedor precisa estar visível ao ator (credores do ator, via `DebtorUseCases#get`); a lista de todos os
  marcadores e a configuração exigem acesso a todos os credores (403 com `SCOPE_DENIED`); sugestões só do credor no
  escopo, e sugestão sem credor de origem só para quem tem todos os credores.
- Auditoria (`vulnerability.registered`, `.retired`, `.erased`, `.capacity_changed`, `.suggested`,
  `.suggestion_confirmed`, `.suggestion_dismissed`, `.settings_changed`, `.anonymized`) com a categoria sensível
  **mascarada** (`SENSITIVE`) — a trilha é lida por mais perfis que o marcador. Eventos de negócio e métricas **sem
  devedor e sem categoria** ([logs-e-metricas.md](../logs-e-metricas.md) §3.1a).

### 1.3 Efeitos na operação

- **Selo** `app-vulnerability-badge` (`features/compliance/vulnerability/`) no cabeçalho da ficha do devedor e da ficha
  do caso na estação; abre o detalhe para registrar, confirmar a sugestão ou (quem gerencia) retirar.
- **Roteamento:** `VulnerabilityMarkerChanged` (`shared.events.compliance`, só `debtorId` e `vulnerable`) → o collection
  (`collection.vulnerability-marker`) **relê o estado no compliance sob o lock do devedor** (o booleano do evento não é
  a fonte: eventos podem chegar fora de ordem), guarda `collection_case.vulnerable` e refaz a fila dos casos ativos do
  devedor. No reprocessamento da carteira, o estado é consultado em lote (`VulnerabilityQueries#vulnerableAmong`).
  A fila ganha o critério **"Só devedores com marcador de vulnerabilidade"** (`queue.vulnerable_only`); com prioridade
  menor que as demais, os casos marcados caem nela (fila própria / operador sênior pela equipe da fila). Caso aberto
  depois do marcador já nasce com o indicador (`VulnerabilityQueries#isVulnerable`).
- **Sugestão do assistente:** na transferência por `VULNERABILITY` (`AssistantChannel`), o assistente chama
  `VulnerabilityUseCases#suggest` como sistema, em transação própria (falha não impede a transferência; conflito de
  índice ou regra conta `assistant.vulnerability_suggestions{outcome=rejected}`, falha inesperada `outcome=error` com log
  de erro). Aparece em
  "Minhas pendências" (`compliance.vulnerability_suggestions`) e na tela Compliance → Vulnerabilidade.

### 1.4 Salvaguarda de oferta (configurável, padrão desligado — decisão 1048 [revisar])

Aplicada no `NegotiationGuard` (validação única de todas as ofertas — tela, API, portal, assistente) pela porta
`VulnerabilityQueries#checkOffer`, só quando `offerSafeguardActive` (assessoria ligou e o devedor tem marcador ativo):

| Configuração | Regra | Violação (`POLICY`, nunca concedida) |
| --- | --- | --- |
| `offer_guard_enabled` | maior desembolso mensal da condição (entrada ou parcela; à vista = total) ≤ menor capacidade de pagamento declarada entre os marcadores ativos; sem capacidade declarada, não se aplica | `vulnerability.payment_capacity_exceeded` |
| `automated_offers_blocked` | ator `DEBTOR` (portal), `ASSISTANT` ou `INTEGRATION` (API pública) não negocia com devedor marcado (decisão 1062 [revisar]) | `vulnerability.automated_channel_blocked` |

O sistema não fixa percentual de renda nem valor de mínimo existencial (o Decreto 11.150/2022 e suas alterações, o
plano de repactuação e a política do credor definem); a assessoria registra a capacidade declarada.

## 2. Contestação aberta no portal → contestação no collection (I14)

- No portal, o pedido `DISPUTE` traz o motivo (`NOT_RECOGNIZED`, `ALREADY_PAID`, `WRONG_AMOUNT`, `OTHER`) e, se o
  devedor tiver mais de uma dívida no portal, a carteira. O pedido continua indo para a fila humana
  (`portal.service_request_dispatch`).
- Na mesma transação do pedido, uma linha `portal.service_request_dispute` (`PENDING`) e o job
  `portal.dispute_intake`, que chama **`DisputeIntakeUseCases#openFromPortal`** (interface pública do collection; o
  portal passa a depender de `collection :: usecases`, sem ciclo). Linha própria para o resultado: os dois jobs não
  concorrem pela linha do pedido.
- O collection abre, como sistema, uma contestação por caso ativo do devedor nas carteiras do pedido (a escolhida ou as
  habilitadas no portal), pela mesma abertura da manual (`DisputeService#openFor`): origem `DEBTOR`, bloqueante,
  títulos em cobrança do caso, prazos pela hierarquia de prazos **contados do instante do pedido no portal**
  (`opened_at` = criação do pedido), descrição com o protocolo do pedido — **o relato do devedor não é copiado**: fica
  no pedido do portal, aberto com a permissão e o log de acesso de lá (`PortalDisputeCommand.withNote` só avisa que há
  relato; decisão 1063), distribuição,
  auditoria (`meta.originReference`), `dispute.opened` com `trigger=portal` e `DisputeStatusChanged`.
- **Idempotente:** `collection.dispute.origin_reference = portal_request:<id>` com índice único por caso; repetir não
  duplica. Caso que já tem contestação em aberto não ganha outra (`ALREADY_OPEN`); sem caso ativo (`NO_CASE`) ou sem
  títulos em cobrança (`NO_TITLES`), o atendente trata pela fila humana.
- Resultado no pedido (`OPENED`, `ALREADY_OPEN`, `NO_CASE`, `NO_TITLES`), visível ao devedor ("Contestação
  registrada: a cobrança fica suspensa até a resposta") e na lista de pedidos do portal. Evento
  `portal.dispute_registered`; métricas `portal.dispute_intake{outcome}` e `collection.disputes.portal_intake{outcome}`.

## 3. Reclamações (módulo `complaint`)

### 3.1 Onde ficou e por quê

Módulo novo `br.com.gestorcobranca.complaint` (schema `complaint`, faixa de migrations **V2400–V2499**), dependências
`organization`, `identity`, `debt` e `collection` (`usecases`). Não ficou no compliance porque a reclamação se vincula
ao **caso** de cobrança e o compliance é dependência do collection (não pode depender dele de volta); nem no collection,
porque o ciclo (canal externo, protocolo, prazo do órgão, resposta ao órgão) é de ouvidoria/compliance, não da
operação, e o collection já é o maior módulo. A tela fica em Compliance → Reclamações (decisão 1050 [revisar]).

### 3.2 Modelo e ciclo

`complaint.complaint`: credor (obrigatório — escopo), devedor e caso (opcionais; o caso precisa ser do credor e do
devedor), canal (`PROCON`, `CONSUMIDOR_GOV`, `BACEN`, `CREDITOR_OMBUDSMAN`, `RECLAME_AQUI`, `OTHER`), protocolo
externo (único por canal), assunto (`ABUSIVE_COLLECTION`, `CONTACT_FREQUENCY`, `THIRD_PARTY_CONTACT`,
`DEBT_NOT_RECOGNIZED`, `WRONG_AMOUNT`, `AGREEMENT_OR_PAYMENT`, `CREDIT_BUREAU`, `DATA_PRIVACY`, `OTHER`), resumo
(criptografado), recebimento, prazo e origem do prazo, data de aviso ("perto do prazo"), responsável, resposta
(criptografada), quando e por quem, `answered_on_time`, resultado e encerramento.

Situações: `RECEIVED → IN_PROGRESS → ANSWERED → CLOSED` (responder também direto de `RECEIVED`; encerrar exige a
resposta). Resultados: `RESOLVED`, `NOT_ACCEPTED`, `UNFOUNDED`, `CLOSED_BY_CHANNEL`.

**Prazo** (`ComplaintDeadlines`): o informado pelo canal no registro prevalece; senão a regra do canal
(`complaint.complaint_deadline_rule`: dias corridos e aviso, por assessoria); senão o padrão de 10 dias corridos com
aviso de 3 (decisão 1051 [revisar] — não é prazo legal; cada órgão informa o seu). Mudança de prazo (prorrogação) exige
motivo e é auditada. "No prazo" = resposta até o fim do dia do prazo no fuso de negócio.

**Evidências** (`complaint.complaint_evidence`): PDF, PNG ou JPEG conferidos pelo conteúdo, até 10 MB e 20 por
reclamação; gravadas na quarentena e liberadas pelo job `complaint.evidence_scan` (mesmo pipeline dos documentos do
credor nas contestações: `MalwareScanner`, `MALWARE_DETECTED`, nunca liberada sem veredito; com a verificação desligada,
`NOT_SCANNED`).

### 3.3 Permissões e segurança

| Permissão | Perfis padrão | O que libera |
| --- | --- | --- |
| `COMPLAINT_REGISTER` | Operador (e herdeiros), Compliance | registrar, consultar a fila e o detalhe, corrigir vínculo e assunto, anexar evidências |
| `COMPLAINT_MANAGE` | Supervisor, Gestor, Compliance, Administrador | o anterior, mais assumir/atribuir, começar, responder, encerrar, mudar prazo, regras por canal (exige acesso a todos os credores) e indicador |

Escopo por credor em tudo (RLS por tenant + `AccessScope`); registrar para credor fora do escopo é 403; protocolo já
existente responde 409 com mensagem neutra (não revela a reclamação de outro credor). A fila não traz resumo nem
resposta (minimização); o detalhe traz e registra `SENSITIVE_DATA_VIEWED`. Auditoria `complaint.*`. O devedor
vinculado não pode ser desvinculado (`complaint.debtor_unlink_not_allowed`: sairia do alcance da anonimização). Na
anonimização do devedor, resumo, resposta e protocolo são substituídos e os **arquivos das evidências apagados**
(registro `REMOVED`, sem chave); ficam canal, assunto, datas e resultado para os indicadores.

### 3.4 Fila, pendências e indicador

- Fila `/compliance/reclamacoes` com prazo em destaque (vencida, perto do prazo), filtros por credor, situação, canal,
  responsável, prazo e protocolo.
- "Minhas pendências" (`ComplaintPendingWork`, para `COMPLAINT_MANAGE`): `complaint.mine_due` (do responsável) e
  `complaint.unassigned_due` (sem responsável), perto do prazo ou vencidas.
- Job `complaint.overdue` (a cada 30 min, ShedLock): aviso único de prazo vencido (`complaint.overdue`, WARN técnico,
  métrica para alarme).
- Indicador por credor (`GET /api/v1/complaints/indicators/by-creditor`, [indicadores.md](../../negocio/indicadores.md) §9).

### 3.5 API (`/api/v1/complaints`)

`GET /` (fila), `POST /` (registrar), `GET /{id}` (detalhe), `PUT /{id}` (vínculo e assunto), `PUT /{id}/due-date`,
`POST /{id}/assign`, `GET /{id}/assignee-options`, `POST /{id}/start`, `POST /{id}/answer`, `POST /{id}/close`,
`GET|POST /{id}/evidences`, `GET /{id}/evidences/{evidenceId}`, `GET /deadline-rules`, `PUT /deadline-rules/{canal}`,
`GET /indicators/by-creditor`.

## 4. Migrations

| Versão | Módulo | Conteúdo |
| --- | --- | --- |
| `V0503__compliance_vulnerability_marker` | compliance | marcador, sugestão e configuração (RLS, `check`) |
| `V0609__collection_vulnerability_and_portal_dispute` | collection | `collection_case.vulnerable`, `queue.vulnerable_only`, `dispute.origin_reference` (+ índice único por caso) |
| `V1304__portal_dispute_intake` | portal | `service_request.dispute_type` e `portal.service_request_dispute` |
| `V2400__complaint_tables` | complaint (faixa nova) | reclamações, evidências e regras de prazo por canal |
| `V0508__compliance_data_processor` | compliance | cadastro de operadores e suboperadores (§8.2) |
| `V0507__compliance_retention_policy_days` | compliance | retenção por assessoria: categorias do portal e do scoring, `retention_days` (§7) |

## 5. Pendências e fora do escopo

- **Retenção dos marcadores retirados e prova do consentimento** (decisão 1064 [revisar], para o responsável e o
  encarregado): por quanto tempo guardar o histórico de marcadores retirados por `SITUATION_ENDED`/
  `REGISTERED_BY_MISTAKE` (hoje ficam até a anonimização do devedor) e como provar o consentimento quando a base é
  `CONSENT` (vínculo com o registro de bases legais / `legal-bases`). Não implementado.
- **Retenção das reclamações sem devedor vinculado** (decisão 1065 [revisar]): não entram na anonimização de nenhum
  titular; prazo de guarda e descarte a definir com o encarregado (política de retenção nova `COMPLAINTS`).

- **Integração automática com consumidor.gov.br, Procon (ProConsumidor) ou RDR do Bacen:** provedor externo → issue
  sugerida, não código. Hoje o registro é manual, com protocolo e prazo do canal.
- **Registro da audiência de repactuação (CDC art. 104-A):** data, juízo/Procon, proposta e resultado ligados ao
  marcador de superendividamento — pendência documentada (pede desenho com o jurídico: o módulo `legal` tem
  audiências; avaliar reaproveitar).
- **Detecção de vulnerabilidade fora do assistente** (IA9: caixa de entrada, transcrições) — depende da decisão 371.
- **Reclamação aberta a partir do caso ou da ficha do devedor:** o diálogo `ComplaintFormDialog` aceita credor,
  devedor e caso pré-preenchidos; o botão nas fichas fica para a integração das frentes D/E.

## 6. Responsável por solicitação de titular (LGPD art. 18, compliance)

Regra única (`DataSubjectRequestAssignees.isEligible`), usada pela lista da tela e pela gravação: **usuário ativo, com
`DATA_SUBJECT_REQUEST_MANAGE` e acesso a todos os credores** — o mesmo alcance que os comandos da solicitação exigem de
quem atua (`loadForCommand` → `requireAllCreditors`), porque o atendimento alcança todos os dados do titular na
assessoria.

- Lista: `GET /api/v1/compliance/data-subject-requests/assignable-users?q=&ids=&page=&size=`
  (`DATA_SUBJECT_REQUEST_MANAGE` + todos os credores, sem `USER_VIEW`) devolve só id e nome dos elegíveis, ordenados
  por nome (busca sem acento e sem diferenciar maiúsculas), até 50 por página e 50 ids na resolução de nomes; cada
  consulta registra `USER_DIRECTORY_QUERIED` no log de acesso (`resource=data_subject_request_assignee`, `returned`),
  nunca o texto buscado.
- Gravação: `POST /{id}/assign` e `POST /{id}/start` com responsável recusam (`422`, `dsr.assignee_invalid`, "O
  responsável precisa estar ativo, atender solicitações de titulares e ter acesso a todos os credores"; "Responsável não
  encontrado" para id inexistente) — nada é gravado nem auditado.
- Tela (`request-detail`): o seletor "Atribuir responsável" usa a lista acima (`RequestAssignees`), não o cadastro de
  usuários; o botão aparece para quem tem acesso a todos os credores (antes exigia `USER_VIEW`). Os nomes de
  responsável e de quem confirmou a identidade vêm de `/users/display-names` (só id e nome).
- Dependência nova do módulo: `compliance → identity :: usecases` (`UserDirectory`), sem ciclo
  ([visão geral](../../arquitetura/visao-geral.md) §2.1).
- **Indicador de reclamações nos painéis** (`analytics`): hoje o indicador por credor fica na tela de reclamações; levar
  para o painel de confiabilidade exige mudar o contrato de leitura do analytics (frente F).
- **Selo na lista de casos e na fila do operador** (além da ficha): depende de expor `vulnerable` nas listagens do
  collection (frentes D/E).

## 7. Retenção de dados por assessoria (compliance)

A tela **Retenção de dados** (`/compliance/retencao`, `RETENTION_MANAGE`) é o lugar único de "quanto tempo guardamos
cada dado" da assessoria. Os prazos que eram fixos no `application.yml` (portal e scoring) passaram a ser categorias
dela, ajustáveis pelo encarregado (DPO) de cada assessoria, com auditoria; os valores do `application.yml` continuam
como **padrão do sistema** enquanto a assessoria não configura.

| Categoria | Dado | Unidade | Limites | Padrão do sistema | Ação | Aplicada por |
| --- | --- | --- | --- | --- | --- | --- |
| `DEBTOR_INACTIVE` | devedor sem títulos em cobrança | meses | 1–1200 | — | anonimizar | compliance (simular/executar) |
| `IMPORT_FILES` | arquivos de carga | meses | 1–1200 | — | excluir arquivo | ingestion |
| `MESSAGE_CONTENT` | conteúdo de mensagens (e turnos do assistente) | meses | 1–1200 | — | excluir conteúdo | communication, assistant |
| `CALL_RECORDINGS` | gravações de chamadas | meses | 1–1200 | — | excluir arquivo | telephony |
| `PORTAL_ACCESS_CHALLENGES` | pedidos de código/link vencidos (HMAC do documento, inclusive de não devedor) | dias | 1–365 | 30 (`app.portal.retention.challenges`) | excluir registro | portal |
| `PORTAL_ACCESS_THROTTLES` | contadores do bloqueio progressivo sem uso (sem bloqueio vigente) | dias | 7–365 | 30 (`throttles`) | excluir registro | portal |
| `PORTAL_SESSION_CLIENT_DATA` | IP e navegador das sessões encerradas | dias | 30–1825 | 365 (`session-client-data`) | apagar IP e navegador | portal |
| `SCORING_CASE_FACTS` | fatos dos casos usados nas features | dias | 730–3650 | 1095 (`app.scoring.fact-retention-days`) | excluir registro | scoring |
| `SCORING_FEATURE_SNAPSHOTS` | snapshots com desfecho apurado (treino e monitoramento) | dias | 365–3650 | 730 (`snapshot-retention-days`) | excluir registro | scoring |

- **Unidade e limites** são da categoria (`RetentionCategory`), conferidos no domínio (`retention.amount_invalid`) e no
  formulário. Mínimos com motivo técnico: contadores do portal 7 dias (menos zera a escalada do bloqueio de quem
  insiste), IP e navegador 30 dias (apuração de incidentes), fatos do score 730 dias (janela de fatos das features,
  `fact-lookback-days`), snapshots 365 dias (janela de treino padrão). O módulo dono declara o mínimo que a configuração
  dele exige (`RetentionDefault.requiredMinimum`; o score informa o `fact-lookback-days` para os fatos) e a aplicação
  não sobe se ele passar do mínimo da categoria (decisão 1129).
- **Padrão do sistema**: o módulo dono informa o prazo pela porta `compliance :: spi` `RetentionDefaults`
  (`PortalRetentionDefaults`, `ScoringRetentionDefaults`); `RetentionDefaultRegistry` confere na subida que toda
  categoria com padrão tem exatamente um, dentro dos limites (senão a aplicação não sobe). As categorias antigas não têm
  padrão: sem política habilitada, nada é descartado (comportamento mantido).
- **Descarte obrigatório** nas categorias com padrão: a política só ajusta o prazo; desabilitar é recusado
  (`retention.discard_mandatory`, também `check` no banco). Lado seguro para a minimização (LGPD art. 6º III): antes o
  descarte sempre ocorria e continua sempre ocorrendo.
- **Leitura pelos módulos donos**: `RetentionUseCases#retentionFor(category)` (no tenant do job) devolve
  `AppliedRetention` (prazo, unidade, origem `TENANT`|`SYSTEM_DEFAULT`, `cutoff(now)` em dias ou meses de calendário no
  fuso de operação); vazio = nada a descartar. Substituiu o `policyFor` (todos os chamadores migrados: communication,
  assistant, telephony, ingestion). O portal e o scoring passam a depender de `compliance :: usecases` e
  `compliance :: spi` (sem ciclo; [visão geral](../../arquitetura/visao-geral.md) §2.1). Os jobs registram no log
  técnico o prazo e a origem de cada categoria.
- **API**: `GET /api/v1/compliance/retention/policies` traz todas as categorias com `unit`, `retentionAmount` (da
  assessoria, nulo se não configurada), `defaultAmount`, `minAmount`, `maxAmount`, `enabled` (sempre `true` nas com
  padrão) e `configured`; `PUT /policies/{category}` recebe `{retentionAmount, enabled}` (antes `retentionMonths`);
  `DELETE /policies/{category}` ("Usar o padrão", `RETENTION_MANAGE` e todos os credores, como o `PUT`) apaga a
  política da assessoria e a categoria volta ao padrão do sistema — só nas categorias com padrão
  (`retention.no_system_default`, 422, nas outras); sem política, nada muda (decisão 1125).
- **Auditoria** (`retention_policy.created|updated`): categoria, `retentionUnit`, `retentionAmount` (antes/depois),
  `enabled` e, nas categorias com padrão, `effectiveRetentionAmount` (o prazo efetivo de antes — o padrão, na primeira
  configuração — e o de depois), com `systemDefaultAmount` nos metadados. `retention_policy.reset_to_default`: prazo
  apagado (`retentionAmount` antes), `effectiveRetentionAmount` (o da assessoria → o padrão) e `source`
  (`TENANT` → `SYSTEM_DEFAULT`), com categoria, unidade e padrão nos metadados.
- **Banco** (`V0507__compliance_retention_policy_days`): coluna `retention_days` ao lado de `retention_months`; cada
  política usa só a coluna da unidade da categoria (`check ck_retention_policy_period`, com `is not null` explícito na
  coluna da unidade: sem ele, a lógica de três valores do SQL deixaria passar prazo nulo). A mesma migration concede
  `delete` em `retention_policy` ao `app_runtime` (a V0500 revogara; "Usar o padrão" apaga a política). Deploy gradual: as instâncias
  antigas continuam gravando `retention_months` nas categorias antigas (a coluna só deixou de ser obrigatória); durante
  a troca, a listagem numa instância antiga falharia se alguém já tivesse configurado uma categoria nova (enum
  desconhecido) — janela curta, sem efeito nos jobs, que leem só a própria categoria.
- **Testes**: `RetentionPolicyTest` e `RetentionDefaultRegistryTest` (unidade, padrão, limites, corte em dias e meses,
  mínimo exigido pelo módulo dono), `ScoringRetentionDefaultsTest` (lookback como mínimo), `ComplianceModuleIT`
  (listagem com padrão, `check` com prazo nulo, "Usar o padrão" com escopo e auditoria), `retention.spec` (o diálogo
  chama o `DELETE` e não grava o número do padrão), `PortalRetentionIT` (prazo da assessoria × padrão, descarte obrigatório,
  auditoria) e `ScoringRetentionIT` (prazo por assessoria × padrão, isolamento por RLS).

## 8. Resposta sugerida ao titular e cadastro de operadores (LGPD art. 18, I e VII; art. 37 e 39) — 11/11/2026

Pedido do responsável: nas solicitações de **confirmação de tratamento** (art. 18, I) e de **informação sobre
compartilhamento** (art. 18, VII), o sistema reúne o que sabe e monta um **rascunho de resposta**, que o analista confere,
edita e só então usa (revisão humana; nada é enviado sozinho). Decisões 1100–1111.

### 8.1 Fontes de compartilhamento sem acoplar módulos (`shared.privacy`)

O compliance não pode depender de communication, telephony, legal, payment, assistant ou publicapi (eles dependem do
compliance pelo porteiro de contato — haveria ciclo). Mesmo padrão de `shared.pendingwork`: o contrato
`DataSharingSource` é linguagem publicada do kernel, implementado pelo módulo dono e recebido pelo compliance por
injeção de `List<DataSharingSource>` (`DataSharingCollector`). O grafo de módulos não muda.

| Tipo | Papel |
| --- | --- |
| `DataSharingSource` | `id`, `label`, `sharedWith(DataSharingQuery)` (fatos do devedor) e `recipientsInUse()` (prestadores configurados na assessoria) |
| `DataSharingFact` | tipo do destinatário (`CREDITOR`, `PROVIDER`, `LAW_FIRM`, `INTEGRATION`), **chave estável** (`<modulo>.<nome>` ou `<modulo>.<tipo>:<uuid>`, `check` no registro), id, nome, finalidade (`DataSharingPurpose`), primeiro e último uso, quantidade e quantidade por extenso, link da evidência |
| `DataSharingLink` | rota do frontend, filtros (só ids/códigos — validados), permissões da rota (`anyPermission` e `allPermissions`, como o `permissionGuard`) e rótulo |
| `DataSharingUsage` / `DataSharingRows` | linha agregada lida pelos adaptadores JDBC (colunas `code`, `recipient_id`, `name`, `first_at`, `last_at`, `total`, `latest_id`) |

Cada fonte segue o padrão das pendências: `application/service/<Modulo>DataSharing` + porta
`application/port/out/<Modulo>DataSharingQueries` + adaptador `infrastructure/persistence/Jdbc<Modulo>DataSharingQueries`
(consulta pelo índice do devedor). O coletor roda **cada fonte em transação somente leitura própria**
(`TenantExecution.callAsReadOnly`, com o ator da requisição: RLS do tenant); a fonte que falha vai para `unavailable`
(log técnico WARN `Fonte de compartilhamento indisponível`, `source`, `error`, `rootCause` — classe da causa mais
específica — e `sqlState`, nunca a mensagem, que pode trazer parâmetros da consulta) e o rascunho segue com as demais,
com alerta. O rascunho e a lista de provedores sem cadastro **não seguram conexão** enquanto as fontes rodam
(`propagation = NOT_SUPPORTED`; a leitura da solicitação e a montagem com a trilha em transações curtas próprias).

| Fonte | O que registra (por devedor) | Chave | Evidência (link e permissão) |
| --- | --- | --- | --- |
| `debt` | credores com títulos do devedor, atuais e anteriores (cessões em `title_ownership`); contagem de títulos | `debt.creditor:<id>` | ficha do credor (`ORGANIZATION_AREA_VIEW` + `CREDITOR_VIEW`) |
| `communication` | mensagens das conversas do devedor que passaram pelo provedor — recebidas (`INBOUND`) ou enviadas a ele (`provider_message_id` ou `sent_at`); a que ficou na fila ou falhou antes do envio não conta —, agrupadas pelo **provedor da conta de canal** (`WHATSAPP_CLOUD`, `SMTP`, `SANDBOX`) | `communication.<provedor>` | conversa mais recente daquele provedor (`INBOX_VIEW`) |
| `telephony` | chamadas que chegaram à plataforma (discagem iniciada, id do provedor ou atendida), por provedor (`TWILIO`, `PLIVO`, `ALVARIA`, `TARIFANDO`, `SANDBOX`); a recusada pelo porteiro não conta | `telephony.<provedor>` | `/telefonia/chamadas?devedor=` (`TELEPHONY_SUPERVISE` + `DEBTOR_VIEW`) |
| `payment` | cobranças Pix que chegaram ao PSP — registradas (`registered_at`), pagas ou recusadas por ele (`FAILED`, exceto `PIX_ACCOUNT_INACTIVE`, que encerra sem chamar o PSP); cancelada ou vencida antes do registro não conta —, por **conta Pix** (o PSP é identificado pela conta) | `payment.pix_account:<id>` | `/pagamentos/instrucoes?devedor=` (`PAYMENT_VIEW`) |
| `assistant` | turnos do assistente com chamada ao modelo (`model_calls > 0`), por provedor de IA (`bedrock`, `sandbox`) | `assistant.<provedor>` | turno mais recente (`ASSISTANT_REVIEW`) |
| `legal` | casos jurídicos com **escritório parceiro** responsável (`responsible_type = LAW_FIRM`) | `legal.law_firm:<id>` | caso jurídico mais recente (`LEGAL_VIEW`) |
| `publicapi` | tokens de integração cujos lotes de importação trouxeram títulos do devedor | `publicapi.token:<id>` | token (`PUBLIC_API_TOKEN_VIEW`) |

Prestadores em uso (`recipientsInUse`): contas de canal ativas, plataformas de telefonia ativas, contas Pix ativas e o
provedor de IA configurado na instância (`LanguageModelPort`) **só se a assessoria tiver política do assistente
publicada e ligada** (`status = PUBLISHED`, `mode <> OFF`); escritórios, credores e integrações não entram no cadastro
de operadores.

**O que não foi possível (registrado):** histórico de troca de escritório do caso jurídico (só o escritório atual é
guardado; a troca fica na trilha de auditoria) e as medidas extrajudiciais/provedores do jurídico (hoje todos manuais,
texto livre); consultas da API pública por devedor (o log de acesso guarda o padrão da rota, não o id — só a carga por
lote é rastreável, e um título regravado guarda só o último lote); boleto e link de pagamento (sem prestador registrado);
o SMTP é o servidor de envio da plataforma (sem conta por empresa) — o nome da empresa vem do cadastro de operadores.

### 8.2 Cadastro de operadores e suboperadores (compliance)

Tabela `compliance.data_processor` (`V0508`, RLS, `check` de papel, garantia, país ISO, chave e período; única por
assessoria e chave): chave do provedor (casa com a das fontes; manual = `custom.<nome>`), empresa, papel (`OPERATOR`,
`SUB_OPERATOR`, `JOINT_CONTROLLER`), finalidade, categorias de dados (texto curto), país (ISO alfa-2) e — fora do Brasil
— a **garantia do art. 33** (obrigatória no domínio e no banco), referência do contrato/DPA (texto curto, sem anexos),
vigência e ativo. A chave não muda (outro provedor é outro cadastro).

- API `/api/v1/compliance/data-processors`: `GET /` (lista), `GET /unregistered` (prestadores em uso sem cadastro),
  `GET /{id}`, `POST /` (`data_processor.duplicate` 409 para chave repetida), `PUT /{id}`. Permissão
  `DATA_SUBJECT_REQUEST_MANAGE` (a mesma das bases legais e das solicitações); **alterar e `GET /unregistered` exigem
  todos os credores** (o cadastro é da assessoria; a lista de provedores em uso traz contas de canal, telefonia e Pix de
  todos os credores — com escopo parcial, 403 sem vazamento). A tela só busca e mostra a seção para quem pode cadastrar. Auditoria `data_processor.registered` (estado inicial) e `data_processor.updated` (antes e
  depois de cada campo alterado).
- Sem semente: o sistema não cria cadastros sozinhos; a tela mostra os **provedores em uso sem cadastro** para cadastrar
  com a chave, o nome e a finalidade pré-preenchidos (decisão 1103).
- **Operador manual** (`custom.<nome>`, ex.: hospedagem, suporte): nenhuma fonte registra o uso dele, mas trata dados de
  todos os titulares; ativo e vigente, entra no rascunho de todo titular localizado (§8.3, decisão 1122).

### 8.3 Rascunho de resposta

`GET /api/v1/compliance/data-subject-requests/{id}/response-draft` (`DataSubjectResponseDraftUseCases`):

- **Regras**: só `CONFIRMATION` e `INFORMATION_SHARING` (`dsr.draft_unavailable`, 422); identidade confirmada
  (`dsr.identity_not_verified`, 409); solicitação aberta (`dsr.draft_closed`, 409); `DATA_SUBJECT_REQUEST_MANAGE` e
  **todos os credores** (403 sem vazamento), o mesmo alcance de atuar no pedido.
- **Montagem**: fatos das fontes agregados por chave (ex.: duas contas do mesmo provedor viram um destinatário), base
  legal **vigente** de cada credor (`LegalBasisRecord`), cadastro do operador de cada prestador e, com titular
  localizado, os **operadores cadastrados à mão** ativos e vigentes (`custom.*`, como prestadores, sem período nem
  contagem, com a finalidade do cadastro na tabela e no texto — sem eles a frase de completude seria falsa); o texto vem do
  `ResponseDraftComposer` (domínio, sem Spring): linguagem simples, sem códigos internos, enums ou jargão; nome,
  finalidade e país do cadastro do operador quando houver; "Não compartilhamos com outras empresas" só quando todas as
  fontes responderam. Cada parágrafo traz as chaves dos destinatários de onde veio (explicabilidade). Titular não
  localizado (sem devedor ou anonimizado): não há tratamento/compartilhamento a informar.
- **Alertas**: `PROCESSOR_UNREGISTERED`, `PROCESSOR_NOT_CURRENT` (inativo ou fora da vigência),
  `CREDITOR_WITHOUT_LEGAL_BASIS`, `SOURCE_UNAVAILABLE` (perigo) e `TEXT_TOO_LONG` (acima dos 4.000 caracteres da resposta).
- **Só sob pedido**: a tela não monta o rascunho ao abrir a solicitação; a pessoa clica em "Montar resposta sugerida"
  (e "Montar de novo"), porque cada montagem lê dado pessoal e entra na trilha (decisão 1127).
- **Rastreabilidade**: cada geração registra `SENSITIVE_DATA_VIEWED` (`resource=debtor`, `resourceId`,
  `view=data_subject_response_draft`, `requestId`, `requestType`, `recipients`) e a auditoria
  `data_subject_request.response_draft_generated` (`debtorId`, `recipients`, códigos dos alertas, fontes indisponíveis,
  `textSha256`). A resposta devolve `textSha256`; a conclusão (`POST /{id}/complete`, campo opcional `draftSha256`)
  registra na auditoria `fromDraft`, `draftSha256` e `draftEdited` (o texto concluído difere do sugerido) e no log de
  negócio `data_subject_request.completed` os atributos `fromDraft` e `draftEdited`.

### 8.4 Telas e navegação

- **Solicitação** (`request-detail`): cartão "Resposta sugerida" (`ResponseDraftCard`) nos dois tipos, para quem tem
  todos os credores: estado inicial com "Montar resposta sugerida" (nada é consultado ao abrir), carregando, erro
  ("Tentar novamente") e "confirme a identidade"; alertas com atalhos
  (`app-config-link`, nova aba: "Cadastrar operador" → `/compliance/operadores?chave=`, "Registrar base legal" →
  `/compliance/bases-legais?credor=`; "Montar de novo" para fonte indisponível); tabela agrupada (credores, prestadores,
  escritórios, integrações) com finalidade, período, registros, base legal ou cadastro do operador e os links da
  evidência **só quando a pessoa abre a tela de destino**; atalho para a configuração do provedor (contas de canal,
  plataformas de telefonia, contas Pix, políticas do assistente); prévia do texto (somente leitura) com "Vem de:" em
  cada parágrafo. "Usar como resposta" (só em atendimento; antes, a orientação do próximo passo) abre a conclusão com o
  texto editável; o diálogo envia o resumo do rascunho.
- **Operadores e suboperadores** (`/compliance/operadores`, menu Compliance, `DATA_SUBJECT_REQUEST_MANAGE`): provedores
  em uso sem cadastro no topo (só para quem tem todos os credores: "Cadastrar" e atalho para a configuração do provedor,
  com as permissões das constantes das rotas de destino), lista com país, garantia,
  contrato e situação; formulário com país (mais comuns + "Outro país") e garantia só fora do Brasil; `?chave=` abre o
  cadastro (edição se existe; senão, novo com o que se sabe). Sem todos os credores, consulta somente (botões ocultos).
- **Fluidez**: a fila de solicitações aceita `?devedor=` (só o id; aviso "Solicitações do titular" com link ao devedor e
  "Ver todas"; sem todos os credores, a fila do titular ainda abre — o servidor confere o devedor visível); a solicitação
  leva a "todas as solicitações do titular"; a ficha do devedor mostra as solicitações em "Relacionados" (kinds
  `dataSubjectRequest`/`dataSubjectRequestList`, `DATA_SUBJECT_REQUEST_MANAGE`); a lista de chamadas ganhou o filtro
  `?devedor=` (com `DEBTOR_VIEW`, como o filtro por operador).

### 8.5 Testes

`ResponseDraftComposerTest` (inclusive operador manual), `DataProcessorTest`, `DataSharingFactTest`,
`DataSharingCollectorTest` (SQLState), `AssistantDataSharingTest` (provedor de IA só com política publicada) (unidade);
`DataSubjectResponseDraftIT` (PostgreSQL: credores, mensagens, chamadas, Pix e escritório reais; mensagem na fila e
cobrança cancelada antes do registro não contam; agregação por provedor; operador manual ativo e vigente no texto,
inativo e vencido fora; isolamento de outro devedor e de outra assessoria; cadastro do operador no texto; prestadores
sem cadastro, 403 com escopo parcial; 403 sem permissão e com escopo parcial sem vazamento; log de acesso; auditoria do
uso). Front: `response-draft-card.spec` (nada consultado ao abrir), `processor-list.spec` (sem chamada sem todos os
credores), `data-sharing.spec` (permissões dos atalhos = rotas), `request-list.spec` (filtro do titular).
