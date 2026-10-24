# Arquitetura — Gestor de Cobrança

Documento de referência técnica. Toda decisão de código deve ser coerente com ele; divergências exigem um ADR em `docs/arquitetura/adr/`.

## 1. Visão geral

```
                 ┌──────────────── AWS ─────────────────────────────────────────┐
  Navegador ──►  │ ALB (HTTPS, WAF opcional)                                    │
  (Angular SPA)  │   ├─► EC2 (Auto Scaling Group, Linux) ── Spring Boot (Java)  │
  Provedores ──► │   └─► EC2 ...                                                │
  (webhooks)     │          │            │                 │                    │
                 │          ▼            ▼                 ▼                    │
                 │   RDS PostgreSQL     S3 (arquivos)   CloudWatch (logs+métricas)│
                 └──────────────────────────────────────────────────────────────┘
```

* **Backend:** Java 21, Spring Boot 4, monólito modular (Spring Modulith). Um único artefato, implantado em N instâncias EC2 atrás de um ALB. Todas as instâncias são *stateless*; estado vive no PostgreSQL e no S3.
* **Frontend:** Angular (standalone components, signals), empacotado no JAR e servido como estático pelo próprio backend em `/` — ver ADR-0007.
* **Banco:** PostgreSQL 16 (RDS), um schema por módulo, *Row Level Security* por tenant.
* **Arquivos:** S3 (carteiras importadas, evidências, termos de acordo, relatórios exportados), criptografia SSE e chaves por prefixo de tenant.
* **Observabilidade:** três fluxos de log separados (técnico, negócio, acesso) + métricas Micrometer publicadas no CloudWatch.

## 2. Módulos (bounded contexts)

Pacote raiz: `br.com.gestorcobranca`. Cada pacote de primeiro nível é um módulo Spring Modulith.

| Módulo | Escopo funcional | Features |
| --- | --- | --- |
| `shared` | Kernel compartilhado (aberto): tipos de valor, tenancy, segurança base, auditoria, logs, eventos de integração, criptografia, armazenamento de arquivos, jobs | transversal |
| `organization` | Tenant (assessoria), unidades, credores, contratos de serviço, carteiras, regras contratuais versionadas | F03 |
| `identity` | Usuários, perfis, permissões, equipes, escopo por credor/carteira, autenticação, MFA, sessões, log de acesso | F01, F02 |
| `debt` | Devedor, contatos, contratos de origem, títulos, parcelas, saldos, histórico de titularidade, garantias, partes da obrigação e prescrição | F05 |
| `ingestion` | Lotes de importação (CSV/XLSX/API), validação, erros por linha, reimportação idempotente | F04 |
| `compliance` | Restrições de contato, opt-out, bases legais, solicitações de titulares (LGPD), retenção e descarte | M09 |
| `collection` | Caso de cobrança, elegibilidade, filas, atribuição, interações/tabulação, tarefas e retornos, promessas simples, contestações — ver `docs/tecnico/desenho/collection.md` | F06, F07, F21 |
| `negotiation` | Políticas de cobrança versionadas, motor de cálculo, escada de concessões, perfis de alçada (humanos e assistente), propostas e aprovação, acordos, parcelas, quebra, renegociação, termo — ver `docs/tecnico/desenho/negociacao-e-alcadas.md` | F08, F09, F16 |
| `payment` | Instruções de pagamento (Pix/boleto/link), pagamentos externos e integrados, webhooks, alocação, conciliação, estornos | F10, F14, F15 |
| `settlement` | Regras de comissão da assessoria versionadas por contrato, recebimentos e razão de comissão, demonstrativo/prestação de contas por credor com aprovação segregada, exportação conciliável e repasses — ver `docs/tecnico/desenho/prestacao-de-contas.md` | F17, F22 |
| `communication` | Canais (WhatsApp, e-mail, SMS), templates, conversas com dono do atendimento (assistente, fila ou operador), transferências, mensagens, caixa de entrada — ver `docs/tecnico/desenho/comunicacao.md` | F12 |
| `campaign` | Segmentos, campanhas, réguas, execução, controle de concorrência, testes A/B | F13, F25 |
| `telephony` | Integração CTI/discador: chamadas, clique para ligar, campanhas de discagem (progressiva/preditiva), presença do operador, gravações, resultado vinculado ao caso — ver `docs/tecnico/desenho/telefonia.md` | F20 |
| `portal` | Autonegociação do devedor (acesso seguro, consulta, proposta, formalização) — ver `docs/tecnico/desenho/portal.md` | F19 |
| `scoring` | Score de propensão à recuperação e priorização: features, scorecard e regressão logística versionados, escoragem em lote, monitoramento, recuperado × esperado — ver `docs/tecnico/desenho/scoring.md` | F23 |
| `assistant` | IA de atendimento e negociação: ator próprio (`ASSISTANT`) com perfil de alçada, modos sombra/copiloto/autônomo, guardrails e transferência para humano — ver `docs/tecnico/desenho/assistente.md` | F24 |
| `analytics` | Read models e relatórios (painel operacional, recuperação, produtividade, exportação) | F11, F18 |
| `incentive` | Métricas operacionais dos negociadores, atribuição de crédito (humano × assistente), planos de comissão versionados, apuração, extrato, contestação e aprovação — ver `docs/tecnico/desenho/remuneracao-variavel.md` | remuneração variável |
| `legal` | Workflow jurídico: elegibilidade, viabilidade, autorização do credor, medidas extrajudiciais, processos, prazos, audiências, custas, escritórios parceiros — ver `docs/tecnico/desenho/juridico.md` | jurídico |

