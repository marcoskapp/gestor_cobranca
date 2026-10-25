# Infraestrutura AWS — Terraform

Implementa [`docs/arquitetura/infraestrutura-aws.md`](../../docs/arquitetura/infraestrutura-aws.md): VPC em 2 AZs, ALB, Auto Scaling Group de EC2 Graviton (Amazon Linux 2023), RDS PostgreSQL 16, S3 e CloudWatch, com segredos fora do código e do state.

```
infra/terraform/
  modules/
    network/        VPC, sub-redes (pública/app/dados), IGW, NAT, endpoint S3, flow logs
    security/       sg-alb → sg-app → sg-db
    storage/        buckets app, artifacts, logs (+ secrets, quando sem SSM)
    database/       RDS PostgreSQL 16, parameter group, Enhanced Monitoring, logs exportados
    compute/        IAM da instância, launch template (user-data), ASG, escala, agendamento
    loadbalancer/   ALB, target group, listeners 80→443 e 443
    observability/  grupos de log, metric filters, alarmes, painel, SNS (opcional)
  envs/
    homologacao/    composição + terraform.tfvars.example + backend.hcl.example + tests/
    producao/       idem; parâmetros da fase 2 comentados no tfvars.example
scripts/
  instance/         executados NA instância (user-data, systemd, CloudWatch Agent, migrations, db-admin)
  deploy/           build.sh, migrate.sh, release.sh, db-admin.sh (pipeline/operador)
  ops/              bootstrap-state.sh, provision-secrets.sh, export-access-logs.sh
  db/               bootstrap-roles.sql
```

`main.tf`, `optional.tf`, `variables.tf`, `outputs.tf` e `versions.tf` são **idênticos** nos dois ambientes; as diferenças ficam só em `terraform.tfvars` e `backend.hcl`. Ao alterar um, copie para o outro no mesmo PR (`diff -r envs/homologacao envs/producao` deve listar apenas os `.example` e `tests/`).

## Serviços que requerem aprovação

Todos desligados por padrão. Cada um é ligado por variável, depois da aprovação registrada no PR.

| Variável | Serviço | Sem aprovação |
| --- | --- | --- |
| `enable_acm_certificate` | ACM (certificado do ALB, validação DNS) | `certificate_arn` de um certificado existente (importado no ACM) |
| `enable_route53` | Route 53 (alias do domínio, validação do ACM) | CNAME para `alb_dns_name` no provedor de DNS |
| `enable_waf` | AWS WAF (regras gerenciadas + limite de taxa por IP) | proteção só no ALB e na aplicação (rate limit de login) |
| `use_ssm_parameters` | SSM Parameter Store + KMS (chave dedicada com rotação anual) | bucket S3 de segredos dedicado (ver abaixo) |
| `enable_sns_notifications` | SNS (e-mail dos alarmes) | alarmes visíveis no painel/console |
| `data_kms_key_arn`, `logs_kms_key_arn` | KMS com chave do cliente para S3/RDS/EBS/logs | chaves gerenciadas pela AWS (SSE-S3, `aws/rds`, `aws/ebs`) |

## Segredos

Nenhum segredo fica em código, `tfvars`, state ou saída do Terraform. Os valores são gravados por `scripts/ops/provision-secrets.sh` direto no cofre do ambiente e lidos pelas instâncias no boot.

| Nome | Uso | Formato |
| --- | --- | --- |
| `app/db-password` | `DB_PASSWORD` (`app_runtime_login`) | 40 alfanuméricos |
| `migration/db-owner-password` | `DB_MIGRATION_PASSWORD` (`app_owner`, somente migrations) | 40 alfanuméricos |
| `app/crypto-key-<id>` | `APP_CRYPTO_KEY_K1` / `APP_CRYPTO_KEYS_<ID>` | Base64 de 32 bytes |
| `app/crypto-blind-index-key` | `APP_CRYPTO_BLIND_INDEX_KEY` | Base64 de 32 bytes |
| `app/jwt-private-key`, `app/jwt-public-key` | `/etc/gestor-cobranca/jwt-*.pem` | PEM PKCS#8 / X.509 (RSA 2048) |
| `app/smtp-username`, `app/smtp-password` | `SMTP_USERNAME`, `SMTP_PASSWORD` | do provedor SMTP |

