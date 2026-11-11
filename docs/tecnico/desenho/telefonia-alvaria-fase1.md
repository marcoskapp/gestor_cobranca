# Alvaria Engagement Center — Fase 1 (clique para ligar com sessão por operador)

> **Situação: proposta aguardando aprovação do responsável.** Nada deste desenho está implementado. Substituirá o
> anexo A de [telefonia.md](telefonia.md) quando aprovado. Fonte: documentação oficial
> (`https://via-api-documentation.s3.amazonaws.com/ViaEngagementCenterExternalSwagger.html`, lida em 11/11/2026). O
> que a página não traz (esquema dos eventos `EventEC*`, origem do `interactionId`) está marcado **[confirmar]** e
> listado no §13.

## 0. O que a documentação muda em relação ao adaptador atual

| Achado | Consequência |
| --- | --- |
| `interactionId` é gerado pela Alvaria e o dial é `POST …/interactions/{interactionId}/dial`; nenhum endpoint cria interação | Não há referência do Gestor no dial: a correlação chamada ↔ interação vem da **sessão do operador** (§5.3) [confirmar: P1] |
| Sem cabeçalho de idempotência; comandos respondem 202 | Dial com resultado ambíguo **nunca é repetido** (como a decisão 582) |
| `DialParms`: `dialType`, `ehubSessionId`, `workTypeId` (obrigatório), `externalRouteId`, `phoneNumber`, `userId`, `requireWrap` | `ehubSessionId` (um por connect) vai em todo comando; `requireWrap=true` alinha o wrap à tabulação |
| `ConnectParms`: `station` (telefone do áudio do agente), `initialState`, `attach` | `station` pode ser telefone pessoal: dado pessoal, cifrado e mascarado |
| `keepAlive` a cada minuto depois de iniciado | Só o dono da sessão envia (§4) |
| `notReady(reasonId)`, `disconnect(reasonId)` (pendente até acabar a interação), `forceDisconnect` | Motivos de pausa mapeados para `reasonId` por conta |
| `dispose`: `dispositionId`, `callbackFlag`, `interactionDataCollection` (até 20 campos) | O id da chamada no Gestor vai como campo opaco `gestorCallId` (conciliação de relatórios) |
| Token em `https://auth.{region}.id.aspect-cloud.net/oauth2/access_token`, escopo `engagementcenterapi` | Hosts montados a partir de `orgId` e `region` de lista fechada (anti-SSRF) |
| Sem consulta de estado atual do agente ou da interação | Conciliação só por eventos e prazos (§6) [confirmar: P6] |
| Eventos só por long polling (Streaming API), uma sessão por agente; sem webhook | Laço de polling por operador com dono único entre as instâncias (§4) |

## 1. Escopo

**Entra:** sessão Alvaria por operador (connect com a estação, keepAlive, ready/notReady espelhando a presença,
disconnect); clique para ligar (porteiro → `dial` → eventos → `terminate`); tabulação → `dispose` com o `dispositionId`
mapeado; long polling traduzido para o evento canônico de chamada; configuração de usuário Alvaria, estação,
workType, rota externa, motivos e disposições.

**Fica desligado para a Alvaria até a Fase 2** (capacidade declarada pelo adaptador e recusada pelos serviços):
discador/lote e bridge (campanha e regra `CAMPAIGN` com conta Alvaria recusadas); gravação (Recording API
inacessível: conta Alvaria com gravação ligada é recusada); webhook (a Alvaria não tem; `AlvariaWebhookAdapter` sai);
chamadas recebidas e de Outreach (só contadas). A decisão 565 continua valendo: o discador da Alvaria não é usado.

## 2. Porta de saída e capacidades

- `TelephonyProvider.capabilities()`: `DIALER`, `BRIDGE`, `RECORDING`, `WEBHOOK`, `AGENT_SESSION`. Sandbox, Twilio e
  Plivo: tudo menos `AGENT_SESSION`; Alvaria: só `AGENT_SESSION`. Campanha, roteamento, distribuição, gravação e rota
  de webhook passam a consultar a capacidade.
- `OriginateRequest`/`HangupRequest` ganham `ProviderSessionRef` (sessão, usuário Alvaria, `ehubSessionId`).
- `DialResult.acceptedPending`: aceite 202 sem id do provedor; a chamada marca `dial_accepted_at` e não é discada de
  novo; o id chega pelo evento.
