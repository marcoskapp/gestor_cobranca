# Estrutura recomendada na AWS

Objetivo: começar com **custo operacional baixo** e crescer **por configuração, não por reescrita**, à medida que entram assessorias, carteiras e volume de mensagens. Toda a estrutura será descrita em Terraform (`infra/terraform`), de modo que cada fase seja uma mudança de variáveis revisada por pull request.

Premissas do projeto: aplicação em EC2 Linux com balanceamento de carga, PostgreSQL no RDS, arquivos no S3 e observabilidade no CloudWatch. Serviços adicionais aparecem marcados como **requer aprovação**.

## 1. Região

**Recomendação: São Paulo (`sa-east-1`).** Menor latência para os usuários e devedores, e dados pessoais mantidos no Brasil, o que simplifica a análise de LGPD e os contratos com credores.

A região custa mais que as dos EUA — por exemplo, um `db.t4g.medium` PostgreSQL sai por cerca de US$ 100/mês em São Paulo contra US$ 47/mês na Virgínia ([Bytebase](https://www.bytebase.com/dbcost/rds/instance/db.t4g.medium/)). A diferença deve ser avaliada pelo jurídico contra a exigência contratual dos credores antes de optar por outra região.

## 2. Arquitetura alvo

```mermaid
flowchart TB
    U["Usuários e devedores"] -->|HTTPS| R53["Route 53<br/>(DNS)"]
    P["Provedores<br/>(webhooks)"] -->|HTTPS| R53
    R53 --> WAF["AWS WAF<br/>(requer aprovação)"]
    WAF --> ALB

    subgraph VPC["VPC 10.0.0.0/16 — sa-east-1"]
        subgraph PUB["Sub-redes públicas (2 AZs)"]
            ALB["Application Load Balancer<br/>TLS (ACM) · health check"]
            NAT["NAT Gateway"]
        end
        subgraph APP["Sub-redes privadas de aplicação (2 AZs)"]
            ASG["Auto Scaling Group<br/>EC2 Graviton (t4g) · Amazon Linux 2023<br/>Java 21 + CloudWatch Agent"]
        end
        subgraph DB["Sub-redes privadas de dados (2 AZs)"]
            RDS[("RDS PostgreSQL 16<br/>criptografado · backups automáticos")]
        end
        S3E["VPC endpoint S3<br/>(gateway, sem custo)"]
    end

    ALB --> ASG
    ASG --> RDS
    ASG --> S3E --> S3[("S3<br/>arquivos · artefatos")]
    ASG --> NAT -->|"WhatsApp, Pix, SMTP…"| INTERNET["Internet"]
    ASG -.-> CW["CloudWatch<br/>Logs · Metrics · Alarms"]
    ASG -.-> SSM["SSM Parameter Store / KMS<br/>segredos (requer aprovação)"]
```

### Componentes

| Componente | Configuração inicial | Por quê |
| --- | --- | --- |
| VPC | 2 zonas de disponibilidade; sub-redes públicas, privadas de aplicação e privadas de dados | Separação de camadas; o banco nunca tem rota para a internet |
| ALB | HTTPS (TLS 1.2+), redireciona 80→443, health check em `/actuator/health/readiness`; `X-Forwarded-For` em modo `append` (o IP real fica **à direita**) | Único ponto de entrada; distribui carga e substitui instâncias doentes |
| EC2 / Auto Scaling Group | `t4g.medium` (2 vCPU, 4 GiB), Amazon Linux 2023, IMDSv2 obrigatório, sem chave SSH | Graviton custa menos por desempenho; o ASG garante substituição automática e crescimento |
| Acesso às instâncias | AWS Systems Manager Session Manager | Elimina bastion e porta 22 aberta; toda sessão fica auditada |
| RDS PostgreSQL 16 | `db.t4g.medium`, gp3 50 GiB com autoscaling de armazenamento, criptografia KMS, backups 14 dias, deletion protection, `rds.force_ssl=1` | Banco gerenciado com recuperação ponto-a-ponto |
| S3 | Bloqueio de acesso público, criptografia SSE, versionamento, *lifecycle* (Standard-IA após 90 dias, Glacier para arquivos de evidência antigos) | Arquivos com custo decrescente ao envelhecer |
| CloudWatch | 3 grupos de log com retenções distintas, métricas `GestorCobranca`, painel operacional e alarmes | Observabilidade exigida pelo projeto |
| NAT Gateway | 1 unidade (em uma AZ) na fase inicial | Saída para provedores externos sem expor as instâncias |

### Grupos de segurança (cadeia de confiança)

| Grupo | Entrada permitida | Saída |
| --- | --- | --- |
| `sg-alb` | 443 e 80 da internet (ou do WAF) | 8080 para `sg-app` |
| `sg-app` | 8080 somente de `sg-alb` | 5432 para `sg-db`; 443 para internet via NAT; S3 via endpoint; 587 (SMTP com STARTTLS) para o provedor de e-mail |
| `sg-db` | 5432 somente de `sg-app` | nenhuma |

### Perfil IAM das instâncias (menor privilégio)

- `s3:GetObject/PutObject/DeleteObject` apenas no bucket da aplicação (prefixo `tenants/*`) e leitura do prefixo de artefatos de deploy (e dos scripts de bootstrap).
- `logs:CreateLogStream/PutLogEvents` nos três grupos de log e no grupo da saída do Run Command; `cloudwatch:PutMetricData` restrito aos namespaces `GestorCobranca` (aplicação) e `GestorCobranca/Host` (memória e disco, CloudWatch Agent).
- `ssm:GetParameter(s)` e `kms:Decrypt` somente nos segredos de **runtime** `/gestor-cobranca/<ambiente>/app/*` — ou, enquanto o SSM não for aprovado, `s3:GetObject` em `s3://<bucket de segredos>/<ambiente>/app/*`.
- Os segredos de **migração** (`.../migration/*`, senha de `app_owner`, dono do schema) **não** são legíveis pelo papel em execução: uma falha na aplicação não pode virar `ALTER TABLE ... DISABLE ROW LEVEL SECURITY` em todas as assessorias. `scripts/deploy/migrate.sh` (pipeline/operador) cria no papel, só durante a execução, a política inline temporária `migration-window`, que permite ler `migration/*` **apenas** da instância escolhida para a migração (`ec2:SourceInstanceARN`) e **até** o fim da janela (`aws:CurrentTime`, 75 min), e a remove ao terminar. Quem executa o deploy precisa de `iam:PutRolePolicy`/`iam:DeleteRolePolicy` no papel das instâncias. Com `db_admin_access = true` (temporário) o papel também lê `migration/*`, usado por `db-admin.sh` para criar e rotacionar a senha de `app_owner`.
- `secretsmanager:GetSecretValue` no segredo da senha mestre do RDS **somente** enquanto `db_admin_access = true` (criação de papéis e rotação de senhas; desligado no restante do tempo).
- `AmazonSSMManagedInstanceCore` para o Session Manager e o Run Command (execução das migrations).

### IP do cliente e proxies confiáveis

A aplicação só interpreta `X-Forwarded-For`/`-Proto`/`-Port` de conexões vindas das sub-redes públicas do ALB (saída `public_subnet_cidrs` do módulo de rede → `APP_HTTP_TRUSTED_PROXIES`; obrigatório no perfil `prod`). O `X-Forwarded-For` é lido da direita para a esquerda e o primeiro salto fora dessas sub-redes é o cliente; valores à esquerda (escritos pelo cliente) e o cabeçalho `Forwarded` são ignorados. O ALB não remove `Forwarded` (não há ação de listener para isso) — a aplicação o descarta. O limite de taxa do WAF agrega pelo **IP de origem da conexão** (`aggregate_key_type = "IP"`), nunca pelo `X-Forwarded-For`, que sem CDN confiável à frente é escolhido pelo cliente.

Nenhuma chave de acesso AWS fica em arquivo ou variável de ambiente: o SDK usa as credenciais temporárias do perfil da instância.

## 3. Logs no CloudWatch

| Grupo de log | Origem | Retenção sugerida | Uso |
| --- | --- | --- | --- |
| `/gestor-cobranca/<env>/technical` | `technical.log` | 30 dias | Diagnóstico e desempenho (Logs Insights por rota, tenant, requestId) |
| `/gestor-cobranca/<env>/business` | `business.log` | 13 meses | Análises operacionais; cópia permanente também na tabela `shared.business_event` |
| `/gestor-cobranca/<env>/access` | `access.log` | 13 meses + exportação mensal para S3/Glacier por 5 anos | Rastreabilidade de acesso; cópia também em `shared.access_log` |
| `/gestor-cobranca/<env>/run-command` | saída do SSM Run Command | 13 meses | Quem executou migrations e operações de banco, quando e com que resultado |

Retenção curta no CloudWatch e arquivamento no S3 reduzem o custo de armazenamento de log, que cresce rapidamente com o volume.

### Alarmes iniciais

| Alarme | Condição |
| --- | --- |
| Erros 5xx no ALB | > 1% das requisições em 5 min |
| Latência p95 | > 1,5 s por 10 min |
| Instâncias não saudáveis | ≥ 1 por 5 min |
| CPU do RDS / conexões | > 80% por 15 min |
| Espaço livre no RDS | < 20% |
| Jobs em DEAD (`jobs.dead`) | > 0 |
| Eventos pendentes (`events.publications.incomplete`) | crescimento contínuo por 15 min |
| Falha ao gravar log de acesso (`access.log.write_failures`) | > 0 |
| Falhas de selagem da auditoria (`audit.seal.failures`) | > 0 |
| Picos de login negado (`access.events{type=LOGIN_FAILURE}`) | acima do padrão histórico |
| SLA da caixa de entrada (`communication.inbox.sla.breaches`) | soma > `inbox_sla_breaches_threshold` (padrão 10) em 15 min |
| Tempo real da caixa de entrada (`communication.realtime.listener.failures`) | > 3 em 5 min (ADR-0008) |

Tempo real da caixa de entrada (ADR-0008): conexões SSE atravessam o ALB sem configuração especial — o *heartbeat* de 20 s fica abaixo do `idle_timeout` (120 s); cada instância mantém uma conexão a mais com o RDS (fora do pool) para o `LISTEN`; nenhum serviço adicional.

## 4. Fases de crescimento

A aplicação é *stateless*: todo o estado está no PostgreSQL e no S3. Por isso escalar é aumentar o número ou o tamanho das instâncias, sem mudança de código.

```mermaid
flowchart LR
    F1["Fase 1 — Piloto<br/>1–2 assessorias"] --> F2["Fase 2 — Produção<br/>clientes pagantes"] --> F3["Fase 3 — Escala<br/>alto volume"]
```

### Fase 1 — Piloto (custo mínimo com segurança completa)

| Item | Configuração |
| --- | --- |
| EC2 | ASG mínimo 1, máximo 3, `t4g.medium` |
| RDS | `db.t4g.medium` **Single-AZ** |
| NAT | 1 NAT Gateway |
| Ambientes | produção + homologação (homologação desligada fora do horário comercial por agendamento) |

Com mínimo de 1 instância, uma falha causa indisponibilidade de alguns minutos até o ASG substituí-la. Aceitável em piloto; se não for, usar mínimo 2 desde o início.

### Fase 2 — Produção com clientes pagantes

| Item | Mudança |
| --- | --- |
| EC2 | ASG mínimo 2 (uma por AZ), máximo 6; escala por CPU (alvo 60%) e por requisições por instância |
| RDS | **Multi-AZ** (failover automático); `db.t4g.large` ou `db.m7g.large` conforme carga |
| Custos | Compute Savings Plan de 1 ano e instância reservada do RDS (economia típica de 30% a 40% sobre on-demand) |
| Segurança | AWS WAF com regras gerenciadas e limite de taxa; GuardDuty (ambos requerem aprovação) |
| NAT | um NAT Gateway por AZ, se a disponibilidade da saída para provedores for crítica |

### Fase 3 — Escala

| Gatilho | Ação |
| --- | --- |
| Jobs e campanhas competindo com a API | Segundo ASG da mesma aplicação em modo *worker* (sem tráfego do ALB), escalando pela métrica `jobs.pending` |
| Relatórios pesados afetando a operação | Réplica de leitura do RDS dedicada ao módulo `analytics` |
| Banco no limite vertical | Migração para Aurora PostgreSQL (compatível; troca de endpoint) |
| Volume de jobs muito alto | Trocar a fila PostgreSQL por Amazon SQS (a fila é uma porta de saída no código) |
| Frontend com muitos acessos | Servir o Angular por S3 + CloudFront |

## 5. Estimativa de custo mensal (ordem de grandeza)

Valores aproximados em US$, on-demand, `sa-east-1`, sem impostos. O único valor confirmado em fonte é o do RDS `db.t4g.medium` ([Bytebase](https://www.bytebase.com/dbcost/rds/instance/db.t4g.medium/)); os demais são estimativas e **devem ser recalculados no [AWS Pricing Calculator](https://calculator.aws/) antes da contratação**.

| Item | Fase 1 | Fase 2 |
| --- | --- | --- |
| EC2 `t4g.medium` (1 → 2 instâncias) | 40–55 | 80–110 |
| Application Load Balancer | 25–35 | 30–50 |
| NAT Gateway (1 → 2) + tráfego | 50–70 | 100–140 |
| RDS `db.t4g.medium` Single-AZ → Multi-AZ, gp3 50 GiB | 110–120 | 210–240 |
| S3 (até ~100 GiB) | 3–5 | 5–15 |
| CloudWatch (logs, métricas, alarmes) | 15–40 | 40–100 |
| WAF | — | 15–30 |
| **Total aproximado** | **~US$ 245–325** | **~US$ 480–685** |

Maiores alavancas de economia, em ordem: Savings Plans/instâncias reservadas na fase 2; desligar homologação fora do horário; *lifecycle* do S3 e retenção curta de logs no CloudWatch; tamanho das instâncias ajustado pelas métricas reais após 30 dias de operação.

## 6. Implantação

1. O pipeline gera o JAR e o pacote do frontend, executa testes e publica no S3 (`artifacts/<versão>/`).
2. Uma nova versão do *launch template* aponta para o artefato.
3. *Instance refresh* do ASG substitui as instâncias gradualmente (mínimo 90% saudáveis), com o ALB drenando conexões.
4. Migrations Flyway são executadas uma única vez, antes do refresh, por um passo dedicado com o papel `app_owner` (o mesmo JAR em modo `migrate`, executado numa instância do ASG via SSM Run Command, sem abrir portas), com a senha de `app_owner` legível só durante a janela de migração e só por essa instância; as instâncias de aplicação conectam com `app_runtime_login`, que não altera schema.
5. Reversão: voltar o *launch template* à versão anterior (migrations são sempre compatíveis com a versão anterior).

## 7. Backup e recuperação

| Item | Política |
| --- | --- |
| RDS | Backups automáticos de 14 dias (restauração ponto-a-ponto) + snapshot manual mensal mantido por 12 meses |
| S3 | Versionamento; replicação para outra região na fase 2 se exigida contratualmente |
| Infraestrutura | Recriável por Terraform |
| Objetivo inicial | RPO ≤ 5 min (logs de transação do RDS); RTO ≤ 1 h (fase 1) e ≤ 15 min (fase 2, Multi-AZ) |

Implementação em Terraform, scripts de implantação e procedimentos (primeira implantação, rotação de segredos, rollback): [`infra/terraform/README.md`](../../infra/terraform/README.md).