**Com `use_ssm_parameters = true`** — SecureStrings em `/gestor-cobranca/<env>/<nome>`, cifradas pela chave KMS do ambiente; o papel da instância só tem `ssm:GetParameter(s)` em `.../app/*` e `kms:Decrypt` nessa chave (via SSM).

**Runtime × migração** — o papel das instâncias lê somente `app/*`. `migration/*` (senha do dono do schema) fica legível apenas: (a) durante `scripts/deploy/migrate.sh`, pela política inline temporária `migration-window`, restrita à instância da migração (`ec2:SourceInstanceARN`) e a 75 minutos (`aws:CurrentTime`), removida ao fim da execução; ou (b) com `db_admin_access = true`, para `db-admin.sh`. O pipeline/operador que executa `migrate.sh` precisa de `iam:PutRolePolicy` e `iam:DeleteRolePolicy` no papel das instâncias (saída `instance_role_name`).

**Com `use_ssm_parameters = false`** (padrão, sem aprovação) — bucket `gestor-cobranca-<env>-secrets-<conta>`, em `s3://…/<env>/<nome>` (o papel das instâncias lê só `<env>/app/*`; `migration/*` como acima): SSE, versionamento, TLS obrigatório, sem acesso público e política que **nega** leitura a qualquer principal que não seja o papel das instâncias ou os administradores de `secrets_admin_principal_arns`, e nega escrita a todos exceto esses administradores — inclusive outros usuários da conta com permissões amplas de S3. Os logs de acesso do S3 registram cada leitura.

Nas instâncias, o user-data grava os PEM em `/etc/gestor-cobranca/` (0600, dono `gestor`) e as variáveis sensíveis em `/etc/gestor-cobranca/secrets.env` (0600, `root`), lido pelo systemd antes de trocar para o usuário do serviço. As variáveis não sensíveis ficam em `app.env`.

**Senha mestre do RDS** — `manage_master_user_password = true`: o próprio RDS gera a senha e a guarda num segredo do Secrets Manager que ele mesmo gerencia e rotaciona. O Terraform só conhece o ARN (`db_master_user_secret_arn`); a senha nunca aparece no plano, no state nem em variável. A conta mestre é usada só para criar os papéis e rotacionar senhas (`scripts/deploy/db-admin.sh`). Para isso, `db_admin_access = true` dá temporariamente ao papel das instâncias `secretsmanager:GetSecretValue` **apenas nesse segredo**. Desligue logo depois do uso. A mudança altera só a política IAM, sem recriar instâncias.

## Pré-requisitos

- Terraform ≥ 1.10 (ou OpenTofu ≥ 1.10), AWS CLI v2, `jq`, `openssl`; para o build: JDK 21, Maven 3.9+, Node/npm (frontend).
- Credenciais AWS com permissão de administração da infraestrutura (de preferência SSO), na região `sa-east-1`.
- Certificado TLS do domínio (ARN no ACM) ou aprovação de `enable_acm_certificate`.
- Plugin do Session Manager na AWS CLI, para sessões interativas.

## 1. Bootstrap do state (uma vez por conta)

```bash
scripts/ops/bootstrap-state.sh --region sa-east-1
cd infra/terraform/envs/homologacao
cp backend.hcl.example backend.hcl          # ajustar o ID da conta (não versionado)
cp terraform.tfvars.example terraform.tfvars # ajustar (não versionado)
terraform init -backend-config=backend.hcl
```

O state fica num bucket versionado e criptografado, com trava nativa do S3 (`use_lockfile`, sem DynamoDB). Um bucket serve aos dois ambientes, com chaves distintas.

## 2. Ordem de aplicação (primeira vez em cada ambiente)

1. **Base, sem computação.** Com `enable_compute = false`:
   ```bash
   terraform apply
   ```
   Cria rede, security groups, buckets, RDS (cerca de 15 min), ALB e observabilidade. O RDS sobe com o banco `cobranca` e só a conta mestre.
