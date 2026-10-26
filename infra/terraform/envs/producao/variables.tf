# Variáveis do ambiente (arquivo idêntico em homologacao e producao).

variable "environment" {
  description = "Nome do ambiente."
  type        = string

  validation {
    condition     = contains(["homologacao", "producao"], var.environment)
    error_message = "environment deve ser homologacao ou producao."
  }
}

variable "region" {
  description = "Região AWS."
  type        = string
  default     = "sa-east-1"
}

variable "domain_name" {
  description = "Domínio público da aplicação (ex.: app.gestorcobranca.com.br)."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name deve ser um nome DNS válido em minúsculas."
  }
}

variable "cors_allowed_origins" {
  description = "Origens do CORS. Vazio = somente https://<domain_name>."
  type        = list(string)
  default     = []
}

variable "jwt_issuer" {
  description = "Emissor dos JWT. null = https://<domain_name>."
  type        = string
  default     = null
}

# --- Rede ------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR /16 da VPC (distinto por ambiente, permitindo peering futuro)."
  type        = string
}

variable "nat_gateway_mode" {
  description = "single (fase 1) ou per_az (fase 2, saída resiliente)."
  type        = string
  default     = "single"
}

variable "enable_interface_endpoints" {
  description = "Endpoints de interface para SSM e CloudWatch (custo por hora/AZ)."
  type        = bool
  default     = false
}

variable "flow_logs_traffic_type" {
  description = "VPC Flow Logs: ALL, REJECT, ACCEPT ou null."
  type        = string
  default     = "REJECT"
}