### 2.1 Grafo de dependências permitido (acíclico)

```
shared ◄── organization ◄── identity
              ▲   ▲
              │   └─ debt ◄── ingestion
              │       ▲  ▲
              │       │  └── compliance
              │       │        ▲
              ├── collection ──┘  (collection → debt, organization, identity, compliance)
              │       ▲
              ├── negotiation        (→ collection, debt, organization, identity)
              │       ▲
              ├── payment            (→ negotiation, debt, organization)
              │       ▲
              ├── settlement         (→ payment, negotiation, organization)
              ├── communication      (→ collection, debt, compliance, organization)
              ├── campaign           (→ communication, collection, negotiation, debt, compliance, organization)
              ├── telephony          (→ collection, compliance, debt, organization)
              ├── portal             (→ negotiation, payment, communication, debt, organization)
              ├── scoring            (→ collection, debt, payment, organization)
              ├── assistant          (→ negotiation, communication, collection, compliance, debt, organization)
              ├── legal              (→ collection, debt, compliance [feriados], identity, organization; acordos do negotiation e réguas do campaign por evento)
              ├── incentive          (→ identity, organization, compliance [feriados]; fatos por eventos de integração)
              └── analytics          (→ organization; lê o banco por views próprias)
```

Todo módulo pode depender de `shared`. A dependência é declarada em `package-info.java` com `@ApplicationModule(allowedDependencies = …)` e verificada por teste (`ModularityTest`).

**Reações no sentido contrário ao grafo** (ex.: `collection` precisa saber que um pagamento foi confirmado) acontecem **somente** por *eventos de integração* definidos em `shared.events` (linguagem publicada). Nunca se cria dependência cíclica para isso.

### 2.2 Interface pública de um módulo

Outro módulo só pode usar:

* `<modulo>.application.port.in` — casos de uso (interfaces) e seus DTOs de entrada/saída (records). Marcado com `@NamedInterface("usecases")`.
* Nada mais. Entidades, repositórios e controladores são internos.

## 3. Camadas dentro de um módulo (arquitetura hexagonal)

```
br.com.gestorcobranca.<modulo>
├── package-info.java                 @ApplicationModule(...)
├── domain/                           regras de negócio puras
│   ├── model/                        agregados, entidades, value objects, enums de estado
│   ├── service/                      serviços de domínio (cálculos, políticas)
│   └── repository/                   interfaces de repositório (portas de saída do domínio)
├── application/                      orquestração de casos de uso, transações, autorização
│   ├── port/in/                      interfaces de casos de uso + commands/queries/results (PÚBLICO)
│   ├── port/out/                     portas para infraestrutura externa (provedores, storage…)
│   ├── service/                      implementações dos casos de uso (@Service, @Transactional)
│   └── listener/                     consumidores de eventos de integração
├── infrastructure/                   adaptadores de saída
│   ├── persistence/                  Spring Data JPA, implementações dos repositórios do domínio
│   ├── provider/                     clientes de provedores externos (HTTP)
│   └── config/                       @Configuration e @ConfigurationProperties do módulo
└── web/                              adaptador de entrada HTTP
    ├── <Recurso>Controller.java
    └── dto/                          requests/responses da API REST (records) + mappers
```

Regras (verificadas por ArchUnit em `ArchitectureTest`):

1. `domain` não depende de `application`, `infrastructure`, `web`, nem de Spring Web/Spring Data. Anotações JPA (`jakarta.persistence`) são permitidas no modelo — ver ADR-0003.
2. `application` não depende de `infrastructure` nem de `web`.
3. `web` só chama `application.port.in`; nunca repositórios.
4. Controllers não contêm regra de negócio; services não conhecem HTTP.
5. Nenhuma classe fora de `infrastructure.persistence` estende `JpaRepository`.

## 4. Multi-tenancy e isolamento de dados (F02)

Defesa em profundidade, em três níveis:

1. **Aplicação:** `TenantContext` é preenchido a partir do JWT autenticado; todo agregado tenant-scoped estende `TenantScopedEntity` e recebe `tenant_id` automaticamente na criação.
2. **Banco (RLS):** toda tabela de tenant tem `tenant_id uuid not null` e política `tenant_isolation` com `FORCE ROW LEVEL SECURITY`. O `TenantAwareJpaTransactionManager` executa `set_config('app.tenant_id', …, true)` no início de cada transação. Sem tenant definido, nenhuma linha é visível. A aplicação conecta com o papel `app_runtime` (não-dono das tabelas); migrations rodam com `app_owner`.
3. **Escopo por credor/carteira:** usuários possuem escopo de credores (ou "todos"). `AccessScope` filtra consultas e valida comandos; violações geram `AccessDeniedException` e um evento no log de acesso.