- Portas novas: `AgentSessionProvider` (connect, keepAlive, ready, notReady, disconnect, forceDisconnect, dispose),
  `ProviderEventStream` (poll, acknowledge) e `SessionLeases`. O evento traduzido não carrega telefone.

## 3. Sessão do operador

Tabela `telephony.agent_session` (RLS), uma linha por operador × conta Alvaria, no modelo **estado desejado × estado
real**: o job `telephony.agent_session_sync` faz o real convergir para o desejado, então repetição e eventos fora de
ordem não têm efeito duplo. Ids da Alvaria (`via-client-sessionid` cifrado, `ehubSessionId`) e controle de falhas e
backoff ficam na linha.

| Gestor (presença) | Alvaria |
| --- | --- |
| login | abre o streaming → `connect` (`station`, `initialState=NotReady`) → keepAlive |
| disponível | `ready` |
| pausa (motivo) | `notReady(reasonId)` |
| em chamada / pós-atendimento | nada (a Alvaria conduz pela interação) |
| tabulação | `dispose`, depois `ready`/`notReady` conforme o Gestor |
| logout / sem sinal | `disconnect(reasonId)` |
| saída forçada pelo supervisor | `forceDisconnect` (já auditada) |

O ponto único é o `PresenceRecorder`, que grava o job na mesma transação da mudança de presença (sem HTTP dentro da
transação, decisões 852 e 904). O clique para ligar só usa a conta Alvaria com a sessão do operador pronta; senão a
conta sai das candidatas (próxima conta da regra ou aviso "Plataforma indisponível"). Queda da sessão na Alvaria
reconecta com backoff e **não** muda a presença do Gestor.

## 4. Várias instâncias: quem faz o polling de cada operador

- **Lease no PostgreSQL** (`telephony.session_lease`, global, só ids, como `webhook_route`): dono, `epoch` (fencing) e
  validade. Um supervisor por instância (a cada 5 s) renova os próprios leases e reivindica os órfãos com
  `FOR UPDATE SKIP LOCKED`, com teto por instância (padrão 200 sessões) e jitter — reparte a carga sem coordenador.
- **Laço por sessão** (virtual thread): `GET /events` (espera de 15 s) → aplica numa transação que confere dono e
  `epoch` → reconhece só depois do commit (entrega *at-least-once*, aplicação idempotente). O keepAlive fica no mesmo
  laço: um dono por sessão.
- **Failover**: instância caída → lease vence em até 45 s → outra assume a mesma sessão de streaming e recebe os
  eventos não reconhecidos (antes do `inactivityDelay` de 300 s). Desligamento ordenado libera os leases sem
  desconectar o operador.
- **Comandos** (dial, terminate, dispose, ready…) são jobs comuns e rodam em qualquer instância.
- Descartadas: instância única com ShedLock (ponto único e gargalo) e afinidade no balanceador (não cobre jobs nem
  queda).

## 5. Fluxos

**5.1 Clique para ligar.** Igual a hoje até o `DialJob`: o porteiro de contato autoriza e registra **antes** de
qualquer HTTP. Depois, `dial` com `dialType=External`, workType, rota externa, usuário e `ehubSessionId`. 202 →
aceite pendente; 429/503/falha de conexão → contingência da decisão 562; tempo esgotado ou 5xx ambíguo → aceite
pendente **sem repetir**, confirmado só por evento. Encerrar (operador ou supervisor) → `terminate` por job.

**5.2 Tradução dos eventos.**

| Evento | Efeito no Gestor |
| --- | --- |
| `EventECDialing` / `EventECDialInitiated` | vincula o `interactionId` à chamada (sem "tocando": não há esse evento) [confirmar: P3] |
| `EventECActive` | atendida (sem AMD: tratada como pessoa) |
| `EventECInteractionClear` | concluída se foi atendida; senão não atendida ou falha pelo último `failReason` |
| `EventECError` | `failReason` mapeado (ex.: 140 → devedor desligou); desconhecido → falha `ALVARIA_<código>` [confirmar: P4] |
| `EventECWrap` / `EventECIdle` | só o estado real da sessão |

**5.3 Correlação.** Interação nova em `Dialing` é vinculada pela sessão → operador → chamada atual dele (em discagem,
com aceite pendente, mesma conta). Funciona porque o operador tem uma chamada por vez. Interação sem correspondência
(recebida, Outreach, discagem fora do Gestor) só vira métrica.

**5.4 Tabulação → dispose.** A tabulação grava o job `telephony.provider_dispose` na mesma transação; o job busca o
`dispositionId` mapeado (sem mapeamento → disposição padrão da conta) e envia `gestorCallId` como dado da interação.

