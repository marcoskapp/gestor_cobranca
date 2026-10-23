# Convenções de desenvolvimento

Guia obrigatório para qualquer código novo. A implementação de referência são os módulos `organization` (CRUD com escopo por credor, eventos e auditoria) e `identity` (fluxos de segurança). Em caso de dúvida, copie o padrão deles. Regras de arquitetura estão em [ARCHITECTURE.md](../arquitetura/visao-geral.md) e são verificadas por `ArchitectureTest` e `ModularityTest`.

## 1. Estrutura de um módulo

```
br.com.gestorcobranca.<modulo>
├── package-info.java              @ApplicationModule(allowedDependencies = {"shared", "<outro> :: usecases"})
├── domain/model/                  entidades JPA (estendem TenantScopedEntity), enums de estado, value objects
├── domain/service/                regras puras (cálculos, políticas), sem Spring
├── domain/repository/             interfaces de repositório (Java puro + Page/Pageable)
├── application/port/in/           package-info com @NamedInterface("usecases"); interfaces de caso de uso + records
├── application/port/out/          portas para provedores externos, storage, leitura especializada
├── application/service/           implementações (@Service, @Transactional), jobs, listeners
├── infrastructure/persistence/    interfaces Spring Data que estendem o repositório de domínio; JDBC de leitura
├── infrastructure/provider/       clientes HTTP de provedores (RestClient)
├── infrastructure/config/         @Configuration, PublicEndpoint de webhooks
└── web/                           controllers + records de request
```

## 2. Domínio

- Entidade persistente estende `TenantScopedEntity` (ou `BaseEntity` só para dados globais). Id, versão, tenant e metadados de criação são automáticos.
- Construtor `protected` sem argumentos para o JPA; construtor público que valida invariantes.
- Sem setters públicos: métodos com nome de negócio (`formalize()`, `breakAgreement(reason)`), que validam e mudam o estado.
- Validação com `Guard.require(...)` (→ 422) e `Guard.requireState(...)` (→ 409). Códigos de erro estáveis: `<agregado>.<motivo>`.
- Dinheiro: `Money` (coluna `numeric(19,2)`), percentuais: `Percentage` (`numeric(9,4)`). Conversores aplicados automaticamente.
- Datas de negócio: `LocalDate` no fuso `TimeConfig.BUSINESS_ZONE`; instantes: `Instant` UTC. Nunca `Instant.now()` — injete `Clock`.
- JSON em entidade: campo `String` com `@JdbcTypeCode(SqlTypes.JSON)` e conversão via `JsonCodec`.
- Dados pessoais do devedor (documento, telefone, e-mail, endereço): `@Convert(converter = EncryptedStringConverter.class)` + coluna `*_hash` com `BlindIndex` para busca.
- Coleções simples: `@ElementCollection` com tabela filha protegida por RLS via linha pai (ver `V0200`).
- Status sempre `@Enumerated(EnumType.STRING)` com `check` correspondente no SQL. Um ciclo de vida por conceito (RB/5.2).

## 3. Repositórios

```java
// domínio
public interface AgreementRepository {
    Agreement save(Agreement a);
    Optional<Agreement> findById(UUID id);
    List<Agreement> findAllByIdIn(Collection<UUID> ids);   // nunca findAllById(Collection)
}
// infraestrutura (package-private)
interface AgreementJpaRepository extends JpaRepository<Agreement, UUID>, AgreementRepository { ... }
```

- Buscas dinâmicas: método `default` com `Specification` (ver `CreditorJpaRepository`).
- Operações financeiras concorrentes: `@Lock(PESSIMISTIC_WRITE)` em método `...ForUpdate`.
- Leitura agregada/relatórios: `NamedParameterJdbcTemplate` em `infrastructure/persistence`, exposta por porta `application/port/out`.

## 4. Casos de uso (application)

- Interface em `port/in` + records `XxxCommand`/`XxxView` aninhados ou no mesmo pacote. Views nunca expõem entidades.
- Serviço `@Service @Transactional` package-private, consultas com `@Transactional(readOnly = true)`.
- Escopo por credor **sempre**: leitura por id → `accessScope.requireVisible(recurso, id, creditorId)` (404); comandos → `accessScope.requireCreditor(creditorId)` (403); listagens → filtrar por `accessScope.restrictCreditors(...)`/`hasAllCreditors()`.
- Toda alteração relevante grava `AuditEntry` (antes/depois) na mesma transação.
- Fatos de negócio relevantes → `BusinessEventLogger.log(BusinessEvent.of(BusinessEventTypes.X)...)` com credor, carteira, devedor, caso, valor, canal.
- Reações de outros módulos → publicar evento de `shared.events.*` com `ApplicationEventPublisher` dentro da transação. Novos eventos: record em `shared/events/<modulo>/` (ids, `BigDecimal`, textos; nada pessoal).
- Consumidores: estender `TenantEventListener`, método `@Async(AsyncConfig.EVENT_EXECUTOR) @TransactionalEventListener`, corpo `handleOnce(evento, "<modulo>.<reacao>", () -> ...)`.
- Trabalho assíncrono/agendado por item: `JobQueue.enqueue(tipo, payload, quando, dedupKey)` + `JobHandler`. Varredura periódica: `@Scheduled` + `@SchedulerLock`, iterando `TenantDirectory.activeTenantIds()` com `TenantExecution`.
- Comandos externos repetíveis (API pública, registro de pagamento): `IdempotencyService`.
- Arquivos: `FileStorage` (chave sempre prefixada pelo tenant); download registra `AccessEventType.FILE_DOWNLOADED`.
- Visualização de dado pessoal sem máscara exige `DEBTOR_SENSITIVE_VIEW` e registra `SENSITIVE_DATA_VIEWED`.

