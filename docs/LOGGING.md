# Logs e métricas

## 1. Três fluxos separados

| Fluxo | Como gerar | Arquivo / destino | Persistência adicional |
| --- | --- | --- | --- |
| Técnico | logger da classe (`LoggerFactory.getLogger(X.class)`) | `technical.log` → `/gestor-cobranca/<env>/technical` | — |
| Negócio | `BusinessEventLogger.log(BusinessEvent...)` | `business.log` → `/gestor-cobranca/<env>/business` | `shared.business_event` (mesma transação) |
| Acesso | `AccessLogger.log(AccessEvent...)` | `access.log` → `/gestor-cobranca/<env>/access` | `shared.access_log` (transação própria) |

Os appenders são independentes (`additivity=false`): eventos de negócio e de acesso nunca aparecem no log técnico e vice-versa. Todos são JSON de uma linha (`JsonLogFormatter`), prontos para o CloudWatch Logs Insights.

## 2. Campos

Comuns: `@timestamp`, `level`, `stream`, `logger`, `thread`, `service`, `env`, `instance`, `message`, e do contexto (MDC) `requestId`, `tenantId`, `actorId`, `sessionId`, `clientIp`, `userAgent`, `route`, `process` (jobs).

- **Técnico, por requisição** (`logger=http.request`): `http.method`, `http.route` (padrão, sem ids), `http.status`, `durationMs`, `slow=true` acima de 2 s.
- **Negócio**: `eventType`, `eventId`, `occurredAt`, `creditorId`, `portfolioId`, `debtorId`, `caseId`, `referenceType`, `referenceId`, `channel`, `amount`, `durationMs`, `attr.*`.
- **Acesso**: `eventType`, `success`, `actorType`, `principal` (mascarado), `detail.*`.

Dados pessoais são mascarados automaticamente na mensagem; pares chave-valor devem conter apenas ids e valores não pessoais.

## 3. Catálogo de eventos de negócio

Definido em `shared/logging/business/BusinessEventTypes`. Os nomes são contrato com painéis e consultas — não renomear. Ao criar um tipo, adicione-o à tabela abaixo.

| Domínio | Eventos |
| --- | --- |
| Estrutura | `creditor.created`, `portfolio.created`, `portfolio.status_changed`, `policy.published` |
| Ingestão | `import.started`, `import.completed`, `import.failed` |
| Dívida | `debtor.consolidated`, `title.status_changed` |
| Operação | `case.opened`, `case.assigned`, `case.closed`, `interaction.recorded`, `contact.effective`, `callback.scheduled`, `promise.registered`, `dispute.opened`, `dispute.resolved` |
| Negociação | `negotiation.simulated`, `proposal.created`, `proposal.approval_requested`, `proposal.approved`, `proposal.rejected`, `agreement.formalized`, `agreement.settled`, `agreement.broken`, `agreement.renegotiated`, `agreement.cancelled`, `installment.overdue` |
| Pagamentos | `payment_instruction.created`, `payment.informed`, `payment.confirmed`, `payment.reconciled`, `payment.divergence`, `payment.reversed` |
| Prestação de contas | `commission.accrued`, `settlement.closed` |
| Comunicação | `message.sent`, `message.delivered`, `message.read`, `message.failed`, `message.replied`, `message.blocked` |
| Campanhas | `campaign.activated`, `campaign.paused`, `campaign.step_executed`, `campaign.step_skipped`, `experiment.variant_assigned` |
| Compliance | `contact.opt_out`, `contact.restricted`, `contact.restriction_revoked`, `contact.denied`, `data_subject_request.opened`, `data_subject_request.completed`, `retention.run_completed` |
| Outros | `call.completed`, `portal.accessed`, `portal.agreement_formalized`, `score.calculated`, `assistant.replied`, `assistant.handoff` |

### 3.1 Atributos dos eventos de compliance

| Evento | Quando | Atributos |
| --- | --- | --- |
| `contact.opt_out` / `contact.restricted` | restrição de contato criada (opt-out ou demais tipos) | `type`, `source`, `temporary`, `contactSpecific`; `channel` |
| `contact.restriction_revoked` | restrição revogada | `type`, `activeDays` |
| `contact.denied` | `ContactGate.authorizeAndRecord` negou um contato | `origin`, `reasons` (lista separada por vírgula); `channel` |
| `data_subject_request.opened` / `.completed` | solicitação de titular aberta / encerrada (concluída ou recusada) | `type`, `dueDays`; no encerramento `outcome`, `rejectionCause`, `withinDeadline`, `durationMs` |
| `retention.run_completed` | execução de retenção concluída (simulada ou efetiva) | `category`, `trigger`, `dryRun`, `candidates`, `eligible`, `processed`, `retained`, `failed` |

## 4. Consultas úteis (CloudWatch Logs Insights)

```
# p95 de latência por rota (técnico)
filter logger = "http.request"
| stats pct(durationMs, 95) as p95, count(*) as req by `http.route`
| sort p95 desc

# erros por tenant (técnico)
filter level = "ERROR" | stats count(*) by tenantId, logger

# funil de negociação por credor (negócio)
filter eventType in ["proposal.created", "agreement.formalized", "payment.confirmed"]
| stats count(*) by creditorId, eventType

# valor recuperado por operador no dia (negócio)
filter eventType = "payment.confirmed"
| stats sum(amount) by actorId, bin(1d)

# tentativas de login negadas por IP (acesso)
filter eventType in ["LOGIN_FAILURE", "LOGIN_BLOCKED"]
| stats count(*) as falhas by clientIp | sort falhas desc
```

## 5. Métricas (Micrometer → CloudWatch, namespace `GestorCobranca`)

| Métrica | Uso |
| --- | --- |
| `http.server.requests` (percentis, SLO 200 ms/500 ms/1 s/2 s) | latência e erros por rota |
| `hikaricp.*`, `jvm.*`, `process.*` | saúde do pool e da JVM |
| `business.events{type}` | volume de fatos de negócio |
| `access.events{type,success}`, `access.log.write_failures` | segurança e integridade do log de acesso |
| `jobs.pending`, `jobs.dead`, `jobs.duration{type,outcome}`, `jobs.executed`, `jobs.recovered` | fila de trabalho |
| `events.publications.incomplete`, `executor.*{name=events}` | eventos de integração |
| `audit.sealed`, `audit.seal.failures` | selagem da auditoria |
| `storage.operation{operation,category,outcome}` | S3 |
| `mail.sent{type,outcome}` | e-mail |
| `integration.calls{provider,operation,outcome}` | provedores externos (convenção para os adaptadores) |
| `compliance.contact_gate.decisions{operation,outcome}`, `compliance.contact_gate.denials{operation,reason}` | porteiro de contato (consultas e autorizações, motivos de negação) |
| `compliance.dsr.overdue{type}` | solicitações de titulares que passaram do prazo (alarme) |
| `compliance.restrictions.expired`, `compliance.retention.runs{category,dryRun,outcome}`, `compliance.retention.debtors{outcome}` | fim de vigência de restrições e execuções de retenção |
| `errors.unexpected{type}`, `errors.optimistic_lock`, `errors.data_integrity` | qualidade |
