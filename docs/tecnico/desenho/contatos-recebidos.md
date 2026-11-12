# Desenho — contato novo recebido na carga (selo "Novo" e trabalho diferenciado)

Pedido do responsável (11/2026): um telefone, e-mail ou outro canal que chega numa **nova carga** para um devedor que
**já existia** é informação relevante. O sistema deve destacá-lo dos contatos antigos e permitir um trabalho
diferenciado com ele. Módulos: `debt` (dono do contato), `ingestion` (informa a carga), `collection` (fila),
`campaign` (segmento), `telephony` (ordem do discador) e frontend. A inteligência por contato (taxa de atendimento,
melhor horário) está em [inteligencia-de-contato.md](inteligencia-de-contato.md) e usa os mesmos dados.

## 1. Conceitos

| Termo | Definição |
| --- | --- |
| **Contato recebido depois do cadastro** | Contato criado por uma carga (`source = IMPORT`) para um devedor que **já existia antes dessa carga**. Os contatos da carga que criou o devedor nunca são "novos": tudo é novo para ele. |
| **Janela de novidade** | Período em que o contato aparece como novo: até `novelty_until` = recebimento + prazo da assessoria (padrão **30 dias**, de 1 a 180, configurável). |
| **Uso** | Primeira tentativa por esse contato: ligação discada, mensagem enviada ou interação registrada com esse `contactId`. O uso encerra a novidade ("Novo" vira "Novo · atendeu" ou "Novo · sem resposta" até o fim da janela, para o operador saber que o número já foi testado). |
| **Reenvio** | O credor manda de novo um contato que o devedor já tem. Não cria nada, mas atualiza `last_received_at`: o credor confirma que o dado continua atual. |

O selo vale para todo tipo de contato (telefone, e-mail, endereço) e para o WhatsApp do telefone.

## 2. Dados (`debt`, migration `V0309__debt_contact_receipt.sql`)

Colunas novas em `debt.debtor_contact` (sem dado pessoal novo):

| Coluna | Uso |
| --- | --- |
| `first_import_batch_id uuid` | carga que criou o contato (nulo fora de carga) |
| `last_import_batch_id uuid`, `last_received_at timestamptz` | última carga que enviou o contato (criação ou reenvio) |
| `received_after_registration boolean not null default false` | contato chegou para devedor já existente |
| `novelty_until timestamptz` | fim da janela de novidade (nulo = nunca foi novo) |
| `first_attempt_at timestamptz`, `first_effective_at timestamptz` | primeira tentativa e primeiro contato efetivo por ele (de qualquer origem) |

Índice parcial `(tenant_id, debtor_id) where novelty_until is not null` para a varredura. Os contatos já existentes
ficam com `received_after_registration = false` (nenhum é marcado como novo retroativamente).

Prazo por assessoria: `debt.contact_novelty_settings` (uma linha por assessoria — `id` PK e `unique (tenant_id)`, como
as demais configurações do módulo, ver §9 —, `window_days` com
`check (window_days between 1 and 180)`, RLS por `shared.enable_tenant_rls`, auditoria), com padrão do
sistema `app.debt.contact-novelty.window-days = 30`. Tela em **Configurações › Contatos recebidos** (permissão
`DEBTOR_MANAGE`, a mesma do cadastro de contatos).

## 3. Fluxo

```
ingestion (RowWriter) ── DebtorRecord(+ importBatchId) ──► debt.upsertDebtor
     devedor existia antes da carga?  ─ não ─► contatos criados sem novidade
              │ sim
     contato novo ─► received_after_registration, novelty_until = agora + janela
     contato já existente ─► last_received_at / last_import_batch_id (reenvio)
              │
     ao fim do devedor com contato novo: DebtorContactNoveltyChanged(debtorId, newContactUntil, tipos)
              ▼
 collection (case.new_contact_until)   campaign (audience_case.new_contact_until)
```

* "Existia antes da carga": o devedor não foi criado por esta mesma carga — compara `debtor.created_import_batch_id`
  (coluna nova, preenchida na criação por carga) com o lote atual; devedor criado à mão também conta como existente.
  Reprocessar a mesma linha não muda nada (idempotência da porta).
* **Uso** (consumidor `debt.contact-usage`): `InteractionRecorded` (OUTBOUND ou efetivo), `CallCompleted` e
  `MessageStatusChanged` (`SENT`/`DELIVERED`/`READ` para tentativa; `RECEIVED` como resposta) com `contactId` (campo
  novo dos três eventos) gravam `first_attempt_at`/`first_effective_at` uma única vez (`handleOnce`). Como só a
  primeira vez conta, a duplicidade entre a interação espelho (`source` `TELEPHONY`/`COMMUNICATION`) e o evento próprio
  não altera o resultado. O consumidor ignora `contactId` que não pertence ao `debtorId` do evento
  (`debt.contacts.usage_ignored{reason=contact_mismatch}`). Ligação
  `ANSWERED`, mensagem recebida e interação com contato efetivo contam como efetivo.
