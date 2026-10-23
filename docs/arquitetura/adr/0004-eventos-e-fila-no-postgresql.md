# ADR-0004 — Eventos de integração e fila de jobs no PostgreSQL

**Contexto.** Reações entre módulos (pagamento confirmado pausa a régua, por exemplo) e trabalhos assíncronos (envio de mensagens, importações) não podem se perder nem ser executados em duplicidade. Os serviços autorizados para o projeto não incluem fila gerenciada.

**Decisão.** Eventos de integração publicados na transação da operação e registrados pelo Spring Modulith (*event publication registry* em JPA), entregues após o commit e reprocessados se o consumidor falhar; consumidores idempotentes via `shared.consumed_event`. Fila de jobs em `shared.processing_job` com `FOR UPDATE SKIP LOCKED`, backoff exponencial e estado `DEAD`. Agendamentos com ShedLock.

**Consequências.** Garantias transacionais sem infraestrutura adicional. Limite de vazão de uma fila em banco — suficiente para as fases iniciais; a fila é uma porta (`JobQueue`) e pode migrar para Amazon SQS quando o volume justificar.
