# Topologia lógica da aplicação

A plataforma é um **monólito modular**: um único artefato Java implantado em várias instâncias idênticas, internamente dividido em módulos de negócio com fronteiras verificadas por teste. Essa forma combina a simplicidade operacional de um único deploy com a separação de responsabilidades que permite, no futuro, extrair um módulo como serviço independente se o volume exigir.

## 1. Visão geral

```mermaid
flowchart LR
    subgraph Usuarios["Usuários"]
        OP["Operadores, supervisores,<br/>gestores, financeiro, auditores"]
        DEV["Devedores<br/>(portal de autonegociação)"]
    end

    subgraph Externos["Provedores externos"]
        WA["WhatsApp Cloud API"]
        MAIL["SMTP / Amazon SES"]
        SMS["Provedor de SMS"]
        PSP["PSP — API Pix (Bacen)<br/>boleto / link"]
        CTI["Telefonia / discador"]
        IA["Modelo de IA"]
        CRED["Sistemas dos credores<br/>(API / arquivos)"]
    end

    subgraph Plataforma["Gestor de Cobrança"]
        SPA["Frontend Angular (SPA)"]
        API["API REST /api/v1<br/>Webhooks /api/v1/webhooks<br/>Portal /api/portal/v1"]
        SSE["Tempo real da caixa de entrada<br/>SSE /api/v1/communication/inbox/stream"]
        CORE["Núcleo de aplicação<br/>(módulos de negócio)"]
        JOBS["Processamento assíncrono<br/>eventos · fila de jobs · agendamentos"]
    end

    subgraph Dados["Dados e observabilidade"]
        PG[("PostgreSQL<br/>1 schema por módulo<br/>Row Level Security")]
        S3[("S3<br/>carteiras · evidências · termos<br/>relatórios · gravações")]
        CW["CloudWatch<br/>logs técnico · negócio · acesso<br/>métricas e alarmes"]
    end

    OP --> SPA --> API
    DEV --> SPA
    CRED --> API
    WA & PSP & CTI --> API
    API --> CORE
    CORE <--> JOBS
    CORE --> PG
    JOBS --> PG
    CORE --> S3
    JOBS --> WA & MAIL & SMS & PSP & CTI & IA
    CORE -. logs/métricas .-> CW
    JOBS -. logs/métricas .-> CW
    PG -. "LISTEN/NOTIFY communication_inbox<br/>(só ids, após o commit, a todas as instâncias)" .-> SSE
    SSE -. "avisos (ADR-0008)" .-> SPA
```

O tempo real da caixa de entrada não usa serviço adicional: gatilhos nas tabelas de conversa e mensagem emitem `NOTIFY` (somente ids), cada instância escuta o canal numa conexão própria e repassa às conexões SSE abertas nela, filtrando assessoria e credores do escopo; o navegador então busca os dados pelos endpoints normais ([ADR-0008](adr/0008-tempo-real-sse-listen-notify.md)).

## 2. Camadas de cada módulo

Todo módulo segue a mesma arquitetura hexagonal. As setas indicam a única direção de dependência permitida (verificada automaticamente por ArchUnit e Spring Modulith).

```mermaid
flowchart TB
    WEB["web<br/>controllers REST, DTOs, validação de entrada,<br/>autorização por permissão (@PreAuthorize)"]
    APP["application<br/>casos de uso (port.in), transações,<br/>escopo por credor, auditoria, eventos"]
    DOM["domain<br/>agregados, regras de negócio, cálculos,<br/>interfaces de repositório"]
    INF["infrastructure<br/>JPA/JDBC, clientes de provedores,<br/>configuração"]
    OUT["Outros módulos"]

    WEB --> APP --> DOM
    INF -. implementa portas .-> APP
    INF -. implementa repositórios .-> DOM
    OUT -- "somente application.port.in" --> APP
```

## 3. Módulos de negócio e dependências

Cada caixa é um módulo (bounded context). Setas sólidas são chamadas síncronas permitidas; reações no sentido contrário ocorrem somente por **eventos de integração** publicados no kernel compartilhado.

```mermaid
flowchart BT
    SH["shared<br/>kernel: segurança, tenancy, auditoria,<br/>logs, eventos, cripto, arquivos, jobs"]
    ORG["organization<br/>assessoria · credores · contratos · carteiras"]
    IDN["identity<br/>usuários · perfis · MFA · sessões"]
    DEBT["debt<br/>devedores · títulos · saldos"]
    ING["ingestion<br/>importação CSV/XLSX/API"]
    CMP["compliance<br/>bloqueios · opt-out · LGPD"]
    COL["collection<br/>casos · filas · interações · contestações"]
    NEG["negotiation<br/>políticas · cálculo · propostas · acordos"]
    PAY["payment<br/>Pix/boleto · conciliação · estornos"]
    SET["settlement<br/>comissão · prestação de contas"]
    COM["communication<br/>WhatsApp · e-mail · SMS · inbox"]
    CAM["campaign<br/>segmentos · réguas · testes A/B"]
    TEL["telephony<br/>chamadas · discador"]
    POR["portal<br/>autonegociação do devedor"]
    SCO["scoring<br/>propensão à recuperação"]
    AST["assistant<br/>IA com limites, handoff e Pix na conversa"]
    ANA["analytics<br/>painéis · relatórios"]

    ORG --> SH
    IDN --> ORG
    DEBT --> ORG
    ING --> DEBT
    CMP --> DEBT
    COL --> DEBT & CMP & IDN
    NEG --> COL
    PAY --> NEG
    SET --> PAY
    COM --> COL & CMP
    CAM --> COM & NEG
    TEL --> COL
    POR --> PAY & COM
    SCO --> PAY & COL
    AST --> NEG & COM & PAY
    ANA --> ORG
```

