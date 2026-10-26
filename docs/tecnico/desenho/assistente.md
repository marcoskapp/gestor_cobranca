# Desenho — módulo `assistant` (IA de atendimento e negociação)

Escopo: F24. Especificação de produto: [`docs/negocio/assistente-ia.md`](../../negocio/assistente-ia.md). Schema `assistant`, migrations V1500–V1599 (`V1500` tabelas; `V1501` pedidos de Pix; permissões em `identity/V0205`).
Dependências: `shared`, `organization`, `debt`, `compliance`, `collection`, `negotiation`, `communication`, `payment` (interfaces `usecases`; `payment` pelo ADR-0009). Fatos de acordo, situação das instruções de pagamento e anonimização chegam por eventos.

> **Estado (25/10/2026).** Implementado: política versionada, orquestração dos turnos, ferramentas validadas no servidor, guardrails, transferência, modos sombra/copiloto/autônomos, trilha append-only, avaliação e métricas de qualidade, REST e **Pix na conversa** (§5.1, ADR-0009). **Provedor de IA (25/10/2026, issue #12): Amazon Bedrock com Claude Haiku 5.5 (nível padrão) e Claude Sonnet 5.5 (nível avançado: negociação, proposta, aceite e Pix)** — adaptador `BedrockLanguageModel` e nível de modelo por etapa (§6); o simulador continua para local/teste. Decisões 120–135, 176–191 e 360–377 em `docs/negocio/decisoes-autonomas.md` (a 135 foi superada pela decisão do responsável sobre o Pix).

Princípio: **a IA conversa, o motor decide.** O modelo entende o devedor e escolhe o próximo passo; toda condição vem do `negotiation` (mesmo `NegotiationGuard` dos humanos), todo contato passa pelo porteiro do `compliance` e toda ação é um caso de uso do módulo dono, executado como ator `ASSISTANT`.

## 1. Componentes

| Componente | Onde | Responsabilidade |
| --- | --- | --- |
| `AssistantPolicy` + `PolicySettings` | `domain.model` | versão da política por assessoria, credor ou carteira (§2) |
| `AssistantSession` + `SessionMemory` | `domain.model` | estado por conversa: etapa, identidade, turnos, falhas de entendimento, memória de negociação (escrita só pelo servidor), consumo, turno em andamento |
| `AssistantTurn`, `ToolCallRecord` | `domain.model` | trilha append-only de cada turno e das chamadas de ferramenta (§7) |
| `TurnReview`, `AssistantAgreementFact` | `domain.model` | amostra de revisão humana; fatos de acordo para as métricas (§8) |
| `PixRequest`, `PixCriterion` | `domain.model` | pedido de Pix na conversa: critérios avaliados (imutáveis), decisão, instrução, entrega e pagamento (§5.1) |
| `PixEligibility`, `PixMessage` | `domain.service` | critérios objetivos de "conversa amigável e conciliação tranquila"; mensagem do Pix com BR Code validado (§5.1) |
| `HandoffDetector` | `domain.service` | gatilhos obrigatórios determinísticos (§4.3) |
| `ResponseGuard` | `domain.service` | guardrails de saída (§4.4) |
| `ContextBuilder`/`ModelContext`, `PersonalDataRedactor` | `domain.service` | contexto minimizado e mascaramento do texto (§4.1) |
| `IdentityChallenge` | `domain.service` | leitura da resposta à confirmação de identidade |
| `PolicyResolver`, `ServiceHours`, `ReviewSampler` | `domain.service` | política vigente, janelas de atendimento, amostragem |
| `TurnOrchestrator`, `AssistantTurnJob` | `application.service` | orquestração do turno (§3) |
| `ModelTierSelector`, `ModelTier`, `ModelRouting` | `domain.service`/`domain.model` | nível de modelo de cada chamada pela política e pela etapa (§6.2) |
| `AssistantPix`, `AssistantPixDelivery`, `AssistantPixRecorder` | `application.service` | ferramentas de Pix, entrega do código (job `assistant.pix_delivery`) e fatos/métricas do Pix (§5.1) |
| `AssistantToolbox`, `ToolSchemas` | `application.service` | ferramentas expostas ao modelo e validação no servidor (§5) |
| `AssistantActors`, `AssistantChannel` | `application.service` | ator `ASSISTANT` por turno; saídas ao communication/compliance |
| `TurnStore` | `application.service` | gravações do turno em transações próprias |
| `LanguageModelPort`, `PromptSource`, `ConversationLocks`, `TurnContentStore`, `QualityStatistics`, `PaymentInstructionPort` | `application.port.out` | portas de saída |
| `BedrockLanguageModel` (+ `BedrockConfiguration`, `BedrockProperties`; tradução, preço, prazo, retentativa e erros no cliente do kernel `shared.ai.bedrock` — `BedrockConverseModel`, `ConverseTranslator`, `BedrockPricing` —, compartilhado com o chat de suporte), `SandboxLanguageModel`, `ClasspathPromptSource`, `PostgresConversationLocks`, `Jdbc*`, `PaymentModulePixInstructions` | `infrastructure` | adaptadores (Bedrock §6.5; o último: `PaymentInstructionPort` sobre `PaymentInstructionUseCases`, ADR-0009) |
| `AssistantProviderGuard` | `infrastructure.config` | um provedor de IA por instância; simulado recusado no `prod` |

## 2. Política do assistente (versionada)

Escopo: assessoria (credor e carteira nulos) → credor → carteira; a **publicada mais específica** vale (`PolicyResolver`). Um código por escopo; ciclo `DRAFT → PUBLISHED → RETIRED`; publicar atribui a próxima versão do código, aposenta a anterior e torna a versão imutável (gatilho `policy_published_immutable`). O **id da versão publicada é o id do ator** `ASSISTANT` (auditoria, eventos, alçada, atribuição). A política é lida a cada turno: nova versão ou desligamento valem na próxima mensagem, inclusive em conversas em andamento.

| Grupo | Parâmetros (`PolicySettings`, jsonb esquema v1) |
| --- | --- |
| Modo | `OFF`, `SHADOW`, `COPILOT`, `AUTONOMOUS_WITH_APPROVAL`, `AUTONOMOUS` (§4 da especificação) |
| Canais e horários | canais (hoje só `WHATSAPP` — o communication só aceita texto livre no WhatsApp); janelas semanais no fuso de operação (fora delas o turno é adiado até a abertura) |
| Alçada | `authorityProfileId` → `AuthorityUseCases#assign(ASSISTANT_POLICY, versão, credor, perfil)` na publicação (exige `AUTHORITY_MANAGE`); sem perfil vale `ASSISTENTE_INICIAL` |
| Prompt | `promptCode` + `promptVersion` (recurso versionado, §6.2) |
| Conversa | tom (`FORMAL`/`PROXIMO`); `maxTurns` (12); `maxMisunderstandings` (2); `minConfidence` (0,60) |
| Identidade | `requireIdentityVerification` (padrão sim); `maxIdentityAttempts` (3) |
| Temas | `allowedTopics` (informados ao modelo); `forbiddenTopics` (bloqueiam a resposta) |
| Transferência | gatilhos adicionais (`extraTriggers`: expressão + motivo); perfil exigido por motivo (`requiredProfiles`) — os obrigatórios não se desligam |
| Transparência | `identificationMessage` obrigatória (precisa dizer "assistente virtual" e como pedir humano), enviada na primeira mensagem de cada etapa; `handoffMessage`, `fallbackMessage`, `approvalMessage` |
| Limites | tokens de saída por chamada (600), tokens por turno (24.000 nas políticas novas — entrada com e sem cache + saída; antes 8.000), ferramentas por turno (6), tempo por chamada (20 s, inclusive a retentativa), custo por conversa (1,00 na moeda do provedor) |
| Modelo | `modelRouting`: `BY_STAGE` (padrão; avançado só em negociação, proposta, aceite e Pix) ou `ADVANCED_ONLY` (avançado em tudo, piloto) — §6.2 |
| Avaliação | `reviewSampleRate` (10%); `allowCreditBureauMention` (negativação só se o credor autorizar) |
| Pix na conversa | `pixInConversation`: `enabled` (padrão **desligado**; exige `requireIdentityVerification`), `maxAmount` (R$ 5.000,00; até R$ 50.000,00), `maxPerConversationPerDay` (3), `expirationMinutes` (60; 15–1.440), `riskLookbackDays` (30; 1–180) — §5.1 |

**Desligamento imediato de carteira** (`POST /portfolios/{id}/disable`): publica uma versão `OFF` da carteira e o job `assistant.portfolio_disable` transfere para humanos as conversas que o assistente conduz nela (motivo `ASSISTANT_DISABLED`).

## 3. Orquestração de um turno

1. `MessageStatusChanged` (`INBOUND`/`RECEIVED`) → consumidor `assistant.message-received` → job `assistant.turn` (um por mensagem).
2. O job trava a conversa (`pg_advisory_xact_lock`) e decide pela **política vigente** e pelo **dono da conversa** (`comunicacao.md` §3):

| Dono | Política | Ação |
| --- | --- | --- |
| `ASSISTANT` | autônoma, canal habilitado, dentro do horário | turno autônomo |
| `ASSISTANT` | fora do horário | adiado até a próxima abertura |
| `ASSISTANT` | ausente, `OFF`, sombra/copiloto ou canal não habilitado | transferência (`ASSISTANT_DISABLED`/`CHANNEL_NOT_ENABLED`) |
| `USER`/`QUEUE` | `SHADOW`/`COPILOT` | rascunho para o operador |
| demais | — | nada |

3. Sessão: criada no primeiro turno; nova **etapa** quando a conversa volta ao assistente depois de transferida (contadores e memória zerados; identidade confirmada há menos de 24 h mantida). As mensagens do devedor ainda não tratadas formam a entrada do turno (rajadas viram um turno; os jobs seguintes terminam sem efeito).
4. Turno autônomo, nesta ordem — tudo antes do modelo é determinístico:
   1. limites: turnos da etapa e custo acumulado da conversa → transferência (`TURN_LIMIT`/`COST_LIMIT`);
   2. gatilhos obrigatórios (`HandoffDetector`) → transferência com o motivo de maior prioridade; pedido de descadastro → restrição `OPT_OUT` (devedor, canal e credor da conversa, origem `DEBTOR`) e transferência **sem** mensagem;
   3. identidade (§4.2);
   4. conversa com o modelo (`LanguageModelPort`) com o contexto minimizado e as ferramentas disponíveis (§5), em rodadas até a resposta final, respeitando tokens, ferramentas e tempo;
   5. guardrails de saída (§4.4) → reprovado: mensagem padrão + transferência (`GUARDRAIL`);
   6. envio por `MessageUseCases#send` como `ASSISTANT` (chave de idempotência por turno) — o communication aplica o porteiro na verificação prévia e de novo no envio; negado → turno `SEND_BLOCKED`.
5. Rascunho (sombra/copiloto): mesmos gatilhos (viram **alerta** na justificativa do rascunho, com o perfil sugerido), contexto e guardrails; o resultado vai para `ConversationUseCases#proposeDraft`; no copiloto a justificativa traz a próxima oferta sugerida. O operador envia ou descarta; nada sai sem humano. Identidade fica a cargo do operador (lembrete no rascunho).
6. Registro do turno (§7) e eventos de negócio.

**Falhas.** Provedor ausente, tempo esgotado, recusa do provedor, limite de rodadas ou erro inesperado → mensagem padrão + transferência (`TECHNICAL_FAILURE`); o devedor nunca fica sem atendimento por causa do assistente. Turno interrompido (instância caiu entre o início e o registro) é recuperado pela varredura `assistant.stale-turns` (marca `pending_turn_id` na sessão; 10 min) com o mesmo tratamento.

**Ator.** Cada chamada a outro módulo roda em transação própria como `Actor.assistant(versão da política, código, permissões, {credor da conversa})`:

| Modo | Permissões |
| --- | --- |
| todos | `INBOX_VIEW`, `MESSAGE_SEND`, `CASE_VIEW`, `DEBTOR_VIEW`, `NEGOTIATION_SIMULATE` |
| autônomos | + `NEGOTIATION_PROPOSE`, `AGREEMENT_VIEW`, `AGREEMENT_FORMALIZE`, `INTERACTION_REGISTER`, `TASK_MANAGE`, `CONTACT_RESTRICTION_MANAGE` |
| `pixInConversation` ligado | + `AGREEMENT_VIEW` (todos os modos); nos autônomos também `PAYMENT_VIEW` e `PAYMENT_INSTRUCTION_CREATE` |

## 4. Limites e guardrails

Três camadas (especificação §4): (1) limites duros — política de cobrança e porteiro, iguais para humanos e IA; (2) alçada do assistente — perfil do `negotiation`; (3) transferência obrigatória — abaixo.

### 4.1 Minimização (o que vai ao provedor)

`ModelContext` (JSON, versão `AssistantTurn.CONTEXT_SCHEMA_VERSION`): nome genérico do assistente, tom, modo, canal, data, **primeiro nome** (só após a identidade), estado e tentativas da identidade, turno/limite, dias de atraso e etapa do caso, saldo atualizado, **ofertas válidas do motor com referências opacas** (`O1`…), proposta corrente (`P1`), temas permitidos e regras. Nunca: documento, contatos, nome completo, ids internos, dados de terceiros, envelope da política (o `negotiation` já não o devolve ao assistente). Antes da confirmação de identidade, nada da dívida entra.

Histórico: as 12 mensagens mais recentes, com documento, e-mail, telefone e números longos mascarados (`PersonalDataRedactor`); mensagens anteriores à confirmação (inclusive a que a confirmou) também com datas e grupos de dígitos mascarados. Texto do devedor é dado, não instrução: o prompt diz isso e, de qualquer forma, condições só existem quando devolvidas pelas ferramentas.

### 4.2 Identidade

Antes de revelar a dívida: 3 primeiros dígitos do CPF (ou o CPF completo, do qual só os 3 primeiros são usados) e data de nascimento, lidos da mensagem pelo servidor (`IdentityChallenge`) e conferidos no debt (`DebtorIdentityUseCases#check`, comparação em tempo constante, o documento não sai do módulo). `UNAVAILABLE` (pessoa jurídica, cadastro sem nascimento, anonimizado) ou tentativas esgotadas → `IDENTITY_NOT_CONFIRMED`. Até a confirmação, só as ferramentas `register_intent` e `transfer_to_human` ficam disponíveis.

### 4.3 Transferência obrigatória (`HandoffDetector` + modelo)

| Motivo | Detecção por regra (exemplos) | Perfil padrão |
| --- | --- | --- |
| `DISPUTE` | "não reconheço", "nunca contratei", "contesto" | `SENIOR` |
| `PAYMENT_CLAIM` | "já paguei", "tenho o comprovante" | `SENIOR` |
| `FRAUD_SUSPECTED` | "golpe", "fraude", "usaram meus dados" | `SUPERVISOR` |
| `DECEASED` | "faleceu", "óbito" | `SUPERVISOR` |
| `VULNERABILITY` | desemprego, doença, internação, superendividamento, idoso, mínimo existencial | `SENIOR` |
| `LEGAL_MENTION` | advogado, Procon, processo, justiça, defensoria | `SUPERVISOR` |
| `COMPLAINT` | Reclame Aqui, ouvidoria, Banco Central | `SUPERVISOR` |
| `ABUSIVE` | xingamento, ameaça | `SUPERVISOR` |
| `HUMAN_REQUESTED` | "humano", "atendente", "pessoa de verdade" | qualquer |
| `OPT_OUT` | "não quero mais receber", "parem de mandar", `SAIR` | qualquer (sem mensagem) |
| `LOW_CONFIDENCE` | confiança abaixo do mínimo `maxMisunderstandings` vezes seguidas | qualquer |
| `TURN_LIMIT`, `COST_LIMIT`, `IDENTITY_NOT_CONFIRMED`, `GUARDRAIL`, `TECHNICAL_FAILURE`, `ASSISTANT_DISABLED`, `CHANNEL_NOT_ENABLED` | limites e falhas | qualquer |
| `REQUIRES_APPROVAL` | proposta acima da alçada (motor) | quem tem alçada |
| `FORMALIZATION_APPROVAL` | aceite registrado, formalização por humano | qualquer |
| `POLICY_TRIGGER`, `MODEL_REQUESTED` | gatilho adicional da política; pedido do modelo | qualquer |
| `PAYMENT_ASSISTANCE` | Pix recusado por critério em que um atendente pode concluir o pagamento, ou não entregue (§5.1) | qualquer |

Regra **e** modelo: o modelo informa sinais em `register_intent` e pode chamar `transfer_to_human` (motivos restritos ao catálogo); qualquer um basta. A política troca o perfil exigido por motivo. A transferência usa `ConversationUseCases#handoffToHumans` com resumo **sem dados pessoais** (motivo, origem, versão da política, etapa, identidade, intenção, saldo, última oferta, proposta) e o perfil exigido, que filtra a fila na caixa de entrada.

### 4.4 Guardrails de saída (`ResponseGuard`)

Toda resposta e todo rascunho: valor em reais, percentual, data e número de parcelas citados precisam constar dos **fatos permitidos** (`AllowedFacts`, preenchidos só a partir das saídas das ferramentas); recusa ameaça, constrangimento/exposição a terceiros, linguagem abusiva, conselho jurídico, promessa fora da política, menção a negativação (salvo `allowCreditBureauMention`), passar-se por humano, dado pessoal (documento, e-mail, telefone), temas proibidos, resposta vazia ou com mais de 1.000 caracteres. Filtro de conteúdo do provedor conta como guardrail (`PROVIDER_FILTERED`).

## 5. Ferramentas (validação no servidor)

| Ferramenta | Disponível | Caso de uso | Validação |
| --- | --- | --- | --- |
| `register_intent` | sempre | — | intenção do catálogo; confiança 0–1; sinais só do catálogo de motivos |
| `get_debt_summary` | após identidade (rascunho: sempre) | `NegotiationSimulationUseCases#simulate` nível 0, à vista | títulos em aberto do devedor no credor/carteira da conversa |
| `simulate_options` | idem | `simulate` | até 3 formas (1–60 pagamentos); nível > 0 exige motivo de concessão do catálogo; ofertas válidas devolvidas com referência `O1`… |
| `propose_offer` | autônomos, após identidade | `ProposalUseCases#create` | só referência a oferta devolvida nesta sessão; descontos e parcelas = os da oferta; acima da alçada → aviso ao devedor, proposta `HANDED_OFF` + `HandoffRequested` (negotiation), turno encerrado |
| `register_acceptance` | idem | `ProposalUseCases#accept` (+ `#formalize` em `AUTONOMOUS` com perfil `canFormalize`) | só a proposta corrente (`P1`); exige manifestação explícita de aceite na mensagem do devedor ("aceito", "pode fechar"…, sem negação); canal e id da mensagem na evidência |
| `register_promise` | idem, com caso | `InteractionUseCases#record` (tabulação `PROMISE`, origem `ASSISTANT`) | valor devolvido pelas ferramentas; data não passada; idempotente pela mensagem |
| `schedule_callback` | idem, com caso | `InteractionUseCases#record` (`CALLBACK`) | até 15 dias, no futuro |
| `transfer_to_human` | autônomos | `ConversationUseCases#handoffToHumans` | motivo do catálogo; resumo mascarado |
| `get_installments` | `pixInConversation` ligado, após identidade (rascunho: sempre) | `AgreementUseCases#search` | acordos `FORMALIZED`/`IN_PAYMENT` do devedor **e** credor (e carteira) da conversa; até 6 parcelas em aberto com referência `I1`…; valor = saldo da parcela |
| `generate_payment` | idem; nos autônomos só com o adaptador de `PaymentInstructionPort` | `PaymentInstructionPort#issueImmediatePix` (autônomos) | só referência `I…` devolvida nesta sessão; o modelo **nunca** informa valor; critérios do §5.1 no servidor; um pedido por turno; rascunho → só sugestão |

Argumentos inválidos, ferramenta indisponível ou acima do limite do turno → `REJECTED` sem chamar o módulo dono; recusa do módulo dono → `ERROR` com o código. A trilha guarda só os campos conhecidos dos argumentos.

**Formalização e atribuição** (`remuneracao-variavel.md`, `negociacao-e-alcadas.md` §10.7): `AUTONOMOUS` + perfil com `canFormalize` → o assistente formaliza (`ASSISTANT_AUTONOMOUS`, pool da equipe); `AUTONOMOUS_WITH_APPROVAL` ou `ASSISTENTE_INICIAL` → aceite registrado, mensagem `approvalMessage` e transferência `FORMALIZATION_APPROVAL`; o humano formaliza (`ASSISTANT_HANDOFF_CONFIRMED`); acima da alçada o humano adota ou renegocia (`ASSISTANT_HANDOFF_CONFIRMED`/`NEGOTIATED`). Copiloto não cria propostas: a proposta do humano marcada `copilot` é `HUMAN_COPILOT`.

### 5.1 Pix na conversa (ADR-0009)

Decisão do responsável: *"PIX na conversa: se a conversa foi amigável e a conciliação com o devedor tranquila, o Pix pode ser gerado pelo assistente IA."* A IA conversa e escolhe a parcela por referência; o servidor decide e o payment gera.

**Critérios** (`PixEligibility`, todos avaliados e gravados; a recusa usa o primeiro que falhou):

| # | Critério | Recusa | Transfere? |
| --- | --- | --- | --- |
| 1 | política com `pixInConversation.enabled` | `PIX_DISABLED` | não (a ferramenta nem é oferecida) |
| 2 | modo `AUTONOMOUS`/`AUTONOMOUS_WITH_APPROVAL` (gera) ou `SHADOW`/`COPILOT` (só sugere) | `MODE_NOT_ALLOWED` | não |
| 3 | identidade `VERIFIED` na sessão (rascunho: o operador confere) | `IDENTITY_NOT_VERIFIED` | não |
| 4 | acordo `FORMALIZED`/`IN_PAYMENT` (lido de novo no negotiation) | `NO_FORMALIZED_AGREEMENT` | não |
| 5 | acordo do mesmo devedor, credor e carteira da conversa | `OTHER_PARTY` | não |
| 6 | parcela/entrada com saldo em aberto (`OPEN`, `PARTIALLY_PAID`, `OVERDUE`) | `INSTALLMENT_NOT_OPEN` | não |
| 7 | modo com aprovação: acordo formalizado por humano (`HUMAN`, `HUMAN_COPILOT`, `ASSISTANT_HANDOFF_*`) | `NOT_FORMALIZED_BY_HUMAN` | sim |
| 8 | nenhum gatilho de conduta na sessão nos últimos `riskLookbackDays` (motivos com `HandoffReason#conversationRisk`: contestação, alegação de pagamento, fraude, falecimento, vulnerabilidade, advogado/Procon/processo, reclamação, agressividade, pedido de humano, opt-out, baixa confiança, identidade não confirmada, guardrail, gatilho da política, pedido do modelo), nenhum guardrail acionado e nenhum gatilho na mensagem atual (nem alerta do rascunho) | `CONVERSATION_RISK` | sim |
| 9 | nenhuma contestação `OPEN`/`UNDER_REVIEW` no caso do acordo ou da conversa | `OPEN_DISPUTE` | sim |
| 10 | pedido ou aceite explícito de pagar agora na mensagem (`HandoffDetector#explicitPaymentRequest`: "manda o Pix", "quero pagar agora", "aceito"…, sem negação nem adiamento) | `NO_EXPLICIT_REQUEST` | não (o modelo pede confirmação) |
| 11 | saldo da parcela ≤ `maxAmount` | `AMOUNT_ABOVE_LIMIT` | sim |
| 12 | Pix na conversa no dia (fuso de operação) < `maxPerConversationPerDay` (cancelados com o turno não contam) | `DAILY_LIMIT_REACHED` | sim |

Recusa do payment (sem conta Pix, provedor indisponível, parcela paga entre a leitura e a geração) → `PAYMENT_UNAVAILABLE`, com transferência. Limites operacionais (`TURN_LIMIT`, `COST_LIMIT`, falhas) e encaminhamentos do motor (`REQUIRES_APPROVAL`, `FORMALIZATION_APPROVAL`) não contam como risco. Recusa que transfere: turno `HANDED_OFF` com `PAYMENT_ASSISTANCE` (mensagem de transferência ao devedor; resumo com o código da recusa). Ao modelo só voltam os códigos dos critérios e o resultado — sem limites nem valores além do da parcela.

**Geração.** `PaymentInstructionPort#issueImmediatePix` como o ator do turno: `PIX_IMMEDIATE` (sem dados do pagador ao PSP), valor = saldo da parcela pelo motor, validade `expirationMinutes`, canal da conversa, chave de idempotência `assistant:pix:<turno>:<parcela>`. O payment devolve instrução aberta igual (mesma parcela e valor), que é **reutilizada**; reutilizada com menos de 10 min de validade é cancelada e substituída. O valor e a data da validade entram nos fatos permitidos do turno (a resposta pode citá-los).

**Fim do turno.** Só um turno `REPLIED` libera a entrega; qualquer outro resultado (guardrail, transferência, porteiro, falha) cancela a instrução criada no turno (`settle`; uma reutilizada, criada antes por outro canal, é mantida) e o pedido fica `CANCELLED`. O pedido (`assistant.pix_request`) é gravado com o turno e agenda `assistant.pix_delivery`.

**Entrega** (`AssistantPixDelivery`, sob o lock da conversa, como o ator `ASSISTANT` da política do pedido; reagendada por `PaymentInstructionStatusChanged`):

| Instrução | Ação |
| --- | --- |
| `PENDING` | aguarda o evento de ativação |
| `ACTIVE` | `PixMessage`: BR Code com estrutura EMV e CRC16 válidos, **dinâmico** (URL do PSP, sem chave no código), moeda 986/BR, nome do recebedor (campo 59), valor do campo 54 (se houver) e da instrução iguais ao saldo da parcela; texto fixo (parcela/entrada/à vista, valor, validade dd/mm/aaaa hh:mm, "Pix Copia e Cola", aviso para conferir o recebedor no app do banco e de que não pedimos pagamento para chave de pessoa física) passa pelo `ResponseGuard` com os únicos fatos valor, data e número da parcela. Envio por `MessageUseCases#send` (chave `assistant:pix:<pedido>`; porteiro de contato) → `DELIVERED`; porteiro negou → `NOT_DELIVERED` |
| `FAILED`/`EXPIRED`/`CANCELLED`, código inválido, validade vencida | `NOT_DELIVERED`, mensagem padrão (`fallbackMessage`) e transferência `PAYMENT_ASSISTANCE` |
| `PAID` antes da entrega | `NOT_DELIVERED` (`ALREADY_PAID`) e pagamento registrado |
| conversa já não está com o assistente | `NOT_DELIVERED` (`CONVERSATION_LEFT_ASSISTANT`); o atendente vê a instrução no acordo |

QR Code: o communication ainda não envia mídia, então vai só o "copia e cola" (pendência §11). **Conversão:** `PaymentInstructionStatusChanged` `PAID` grava `paid_at` em todos os pedidos da instrução (`assistant.pix_paid`, `assistant.pix.paid{within_24h}`); a atribuição do acordo no incentive não muda (o Pix não altera quem fechou).

**Rascunho (sombra/copiloto).** As mesmas ferramentas e critérios (identidade a cargo do operador); atendidos → pedido `SUGGESTED` e a justificativa do rascunho traz "Pix elegível: gere pela tela do acordo (parcela, valor)"; nada é gerado nem enviado.


## 6. Provedor de IA (`LanguageModelPort`)

**Provedor aprovado (25/10/2026, issue #12): Amazon Bedrock com modelos Claude.** Um provedor por instância: `app.assistant.bedrock.enabled` (Bedrock) **ou** `app.assistant.sandbox.enabled` (simulado, local/teste) — os dois juntos, o simulado no perfil `prod` ou a configuração do Bedrock inválida impedem a subida (`AssistantProviderGuard`, `BedrockConfiguration`). Sem nenhum, o turno com o assistente termina em `MODEL_UNAVAILABLE` e a conversa vai para humanos.

### 6.1 Contrato

`complete(ModelRequest) → ModelResponse`: prompt de sistema, contexto (JSON), mensagens (`USER`/`ASSISTANT`/`TOOL`), definições de ferramentas (nome, descrição, JSON Schema), `maxOutputTokens`, `timeout` (tempo total da chamada, inclusive a retentativa), metadados opacos (turno, assessoria) e **nível de modelo** (`ModelTier`: `STANDARD`/`ADVANCED`, §6.2) → texto, chamadas de ferramenta (`id`, nome, argumentos JSON), motivo de parada (`END_TURN`, `TOOL_USE`, `MAX_TOKENS`, `CONTENT_FILTERED`), uso (`Usage`: entrada sem cache, saída, lidos e gravados no cache de prompt), custo (valor, moeda), modelo efetivamente usado e latência. Falhas: `ModelTimeoutException`, `ModelUnavailableException` (transitórias) e `ModelRejectedException` (definitiva). Regras no Javadoc da porta. No turno, a entrada registrada (`input_tokens`, limite `maxTokensPerTurn`) é o prompt inteiro (com e sem cache); os tokens de cache vão à parte nos eventos (`cacheReadTokens`, `cacheWriteTokens`).

### 6.2 Nível de modelo por etapa (`ModelTierSelector`)

O servidor escolhe o nível **a cada chamada** — o modelo não escolhe o próprio nível:

| Política (`modelRouting`) | Nível |
| --- | --- |
| `BY_STAGE` (padrão; políticas gravadas antes da opção valem assim) | `ADVANCED` quando a intenção registrada no turno (ou, antes de o modelo registrá-la, a do turno anterior) é `NEGOTIATE`, `ACCEPT` ou `PAYMENT`, ou quando a etapa já tem ofertas, proposta ou parcelas devolvidas pelo motor (negociação, proposta, aceite e Pix); `STANDARD` no resto (saudação, identificação, saldo, dúvidas, promessa, retorno). Uma etapa nova (conversa devolvida ao assistente) zera a memória e volta ao padrão |
| `ADVANCED_ONLY` | `ADVANCED` em todas as chamadas — padrão de mercado para piloto (decisão 362) |

No Bedrock: `STANDARD` = **Claude Haiku 5.5** e `ADVANCED` = **Claude Sonnet 5.5** (`app.assistant.bedrock.standard|advanced.model-id`). Métrica `assistant.model.routing{tier,reason}`; eventos de turno com `modelTier` (maior nível do turno) e `advancedModelCalls`.

### 6.3 Prompts

Recursos `classpath:assistant/prompts/<código>-v<versão>.txt` (primeira linha `# descrição`), em português, com instruções de transparência, identidade, "a IA conversa, o motor decide", conduta e transferência; sem segredos. Versão nunca editada (nova versão = novo arquivo); o SHA-256 vai para cada turno; a política referencia código e versão e a troca fica na auditoria da publicação. Atual: `negociacao` v2 (v1 + orientação do Pix na conversa; padrão das políticas novas — as publicadas com v1 continuam com ela).

### 6.4 Simulador (local/teste)

`SandboxLanguageModel` (`app.assistant.sandbox.enabled=true`, recusado no `prod` e junto com o Bedrock por `AssistantProviderGuard`): roteiro determinístico por intenção e palavras-chave, usando as ferramentas como um modelo real (sempre `register_intent` primeiro): saldo → `get_debt_summary`; negociar/à vista/"Nx"/desconto → `simulate_options`; aceito (opcional `O2`) → `propose_offer` + `register_acceptance`; Pix/pagar agora → `get_installments` + `generate_payment` (opcional `I2`); identidade pendente → pede os dados. Marcadores de teste: `[sandbox:confuso]`, `[sandbox:sinal:<MOTIVO>]`, `[sandbox:valor-inventado]`, `[sandbox:timeout]`, `[sandbox:indisponivel]`, `[sandbox:transferir]`, `[sandbox:nivel:N]`. Tokens por caracteres/4; custo pelas tarifas simuladas (`app.assistant.sandbox.cost-per-1k-*`). Nenhuma rede. Ignora o nível de modelo.

### 6.5 Amazon Bedrock (`infrastructure/provider/bedrock`)

`BedrockLanguageModel` sobre a **Converse API** (desde 26/10/2026 o cliente fica no kernel, `shared.ai.bedrock.BedrockConverseModel`, e o contrato neutro em `shared.ai.LanguageModel`/`ModelTier`; `LanguageModelPort` estende o contrato — sem mudança de comportamento, métricas `assistant.bedrock.*` mantidas; o chat de suporte usa o mesmo cliente, ver `chat-suporte.md` §6) (`bedrock-runtime`, SDK `software.amazon.awssdk:bedrockruntime` pelo BOM), criado por `BedrockConfiguration` quando `app.assistant.bedrock.enabled=true`.

| Tema | Como |
| --- | --- |
| Nível → modelo | `ModelTier` → `app.assistant.bedrock.standard|advanced.model-id` (perfil de inferência de sistema, ex.: `us.anthropic.claude-haiku-5-5`; os modelos Claude 5.5 não aceitam invocação sob demanda pelo id base) |
| Tradução (`ConverseTranslator`) | `system` = [prompt, `cachePoint`, cabeçalho fixo + contexto JSON] — o contexto nunca entra nas mensagens do devedor; `toolConfig.tools` = `toolSpec` (nome, descrição, JSON Schema) + `cachePoint`; `USER` → texto; `ASSISTANT` → texto + `toolUse` (id da chamada); `TOOL` → `toolResult` com o `toolUseId` (status `error` quando o resultado traz `error`); mensagens seguidas do mesmo papel unidas (a API exige alternância); mensagens do assistente antes do primeiro texto do devedor ou depois do último (pré-preenchimento, recusado pelos Claude atuais) omitidas; `maxOutputTokens` → `inferenceConfig.maxTokens` (sempre explícito: sem ele o Bedrock reserva cota pelo máximo do modelo); metadados opacos → `requestMetadata` (só os que casam com o padrão aceito; nunca dado pessoal) |
| Cache de prompt | `cachePoint` depois das ferramentas e do prompt de sistema (prefixo estável); o contexto, que muda a cada chamada, fica depois do ponto. Validade `app.assistant.bedrock.cache-ttl` (`5m` padrão; `1h` exige o preço de gravação correspondente). Haiku 5.5 e Sonnet 5.5: cache explícito, mínimo de 512 tokens por ponto, até 4 pontos; abaixo do mínimo o ponto é ignorado sem erro. Desligável por nível (`prompt-caching: false`) |
| Prazo | `request.timeout()` (política, 2–60 s) é o prazo **total**: cada tentativa leva `apiCallTimeout`/`apiCallAttemptTimeout` com o prazo restante; conexão 3 s; socket 65 s (teto técnico); SDK sem retentativas próprias (`AwsRetryStrategy.doNotRetry()`) |
| Retentativa | no máximo **uma**, para `ThrottlingException`, `ServiceUnavailableException`, `InternalServerException`, `ModelNotReadyException` e outros 429/5xx, depois de `retry-backoff` (400 ms) e só se ainda houver prazo |
| Erros | tempo esgotado (`ApiCallTimeout`/`ApiCallAttemptTimeout`, `ModelTimeoutException` 408, socket) → `ModelTimeoutException`; limite de taxa, 5xx, `ModelErrorException`, rede, saída malformada (`malformed_*`) → `ModelUnavailableException`; `ValidationException`, `AccessDeniedException` (inclusive modelo não habilitado), `ResourceNotFoundException`, `ServiceQuotaExceededException`, outros 4xx e `model_context_window_exceeded` → `ModelRejectedException`; `content_filtered`/`guardrail_intervened` → `StopReason.CONTENT_FILTERED` (o orquestrador transfere com o guardrail `PROVIDER_FILTERED`). Em todos os casos o turno transfere para humanos |
| Resposta | texto, `toolUse` → `ToolCall` (ids do Bedrock), `Usage` com `cacheReadInputTokens`/`cacheWriteInputTokens`, custo pela tabela (`BedrockPricing`), modelo configurado do nível e latência total (inclusive retentativa) |
| Custo | `app.assistant.bedrock.<nível>.pricing`: entrada, saída, leitura e gravação de cache por milhão de tokens e, opcionalmente, limiar de contexto longo com os preços da faixa (Haiku 5.5: prompt — entrada com e sem cache — acima de 100 mil tokens cobra todos os itens pela faixa longa). **Os preços do `application.yml` são os da API da Anthropic e precisam ser conferidos na conta** (no Bedrock podem diferir; a Anthropic informa acréscimo de 10% nos perfis regionais/geográficos sobre o global). Moeda `currency` (USD) |
| Credenciais | cadeia padrão do SDK (`DefaultCredentialsProvider`) = papel IAM da instância EC2; nada em arquivo ou variável |
| Observabilidade | métricas `assistant.bedrock.*` e `integration.calls{provider=bedrock,operation=converse}` (`logs-e-metricas.md` §5); logs técnicos só com modelo, nível, resultado, código e id da requisição da AWS (a mensagem de erro da AWS vai mascarada e truncada) — nunca conteúdo da conversa |

Configuração (`app.assistant.bedrock.*`, variáveis `ASSISTANT_BEDROCK_*` nas instâncias): `enabled`, `region`, `international-transfer-approved`, `endpoint-override` (VPC endpoint), `connect-timeout`, `socket-timeout`, `max-connections`, `retry-backoff`, `currency`, `cache-ttl`, `standard|advanced.{model-id, prompt-caching, pricing.*}`. Validada na subida (`BedrockProperties#validate`): região, ids, todos os preços (e os da faixa longa quando houver limiar), TTL, moeda e a aprovação da transferência internacional.

### 6.6 Residência de dados **[revisar]**

Pesquisa nos cartões de modelo da AWS e na documentação da Anthropic (25/10/2026): **Claude Haiku 5.5 e Claude Sonnet 5.5 não estão disponíveis em `sa-east-1` nem na própria região nem por perfil geográfico** — São Paulo só tem o perfil **global** (`global.anthropic.claude-*-5-5`). Os perfis geográficos existentes são US, EU, AU e JP (o Haiku também JP). Não há perfil "América do Sul". Portanto **nenhuma opção processa os dados do turno no Brasil**.

| Opção | Configuração | Onde o modelo processa | Observações |
| --- | --- | --- | --- |
| A — **padrão configurado (mais conservadora)** | `region: us-east-1`, `us.anthropic.claude-haiku-5-5` / `us.anthropic.claude-sonnet-5-5` | somente regiões dos EUA listadas no perfil (us-east-1, us-east-2, us-west-1, us-west-2…) | jurisdição única e declarável no contrato; o pedido sai do Brasil até o endpoint da Virgínia (pela internet via NAT, HTTPS); a Anthropic informa acréscimo de ~10% sobre o global |
| B | `region: sa-east-1`, `global.anthropic.claude-*` | **qualquer região comercial** da AWS, escolhida dinamicamente | endpoint e CloudTrail em São Paulo, mas o processamento pode ocorrer em qualquer país; mais barato e com mais capacidade; o IAM só libera o global quando configurado (senão nega `aws:RequestedRegion = unspecified`) |
| C | perfil `eu.*`, `au.*` ou `jp.*` | regiões do bloco escolhido | sem vantagem para o Brasil; só se um contrato exigir esse bloco |

O que acontece com os dados em qualquer opção: o turno envia ao Bedrock o prompt, o **contexto minimizado** (primeiro nome, situação e valores do motor — nunca documento, contato ou endereço) e o texto do devedor já mascarado (`PersonalDataRedactor`); o tráfego entre regiões fica na rede da AWS, cifrado; o CloudTrail registra a chamada na região de origem com a região que processou (`additionalEventData.inferenceRegion`); os provedores dos modelos não acessam prompts nem respostas (contas de implantação operadas pelo Bedrock); o **registro de invocação do Bedrock deve permanecer desligado** na conta (se ligado, grava prompts e respostas completos em CloudWatch/S3). Processar fora do Brasil é **transferência internacional** (LGPD art. 33): exige base contratual (cláusulas-padrão da ANPD no contrato com a AWS/termos dos modelos de terceiros) e transparência no aviso de privacidade aos devedores e credores. **A escolha entre A e B e a aprovação da transferência são do responsável**: por isso `international-transfer-approved` é falso por padrão e a aplicação recusa subir com o Bedrock até que seja ligado (e o Terraform recusa `assistant_bedrock_enabled` sem `bedrock_international_transfer_approved`).

Fontes: [Claude Haiku 5.5 — Amazon Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-5-5.html), [Claude Sonnet 5.5 — Amazon Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-5-5.html), [Claude in Amazon Bedrock (Anthropic)](https://platform.claude.com/docs/build-with-claude/claude-in-amazon-bedrock), [Claude on Amazon Bedrock (Anthropic)](https://platform.claude.com/docs/en/build-with-claude/claude-on-amazon-bedrock), [Cross-Region inference](https://docs.aws.eu/bedrock/latest/userguide/cross-region-inference.html), [Prerequisites for inference profiles](https://docs.aws.amazon.com/bedrock/latest/userguide/inference-profiles-prereq.md), [Data protection — Amazon Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/data-protection.html), [Converse API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html).

### 6.7 Roteiro de homologação (quando houver conta)

Pré-requisitos: modelos habilitados na conta (`infraestrutura-aws.md` §8), aprovação da transferência internacional registrada, `terraform apply` de homologação com `assistant_bedrock_enabled = true` e `bedrock_international_transfer_approved = true`, registro de invocação do Bedrock **desligado**.

1. **Subida**: a instância sobe e o log técnico traz `Provedor de IA do assistente: Amazon Bedrock` e `Cliente do Amazon Bedrock configurado` com os dois modelos; sem a aprovação, a subida falha com a mensagem de `international-transfer-approved` (teste negativo).
2. **Fumaça pela CLI** (Session Manager na instância, mesmo papel): `aws bedrock-runtime converse --region us-east-1 --model-id us.anthropic.claude-haiku-5-5 --messages '[{"role":"user","content":[{"text":"Responda apenas: ok"}]}]' --inference-config '{"maxTokens":20}'` e o mesmo com `us.anthropic.claude-sonnet-5-5`. `AccessDeniedException` = acesso ao modelo não habilitado ou política IAM; `ValidationException … on-demand throughput isn't supported` = id base no lugar do perfil.
3. **Negação do global** (opção A): a mesma chamada com `global.anthropic.claude-haiku-5-5` deve dar `AccessDeniedException`.
4. **Política sombra** numa carteira de teste, devedor fictício, com `modelRouting = BY_STAGE`: saudação, confirmação de identidade, "quanto devo?", "quero parcelar em 3x", "aceito O1", "manda o Pix". Conferir no detalhe do turno e nos eventos: `modelTier`/`advancedModelCalls` (saldo no padrão; negociação, aceite e Pix no avançado), ferramentas chamadas na ordem esperada, nenhum valor fora do motor (guardrails), resposta ≤ 600 caracteres.
5. **Cache**: na segunda chamada do mesmo turno, `assistant.bedrock.tokens{type=cache_read}` > 0 (o prefixo ferramentas + prompt passa de 512 tokens); na primeira de cada 5 min, `cache_write` > 0.
6. **Custo**: comparar `assistant.cost`/`assistant.bedrock.cost` de um dia com o Cost Explorer (serviço do provedor do modelo no AWS Marketplace) e corrigir `pricing` se divergir.
7. **Erros**: `limits.timeoutSeconds = 2` e uma pergunta longa → `MODEL_TIMEOUT` e transferência; trocar temporariamente o `advanced.model-id` por um perfil inexistente → `MODEL_REJECTED` (`ResourceNotFoundException`/`ValidationException`) e transferência; nenhum caso deixa o devedor sem resposta.
8. **Conversas de referência** (§8) com `ADVANCED_ONLY` e depois `BY_STAGE`: nenhuma condição fora da política; comparar qualidade e custo por conversa antes de liberar `BY_STAGE` em produção.
9. **Logs**: Logs Insights no grupo técnico por `provider = "bedrock"`: só códigos, modelos e ids — nenhum texto de conversa.

## 7. Trilha e retenção

`assistant.assistant_turn` (append-only: gatilho `turn_purge_only` + privilégio de coluna): sessão, conversa, credor, carteira, devedor, caso, versão da política, prompt (código, versão, SHA-256), modo, etapa e número do turno, mensagem de origem e ids das mensagens tratadas, **entrada** (cifrada), **contexto enviado** (cifrado, SHA-256, versão do esquema — o da última chamada), **resposta** (cifrada), resultado, motivo e origem da transferência, guardrails, intenção e confiança, provedor e modelo, chamadas ao modelo e ferramentas, tokens, custo, latência do modelo e total, mensagem/rascunho/proposta/acordo gerados, código de erro. `assistant.turn_tool_call` (append-only): sequência, ferramenta, argumentos e resultado (só referências, ids, códigos e valores do motor), resultado da validação, referência ao agregado, duração.

`assistant.pix_request` (avaliação imutável por gatilho; só entrega e pagamento mudam; sem remoção): turno, conversa, credor, carteira, devedor, caso, política, modo, decisão, código da recusa, critérios (`[{criterion, passed, detail}]`), acordo, parcela, valor, instrução, reutilização, validade, situação/código/mensagem da entrega e data do pagamento — nunca o "copia e cola" nem texto do devedor.

Retenção: o job diário `assistant.retention` descarta entrada, contexto e resposta dos turnos mais antigos que a política `MESSAGE_CONTENT` do compliance (a mesma do conteúdo das mensagens), mantendo metadados, hash e consumo. Anonimização do devedor (`DebtorAnonymized`): descarte imediato do conteúdo dos turnos do devedor e do comentário das revisões.

## 8. Avaliação

- **Amostragem**: turnos com guardrail ou falha sempre; demais pela `reviewSampleRate` da política, de forma determinística pelo id do turno. Revisão (`ASSISTANT_REVIEW`): nota 1–5, veredito (`ADEQUATE`, `NEEDS_IMPROVEMENT`, `OUT_OF_POLICY` — exige comentário), etiquetas (`TONE`, `ACCURACY`, `TRANSPARENCY`, `UNNECESSARY_HANDOFF`, `MISSED_HANDOFF`, `PRIVACY`, `LEGAL_RISK`, `OFFER_QUALITY`); comentário cifrado; uma decisão por turno (gatilho); quem publicou a política do turno não o avalia.
- **Qualidade** (`GET /quality?from&to&creditorId&portfolioId`): turnos e conversas, Pix na conversa (gerados, entregues, recusados por motivo, sugeridos, pagos, pagos em até 24 h e conversão gerado → pago), resultados, transferências por motivo e taxa, guardrails por código, acordos por origem e por grupo (assistente: `ASSISTANT_*`; humanos: `HUMAN`, `HUMAN_COPILOT`), **quebra precoce** (quebrados em até 60 dias) por grupo, reclamações (transferências por Procon/advogado/processo, reclamação ou opt-out), custo, custo por acordo com participação do assistente, tokens, latência média, revisões (avaliadas, nota média, fora da política, pendentes). Fatos de acordo pelos eventos `AgreementAttributionRecorded`/`AgreementStatusChanged` (ordem qualquer).
- **Conversas de referência** (pendente — §11): conjunto versionado (desconto acima do limite, contestação, terceiro se passando pelo titular, manipulação "ignore suas regras") executado contra cada mudança de política, prompt ou modelo; nenhum caso pode resultar em condição fora da política.

## 9. Eventos, métricas e auditoria

- Consumidos: `MessageStatusChanged` (`assistant.message-received`), `AgreementAttributionRecorded`, `AgreementStatusChanged`, `DebtorAnonymized`, `PaymentInstructionStatusChanged` (`assistant.pix-instruction`). Não publica eventos de integração novos: a transferência usa `ConversationUseCases#handoffToHumans` (o communication registra `assistant.handoff`) ou o `HandoffRequested` do negotiation.
- Negócio: `assistant.replied`, `assistant.handoff_triggered`, `assistant.draft_proposed`, `assistant.approval_requested`, `assistant.guardrail_blocked`, `assistant.reply_blocked`, `assistant.turn_failed`, `assistant.policy_retired`, `assistant.portfolio_disabled`, `assistant.review_recorded`, `assistant.pix_issued`, `assistant.pix_denied`, `assistant.pix_suggested`, `assistant.pix_delivery`, `assistant.pix_paid`, `policy.published` (`policyKind = assistant`) — atributos em `logs-e-metricas.md` §3.9.
- Métricas `assistant.*` — `logs-e-metricas.md` §5 (alarmes sugeridos: `assistant.model.calls{outcome≠ok}`, `assistant.turns{outcome=FAILED}`, `assistant.turns.recovered`, `assistant.jobs.failures`).
- Auditoria: políticas (criação, alteração com antes/depois, publicação com versão, prompt e alçada, aposentadoria, desligamento de carteira) e revisões. As ações nos outros módulos são auditadas por eles com `actorType = ASSISTANT` e o id da versão da política.

## 10. API REST (`/api/v1/assistant`)

| Recurso | Métodos | Permissão |
| --- | --- | --- |
| `/policies` · `/policies/{id}` · `/policies/{id}/versions` · `/policies/effective?creditorId&portfolioId` | `GET` | `ASSISTANT_MANAGE` ou `ASSISTANT_REVIEW` |
| `/policies` · `/policies/{id}` · `/policies/{id}/versions` · `/policies/{id}/publish` · `/policies/{id}/retire` · `/policies/template?mode` | `POST`, `PUT`, `GET` | `ASSISTANT_MANAGE` (publicar com alçada: também `AUTHORITY_MANAGE`) |
| `/portfolios/{portfolioId}/disable` | `POST` | `ASSISTANT_MANAGE` |
| `/prompts` | `GET` | `ASSISTANT_MANAGE` ou `ASSISTANT_REVIEW` |
| `/conversations/{id}/session` · `/conversations/{id}/turns` · `/turns/{id}` | `GET` | `ASSISTANT_REVIEW` (detalhe registra `SENSITIVE_DATA_VIEWED`; traz `pixRequests` com os critérios avaliados) |
| `/reviews?status&creditorId` · `/reviews/{id}` | `GET` · `POST` | `ASSISTANT_REVIEW` |
| `/quality?from&to&creditorId&portfolioId` | `GET` | `ASSISTANT_REVIEW` |

Escopo por credor em tudo (404 leitura, 403 comando, listagens filtradas); política da assessoria exige todos os credores. RLS forçado em todas as tabelas. Permissões: `ASSISTANT_MANAGE` (admin, gestor) e `ASSISTANT_REVIEW` (admin, gestor, supervisor) — `identity/V0205`.

## 11. Pendências

- ~~Provedor de IA~~ (25/10/2026): Amazon Bedrock aprovado e implementado (§6.5). **[revisar]** Pendentes do responsável: escolha da opção de residência e aprovação da transferência internacional (§6.6), conferência dos preços na conta, homologação (§6.7) e, se desejado, VPC endpoint de interface do `bedrock-runtime` (só serve a região da própria VPC, isto é, a opção B).
- **Pix na conversa**: QR Code como imagem quando o communication enviar mídia (hoje só o "copia e cola"); Pix com vencimento (`cobv`) para parcela futura não é usado pelo assistente (envia dados do pagador ao PSP).
- **Conversas de referência** versionadas e execução automática por mudança de política/prompt/modelo (§8).
- `compliance.AttemptOrigin` sem `ASSISTANT` (as tentativas do assistente aparecem como `SYSTEM`; o autor real está na mensagem e na trilha).
- E-mail/SMS (dependem de templates por intenção) e portal (módulo `portal`).
- ~~Telas~~ (24/10/2026): políticas por escopo (editor com validação contínua, publicação, nova versão, aposentadoria, desligamento de carteira com impacto), fila de revisão com segregação, detalhe do turno e painel de qualidade (`/assistente`).
- Validação jurídica do roteiro, da verificação de identidade e dos gatilhos (especificação §9).
- Experimentos A/B entre versões de política (F25, módulo campaign).