* **Fim da novidade** do devedor = maior `novelty_until` entre os contatos ainda sem tentativa. A cada tentativa por um
  contato novo e na varredura diária (`debt.contact_novelty_sweep`, 03:40, ShedLock, percorrendo as assessorias com
  o tenant definido em cada rodada) o debt recalcula e publica
  `DebtorContactNoveltyChanged` quando o valor muda (nulo = nenhum contato novo pendente).

### Evento `DebtorContactNoveltyChanged` (`shared.events.debt`)

`eventId`, `tenantId`, `occurredAt`, `debtorId`, `newContactUntil` (nulo encerra), `channels` (tipos com contato novo
pendente: `PHONE`, `EMAIL`, `ADDRESS`, e `WHATSAPP` quando o telefone tem WhatsApp), `importBatchId` (nulo na
varredura). Sem valor de contato.

## 4. Uso operacional

| Onde | Comportamento |
| --- | --- |
| **Ficha do devedor e painel do caso** | Selo "Novo" (ícone e texto, não só cor) ao lado do contato, com dica "Recebido na carga de 10/11/2026; ainda sem tentativa". Depois do uso, "Novo · atendeu"/"Novo · sem resposta" até o fim da janela. Contatos reenviados mostram na dica "Confirmado pelo credor em …". |
| **Filas** (`collection`) | Critério `new_contact_only` ("só devedores com contato novo"), como o critério de vulnerabilidade. Coluna `collection.collection_case.new_contact_until` (`V0610`), atualizada pelo evento; o roteamento é reavaliado quando o valor muda. |
| **Segmentos de campanha** | Critério `hasNewContact` (sim/não), em SQL e em memória (`SegmentMatcher`), coluna `campaign.audience_case.new_contact_until` (`V1103`). |
| **Discador** | Ao montar a lista de telefones do devedor, os números novos ainda sem tentativa vêm primeiro (depois a ordem atual por prioridade). A ordem nunca ultrapassa a política de contato: o porteiro continua decidindo cada discagem. |
| **Lista de devedores** | Filtro "Com contato novo" e contador no relatório da carga ("312 devedores receberam contato novo"), com link para a lista filtrada. |

## 5. Logs, métricas e eventos de negócio

* Evento de negócio `contact.received_new` (por devedor, na carga): `debtorId`, `importBatchId`, quantidade por tipo —
  sem valor. `contact.first_attempt` com `received_after_registration` e `effective` (base da comparação novo × antigo
  em [inteligencia-de-contato.md](inteligencia-de-contato.md)).
* Métricas: `debt.contacts.received{kind=new|resent}`, `debt.contacts.novelty_sweep{outcome}`.
* Auditoria: `debtor_contact.created` ganha `receivedAfterRegistration`; alteração do prazo → `contact_novelty_settings.updated`.

## 6. Segurança e LGPD

O selo e os critérios não expõem o valor (só tipo e datas). Os eventos não levam contato nem documento. A origem
`IMPORT` continua sendo a base legal registrada do contato (credor controlador). Contato marcado como inválido perde o
selo.

## 7. Testes

Domínio (`DebtorContactTest`: janela, uso, reenvio), `ContactReceiptIT` (carga 1 cria devedor sem novidade; carga 2
traz telefone novo → evento, critério de fila e segmento; ligação pelo número encerra a novidade; reenvio atualiza
`last_received_at`), `SegmentMatcherTest` e testes do frontend (selo, filtros).

## 8. Decisões (numeradas em `decisoes-autonomas.md` na entrega)

* Janela padrão de 30 dias e encerramento no primeiro uso (alternativas: até o primeiro contato efetivo; prazo fixo).
* Contatos da carga que criou o devedor nunca são novos.
* Uso por qualquer canal encerra a novidade só daquele contato.
* Discador prioriza número novo sem tentativa (ordem, não regra de contato).

## 9. Implementação (11/2026) e diferenças em relação ao desenho

* **Migrations**: `V0309__debt_contact_receipt.sql` (colunas do contato e do devedor, `contact_novelty_settings`),
  `V0610__collection_case_new_contact_and_person_type.sql` (caso e fila; tipo de pessoa dos casos existentes preenchido
  a partir do cadastro do devedor, percorrendo as assessorias) e `V1103__campaign_audience_new_contact_and_person_type.sql`.