2. **Segredos.**
   ```bash
   scripts/ops/provision-secrets.sh --env homologacao generate
   printf '%s' 'usuario-smtp' | scripts/ops/provision-secrets.sh --env homologacao set app/smtp-username
   scripts/ops/provision-secrets.sh --env homologacao set app/smtp-password   # digita o valor e Ctrl-D
   scripts/ops/provision-secrets.sh --env homologacao check
   ```
3. **Primeiro artefato.**
   ```bash
   scripts/deploy/build.sh --env homologacao --version 0.1.0
   ```
4. **Computação em modo bootstrap.** No `terraform.tfvars`: `enable_compute = true`, `bootstrap_mode = true`, `db_admin_access = true` e `initial_artifact_version = "0.1.0"`, depois `terraform apply`. A instância sobe e o serviço inicia, mas ainda não fica *ready*: o banco não tem papéis nem schema e o systemd reinicia a aplicação a cada 10 s. Com `bootstrap_mode`, o ASG usa o health check do EC2, então a instância não é substituída em ciclo.
5. **Papéis do banco** (`scripts/db/bootstrap-roles.sql`, executado na instância via SSM com a conta mestre):
   ```bash
   scripts/deploy/db-admin.sh --env homologacao bootstrap-roles
   ```
6. **Migrations:**
   ```bash
   scripts/deploy/migrate.sh --env homologacao --version 0.1.0
   ```
   Na tentativa seguinte do systemd, a aplicação inicia e o target fica saudável no ALB.
7. **Modo normal.** Volte `bootstrap_mode = false` e `db_admin_access = false` e rode `terraform apply`. O health check passa a ser o do ALB e a instância perde o acesso à senha mestre.
8. **DNS.** Aponte o domínio para `alb_dns_name` (CNAME) ou use `enable_route53`.

## 3. Implantação de uma versão

```bash
scripts/deploy/build.sh   --env producao --version 1.4.0      # testes, empacota, publica (imutável)
scripts/deploy/release.sh --env producao --version 1.4.0      # migrations + instance refresh
```

O `release.sh`:

1. executa `migrate.sh` (uma única vez, numa instância do ASG, via SSM Run Command);
2. cria uma versão do launch template a partir da mais recente, trocando só a tag `ArtifactVersion`;
3. dispara o *instance refresh* (`MinHealthyPercentage` 90, `MaxHealthyPercentage` 200: sobe as novas antes de retirar as antigas), com reversão automática se o alarme de 5xx ou o de instâncias não saudáveis disparar;
4. aguarda o fim. O ALB drena cada instância antiga (*deregistration delay* de 60 s) e o Spring encerra de forma graciosa (`SIGTERM`).

Na instância, o user-data lê a versão pela tag (IMDSv2), baixa `artifacts/<versão>/gestor-cobranca.jar` e `SHA256SUMS` e **recusa** o artefato se o hash não conferir.

**Rollback:**

```bash
scripts/deploy/release.sh --env producao --rollback                         # volta ao artefato anterior
scripts/deploy/release.sh --env producao --rollback --to-template-version 12
```

O rollback cria uma versão nova do launch template com o conteúdo da anterior e repete o refresh. As migrations **não** são revertidas, porque são sempre compatíveis com a versão anterior (*expand/contract*). Se a AWS reverter um refresh sozinha, o `release.sh` restaura o launch template para a versão em uso e termina com erro.

**Invariante com o Terraform:** a versão mais recente do launch template é sempre a que está em uso. O Terraform referencia `latest_version` e ignora mudanças de tag no launch template. Quando ele altera o template (AMI, tipo de instância, scripts de bootstrap), a nova versão herda a `ArtifactVersion` vigente e o próprio Terraform dispara o refresh.

## 4. Contrato com o backend