Jobs sem usuário (agendados, consumidores de eventos) executam via `TenantExecution.inTenant(tenantId, …)`, iterando tenants explicitamente. Não existe "modo bypass" de RLS na aplicação.

## 5. Consistência e confiabilidade

* **Dinheiro:** `Money` (BigDecimal, escala 2, `RoundingMode.HALF_EVEN`, BRL). Nunca `double`.
* **Concorrência:** `@Version` (lock otimista) em todos os agregados; lock pessimista (`SELECT … FOR UPDATE`) apenas em operações financeiras de alocação/baixa.
* **Idempotência:** comandos externos (webhooks, importação, API pública) carregam chave de idempotência persistida (`shared.idempotency_key` / `payment.webhook_event` com `unique`).
* **Eventos:** publicação transacional via Spring Modulith (*event publication registry* em JPA). Consumidores são idempotentes (`shared.consumed_event`). Publicações incompletas são reprocessadas por job com ShedLock.
* **Jobs distribuídos:** `@Scheduled` + ShedLock (tabela `shared.shedlock`) para execução única entre instâncias; filas de trabalho com `FOR UPDATE SKIP LOCKED` (`shared.processing_job`).
* **Integrações:** `RestClient` com timeouts explícitos e `@Retryable` (Spring Framework 7) apenas para falhas transitórias; erros permanentes vão para análise.
* **Histórico:** nada financeiro é apagado; estornos e correções são eventos compensatórios. Auditoria é *append-only* com encadeamento de hash.

## 6. Segurança

Ver `docs/tecnico/seguranca.md`. Resumo: Spring Security com JWT RS256 de curta duração (10 min), refresh token opaco rotativo com detecção de reuso, MFA TOTP, bloqueio progressivo, política de senha (Argon2id), autorização por permissão (`@PreAuthorize("hasAuthority('…')")`) + escopo por credor, criptografia de campos sensíveis (AES-256-GCM) com *blind index* para busca, mascaramento em logs e respostas, cabeçalhos de segurança, CORS restrito, rate limiting por IP/usuário em endpoints sensíveis.

## 7. Logs e métricas

Ver `docs/tecnico/logs-e-metricas.md`. Três fluxos independentes:

| Fluxo | Logger | Destino | Finalidade |
| --- | --- | --- | --- |
| Técnico | todos os loggers de classe | `technical.log` → CloudWatch `/gestor-cobranca/<env>/technical` | manutenção, diagnóstico, desempenho |
| Negócio | `BUSINESS` via `BusinessEventLogger` | `business.log` → `/…/business` + tabela `shared.business_event` | inteligência operacional e tomada de decisão |
| Acesso/segurança | `ACCESS` via `AccessLogger` | `access.log` → `/…/access` + tabela `shared.access_log` | rastreabilidade de acesso e investigação |

Métricas (Micrometer → CloudWatch, namespace `GestorCobranca`): latência por endpoint, taxa de erro, pool de conexões, filas de jobs, eventos pendentes, integrações (sucesso/falha/latência por provedor), além de métricas de negócio agregadas.

## 8. Migrations e banco

* Flyway, um schema por módulo. Faixas de versão por módulo para evitar colisão:

| Faixa | Módulo |
| --- | --- |
| V0001–V0099 | shared |
| V0100–V0199 | organization |
| V0200–V0299 | identity |
| V0300–V0399 | debt |
| V0400–V0499 | ingestion |
| V0500–V0599 | compliance |
| V0600–V0699 | collection |
| V0700–V0799 | negotiation |
| V0800–V0899 | payment |
| V0900–V0999 | settlement |
| V1000–V1099 | communication |
| V1100–V1199 | campaign |
| V1200–V1299 | telephony |
| V1300–V1399 | portal |
| V1400–V1499 | scoring |
| V1500–V1599 | assistant |
| V1600–V1699 | analytics |
| V1700–V1799 | legal |
| V1800–V1899 | incentive |

* Nome: `V0301__debt_create_debtor.sql`. Arquivos em `backend/src/main/resources/db/migration/<modulo>/`.
* Toda tabela de tenant: `tenant_id`, RLS habilitado e forçado, política `tenant_isolation`, `GRANT` para `app_runtime`. Use a função auxiliar `shared.enable_tenant_rls('<schema>.<tabela>')` criada em `V0002`.
* Chaves estrangeiras entre schemas são permitidas apenas na direção do grafo de dependências.

## 9. Estrutura do repositório

```
gestor-cobranca/
├── backend/        Spring Boot (Maven)
├── frontend/       Angular
├── infra/
│   ├── terraform/  VPC, ALB, ASG/EC2, RDS, S3, CloudWatch, IAM
│   └── docker/     Docker Compose para ambiente local
├── scripts/        build, deploy e bootstrap de instância
└── docs/           arquitetura, convenções, ADRs, segurança, logs
```