## 6. Idempotência e conciliação

- Evento: chave = id do evento [P2] ou hash sintético (sessão, interação, tipo, instante, motivo), gravada na
  idempotência atual; a chamada só avança de estado.
- Dial sem evento em 60 s → falha `ALVARIA_NO_EVENT`, operador liberado; vínculo tardio encerra a interação no provedor.
- Interação que volta ativa depois de a chamada ter terminado → `terminate` por job.
- Sessão perdida (410 ou retomada depois do `inactivityDelay`) → chamadas abertas do operador encerradas
  (`ALVARIA_SESSION_LOST`), operador tabula, sessão recriada.
- Varredura na conciliação atual (a cada 30 s): desejado ≠ real há mais de 60 s → novo sync.

## 7. Configuração, segredos e limites

- Credenciais da conta (`orgId`, `region`, `clientId`, `clientSecret`, `x-api-key`) cifradas na conta, como Twilio e
  Plivo; nunca devolvidas nem auditadas (só as chaves). Secrets Manager seria serviço novo: só com aprovação (issue).
- Hosts montados de `orgId` (formato restrito) e `region` (lista fechada); `apiBaseUrl` e `webhookSecret` saem.
- Parâmetros não secretos da conta (workType, rota externa, motivos de pausa, motivo de saída, disposição padrão) em
  `settings` auditado; disposições em tabela própria (conta × código de tabulação).
- Ramal do operador: usuário Alvaria e estação (cifrada, sempre mascarada, nunca em log).
- Opcional: "Carregar da Alvaria" (Provisioning API, só leitura) para escolher ids em vez de digitar.
- Limites: 429 respeita `Retry-After`; limitador por conta; 401 renova o token uma vez; polling com backoff até 30 s.

## 8. Logs, métricas e auditoria

Logs técnicos só com ids internos, operação, status e código — nunca telefone, estação, usuário Alvaria, token,
chave ou id de sessão de streaming (teste de captura de log confere). Evento de negócio novo
`agent.provider_session_changed`. Métricas: chamadas de integração por operação, sessões por estado, leases, polls,
eventos aplicados/duplicados/desconhecidos, atraso de aplicação (p95), dispose e motivos de conciliação. Alarmes:
sessões perdidas, atraso p95 > 10 s, sessão sem dono > 60 s, dispose em `DEAD`. Detalhe em
`logs-e-metricas.md` na implementação.

## 9. Ativação

Flag `app.telephony.alvaria.enabled` (padrão desligada; desliga também polling e sync) **e** `CONTRACT_PENDING`, que
já exige a homologação explícita. Só no ambiente de homologação até o roteiro do §11 passar contra a Alvaria real.
Contas Alvaria antigas (formato presumido) ficam inativas e pedem as credenciais novas.

## 10. Onde muda

- Migration `V1205__telephony_alvaria_sessions.sql`: `agent_session` (RLS), `session_lease` (global),
  `provider_disposition` (RLS), colunas novas em conta, ramal e chamada; inativação das contas Alvaria antigas.
- `domain`: `AgentSession`, `ProviderDisposition`; `Call` com aceite pendente.
- `application`: portas novas; `AgentSessionSyncJob`, `AgentStreamSupervisor`, `AgentStreamLoop`,
  `StreamEventApplier`, `ProviderDisposeJob`, `AgentSessionReconciliation`; ajustes em `PresenceRecorder`, `DialJob`,
  `ProviderHangupJob`, `CallService`, roteamento e campanha (capacidades).
- `infrastructure/provider/alvaria`: contrato reescrito pela documentação, cliente HTTP, adaptadores de sessão,
  streaming e tradução; sai o `AlvariaWebhookAdapter`.
- `web` e frontend: parâmetros e disposições da conta, ramal Alvaria, estado da sessão na barra de presença; manual,
  tour, novidade e base de conhecimento.
- Sem dependência nova entre módulos (`ArchitectureTest`/`ModularityTest`).

## 11. Testes

Dublê da API seguindo a documentação (token, cabeçalhos obrigatórios, 202, streaming com reentrega e 410, roteiros
de eventos, 429/503/timeout/401). Unidade: tradução, contrato, cliente, sessão, leases (fencing). IT
`TelephonyAlvariaSessionIT`: login → connect → ready; pausa → notReady; porteiro negando sem dial; clique → dial →
atendida → fim → tabulação → dispose; terminate pelo supervisor; contingência; dial ambíguo sem repetir; sem evento;
failover com reentrega aplicada uma vez; sessão perdida; capacidades recusando campanha e gravação; nenhum telefone em
log. Roteiro de homologação contra a Alvaria real (inclui rotação de credencial e queda de instância em chamada).

