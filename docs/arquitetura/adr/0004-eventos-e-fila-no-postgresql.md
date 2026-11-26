# ADR-0004 — Eventos de integração e fila de jobs no PostgreSQL

**Contexto.** Reações entre módulos (pagamento confirmado pausa a régua, por exemplo) e trabalhos assíncronos (envio de mensagens, importações) não podem se perder nem ser executados em duplicidade. Os serviços autorizados para o projeto não incluem fila gerenciada.

**Decisão.** Eventos de integração publicados na transação da operação e registrados pelo Spring Modulith (*event publication registry* em JPA), entregues após o commit e reprocessados se o consumidor falhar; consumidores idempotentes via `shared.consumed_event`. Fila de jobs em `shared.processing_job` com `FOR UPDATE SKIP LOCKED`, backoff exponencial e estado `DEAD`. Agendamentos com ShedLock.

**Consequências.** Garantias transacionais sem infraestrutura adicional. Limite de vazão de uma fila em banco — suficiente para as fases iniciais; a fila é uma porta (`JobQueue`) e pode migrar para Amazon SQS quando o volume justificar.

## Evolução dos eventos (adendo de 26/11/2026, H07 parte 2b)

Um evento publicado pode estar no registro de publicações (ou ser reprocessado) no formato antigo depois do deploy.
Por isso, ao acrescentar um campo a um record de `shared.events`:

- O campo novo é **anulável** e nulo quer dizer **"não informado"** (evento no formato anterior). O construtor
  compacto não troca o nulo por um valor padrão, e o construtor com a assinatura antiga passa nulo.
- Se o consumidor, antes do campo, deduzia a informação por outra fonte (consulta a outro módulo, por exemplo), ele
  **mantém essa dedução como reserva** para o nulo, enquanto puder haver publicação no formato antigo. Só depois que
  nenhum evento antigo puder ser entregue a reserva sai, numa mudança própria.
- Um teste de compatibilidade lê o JSON no formato antigo (ex.: `PaymentConfirmedCompatibilityTest`).

Primeiro caso: `PaymentConfirmed.receivedBy` e `AgreementPaymentRecorded.receivedBy` (quem recebeu o dinheiro). Com o
campo, a prestação de contas usa o evento; sem ele, a origem do pagamento externo, como antes (decisão 1630).
