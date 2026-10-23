# Desenho — módulo `communication` (canais, conversas, inbox)

Escopo: M05, F12, RB07. Schema `communication`, migrations V1000–V1099.
Dependências: `shared`, `organization`, `debt`, `compliance`, `collection`.

## 1. Canais e adaptadores

Porta `ChannelGateway` por canal, com adaptador por provedor:

| Canal | Adaptador | Observações |
| --- | --- | --- |
| WhatsApp | WhatsApp Cloud API (Meta) | templates aprovados para iniciar contato; janela de 24 h após a última mensagem do devedor; webhook com assinatura `X-Hub-Signature-256` |
| E-mail | SMTP | remetente por credor/assessoria; bounces alimentam `compliance` |
| SMS | porta definida; adaptador após escolha do provedor (issue #12) | só aviso com link |

Credenciais de provedor por tenant, criptografadas; webhooks por `routingKey` do tenant (`/api/v1/webhooks/<provedor>/{routingKey}`), assinatura validada antes de qualquer processamento.

## 2. Conversa e dono do atendimento

`Conversation` (devedor, canal, credor, caso) com **dono** explícito:

| Dono | Significado |
| --- | --- |
| `ASSISTANT` | o assistente responde (conforme a política do assistente) |
| `QUEUE` | aguardando um humano (fila por equipe/perfil exigido) |
| `USER` | atendida por um operador |
| `NONE` | sem atendimento ativo (ex.: apenas notificações) |

Transições registradas em `ConversationHandoff` (de, para, motivo, resumo gerado, instante). Regras:
- Mensagem recebida em conversa `ASSISTANT` é entregue ao assistente; em `QUEUE`/`USER`, à caixa de entrada.
- Transferência do assistente para humano leva **motivo** e **resumo** e indica o perfil necessário (ex.: alçada `SUPERVISOR`, ou `SENIOR` para contestação), usado no roteamento da fila.
- Operador pode devolver a conversa ao assistente (se a política permitir) ou assumir uma conversa do assistente a qualquer momento.
- No modo **sombra**, o assistente gera rascunhos para a conversa e o operador decide enviar.

## 3. Mensagens

`Message`: direção, canal, autor (tipo de ator e id: USER, ASSISTANT, SYSTEM, DEBTOR), template e variáveis, conteúdo criptografado, status (`QUEUED, SENT, DELIVERED, READ, FAILED, RECEIVED`), id do provedor (idempotência dos webhooks), execução de campanha de origem.

- Envio sempre por `ContactGate.authorizeAndRecord` (`compliance`): bloqueios, horário, frequência — inclusive para o assistente.
- Opt-out por palavra-chave (SAIR, PARAR, STOP…) registrado em `compliance` imediatamente.
- Conteúdo de mensagem segue a política de retenção (`compliance`, categoria `MESSAGE_CONTENT`).
- Eventos `MessageStatusChanged`; eventos de negócio `message.*` com `actorType`.

## 4. Caixa de entrada (F12)

Lista de conversas por fila/equipe, atribuição, SLA de primeira resposta, filtros por credor/carteira/dono; ficha do devedor ao lado (via `collection`/`debt`).
