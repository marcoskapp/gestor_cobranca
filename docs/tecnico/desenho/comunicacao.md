# Desenho — módulo `communication` (canais, conversas, inbox)

Escopo: M05, F12, RB07. Schema `communication`, migrations V1000–V1099 (`V1000` tabelas, `V1001` tabulações de
mensagem no collection para as assessorias existentes, `V1004` categoria do template no provedor).
Dependências: `shared`, `organization :: usecases`, `debt :: usecases`, `compliance :: usecases`,
`collection :: usecases`. Os demais fatos chegam por eventos de integração (`shared.events.*`).

## 1. Canais, contas e adaptadores

`ChannelAccount` por assessoria: canal, provedor, credor (nulo = conta padrão da assessoria), remetente
(`senderAddress`: e-mail ou id do número do WhatsApp; nome; resposta), credenciais do provedor (JSON criptografado
com `FieldEncryptor`, nunca devolvido pela API — a view só lista as chaves configuradas) e `routingKey` aleatório
(256 bits) do webhook. Uma conta **ativa** por (canal, credor) — índice único parcial.

**Remetente por credor:** o envio usa a conta ativa do credor no canal; sem ela, a conta padrão da assessoria
(`ChannelResolver`). Sem nenhuma → `409 channel.not_configured`.