variable "alb_ingress_cidrs_ipv4" {
  description = "Origens IPv4 permitidas no ALB."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "alb_deletion_protection" {
  description = "Proteção contra exclusão do ALB."
  type        = bool
  default     = true
}

# --- TLS / DNS -----------------------------------------------------------------------------

variable "certificate_arn" {
  description = "ARN de certificado TLS existente (ACM, importado ou emitido fora deste código). Obrigatório se enable_acm_certificate = false."
  type        = string
  default     = null
}

variable "enable_acm_certificate" {
  description = "REQUER APROVAÇÃO (ACM). Emite o certificado de domain_name com validação DNS."
  type        = bool
  default     = false
}

variable "enable_route53" {
  description = "REQUER APROVAÇÃO (Route 53). Cria o alias do domínio para o ALB (e a validação do ACM)."
  type        = bool
  default     = false
}

variable "route53_zone_id" {
  description = "Hosted zone do domínio (quando enable_route53 = true)."
  type        = string
  default     = null
}

variable "enable_waf" {
  description = "REQUER APROVAÇÃO (AWS WAF). Web ACL com regras gerenciadas e limite de taxa no ALB."
  type        = bool
  default     = false
}

variable "waf_rate_limit_per_5min" {
  description = "Limite de requisições por IP em 5 minutos (WAF)."
  type        = number
  default     = 2000
}

# --- Segredos e criptografia ---------------------------------------------------------------

variable "use_ssm_parameters" {
  description = "REQUER APROVAÇÃO (SSM Parameter Store + KMS). true: segredos em /gestor-cobranca/<env>/ (SecureString, chave KMS dedicada). false: bucket S3 de segredos dedicado."
  type        = bool
  default     = false
}

variable "secrets_admin_principal_arns" {
  description = "Principais que provisionam segredos no bucket dedicado (use_ssm_parameters = false). Aceita curingas (ex.: papel do SSO)."
  type        = list(string)
  default     = []
}

variable "data_kms_key_arn" {
  description = "REQUER APROVAÇÃO (KMS). Chave do cliente para S3 (app/artefatos), RDS e EBS. null usa as chaves gerenciadas pela AWS."
  type        = string
  default     = null
}

variable "logs_kms_key_arn" {
  description = "REQUER APROVAÇÃO (KMS). Chave do cliente para os grupos de log. null usa a criptografia padrão do CloudWatch Logs."
  type        = string
  default     = null
}

variable "crypto_key_ids" {
  description = "IDs das chaves AES de campo provisionadas."
  type        = list(string)
  default     = ["k1"]
}

variable "crypto_active_key_id" {
  description = "Chave AES ativa para novas cifragens."
  type        = string
  default     = "k1"
}

# --- Banco -------------------------------------------------------------------------------------

variable "db_instance_class" {
  description = "Classe da instância RDS."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_multi_az" {
  description = "RDS Multi-AZ (fase 2)."
  type        = bool
  default     = false
}

variable "db_allocated_storage_gib" {
  description = "Armazenamento gp3 inicial (GiB). Após um autoscaling do RDS, atualizar para o novo valor (limiar do alarme de espaço livre)."
  type        = number
  default     = 50
}

variable "db_max_allocated_storage_gib" {
  description = "Teto do autoscaling de armazenamento (GiB)."
  type        = number
  default     = 200
}

variable "db_max_connections" {
  description = "max_connections do PostgreSQL."
  type        = number
  default     = 300
}

variable "db_backup_retention_days" {
  description = "Retenção de backups automáticos (dias)."
  type        = number
  default     = 14
}

variable "db_deletion_protection" {
  description = "Proteção contra exclusão do RDS."
  type        = bool
  default     = true
}

variable "db_apply_immediately" {
  description = "Aplica mudanças do RDS imediatamente (fora da janela de manutenção)."
  type        = bool
  default     = false
}

variable "db_pool_size" {
  description = "Pool de conexões por instância (DB_POOL_SIZE)."
  type        = number
  default     = 30
}

# --- Computação --------------------------------------------------------------------------------

variable "enable_compute" {
  description = "Cria launch template e ASG. Desligado na primeira aplicação (antes de existir artefato publicado)."
  type        = bool
  default     = true
}

variable "bootstrap_mode" {
  description = "Primeira implantação: health check do ASG pelo EC2 enquanto o schema não existe. Desligar após as migrations."
  type        = bool
  default     = false
}

variable "db_admin_access" {
  description = "Acesso temporário das instâncias à senha mestre do RDS (scripts/deploy/db-admin.sh). Desligar logo após o uso."
  type        = bool
  default     = false
}

variable "initial_artifact_version" {
  description = "Versão do artefato usada ao criar o launch template (depois gerida por scripts/deploy/release.sh)."
  type        = string
}

variable "ami_id" {
  description = "AMI fixa (opcional); null usa a AL2023 arm64 mais recente."
  type        = string
  default     = null
}

variable "instance_type" {
  description = "Tipo de instância Graviton."
  type        = string
  default     = "t4g.medium"
}

variable "asg_min_size" {
  description = "Mínimo de instâncias."
  type        = number
}

variable "asg_max_size" {
  description = "Máximo de instâncias."
  type        = number
}

variable "cpu_target_percent" {
  description = "Alvo de CPU do target tracking."
  type        = number
  default     = 60
}

variable "requests_per_target" {
  description = "Alvo de requisições/minuto por instância (null desliga)."
  type        = number
  default     = 1000
}

variable "business_hours_schedule" {
  description = "Liga/desliga o ASG por agendamento (homologação). null mantém ligado."
  type = object({
    start_cron = string
    stop_cron  = string
    time_zone  = string
  })
  default = null
}

variable "log_level" {
  description = "Nível do log técnico."
  type        = string
  default     = "INFO"
}

variable "smtp_host" {
  description = "Servidor SMTP."
  type        = string
}

variable "smtp_port" {
  description = "Porta SMTP (STARTTLS)."
  type        = number
  default     = 587
}

variable "jobs_concurrency" {
  description = "Paralelismo de jobs por instância."
  type        = number
  default     = 8
}

variable "jvm_max_ram_percentage" {
  description = "Percentual da RAM para o heap da JVM."
  type        = number
  default     = 60
}

# --- Primeiro administrador da plataforma (decisão 500) -----------------------------------------
# Lidos pelo PlatformBootstrap na subida (APP_BOOTSTRAP_PLATFORM_*): cria, uma única vez, o tenant
# "plataforma" e o primeiro PLATFORM_ADMIN, que recebe o link de ativação por e-mail. Depois de criado,
# os valores são ignorados e podem sair do tfvars (minimização do dado pessoal no state).

variable "platform_admin_email" {
  description = "E-mail do primeiro administrador da plataforma. Dado pessoal: marcado como sensível (não aparece no plano); fica no state cifrado. Prefira uma caixa funcional."
  type        = string
  default     = null
  sensitive   = true
}

variable "platform_admin_name" {
  description = "Nome do primeiro administrador da plataforma (opcional)."
  type        = string
  default     = null
}

variable "platform_cnpj" {
  description = "CNPJ do tenant da plataforma (empresa que opera o SaaS). Obrigatório junto com platform_admin_email."
  type        = string
  default     = null
}

# --- Pix (decisões 313 e 316) -----------------------------------------------------------------

variable "payment_pix_allowed_hosts" {
  description = "Hosts dos PSPs contratados aceitos nas contas Pix (api.psp.com.br ou *.psp.com.br). Vazio = qualquer host público; em produção, preencha (decisão 313)."
  type        = list(string)
  default     = []
}

variable "payment_webhook_mtls_enabled" {
  description = "Webhook Pix com mTLS. Exige o listener do ALB com mutual TLS em modo verify e o trust store do PSP, ainda não provisionados (decisão 316): por isso só false é aceito."
  type        = bool
  default     = false
}

# --- Assistente de IA: Amazon Bedrock (aprovado — issue #12) ---------------------------------
# Padrão conservador: desligado; ao ligar, endpoint us-east-1 com perfis geográficos us.* (o modelo
# processa só em regiões dos EUA). sa-east-1 só oferece o perfil global.* (qualquer região comercial).
# [revisar] A transferência internacional (LGPD art. 33) é decisão do responsável.

variable "assistant_bedrock_enabled" {
  description = "Liga o provedor Amazon Bedrock do assistente (permissão IAM mínima e app.assistant.bedrock.enabled)."
  type        = bool
  default     = false
}

variable "bedrock_region" {
  description = "Região do endpoint bedrock-runtime (us-east-1 com perfis us.*; ou sa-east-1 com perfis global.*)."
  type        = string
  default     = "us-east-1"
}

variable "bedrock_standard_model_id" {
  description = "Perfil de inferência/modelo do nível STANDARD (Claude Haiku 5.5)."
  type        = string
  default     = "us.anthropic.claude-haiku-5-5"
}

variable "bedrock_advanced_model_id" {
  description = "Perfil de inferência/modelo do nível ADVANCED (Claude Sonnet 5.5)."
  type        = string
  default     = "us.anthropic.claude-sonnet-5-5"
}

variable "bedrock_international_transfer_approved" {
  description = "Aprovação do responsável para a transferência internacional dos dados do turno (LGPD art. 33) à região/perfil configurados."
  type        = bool
  default     = false
}

# --- Observabilidade ---------------------------------------------------------------------------

variable "enable_sns_notifications" {
  description = "REQUER APROVAÇÃO (SNS). Notificação dos alarmes por tópico SNS."
  type        = bool
  default     = false
}

variable "alarm_email_endpoints" {
  description = "E-mails assinantes dos alarmes (com SNS habilitado)."
  type        = list(string)
  default     = []
}