## 5. Web

- Prefixo `ApiPaths.V1`; recursos no plural (`/agreements/{id}/installments`).
- `@PreAuthorize("hasAuthority('PERMISSAO')")` em todo endpoint. Permissões novas: `shared.security.Permission` + `DefaultRoles`.
- Request = record com Bean Validation (`@NotNull`, `@Size`, `@Pattern`), convertido em command. Resposta = View do caso de uso ou `PageResponse<View>` (`shared.paging`).
- Paginação: `PageQuery.of(page, size)` (máx. 200).
- Webhooks: `ApiPaths.WEBHOOKS + "/<provedor>/{routingKey}"`, declarados como bean `PublicEndpoint`, com validação de assinatura/segredo antes de qualquer processamento; assinatura inválida → `AccessEventType.WEBHOOK_REJECTED`.
- Erros: lance exceções de domínio; o `GlobalExceptionHandler` produz RFC 9457 com `code` e `requestId`.

## 6. Banco e migrations

- Arquivo em `db/migration/<modulo>/V<faixa>__<modulo>_<descricao>.sql` (faixas em ARCHITECTURE.md §8).
- Colunas de `BaseEntity`: `id uuid pk, tenant_id uuid not null references organization.tenant(id), version bigint, created_at, created_by, updated_at, updated_by`.
- `select shared.enable_tenant_rls('<schema>.<tabela>');` em toda tabela com `tenant_id`.
- `check` para enums; `unique` com `tenant_id` primeiro; índices para filtros de fila e listagem.
- Tabelas financeiras/históricas: revogar `update, delete` de `app_runtime` quando forem append-only.
- `grant ... to app_runtime` ao final.
- Tipos devem casar com o mapeamento (o Hibernate valida no startup): `varchar(n)` para `String` com `length`, `numeric(19,2)` para `Money`, `numeric(9,4)` para `Percentage`, `timestamptz` para `Instant`, `date` para `LocalDate`, `jsonb` para JSON.
- Migration publicada nunca é editada: correções são nova migration.

## 7. Logs

- Logger de classe (`LoggerFactory.getLogger(X.class)`) somente para log técnico. Use a API fluente para campos: `LOG.atWarn().addKeyValue("provider", "whatsapp").log("...")`.
- Nunca logar documento, nome, telefone, e-mail ou conteúdo de mensagem de devedor; use ids.
- INFO: marcos de processos e integrações; WARN: falha recuperável; ERROR: falha que exige ação (dispara alarme).

## 8. Integrações externas

- Porta em `application/port/out` com contrato neutro; adaptador em `infrastructure/provider` com `RestClient`, timeouts explícitos (connect 3 s, read 10 s), `@Retryable` apenas para erros transitórios (5xx, timeout), métrica `integration.calls{provider,operation,outcome}` e timer.
- Credenciais de provedor por tenant ficam criptografadas (`EncryptedStringConverter`), nunca em `application.yml`.
- Provedor sem definição: entregar a porta e o contrato; **não** criar adaptador simulado em código de produção.

## 9. Testes

- Unitários (`*Test`): domínio e serviços com dublês; rápidos, sem Spring quando possível.
- Integração (`*IT`, tag `integration`): Spring + PostgreSQL real, cobrindo RLS, migrations e fluxos críticos.
- Todo caso de uso financeiro: teste de concorrência/idempotência.
- Agendamento desligado nos testes (`app.scheduling.enabled=false`, verificado por `SchedulingDisabledIT`): jobs só rodam
  quando o teste chama `JobDrain`/o handler. Vários contextos Spring ficam em cache na mesma JVM e compartilham o banco;
  um `@Scheduled` ativo em qualquer um deles executaria jobs de outros testes com os próprios beans. Não acrescente
  dependência que declare `@EnableScheduling` por conta própria (o Spring Modulith Moments foi excluído por isso).
- Esperas por eventos assíncronos (`awaitIdle`) consultam `event_publication` do tenant e, ao esgotar o tempo, listam as
  publicações pendentes; não aumente o tempo de espera para contornar evento que não é consumido.

## 10. Git

- Branch `feature/<assunto>` a partir da `master`; pull request revisado pelo responsável do projeto.
- Mensagens de commit em português, imperativo, com o porquê no corpo.
