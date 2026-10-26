# ADR-0010 — O negotiation declara a porta de pagamentos em revisão e o payment a implementa (`negotiation :: spi`)

**Situação.** Aceita (26/10/2026). Implementa a decisão do responsável que substitui a decisão 437 de `docs/negocio/decisoes-autonomas.md`; decisões 494–503.

**Contexto.** O responsável pelo produto decidiu: *"A quebra do acordo deve ser suspensa enquanto o pagamento do acordo estiver pendente de revisão."* A quebra é do `negotiation` (job diário, regra fixada na formalização); o pagamento externo informado acima do limite fica `INFORMED` no `payment` até a revisão por outra pessoa. O grafo (`visao-geral.md` §2.1) tem `payment → negotiation` e **proíbe** `negotiation → payment` (ciclo). Até aqui, reações no sentido contrário ao grafo eram feitas só por eventos de integração.

**Decisão.**

1. **Porta declarada pelo dono da regra:** o negotiation declara `negotiation.application.port.spi.PaymentReviewHolds` numa interface nomeada nova, `spi` (`@NamedInterface("spi")`), separada de `usecases` (o que o negotiation *oferece*) porque é o que ele *precisa* que outro módulo forneça. O contrato é neutro e mínimo: por acordo, os externos `INFORMED` com o acordo como destino (id, instante do registro, valor), as alocações confirmadas ainda não baixadas (em trânsito) e as revisões concluídas desde um instante (id, aprovado/recusado, quando).
2. **Implementação pelo payment:** `payment.application.service.PaymentReviewHoldsProvider` (o payment declara `"negotiation :: spi"` em `allowedDependencies`). Consulta de sistema, na transação de quem chama, sob a RLS do tenant corrente; sem dado pessoal.
3. **Consumo síncrono:** o job de quebra lê a porta com o acordo bloqueado (`FOR UPDATE`) e decide (suspender, manter, encerrar, quebrar). A suspensão é estado do negotiation (`negotiation.agreement_break_suspension`), com trilha e eventos de negócio.
4. **Fim na revisão:** ao aprovar/recusar, o payment chama `AgreementBreakSuspensionUseCases#reconcile` (interface `usecases`, direção já permitida) na mesma transação; `reconcile` relê a porta e só aplica o que a fonte determina (idempotente).
5. **Regra geral:** um módulo só implementa a `spi` de outro se já puder depender dele pelo grafo; a interface `spi` de um módulo nunca expõe entidades nem serviços.

**Alternativas consideradas.**

- **Projeção alimentada por eventos** (o payment publica `ExternalPaymentInformed`/`ExternalPaymentReviewed`; o negotiation mantém uma tabela de pendências por acordo): mantém o padrão "evento no sentido contrário", mas cria uma cópia que pode atrasar (o consumo é assíncrono — um pagamento informado minutos antes do job não seguraria a quebra), exige carga inicial dos pendentes já gravados e protocolo de ordem entre "informado" e "revisado". Rejeitada: menos confiável para uma regra que decide se um contrato é desfeito.
- **Dependência `negotiation → payment`:** ciclo no grafo. Rejeitada.
- **Porta em `shared`:** `shared` é kernel transversal; a regra é do negotiation. Rejeitada (o precedente `shared.pendingwork.PendingWorkSource` é transversal por natureza — vários módulos implementam e um agregador consome).

**Consequências.**

- Grafo: `payment → negotiation` passa a usar `usecases` **e** `spi`; continua acíclico (`ModularityTest`).
- O negotiation não sobe sem uma implementação da porta (o payment faz parte do mesmo artefato); testes de módulo isolado do negotiation precisariam de um dublê.
- A leitura é da fonte, na mesma transação: sem atraso, sem cópia, sem carga inicial; a corrida entre aprovação e baixa assíncrona é coberta pelas alocações em trânsito (a quebra espera a próxima execução).
- Testes: `PaymentBreakSuspensionIT` (fluxos completos, isolamento por assessoria e credor), `BreakSuspensionTest` (regras puras), `ModularityTest`/`ArchitectureTest`.
