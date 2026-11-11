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

Prazo por assessoria: `debt.contact_novelty_settings` (`tenant_id` PK, `window_days` com
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
