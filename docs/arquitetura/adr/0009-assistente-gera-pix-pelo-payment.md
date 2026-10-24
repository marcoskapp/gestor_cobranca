# ADR-0009 — O assistente gera o Pix da conversa pela interface pública do payment (`assistant → payment`)

**Situação.** Aceita (24/10/2026). Substitui a decisão 135 de `docs/negocio/decisoes-autonomas.md` ("ferramenta de Pix atrás de `PaymentInstructionPort` sem adaptador").

**Contexto.** O módulo `assistant` tinha a ferramenta de Pix (`generate_payment`) só como porta de saída (`PaymentInstructionPort`), sem adaptador, porque o grafo de dependências (`visao-geral.md` §2.1) não permitia `assistant → payment`. O responsável pelo produto decidiu: *"PIX na conversa: se a conversa foi amigável e a conciliação com o devedor tranquila, o Pix pode ser gerado pelo assistente IA."* Gerar um Pix exige o que só o `payment` tem: conta Pix do credor/assessoria, credenciais do PSP, registro da cobrança, idempotência, reutilização de instrução aberta e conciliação. O `payment` não depende do `assistant` (depende de `negotiation`, `debt`, `organization` e `shared`), então a nova aresta não cria ciclo.

**Decisão.**

1. **Grafo:** `assistant → payment`, **somente** a interface nomeada `usecases` (`payment.application.port.in`), declarada em `assistant/package-info.java` (`"payment :: usecases"`) e verificada por `ModularityTest`/`ArchitectureTest`. Nenhuma entidade, repositório ou serviço do payment é visível ao assistant; o payment continua sem conhecer o assistant.
2. **Adaptador:** `assistant.infrastructure.payment.PaymentModulePixInstructions implements PaymentInstructionPort` sobre `PaymentInstructionUseCases` (`create`, `get`, `cancel`). Sempre **Pix imediato** (`PIX_IMMEDIATE` — cobrança `cob`, que não envia dados do pagador ao PSP), com valor explícito igual ao saldo da parcela calculado pelo motor, chave de idempotência por turno e parcela; permissão (`PAYMENT_INSTRUCTION_CREATE`/`PAYMENT_VIEW`), escopo por credor, conta Pix, reutilização de instrução aberta igual e RLS continuam no payment. Executa como o ator `ASSISTANT` do turno, que só recebe essas permissões quando a política liga `pixInConversation`.
3. **Assincronia do PSP:** o registro da cobrança é assíncrono (instrução `PENDING` → `ACTIVE`). O payment passa a publicar o evento de integração `shared.events.payment.PaymentInstructionStatusChanged` (só ids, enums e valores — nunca o "copia e cola") em toda mudança de situação da instrução; o assistant o consome para entregar o código quando a cobrança fica ativa e para medir a conversão (pago). O conteúdo é lido por `PaymentInstructionUseCases#get`, com a permissão e o escopo do ator.
4. **Critérios no servidor:** o modelo nunca decide sozinho nem informa valor; a elegibilidade ("conversa amigável e conciliação tranquila") é avaliada por regras objetivas no assistant (`PixEligibility`, `assistente.md` §5.1), configuráveis por política e desligadas por padrão.

**Alternativas consideradas.**

- **Evento assíncrono** (`PixRequested` publicado pelo assistant e consumido pelo payment): mantém o grafo, mas o turno não saberia se a cobrança foi aceita (conta Pix ausente, parcela já paga, valor acima do saldo) — o assistente responderia sem a confirmação e a recusa chegaria depois, fora da conversa; a validação de escopo e permissão ficaria implícita no consumidor (sem ator), e a idempotência e a correlação com o turno exigiriam um protocolo próprio. Rejeitada: mais peças, menos rastreável e pior para o devedor.
- **Porta no payment implementada pelo assistant** (inversão: `payment` declara `AssistantPixPort`): inverte a responsabilidade — o payment não tem por que conhecer conversas — e criaria a dependência `assistant → payment` de qualquer forma para implementar a porta. Rejeitada.
- **Manter sem adaptador** (só o humano gera Pix pela tela): contraria a decisão do responsável.

**Consequências.**

- O `assistant` passa a depender de seis módulos de negócio pela interface `usecases` (`negotiation`, `communication`, `collection`, `compliance`, `debt`, `payment`) além de `organization`/`shared`; qualquer nova aresta continua exigindo ADR.
- Chave estrangeira `assistant.pix_request.instruction_id → payment.payment_instruction` (na direção do grafo, permitida por `visao-geral.md` §8).
- O payment publica um evento novo; consumidores atuais não mudam. Outros módulos (portal, analytics) podem usá-lo no futuro sem nova dependência.
- Segurança: o assistant só obtém permissões de pagamento quando a política liga a opção; o código Pix nunca vai ao modelo, à trilha nem aos logs; a mensagem só sai com BR Code dinâmico válido (sem chave Pix no código) e a conta Pix do payment recusa chave de pessoa física (CPF).
- Testes: `ModularityTest` (grafo), `AssistantPixIT` (fluxo com PSP e modelo simulados), testes unitários de cada critério.
