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

**Sem texto livre** em nenhum campo (decisão G2). O marcador vale para todos os credores do devedor (condição da
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
  permissão (decisão G17 [revisar]). O supervisor retira e configura, mas não vê a categoria.
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

### 1.4 Salvaguarda de oferta (configurável, padrão desligado — decisão G4 [revisar])

Aplicada no `NegotiationGuard` (validação única de todas as ofertas — tela, API, portal, assistente) pela porta
`VulnerabilityQueries#checkOffer`, só quando `offerSafeguardActive` (assessoria ligou e o devedor tem marcador ativo):

| Configuração | Regra | Violação (`POLICY`, nunca concedida) |
| --- | --- | --- |
| `offer_guard_enabled` | maior desembolso mensal da condição (entrada ou parcela; à vista = total) ≤ menor capacidade de pagamento declarada entre os marcadores ativos; sem capacidade declarada, não se aplica | `vulnerability.payment_capacity_exceeded` |
| `automated_offers_blocked` | ator `DEBTOR` (portal), `ASSISTANT` ou `INTEGRATION` (API pública) não negocia com devedor marcado (decisão G18 [revisar]) | `vulnerability.automated_channel_blocked` |

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
  relato; decisão G19), distribuição,
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
operação, e o collection já é o maior módulo. A tela fica em Compliance → Reclamações (decisão G6 [revisar]).

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
aviso de 3 (decisão G7 [revisar] — não é prazo legal; cada órgão informa o seu). Mudança de prazo (prorrogação) exige
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

## 5. Pendências e fora do escopo

- **Retenção dos marcadores retirados e prova do consentimento** (decisão G20 [revisar], para o responsável e o
  encarregado): por quanto tempo guardar o histórico de marcadores retirados por `SITUATION_ENDED`/
  `REGISTERED_BY_MISTAKE` (hoje ficam até a anonimização do devedor) e como provar o consentimento quando a base é
  `CONSENT` (vínculo com o registro de bases legais / `legal-bases`). Não implementado.
- **Retenção das reclamações sem devedor vinculado** (decisão G21 [revisar]): não entram na anonimização de nenhum
  titular; prazo de guarda e descarte a definir com o encarregado (política de retenção nova `COMPLAINTS`).

- **Integração automática com consumidor.gov.br, Procon (ProConsumidor) ou RDR do Bacen:** provedor externo → issue
  sugerida, não código. Hoje o registro é manual, com protocolo e prazo do canal.
- **Registro da audiência de repactuação (CDC art. 104-A):** data, juízo/Procon, proposta e resultado ligados ao
  marcador de superendividamento — pendência documentada (pede desenho com o jurídico: o módulo `legal` tem
  audiências; avaliar reaproveitar).
- **Detecção de vulnerabilidade fora do assistente** (IA9: caixa de entrada, transcrições) — depende da decisão 371.
- **Reclamação aberta a partir do caso ou da ficha do devedor:** o diálogo `ComplaintFormDialog` aceita credor,
  devedor e caso pré-preenchidos; o botão nas fichas fica para a integração das frentes D/E.
- **Indicador de reclamações nos painéis** (`analytics`): hoje o indicador por credor fica na tela de reclamações; levar
  para o painel de confiabilidade exige mudar o contrato de leitura do analytics (frente F).
- **Selo na lista de casos e na fila do operador** (além da ficha): depende de expor `vulnerable` nas listagens do
  collection (frentes D/E).