## 12. Adaptador atual: o que fica e o que sai

Fica: cache de token por conta e renovação no 401, classificação de falha não processada × ambígua, códigos
`ALVARIA_<código>`, métricas de integração, HTTPS sem redirecionamento, timeouts, `CONTRACT_PENDING`. Sai: as rotas
presumidas (`/calls/agent-first`, lote, bridge, gravações), `Idempotency-Key`, `apiBaseUrl`, `webhookSecret` e o
webhook assinado.

## 13. Perguntas para a Alvaria

1. Origem do `interactionId` no dial External; o dial deduplica por ele?
2. Esquema dos eventos `EventEC*` (id/sequência, interação, usuário, instante, `failReason`; trazem telefone?).
3. `Dialing` × `DialInitiated`; há sinal de toque ou atendimento distinto de `Active`?
4. Tabela de `failReason` (ocupado, não atende, inválido, rede, recusado pela rota).
5. Limites de taxa (org, usuário, endpoint, token); validade do token; `Retry-After`.
6. Consulta de estado atual do agente/interações para conciliar após perda de sessão?
7. Wrap com `requireWrap` sem dispose: prazo e efeito; o `dispose` volta o agente a Idle?
8. Dá para discar em `NotReady`? Usuário sem filas inbound/Outreach para não receber interações em `Ready`?
9. Caminho exato da Streaming API v3, inscrições necessárias; uma sessão por agente ou uma para vários?
10. Regiões e host do token; Provisioning API com as mesmas credenciais?
11. Formato do `station` e do `phoneNumber`; bina por `externalRouteId`.
12. Gravação do lado deles (padrão, aviso, retenção), região de processamento, subprocessadores e DPA (LGPD).
13. Ambiente de homologação, usuários e estações de teste, IPs de origem.
14. Documentação das APIs Outreach e Recording (Fase 2).

## 14. Decisões que a implementação vai registrar (a numerar na sequência)

| Decisão | Alternativa descartada | Motivo |
| --- | --- | --- |
| Capacidades por adaptador; Alvaria só com sessão de agente na Fase 1 | Manter o contrato presumido | Só o documentado entra em código |
| Lease por sessão no PostgreSQL com fencing, um dono por sessão | Instância única com ShedLock; afinidade no balanceador | Failover em até 45 s sem ponto único |
| Sincronia por estado desejado × real em job durável | Chamar a Alvaria na requisição de presença | Sem HTTP em transação; repetição sem efeito duplo |
| Queda da sessão Alvaria não muda a presença do Gestor | Pausar automaticamente | Não distorce tempo logado e incentivo |
| Dial ambíguo nunca repetido; confirmação só por evento (60 s) | Repetir na mesma conta | Sem idempotência na API: evita ligar duas vezes |
| Correlação pelo operador reservado; interação desconhecida só contada | Correlacionar pelo número | Número não circula |
| Reconhecer evento só depois do commit | Reconhecer ao receber | *At-least-once* com failover |
| Sessão perdida encerra as chamadas abertas do operador | Esperar a varredura de 4 h | Operador não fica preso em chamada |
| Estação cifrada e mascarada; usuário Alvaria fora de logs | Texto puro | Pode ser telefone pessoal (LGPD) |
| Segredos cifrados na conta, sem Secrets Manager | Secrets Manager | Padrão existente; serviço novo pede aprovação |
| Hosts montados de `orgId` e região de lista fechada | URL livre com lista de permitidos | Anti-SSRF mais forte |
| Parâmetros em `settings` e disposições em tabela, com disposição padrão | Tudo em credenciais | Auditáveis; tabulação sem mapeamento não trava |
| `requireWrap=true` e `gestorCallId` no dispose | Sem wrap | Alinha wrap e tabulação; concilia relatórios sem dado pessoal |
| ready/notReady espelham a presença, connect em `NotReady` | Agente sempre em `NotReady` | Reavaliar com a resposta à P8 |
| Remover o webhook Alvaria | Manter inativo | A Alvaria não tem webhook |
| Flag própria desligada por padrão, além da homologação | Só a homologação | Desliga polling e sync em todas as instâncias |