* **Índices**: `ix_debtor_contact_novelty (tenant_id, debtor_id)` para o recálculo da novidade do devedor e
  `ix_debtor_contact_novelty_due (tenant_id, novelty_until, debtor_id)`, parcial em `novelty_until is not null and
  first_attempt_at is null`, para a varredura diária (janelas sem tentativa vencidas no intervalo, com cursor por
  devedor). O caso não tem índice em `new_contact_until`: nenhuma consulta filtra por ele (o critério da fila é avaliado
  sobre o caso já carregado).
* **Recálculo serializado por devedor**: quem recalcula a novidade (uso de contato novo, invalidação ou revalidação de
  contato novo e a varredura, devedor a devedor) bloqueia antes a linha do devedor
  (`ContactNoveltyTracker#lockDebtor` → `DebtorRepository#findByIdForUpdate`, `select … for update`) e só depois lê o
  estado "antes" e toma o instante do recálculo. Sem o bloqueio, usos concorrentes de contatos diferentes do mesmo
  devedor liam o mesmo "antes" e o último a confirmar publicava um valor sem o efeito do outro — e o campaign grava o
  valor do evento. O uso de contato que não é novo não bloqueia (não muda a novidade).
* **Configuração em tabela com `id`** (não `tenant_id` como PK): segue `AmountAdjustmentSettings` (`TenantScopedEntity`
  com versão e auditoria de criação/alteração) e a mesma garantia de uma linha por assessoria (`unique (tenant_id)`).
* **Fim da novidade no consumidor**: o collection e o campaign comparam `new_contact_until` com o instante da avaliação
  (fila e segmento) — a janela vencida deixa de atender na hora, e a varredura diária só refaz o roteamento e republica
  o valor. A varredura procura janelas vencidas nos últimos `app.debt.contact-novelty.sweep-lookback` (padrão 7 dias),
  para cobrir execuções perdidas; um devedor pode ser republicado mais de uma vez (consumidores idempotentes).
* **Ordem dos eventos**: o collection relê no debt (`DebtorSegmentationQueries`, sob o lock do devedor) o estado atual,
  como no marcador de vulnerabilidade; o campaign grava o valor do evento em todos os casos do devedor e descarta entrega
  mais antiga que a aplicada (`new_contact_changed_at`). Na inclusão do caso na projeção, o valor vem do caso do
  collection; depois, só o evento altera.
* **Uso do contato**: ligação de saída conta como tentativa (efetiva se `ANSWERED`); ligação recebida só conta quando
  atendida. Mensagem `SENT`/`DELIVERED`/`READ` é tentativa e `RECEIVED` é tentativa e contato efetivo. A primeira
  tentativa de **qualquer** contato gera `contact.first_attempt` (comparação novo × antigo).
* **Contato inválido**: perde o selo e sai do fim da novidade do devedor; invalidar ou revalidar um contato novo recalcula e
  publica o evento.
* **Reenvio de contato manual**: contato cadastrado à mão que chega numa carga também ganha a data de confirmação pelo
  credor (sem virar novo). Reprocessar uma carga (lote novo com o mesmo arquivo) conta como reenvio.
* **Discador**: `DebtorContactUseCases.ChannelContact` ganhou `newPending`; o `DialerTickJob` escolhe o telefone com
  `nextPhone(telefones, último discado)`: primeiro o número novo sem tentativa (na ordem de prioridade, sem repetir o
  último discado); senão, o seguinte ao último discado na ordem de prioridade. O último discado é o contato da última
  chamada do item (`DialerEntry.lastCallId` → `Call.contactId`); sem ele, ou fora da lista, o primeiro da ordem. O
  rodízio é estável: antes, `tentativas % quantidade` sobre a lista reordenada pulava o número de maior prioridade quando
  a novidade acabava (ex.: [P0, P1] + N: N na 1ª tentativa e P1 na 2ª). A ligação manual (clique para ligar) e o envio
  de mensagens seguem a ordem de prioridade.
* **APIs**: `GET/PUT /api/v1/debtors/contact-novelty-settings` (`DEBTOR_MANAGE`), `GET /api/v1/debtors?newContact=true`
  e `?newContactBatchId=`, `GET /api/v1/ingestion/batches/{id}/contact-receipt` (`IMPORT_VIEW`); `receipt` nos contatos da
  ficha do devedor, do resumo e da ficha do caso; `newContactOnly`/`personType` na fila; `hasNewContact`/`personType` no
  segmento. Tela nova em **Organização › Contatos recebidos** (`/devedores/contatos-recebidos`), logo depois de
  Configurações no menu.