| Item | Contrato |
| --- | --- |
| Porta | 8080 (`SERVER_PORT` padrão) |
| Health | `/actuator/health/readiness` (ALB) e `/actuator/health/liveness`; `/actuator/**` é bloqueado na borda (404) e o ALB faz o health check direto na instância |
| Encerramento | `SIGTERM` → `server.shutdown=graceful`; o systemd espera até 60 s |
| Frontend | `build.sh` copia `frontend/dist/<projeto>/browser` para `static/` do JAR (servido em `/`) |
| Logs | `${LOG_DIR}` = `/var/log/gestor-cobranca`: `technical.log`, `business.log`, `access.log` (JSON, uma linha por evento, com `env`, `eventType`, `success`) |
| Métricas | Micrometer → CloudWatch, namespace `GestorCobranca`, tags comuns `application` e `env`. Os alarmes usam `jobs.dead`, `events.publications.incomplete`, `access.log.write_failures`, `audit.seal.failures` e `access.events{type,success}` (com os sufixos `.value` e `.count` do registro CloudWatch) |
| Runtime | `SPRING_FLYWAY_ENABLED=false`; conecta como `app_runtime_login` |
| S3 | objetos somente sob `tenants/` (é o que o IAM permite). Arquivos de evidência recebem a tag `retention-class=evidence`, que o lifecycle move para Glacier |
| **Modo migrate** | `java -jar gestor-cobranca.jar --spring.main.web-application-type=none --app.mode=migrate --app.scheduling.enabled=false`, com o mesmo ambiente das instâncias acrescido de `SPRING_FLYWAY_ENABLED=true`, `DB_MIGRATION_USERNAME=app_owner`, `DB_MIGRATION_PASSWORD`, `LOG_DIR=/var/log/gestor-cobranca/migrate` e `CLOUDWATCH_METRICS_ENABLED=false`. O processo aplica o Flyway e **termina** com código 0, ou ≠ 0 em qualquer falha. Não pode abrir porta, executar jobs nem publicar eventos, e precisa concluir dentro de `migration_timeout_seconds` (padrão 55 min) com heap de 512 MiB |

Variáveis de ambiente entregues às instâncias: `SPRING_PROFILES_ACTIVE=prod`, `SPRING_FLYWAY_ENABLED=false`, `APP_ENV`, `APP_BASE_URL`, `APP_INSTANCE_ID` (IMDSv2), `DB_URL` (`…?sslmode=verify-full&sslrootcert=/etc/gestor-cobranca/rds-ca.pem`, bundle global de CA do RDS baixado no boot), `DB_USERNAME=app_runtime_login`, `DB_PASSWORD`, `DB_POOL_SIZE`, `APP_CRYPTO_KEY_K1` (e `APP_CRYPTO_KEYS_<ID>`), `APP_CRYPTO_ACTIVE_KEY_ID`, `APP_CRYPTO_BLIND_INDEX_KEY`, `JWT_ISSUER`, `APP_SECURITY_JWT_PRIVATE_KEY=file:/etc/gestor-cobranca/jwt-private.pem`, `APP_SECURITY_JWT_PUBLIC_KEY=file:/etc/gestor-cobranca/jwt-public.pem`, `S3_BUCKET`, `S3_KMS_KEY_ID`, `AWS_REGION`, `CLOUDWATCH_METRICS_ENABLED=true`, `LOG_DIR`, `LOG_LEVEL`, `CORS_ALLOWED_ORIGINS`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `JOBS_CONCURRENCY`.

## 5. Rotação de segredos

Quem rotaciona: um administrador incluído em `secrets_admin_principal_arns` (ou com acesso ao prefixo SSM). Cada rotação termina com um `release.sh` da versão **atual**, que recria as instâncias para relerem os segredos.

| Segredo | Procedimento |
| --- | --- |
| Senha de `app_runtime_login` | (1) `provision-secrets.sh --env E generate --only app/db-password --force`; (2) `terraform apply` com `db_admin_access = true`; (3) `db-admin.sh --env E rotate-password app_runtime_login`; (4) `release.sh --env E --version <atual> --skip-migrations` **imediatamente**: entre os passos 3 e 4, novas conexões das instâncias antigas falham (o pool existente continua); (5) `db_admin_access = false` |
| Senha de `app_owner` | passos 1 a 3 com `migration/db-owner-password` e `app_owner`; não exige refresh |
| Senha mestre do RDS | gerenciada pelo RDS (rotação automática a cada 7 dias, ou sob demanda no console/CLI) |
| Chave AES de campo | gerar `app/crypto-key-k2` (`CRYPTO_KEY_IDS="k1 k2" provision-secrets.sh … generate`), aplicar `crypto_key_ids = ["k1","k2"]` e `crypto_active_key_id = "k2"` (o Terraform faz o refresh); a chave antiga continua decifrando até a recifragem dos dados pelo backend |
| Chave do blind index | **não rotacionável** sem reindexação; o script recusa |
| Par JWT | `generate --only app/jwt-private-key --force` + refresh; os *access tokens* emitidos com a chave anterior expiram em até 10 min (sessões continuam pelo refresh token) |
| Certificado TLS | ACM renova sozinho; com certificado importado, reimportar no mesmo ARN |