| Canal | Provedor (`ChannelGateway`) | Observações |
| --- | --- | --- |
| WhatsApp | `WHATSAPP_CLOUD` — Cloud API (Meta), `RestClient` com timeouts (3 s/10 s), credenciais `accessToken`, `appSecret`, `verifyToken` | template aprovado para iniciar contato; janela de 24 h após a última mensagem do devedor; webhook com `X-Hub-Signature-256`; template de autenticação enviado com `body` + `button` (`sub_type: url`, `index: "0"`, parâmetro = código) |
| E-mail | `SMTP` — Spring Mail da plataforma | remetente/resposta da conta; sem notificação de entrega (fica `SENT`); bounces dependem do SES (issue #9) |
| SMS | somente a porta (`ChannelGateway`); provedor na issue #12 | conta SMS só com o sandbox |
| todos | `SANDBOX` — simulador local/teste (`app.communication.sandbox.enabled`, recusado no perfil `prod` por `SandboxGuard`) | aceita e agenda entrega/leitura simuladas; `[sandbox:reject]` e `[sandbox:transient]` no texto exercitam falhas; `POST /sandbox/inbound` simula resposta |

Integrações: métrica `integration.calls{provider,operation,outcome}` e `integration.calls.duration`. 429, 5xx e
falha de rede → `TransientSendException` (o job tenta de novo; não há retentativa dentro da transação do envio);
demais 4xx → recusa definitiva com o código do provedor (`WHATSAPP_<código>`).

## 2. Templates

`MessageTemplate`: versão `templateVersion` de um código por credor (nulo = assessoria), canal, finalidade
(`COLLECTION`, `REMINDER`, `NOTIFICATION`, `AGREEMENT`, `PAYMENT`, `SERVICE`), assunto (só e-mail), corpo com
`{{variável}}`, variáveis tipadas (JSON), idioma e nome no provedor. Ciclo `DRAFT → PUBLISHED → RETIRED`: rascunho é
editável; publicar retira a versão publicada anterior do código (uma publicada por credor e código); alterar uma
publicada = criar a próxima versão. O envio usa a versão publicada do credor e, sem ela, a da assessoria.

**Variáveis tipadas** (`TemplateRenderer`, domínio puro): `FIRST_NAME` (uma palavra), `MONEY` (`R$ 1.234,56`),
`DATE` (`dd/MM/aaaa`), `INTEGER`, `URL` (só `https`), `CODE` (linha digitável, protocolo), `TEXT` (curto). Não há
tipo para documento, telefone, e-mail ou endereço; valores `TEXT`/`URL` com CPF/CNPJ (formatado ou 11/14 dígitos),
e-mail ou telefone são recusados (`template.variable_personal_data`), `CODE` recusa documento formatado e e-mail;
nenhum valor tem quebra de linha. Toda variável usada é declarada e toda declarada é usada. Parâmetros posicionais do
WhatsApp seguem a ordem das variáveis declaradas.

**Categoria no provedor** (`providerCategory`, só WhatsApp; nula nos demais canais — `check` no banco): `UTILITY`
(padrão; também a dos templates existentes, `V1004`), `MARKETING` ou `AUTHENTICATION`. O template de **autenticação**
(código de acesso do portal) segue o formato da Meta: corpo fixo e **uma única variável, o código** (`CODE`,
obrigatória, até 15 caracteres — `ProviderTemplateRules`); a validade é configuração do template na Meta, não
variável. No envio (`SendMessageJob` → `ProviderTemplate.authentication`), o adaptador manda o código no componente
`body` e no botão "copiar código" (`type: button`, `sub_type: url`, `index: "0"` em texto — formato da documentação da
Meta para *copy code*). Mudar a categoria de um rascunho volta a aprovação para `PENDING`, como qualquer edição. Quem
envia pode consultar o template que o envio usaria (`TemplateUseCases#findEffective`: o do credor e, sem ele, o da
assessoria) para montar só as variáveis declaradas — o portal envia `minutos` apenas quando o template de código o
declara (SMS/e-mail).

**Aprovação do WhatsApp:** rascunho de WhatsApp nasce `PENDING`; `POST /templates/{id}/approval` registra a decisão
do provedor (`PENDING` submetido, `APPROVED` com nome no provedor, `REJECTED` com motivo), referência, data e quem
registrou; editar o rascunho volta para `PENDING`; publicar exige `APPROVED` (também por `check` no banco).

## 3. Conversa e dono do atendimento

`Conversation` (conta, contato, credor; devedor, carteira e caso quando identificados) com contato normalizado
criptografado (envio), blind index (`contact_hash`, localização das respostas) e máscara. Uma conversa aberta por
(conta, contato, credor).

| Dono | Significado |
| --- | --- |
| `ASSISTANT` | o assistente responde (conforme a política do assistente) |
| `QUEUE` | aguardando um humano (fila por equipe/perfil exigido) |
| `USER` | atendida por um operador |
| `NONE` | sem atendimento ativo (ex.: apenas notificações) |

**Alinhamento com o collection (sem duplicar o dono do caso).** Em conversa com caso ativo, toda mudança de dono é
um comando do caso (`CaseWorkUseCases`: `takeOver`, `release`, `assign`, `handToAssistant`), que valida operador,
escopo e quem pode mudar e grava o histórico de atribuição com vigência; o dono resultante do caso é aplicado à
conversa e às demais conversas abertas do caso. Mudanças feitas diretamente no caso (próximo caso, distribuição,
`/cases/{id}/assistant`) chegam por `CaseAssignmentChanged` e na chegada de cada mensagem (`syncFromCase`). Caso sem
dono (`NONE`) com conversa aguardando humano → fila. Sem caso (contato não identificado, caso encerrado), o dono é o
da própria conversa. As leituras de caso que precedem um comando são feitas em transação própria (`CaseReader`), para
que o comando do collection carregue o caso depois do lock do devedor (sem versão antiga em cache).

Regras:
- Mensagem recebida em conversa `ASSISTANT` fica para o assistente (consumo de `MessageStatusChanged`/consulta da
  conversa); em `QUEUE`/`USER`, para a caixa de entrada; conversa `NONE` passa a `QUEUE`.
- Transferência para humano (`handoffToHumans` pelo assistente, sistema ou supervisão, ou o evento `HandoffRequested`)
  exige **motivo** e **resumo** e indica o **perfil exigido** (ex.: `SUPERVISOR`), filtro da fila na caixa de
  entrada; o caso é devolvido à fila no collection (o assistente pode devolver casos que conduz — `release`).
- Operador assume (`take-over`) conversa da fila, sem dono ou do assistente; conversa de outro operador só com
  `CASE_ASSIGN` (`409 conversation.assigned_to_other`). Atribuir a operador (`INBOX_MANAGE`) exige caso.
- Devolver à fila ou ao assistente: o dono, ou quem tem `CASE_ASSIGN`.
- Toda mudança grava `ConversationHandoff` (append-only: de, para, natureza, motivo, resumo criptografado, perfil,
  ator, referência), auditoria, `conversation.handoff` (`assistant.handoff` quando o assistente transfere) e
  `communication.handoffs`. Rascunhos pendentes expiram quando a conversa deixa de estar com um operador.
- Contato desconhecido: conversa sem devedor (credor = o da conta, se dedicada), na fila; enviar exige identificar
  antes (`POST /conversations/{id}/identify`: o contato precisa constar do cadastro do devedor).

### 3.1 Contrato `HandoffRequested` (`shared.events.communication`)

Para módulos que não podem depender de `communication` (ex.: `negotiation` quando `validate` devolve
`REQUIRES_APPROVAL` para o assistente). Campos: `caseId`, `debtorId`, `creditorId`, `conversationId` (nulo = todas as
conversas abertas do caso), `reasonCode`, `requiredProfile`, `summary` (**sem dados pessoais**, até 2000; guardado
criptografado), `referenceType`/`referenceId` (ex.: `proposal`), `requestedByType`/`requestedById`. Consumidor
`communication.handoff-requested`: transferência com natureza `ESCALATION`; sem conversa aberta, o caso conduzido
pelo assistente volta à fila. O módulo `assistant` (que depende de `communication`) usa
`ConversationUseCases#handoffToHumans` diretamente.

### 3.2 Assistente (modos — `assistente.md` §4)

- `AUTONOMOUS*`: o ator `ASSISTANT` envia por `MessageUseCases#send` somente em conversa com dono `ASSISTANT`
  (`409 conversation.not_with_assistant`), sempre pelo porteiro; autor `ASSISTANT`, id = política.
- `SHADOW`/`COPILOT`: `ConversationUseCases#proposeDraft` cria rascunho/sugestão (criptografado) em conversa com
  humano; o operador envia (com edição registrada) ou descarta; nada sai sem humano.

## 4. Mensagens

`Message`: direção, canal, conta, autor (`USER`, `ASSISTANT`, `CAMPAIGN`, `SYSTEM`, `DEBTOR`) e id, template e
variáveis (criptografadas), assunto e conteúdo criptografados, status (`QUEUED, SENT, DELIVERED, READ, FAILED,
RECEIVED`), id do provedor (único por canal — idempotência dos webhooks), tentativa do compliance, tentativas de
envio, falha (código e detalhe), execução de campanha, rascunho de origem e chave de idempotência do cliente.

**Envio (`MessageUseCases#send`)** — destino: conversa, ou devedor + credor + canal (contato de maior prioridade;
WhatsApp exige contato marcado como WhatsApp); caso: o informado ou o único ativo do devedor no credor.
1. Autor pelo ator: operador (assume a conversa se pode trabalhá-la), assistente (só se conduz), sistema/campanha.
2. Conteúdo: template publicado (variáveis validadas) ou texto livre — só WhatsApp, só operador/assistente, só dentro
   da janela de 24 h (`409 message.whatsapp_window_closed`); e-mail e SMS sempre por template.
3. Verificação prévia `ContactGate#evaluate`: negado → mensagem `FAILED/CONTACT_DENIED` (motivos em
   `failureDetail`), `message.blocked`, sem job.
4. `QUEUED`, espera da conversa encerrada (SLA `communication.inbox.first_response`), job `communication.send`.

**Job `communication.send`** (`JobQueue`, retentativas com backoff, DEAD): mensagem não `QUEUED` → nada; conta
inativa/sem adaptador → `FAILED/ACCOUNT_UNAVAILABLE`; janela fechada para texto → `FAILED/WHATSAPP_WINDOW_CLOSED`;
**`ContactGate#authorizeAndRecord` imediatamente antes do envio** (negado → `FAILED/CONTACT_DENIED`); provedor:
aceite → `SENT`; recusa → `FAILED/<código>`; transitória → exceção (transação desfeita, inclusive a tentativa no
compliance; job reagendado). Na última tentativa (`app.communication.send-max-attempts`, padrão 6) a mensagem é
marcada `FAILED/RETRIES_EXHAUSTED` em transação própria e o job vai para `DEAD`. Entrega "ao menos uma vez": aceite do
provedor seguido de falha no commit pode reenviar (a Cloud API não tem chave de idempotência no envio).

**Webhooks e status:** `DELIVERED`/`READ`/`FAILED` do provedor nunca regridem (fora de ordem é absorvido); status de
mensagem desconhecida é ignorado (métrica).

**Mensagem recebida:** idempotente pelo id do provedor; conversa aberta do contato (a de saída mais recente) ou nova
(devedor das conversas anteriores do contato ou não identificado); dono alinhado ao caso; janela de 24 h e espera por
resposta abertas. **Opt-out por palavra-chave** (`SAIR`, `PARAR`, `STOP`, `CANCELAR`, "não quero mais receber"… —
mensagem igual à expressão, sem acentos/pontuação) → `ContactRestrictionUseCases#registerOptOutFromChannel` na mesma
transação (restrição `OPT_OUT` do contato no canal, origem `PROVIDER_STOP_KEYWORD`).

**Interações no caso:** o consumo do próprio `MessageStatusChanged` (`communication.message-interaction`) registra no
collection, após o commit, `SENT` (tabulação `MESSAGE_SENT`, `OUTBOUND`) e `RECEIVED` (`MESSAGE_RECEIVED`, `INBOUND`)
com origem `COMMUNICATION` e `externalRef = message:<id>` (reentrega não duplica). Falha ali não desfaz nem repete o
envio. Sem caso, caso encerrado, mais de 7 dias ou tabulação desativada → não registra (métrica).

**Retenção:** job diário (`communication.retention`, ShedLock) descarta conteúdo, assunto e variáveis das mensagens
mais antigas que a política `MESSAGE_CONTENT` do compliance (quando habilitada), mantendo os metadados; recibos de
webhook fora da janela de replay são apagados.

## 5. Webhooks (`/api/v1/webhooks/whatsapp/{routingKey}`)

`PublicEndpoint` (sem JWT). Ordem de proteção:
1. limite de taxa por (rota, IP) por instância (`requests-per-minute`, 429) e tamanho do corpo (`max-body-bytes`,
   256 KiB, 413) — antes de ler o banco;
2. rota em `communication.webhook_route` (tabela **global, sem RLS**, só chave aleatória e ids) → tenant e conta;
   desconhecida/inativa → 404;
3. no tenant: `X-Hub-Signature-256` = HMAC-SHA256 do corpo **bruto** com o `appSecret` da conta, comparação em tempo
   constante, **antes de interpretar o conteúdo** → 401;
4. recibo do corpo (`webhook_receipt`, SHA-256 único por conta): reentrega idêntica → 200 sem efeito;
5. eventos com instante fora da janela (`max-event-age` 7 dias, `max-clock-skew` 5 min) → descartados (replay);
6. processamento idempotente por id do provedor; corpo ilegível → 400.

Toda recusa registra `WEBHOOK_REJECTED` no log de acesso (provedor, motivo, IP). Handshake `GET` devolve o
`hub.challenge` (somente `[A-Za-z0-9_-]`) apenas com `hub.mode=subscribe` e o `hub.verify_token` da conta.

## 6. Caixa de entrada (F12) e API REST (`/api/v1/communication`)

Lista por dono (`owner=QUEUE,USER…`), operador (`assignee=me|id`), equipe, perfil exigido, credor, carteira, devedor,
caso, canal, situação e "aguardando resposta" (ordenado pela espera mais antiga — SLA de primeira resposta, com
`waitingSeconds` e fim da janela do WhatsApp na view); a ficha do devedor vem do collection (`/cases/{id}/sheet`).

* **Contagem por aba** (`GET /conversations/counts?channel=&awaitingReply=`): minhas, fila (inclui sem dono),
  assistente, todas e `breached` (estouraram a meta) numa única consulta agregada (`JdbcInboxCounter`, `count(*)
  filter`, sob RLS e escopo de credores) — antes eram quatro listagens por ciclo.
* **Linha do tempo do devedor** (`GET /debtors/{debtorId}/messages?creditorId=&page=&size=`): mensagens de todas as
  conversas e canais do devedor, mais recentes primeiro, só das conversas nos credores do escopo (credor fora do escopo →
  página vazia). Substitui a montagem no cliente (duas listagens + uma consulta por conversa).
* **Nome do devedor** na caixa de entrada: `GET /api/v1/debtors/names` (debt), em lote, com `INBOX_VIEW`.

### 6.1 SLA de primeira resposta

Meta por assessoria e canal (`communication.response_target`): aviso e limite em minutos corridos desde a primeira
mensagem do devedor sem resposta (`awaitingSince`). Padrões, enquanto a assessoria não grava os seus: WhatsApp e SMS
15/60 min, e-mail 4/24 h (`ResponseTargets.DEFAULTS`). `GET /response-targets` (`INBOX_VIEW`) devolve os vigentes;
`PUT /response-targets/{channel}` (`INBOX_MANAGE` + todos os credores, auditado `response_target.updated`) altera.

* A view da conversa traz `slaStatus` (`OK`, `WARNING`, `BREACHED`), `slaWarningAt` e `slaBreachAt`; a tela não calcula
  metas.
* Primeira resposta (operador ou assistente): além do timer `communication.inbox.first_response`, o contador
  `communication.inbox.first_response.sla{channel,responder,outcome}` classifica pela meta vigente.
* Varredura a cada minuto (`InboxSlaScanner`, ShedLock `communication.sla_scan`): cada conversa aberta que cruza o limite
  sem resposta gera **uma vez** o evento de negócio `conversation.sla_breached` e a métrica
  `communication.inbox.sla.breaches{channel,owner}` (alarme `inbox-sla-breaches`). O cursor por canal
  (`communication.sla_scan_cursor`) fica no banco e avança na mesma transação: execução atrasada ou pulada retoma de onde
  parou (até 24 h); a primeira execução numa assessoria conta só o que estourar dali em diante. Mudança de meta vale a
  partir da varredura seguinte.

### 6.2 Tempo real (ADR-0008)

`GET /inbox/stream` (`INBOX_VIEW`, `text/event-stream`): eventos `ready`, `conversation` (`conversationId`), `resync`,
`expired`, `unauthorized`, `reconnect` e comentários `ping` (20 s). Gatilhos `tg_conversation_notify` e
`tg_message_notify` (`V1003`) publicam `pg_notify('communication_inbox', {t, c, k})` — só ids — entregue na confirmação da
transação a todas as instâncias; cada uma escuta numa conexão dedicada (`PostgresInboxListener`) e repassa às conexões
locais filtrando assessoria e credores (`InboxChangeHub`). A conexão dura até 5 min (nunca além do token), com token e
sessão revalidados a cada minuto; limites de 5 conexões por usuário e 2.000 por instância (503). O cliente
(`InboxStream`, `fetch` com `Authorization`) recarrega lista, contagens e a conversa aberta a cada aviso e mantém a
consulta periódica espaçada como rede de segurança. Parâmetros em `app.communication.realtime.*`.

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/channels` · `/channels/{id}` · `/{id}/credentials` · `/{id}/activate|deactivate` | `GET`, `POST`, `PUT` | `CHANNEL_MANAGE` |
| `/templates` · `/templates/{id}` · `/{id}/preview` | `GET`, `POST` | `TEMPLATE_MANAGE` ou `MESSAGE_SEND` |
| `/templates` · `/{id}` · `/{id}/approval|publish|retire` | `POST`, `PUT` | `TEMPLATE_MANAGE` |
| `/messages` (cabeçalho `Idempotency-Key`) · `/messages/{id}` | `POST` · `GET` | `MESSAGE_SEND` · `INBOX_VIEW` |
| `/conversations` · `/conversations/counts` · `/{id}` · `/{id}/messages` · `/{id}/handoffs` | `GET` | `INBOX_VIEW` |
| `/debtors/{debtorId}/messages` | `GET` | `INBOX_VIEW` |
| `/response-targets` · `/response-targets/{channel}` | `GET` · `PUT` | `INBOX_VIEW` · `INBOX_MANAGE` (+ todos os credores) |
| `/inbox/stream` (SSE) | `GET` | `INBOX_VIEW` |
| `/conversations/{id}/read|identify|take-over|release|assistant|close` · `/drafts/{draftId}/send|discard` | `POST` | `MESSAGE_SEND` |
| `/conversations/{id}/assign|handoff` | `POST` | `INBOX_MANAGE` |
| `/sandbox/inbound` (só com sandbox) | `POST` | `CHANNEL_MANAGE` |

Escopo por credor em tudo (`AccessScope`: 404 leitura, 403 comando, listagens filtradas); contas e templates da
assessoria e conversas sem credor exigem acesso a todos os credores. RLS forçado em todas as tabelas, exceto
`webhook_route` (global, sem dado de tenant além do id); transferências append-only; mensagens, conversas, contas e
templates sem `DELETE`.

## 7. Eventos, logs e métricas

* Publicado: `MessageStatusChanged` em toda mudança de status (com `senderActorType`, `senderActorId`, `portfolioId`
  usados pelo incentive — tempo de resposta de humanos).
* Consumidos: `MessageStatusChanged` (interação), `HandoffRequested`, `CaseAssignmentChanged`, `CaseStatusChanged`
  (abertura vincula conversas sem caso do devedor no credor).
* Negócio: `message.sent|delivered|read|failed|blocked|replied|received`, `conversation.handoff`,
  `conversation.closed`, `conversation.sla_breached`, `assistant.handoff`, `template.published` — ver `docs/tecnico/logs-e-metricas.md` §3.4. Nunca conteúdo,
  contato ou nome.
* Métricas `communication.*` — `docs/tecnico/logs-e-metricas.md` §5. Auditoria: contas (sem credenciais), templates, aprovação,
  dono da conversa, identificação, encerramento e rascunhos.

## 8. Decisões

| # | Decisão | Alternativa | Motivo |
| --- | --- | --- | --- |
| 1 | Dono do caso (collection) é a referência; a conversa guarda a projeção e manda os comandos ao caso | dono só na conversa | sem duplicar regras, histórico e crédito por carteira; inbox continua consultável por dono |
| 2 | `CaseWorkUseCases#release` aceita caso conduzido pelo assistente (o próprio assistente ou quem tem `CASE_ASSIGN`) | método novo no collection | transferência assistente → fila sem novo caso de uso; mesma semântica de devolução |
| 3 | Leitura de caso antes de comando em transação própria (`CaseReader`) | lock otimista e nova tentativa | o comando do collection precisa carregar o caso depois do lock do devedor |
| 4 | Verificação prévia (`evaluate`) no pedido + `authorizeAndRecord` no job | só no job | retorno imediato ao operador; o registro da tentativa continua no momento do envio |
| 5 | Pedido negado vira mensagem `FAILED/CONTACT_DENIED` (201) | 409 sem registro | rastreabilidade do bloqueio e evento `message.blocked` sem desfazer a transação |
| 6 | Recusa definitiva do provedor mantém a tentativa registrada no compliance | desfazer a tentativa | conservador para limites de frequência; transitórias desfazem (nada foi entregue) |
| 7 | Sem `@Retryable` no adaptador; retentativa só pelo job com backoff | retry em memória | não segurar transação e lock do devedor; DEAD com alarme |
| 8 | Interação no caso registrada pelo consumo do próprio `MessageStatusChanged` | na transação do envio | falha no collection não reenvia mensagem ao devedor |
| 9 | Tabulações `MESSAGE_SENT`/`MESSAGE_RECEIVED` (não efetivas) no catálogo padrão e `V1001` | exigir tabulação configurada | interação automática precisa de tabulação; contato efetivo é decisão do operador |
| 10 | Rota de webhook em tabela global sem RLS | varrer tenants / função `security definer` | o webhook chega sem tenant; a tabela só tem chave aleatória e ids; o segredo fica na conta (RLS) |
| 11 | Replay: recibo SHA-256 do corpo + janela de 7 dias por evento + idempotência por id | só idempotência | reentrega idêntica nem chega ao processamento; eventos antigos assinados não são reaplicados |
| 12 | Contato da conversa guardado criptografado (além do blind index) | buscar no debt a cada envio | responder contato ainda não identificado e manter o destinatário da conversa |
| 13 | Envio exige devedor identificado | permitir resposta a desconhecido | o porteiro exige devedor; identificação explícita evita falar com terceiro |
| 14 | Texto livre só no WhatsApp (janela de 24 h); e-mail e SMS só por template | texto livre em todos | minimização de dados e padronização; e-mail exige assunto do template |
| 15 | `CODE` aceita sequências numéricas (linha digitável) e recusa só documento formatado/e-mail | mesma heurística de `TEXT` | linha digitável tem campos de 11/14 dígitos |
| 16 | Origem no porteiro: operador `OPERATOR`, campanha `CAMPAIGN`, assistente/sistema `SYSTEM` | nova origem `ASSISTANT` | o enum do compliance não tem `ASSISTANT`; o autor real fica na mensagem e nos eventos (pendência no compliance) |
| 17 | Sandbox como adaptador condicionado a propriedade e bloqueado no `prod` | sem simulador | pedido do escopo para local/teste; não há provedor simulado em produção |
| 18 | Permissões existentes (`INBOX_VIEW`, `MESSAGE_SEND`, `INBOX_MANAGE`, `TEMPLATE_MANAGE`, `CHANNEL_MANAGE`) | `CONVERSATION_VIEW/WORK` novas | já cobrem leitura, trabalho e supervisão da caixa de entrada; sem migração de perfis |
| 19 | `DebtorContactUseCases#tryFingerprint` (sem exceção) | `fingerprint` com exceção | endereço inválido vindo do provedor não pode marcar a transação do webhook para rollback |
| 20 | Meta de primeira resposta por assessoria e canal, em minutos corridos, classificada no servidor | por credor; horário comercial | é o parâmetro operacional pedido; horário comercial exigiria calendário por canal (pendência) |
| 21 | Estouro contado por varredura com cursor no banco (uma vez por conversa) | gauge por instância | gauges ficariam desatualizados nas instâncias sem o lock do ShedLock; o cursor garante contagem única |
| 22 | Tempo real por SSE + LISTEN/NOTIFY (ADR-0008), aviso só com ids | WebSocket; ElastiCache/SNS | sem serviço novo da AWS; o cliente rebusca pelos endpoints com permissão e escopo |
| 23 | Categoria do template no provedor como enum (`UTILITY`, `MARKETING`, `AUTHENTICATION`) na versão do template | marcador booleano "é autenticação" | é o conceito da Meta (custo, formato e reclassificação); o formato de envio sai da categoria; existentes = `UTILITY` (decisão 510) |
| 24 | Template de autenticação: uma variável `CODE` (até 15) enviada no corpo e no botão `url` índice `"0"` | manter `codigo` + `minutos` | a Meta recusa/reclassifica parâmetros extras; validade é do template na Meta (decisão 511) |
| 25 | `TemplateUseCases#findEffective` para quem envia montar só as variáveis declaradas | variáveis opcionais ignoradas pelo renderizador | o renderizador continua recusando variável não prevista (erro de configuração aparece); o portal decide pelo template (decisão 512) |

## 9. Pendências

* SMS: adaptador após a escolha do provedor (issue #12). E-mail com bounces/reclamações: SES (issue #9).
* `compliance.AttemptOrigin` sem `ASSISTANT` (decisão 16).
* Tela de configuração das metas de SLA (a API existe; hoje só por `PUT /response-targets/{channel}`).
* SLA em horário comercial (calendário por canal) e meta por credor, se a operação pedir.
* Mídias (imagens, documentos) recebidas: registradas sem o arquivo (só legenda); download pela Cloud API e
  armazenamento no S3 quando houver demanda.