### Eventos de integração (linguagem publicada)

| Evento | Publicado por | Consumido por (reação) |
| --- | --- | --- |
| `PortfolioStatusChanged` | organization | collection (elegibilidade), campaign (pausa) |
| `PortfolioLoaded` | ingestion/debt | collection (abertura de casos), scoring |
| `TitleStatusChanged` | debt | collection, campaign, negotiation |
| `ContactRestrictionChanged` | compliance | communication, campaign, telephony |
| `CaseStatusChanged`, `InteractionRecorded` | collection | campaign, scoring, analytics |
| `DisputeStatusChanged` | collection | campaign, negotiation |
| `AgreementStatusChanged`, `InstallmentStatusChanged` | negotiation | collection, campaign, settlement, portal |
| `PaymentConfirmed`, `PaymentReversed` | payment | collection, campaign, settlement, scoring |
| `PaymentInstructionStatusChanged` | payment | assistant (entrega do Pix gerado na conversa e conversão — ADR-0009) |
| `MessageStatusChanged` | communication | collection, campaign, assistant |
| `CallCompleted` | telephony | collection, scoring |
| `CadenceExhausted` | campaign | legal (critério "régua extrajudicial esgotada") |

Eventos são gravados na mesma transação da operação (Spring Modulith) e entregues após o commit; consumidores são idempotentes e falhas são reprocessadas automaticamente.

## 4. Isolamento de dados (multi-tenant e multi-credor)

```mermaid
flowchart LR
    REQ["Requisição com JWT<br/>(tenant, usuário, sessão,<br/>permissões, credores)"]
    SEC["Spring Security<br/>valida assinatura e sessão"]
    UC["Caso de uso<br/>AccessScope: credor no escopo?"]
    TX["Transação<br/>set_config('app.tenant_id')"]
    RLS["PostgreSQL RLS<br/>tenant_id = app.tenant_id"]

    REQ --> SEC --> UC --> TX --> RLS
```

Três barreiras independentes: (1) o token só autentica com sessão ativa no servidor; (2) os casos de uso filtram e validam o escopo de credores do usuário; (3) o banco recusa qualquer leitura ou escrita de outro tenant, mesmo que haja erro de código.

## 5. Fluxos de log e auditoria

```mermaid
flowchart LR
    APP["Aplicação"]
    APP -- "todas as classes" --> TEC["technical.log<br/>manutenção e desempenho"]
    APP -- "BusinessEventLogger" --> BUS["business.log<br/>+ shared.business_event"]
    APP -- "AccessLogger" --> ACC["access.log<br/>+ shared.access_log"]
    APP -- "AuditTrail (mesma transação)" --> AUD[("shared.audit_event<br/>append-only · hash encadeado")]
    TEC & BUS & ACC --> AGT["CloudWatch Agent"] --> CWL["CloudWatch Logs<br/>3 grupos com retenções distintas"]
    APP -- "Micrometer" --> CWM["CloudWatch Metrics<br/>namespace GestorCobranca"]
```

| Fluxo | Finalidade | Exemplos |
| --- | --- | --- |
| Técnico | Diagnóstico, desempenho, erros | rota, status e duração de cada requisição; falhas de integração; jobs reprocessados |
| Negócio | Inteligência operacional | acordo formalizado (valor, parcelas, canal, operador); pagamento confirmado; contato efetivo |
| Acesso | Rastreabilidade de segurança | login e falhas, MFA, bloqueios, acesso negado, visualização de dado sensível, exportações |
| Auditoria | Prova de alterações | antes/depois de cada mudança relevante, autor, motivo; integridade verificável |

Dados pessoais (CPF, e-mail, telefone) são mascarados automaticamente em todos os logs.

## 6. Processamento assíncrono

```mermaid
flowchart LR
    UC["Caso de uso"] -- "mesma transação" --> EV[("Registro de eventos")]
    UC -- "mesma transação" --> JQ[("Fila de jobs<br/>shared.processing_job")]
    EV -- "após commit" --> L["Consumidores<br/>(idempotentes)"]
    JQ -- "SKIP LOCKED<br/>qualquer instância" --> W["Workers"]
    W --> P["Provedores externos"]
    S["Agendamentos<br/>(ShedLock: 1 instância)"] --> JQ
```

Não há fila ou broker externo nesta fase: PostgreSQL atende eventos, jobs e travas distribuídas com garantias transacionais. Quando o volume justificar, a fila de jobs pode migrar para Amazon SQS sem alterar os casos de uso (a fila é uma porta de saída).