## 6. Operação

- **Sessão na instância:** `aws ssm start-session --target <instance-id>` (sem SSH, auditada).
- **Logs:** grupos `/gestor-cobranca/<env>/{technical,business,access,run-command}`; consultas salvas no Logs Insights (`<prefixo>/tecnico/…`, `/acesso/…`, `/negocio/…`).
- **Arquivo do log de acesso (5 anos):** `scripts/ops/export-access-logs.sh --env producao` todo mês, pelo pipeline (exporta o mês anterior para `s3://…-logs-…/cloudwatch-export/access/AAAA-MM/`; Glacier após 30 dias, expira em 5 anos).
- **Snapshot manual mensal do RDS (12 meses):** pelo pipeline, `aws rds create-db-snapshot --db-instance-identifier <db_identifier> --db-snapshot-identifier <db_identifier>-mensal-AAAA-MM`. Os snapshots com mais de 12 meses são apagados pelo mesmo passo.
- **Homologação fora do horário:** `business_hours_schedule` zera o ASG fora do horário. O RDS continua ligado, porque o agendamento de parada do RDS exigiria um serviço não autorizado.
- **Agendamento × Terraform:** fora do horário o agendamento zera `min/max` do ASG de homologação; um `terraform apply` nesse período os restaura para os valores do tfvars (sobe instâncias). Aplique em homologação dentro do horário comercial.
- **Alarme de espaço livre no RDS:** depois de um autoscaling de armazenamento, atualize `db_allocated_storage_gib` para o novo tamanho.

## 7. Custos por fase (ordem de grandeza, US$/mês, `sa-east-1`, on-demand)

Estimativas a recalcular no [AWS Pricing Calculator](https://calculator.aws/) antes da contratação (ver §5 do documento de infraestrutura).

| Item | Fase 1 (produção) | Fase 2 (produção) | Homologação (fase 1, ASG 12 h/dia útil) |
| --- | --- | --- | --- |
| EC2 `t4g.medium` (1 → 2) + EBS gp3 30 GiB | 45–60 | 90–120 | 15–25 |
| ALB | 25–35 | 30–50 | 20–25 |
| NAT Gateway (1 → 2) + tráfego | 50–70 | 100–140 | 45–55 |
| RDS `db.t4g.medium` Single-AZ → Multi-AZ, gp3 50 GiB | 110–120 | 210–240 | 110–120 |
| S3 (app, artefatos, logs) | 3–8 | 8–20 | 1–3 |
| CloudWatch (logs, métricas da app e do agente, ~20 alarmes, painel) | 20–45 | 45–110 | 10–20 |
| VPC Flow Logs (REJECT) | 1–5 | 2–10 | 1–3 |
| WAF (aprovação) | — | 15–30 | — |
| Endpoints de interface (`enable_interface_endpoints`, 5 × 2 AZs) | — | ~75 (opcional) | — |
| **Total aproximado** | **~US$ 255–345** | **~US$ 500–720** | **~US$ 200–250** |

Principais alavancas: Savings Plans e instância reservada do RDS na fase 2; desligar a homologação fora do horário (ou reduzir o RDS de homologação para `db.t4g.small`); retenção curta do log técnico; ajuste do tamanho das instâncias pelas métricas de 30 dias.

## 8. Validação

```bash
terraform fmt -check -recursive infra/terraform
for e in homologacao producao; do
  terraform -chdir=infra/terraform/envs/$e init -backend=false
  terraform -chdir=infra/terraform/envs/$e validate
  terraform -chdir=infra/terraform/envs/$e test     # planos com provider simulado (tests/plan.tftest.hcl)
done
bash -n scripts/{instance,deploy,ops}/*.sh && shellcheck -x -P SCRIPTDIR scripts/{instance,deploy,ops}/*.sh
```
