variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "environment" {
  description = "Nome do ambiente (APP_ENV e prefixos de log/segredos)."
  type        = string
}

variable "region" {
  description = "Região AWS."
  type        = string
}

variable "account_id" {
  description = "ID da conta AWS."
  type        = string
}

variable "partition" {
  description = "Partição AWS."
  type        = string
  default     = "aws"
}

variable "standard_tags" {
  description = "Tags padrão (as de default_tags), repetidas onde o provider não as propaga (ASG → instâncias, volumes)."
  type        = map(string)
}

# --- Rede e balanceamento -----------------------------------------------------------

variable "app_subnet_ids" {
  description = "Sub-redes privadas de aplicação."
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group das instâncias (sg-app)."
  type        = string
}

variable "target_group_arn" {
  description = "Target group do ALB."
  type        = string
}

variable "alb_arn_suffix" {
  description = "Sufixo do ARN do ALB (política ALBRequestCountPerTarget)."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Sufixo do ARN do target group (política ALBRequestCountPerTarget)."
  type        = string
}

# --- Instâncias ------------------------------------------------------------------------

variable "ami_ssm_parameter" {
  description = "Parâmetro público do SSM com a AMI Amazon Linux 2023 arm64."
  type        = string
  default     = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

variable "ami_id" {
  description = "AMI fixa (opcional). null usa a mais recente do parâmetro público; fixar evita instance refresh a cada nova AMI publicada."
  type        = string
  default     = null

  validation {
    condition     = var.ami_id == null || can(regex("^ami-[0-9a-f]{8,17}$", coalesce(var.ami_id, "x")))
    error_message = "ami_id deve ter o formato ami-xxxxxxxx."
  }
}

variable "instance_type" {
  description = "Tipo de instância Graviton."
  type        = string
  default     = "t4g.medium"

  validation {
    condition     = can(regex("^(t4g|m7g|m8g|c7g|c8g|r7g|r8g|m6g|c6g|r6g)\\.", var.instance_type))
    error_message = "Use um tipo Graviton (arm64), compatível com a AMI arm64."
  }
}

variable "cpu_credits" {
  description = "Créditos de CPU para instâncias burstable (standard ou unlimited)."
  type        = string
  default     = "unlimited"

  validation {
    condition     = contains(["standard", "unlimited"], var.cpu_credits)
    error_message = "cpu_credits deve ser standard ou unlimited."
  }
}

variable "root_volume_size_gib" {
  description = "Tamanho do volume raiz (GiB) — sistema, artefato e logs locais rotacionados."
  type        = number
  default     = 30

  validation {
    condition     = var.root_volume_size_gib >= 20
    error_message = "root_volume_size_gib mínimo é 20."
  }
}

variable "ebs_kms_key_arn" {
  description = "Chave KMS do volume raiz. null usa a chave gerenciada aws/ebs."
  type        = string
  default     = null
}

variable "detailed_monitoring" {
  description = "Monitoramento detalhado do EC2 (métricas de 1 minuto; custo adicional)."
  type        = bool
  default     = false
}

variable "min_size" {
  description = "Mínimo de instâncias no ASG."
  type        = number

  validation {
    condition     = var.min_size >= 1
    error_message = "min_size deve ser pelo menos 1 (o desligamento fora do horário usa business_hours_schedule)."
  }
}

variable "max_size" {
  description = "Máximo de instâncias no ASG."
  type        = number
}

variable "health_check_grace_period_seconds" {
  description = "Carência antes de o ASG considerar o health check do ALB (dnf + download do artefato + subida da JVM)."
  type        = number
  default     = 420
}

variable "instance_warmup_seconds" {
  description = "Aquecimento da instância para métricas de escala e instance refresh."
  type        = number
  default     = 180
}

variable "cpu_target_percent" {
  description = "Alvo de CPU média do target tracking."
  type        = number
  default     = 60

  validation {
    condition     = var.cpu_target_percent >= 20 && var.cpu_target_percent <= 90
    error_message = "cpu_target_percent deve estar entre 20 e 90."
  }
}

variable "requests_per_target" {
  description = "Alvo de requisições por instância por minuto (ALBRequestCountPerTarget). null desliga a política."
  type        = number
  default     = 1000
}

variable "business_hours_schedule" {
  description = "Liga/desliga o ASG por agendamento (homologação). null mantém sempre ligado."
  type = object({
    start_cron = string
    stop_cron  = string
    time_zone  = string
  })
  default = null
}

# --- Artefatos e bootstrap ---------------------------------------------------------------

variable "artifacts_bucket_name" {
  description = "Bucket de artefatos."
  type        = string
}

variable "artifacts_bucket_arn" {
  description = "ARN do bucket de artefatos."
  type        = string
}

variable "artifact_prefix" {
  description = "Prefixo dos artefatos de versão no bucket."
  type        = string
  default     = "artifacts"
}

variable "initial_artifact_version" {
  description = "Versão do artefato na criação do launch template. Depois disso a versão é gerida por scripts/deploy/release.sh."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$", var.initial_artifact_version))
    error_message = "Versão inválida: use letras, dígitos, ponto, hífen ou sublinhado (até 64)."
  }
}

# --- Aplicação ---------------------------------------------------------------------------

variable "app_base_url" {
  description = "URL pública da aplicação (APP_BASE_URL)."
  type        = string
}

variable "cors_allowed_origins" {
  description = "Origens permitidas no CORS."
  type        = list(string)
}

variable "jwt_issuer" {
  description = "Emissor dos tokens JWT (JWT_ISSUER)."
  type        = string
}

variable "app_bucket_name" {
  description = "Bucket de arquivos (S3_BUCKET)."
  type        = string
}

variable "app_bucket_arn" {
  description = "ARN do bucket de arquivos."
  type        = string
}

variable "app_bucket_kms_key_arn" {
  description = "Chave KMS do bucket de arquivos (S3_KMS_KEY_ID). null quando SSE-S3."
  type        = string
  default     = null
}

variable "db_address" {
  description = "Endpoint do RDS."
  type        = string
}

variable "db_port" {
  description = "Porta do RDS."
  type        = number
  default     = 5432
}

variable "db_name" {
  description = "Nome do banco."
  type        = string
}

variable "db_master_username" {
  description = "Usuário mestre do RDS (bootstrap de papéis)."
  type        = string
}

variable "db_master_secret_arn" {
  description = "Segredo da senha mestre gerenciado pelo RDS. Legível pelas instâncias somente com db_admin_access = true."
  type        = string
}

variable "bootstrap_mode" {
  description = "Primeira implantação do ambiente: health check do ASG pelo EC2 (a aplicação só fica ready após as migrations). Desligar após o primeiro deploy."
  type        = bool
  default     = false
}

variable "trusted_proxy_cidrs" {
  description = "CIDRs das sub-redes do ALB. A aplicação só aceita X-Forwarded-For/Proto/Port de conexões vindas daqui (APP_HTTP_TRUSTED_PROXIES); o cliente é o primeiro salto não confiável lido da direita."
  type        = list(string)

  validation {
    condition     = length(var.trusted_proxy_cidrs) > 0 && alltrue([for c in var.trusted_proxy_cidrs : can(cidrhost(c, 0))])
    error_message = "trusted_proxy_cidrs deve ter ao menos um CIDR válido (sub-redes do ALB)."
  }
}

variable "db_admin_access" {
  description = "Concede temporariamente ao papel das instâncias a leitura da senha mestre do RDS (bootstrap de papéis, rotação de senhas). Altera somente a política IAM, sem instance refresh."
  type        = bool
  default     = false
}

variable "db_pool_size" {
  description = "Tamanho do pool Hikari por instância (DB_POOL_SIZE)."
  type        = number
  default     = 30
}

variable "crypto_key_ids" {
  description = "IDs das chaves AES de campo provisionadas (k1, k2...). Durante a rotação, manter a antiga e a nova."
  type        = list(string)
  default     = ["k1"]

  validation {
    condition     = length(var.crypto_key_ids) > 0 && alltrue([for k in var.crypto_key_ids : can(regex("^[a-z][a-z0-9]{0,15}$", k))])
    error_message = "crypto_key_ids: ids minúsculos alfanuméricos (ex.: k1)."
  }
}

variable "crypto_active_key_id" {
  description = "Chave usada para novas cifragens (APP_CRYPTO_ACTIVE_KEY_ID)."
  type        = string
  default     = "k1"
}

variable "log_level" {
  description = "Nível do log técnico (LOG_LEVEL)."
  type        = string
  default     = "INFO"

  validation {
    condition     = contains(["ERROR", "WARN", "INFO", "DEBUG"], var.log_level)
    error_message = "log_level deve ser ERROR, WARN, INFO ou DEBUG."
  }
}

variable "smtp_host" {
  description = "Servidor SMTP (SMTP_HOST)."
  type        = string
}

variable "smtp_port" {
  description = "Porta SMTP (SMTP_PORT)."
  type        = number
  default     = 587
}

variable "jobs_concurrency" {
  description = "Paralelismo de jobs por instância (JOBS_CONCURRENCY)."
  type        = number
  default     = 8
}

variable "jvm_max_ram_percentage" {
  description = "Percentual da memória da instância destinado ao heap (-XX:MaxRAMPercentage)."
  type        = number
  default     = 60

  validation {
    condition     = var.jvm_max_ram_percentage >= 25 && var.jvm_max_ram_percentage <= 80
    error_message = "jvm_max_ram_percentage deve estar entre 25 e 80 (o restante atende metaspace, threads, agente e SO)."
  }
}

# --- Primeiro administrador da plataforma (PlatformBootstrap; decisão 500) -------------------
# Entregues como APP_BOOTSTRAP_PLATFORM_* no app.env só quando informados. O bootstrap é
# idempotente: cria o tenant da plataforma e o primeiro PLATFORM_ADMIN uma única vez; depois os
# valores são ignorados (podem ser removidos do tfvars).

variable "platform_admin_email" {
  description = "E-mail do primeiro administrador da plataforma (APP_BOOTSTRAP_PLATFORM_ADMIN_EMAIL). Dado pessoal: sensível no plano."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition     = var.platform_admin_email == null || can(regex("^[^@\\s'\"]+@[^@\\s'\"]+\\.[^@\\s'\"]+$", var.platform_admin_email))
    error_message = "platform_admin_email deve ser um e-mail válido (sem espaços nem aspas)."
  }
}

variable "platform_admin_name" {
  description = "Nome do primeiro administrador da plataforma (APP_BOOTSTRAP_PLATFORM_ADMIN_NAME). null usa o padrão da aplicação."
  type        = string
  default     = null

  validation {
    condition     = var.platform_admin_name == null || can(regex("^[^'\"\\n\\r\\\\$`]{1,150}$", var.platform_admin_name))
    error_message = "platform_admin_name: até 150 caracteres, sem aspas, barra invertida, cifrão, crase nem quebra de linha."
  }
}

variable "platform_cnpj" {
  description = "CNPJ do tenant da plataforma (APP_BOOTSTRAP_PLATFORM_CNPJ), 14 dígitos, com ou sem pontuação."
  type        = string
  default     = null

  validation {
    condition     = var.platform_cnpj == null || can(regex("^[0-9]{2}\\.?[0-9]{3}\\.?[0-9]{3}/?[0-9]{4}-?[0-9]{2}$", var.platform_cnpj))
    error_message = "platform_cnpj deve ter 14 dígitos (ex.: 11.222.333/0001-81)."
  }
}

# --- Pix (decisões 313 e 316) -------------------------------------------------------------------

variable "payment_pix_allowed_hosts" {
  description = "Hosts de PSP aceitos nas contas Pix (PAYMENT_PIX_ALLOWED_HOSTS): api.psp.com.br ou *.psp.com.br. Vazio = qualquer host público."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([for h in var.payment_pix_allowed_hosts :
    can(regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", h))])
    error_message = "payment_pix_allowed_hosts: nomes DNS em minúsculas (api.psp.com.br ou *.psp.com.br), sem esquema, porta ou caminho."
  }
}

variable "payment_webhook_mtls_enabled" {
  description = "PAYMENT_WEBHOOK_MTLS_ENABLED: webhook Pix com mTLS. Só com o listener do ALB verificando o certificado do PSP (decisão 316)."
  type        = bool
  default     = false
}

variable "java_opts_extra" {
  description = "Opções adicionais da JVM (sem segredos)."
  type        = string
  default     = ""
}

variable "migration_timeout_seconds" {
  description = "Tempo máximo de execução das migrations na instância."
  type        = number
  default     = 3300
}

# --- Observabilidade ----------------------------------------------------------------------

variable "log_group_arns" {
  description = "ARNs dos grupos de log em que as instâncias podem escrever."
  type        = map(string)
}

variable "metrics_namespace" {
  description = "Namespace das métricas da aplicação."
  type        = string
  default     = "GestorCobranca"
}

variable "host_metrics_namespace" {
  description = "Namespace das métricas do CloudWatch Agent (memória, disco)."
  type        = string
  default     = "GestorCobranca/Host"
}

# --- Segredos -----------------------------------------------------------------------------

variable "use_ssm_parameters" {
  description = "REQUER APROVAÇÃO (SSM Parameter Store + KMS). true: segredos lidos de SecureStrings em ssm_parameter_prefix. false: de um bucket S3 dedicado."
  type        = bool
  default     = false
}

variable "ssm_parameter_prefix" {
  description = "Prefixo dos parâmetros (/gestor-cobranca/<env>)."
  type        = string
}

variable "ssm_kms_key_arn" {
  description = "Chave KMS gerenciada pelo cliente das SecureStrings. null quando se usa a chave aws/ssm (a permissão de decrypt é implícita)."
  type        = string
  default     = null
}

variable "secrets_bucket_name" {
  description = "Bucket de segredos (quando use_ssm_parameters = false)."
  type        = string
  default     = ""
}

variable "secrets_bucket_arn" {
  description = "ARN do bucket de segredos (quando use_ssm_parameters = false)."
  type        = string
  default     = ""
}

variable "instance_role_name" {
  description = "Nome do papel IAM das instâncias (referenciado na política do bucket de segredos)."
  type        = string
}

# --- Assistente de IA: Amazon Bedrock (aprovado — issue #12) ---------------------------------
# Nenhum modelo Claude processa no Brasil: sa-east-1 só chama o perfil global (qualquer região
# comercial). Padrão conservador: endpoint us-east-1 com perfis geográficos us.* (só regiões dos
# EUA). Ver docs/tecnico/desenho/assistente.md §6.5 e docs/arquitetura/infraestrutura-aws.md §8.

variable "assistant_bedrock_enabled" {
  description = "Liga o provedor Amazon Bedrock do assistente (permissão IAM e app.assistant.bedrock.enabled)."
  type        = bool
  default     = false
}

variable "bedrock_region" {
  description = "Região do endpoint bedrock-runtime chamado pela aplicação (o perfil de inferência decide onde o modelo processa)."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.bedrock_region))
    error_message = "bedrock_region deve ser uma região AWS (ex.: us-east-1)."
  }
}

variable "bedrock_standard_model_id" {
  description = "Modelo ou perfil de inferência do nível STANDARD (conversa geral, identificação, intenção)."
  type        = string
  default     = "us.anthropic.claude-haiku-5-5"

  validation {
    condition     = can(regex("^((us|eu|apac|au|jp|ca|global)\\.)?anthropic\\.[a-z0-9.-]+(:[0-9]+)?$", var.bedrock_standard_model_id))
    error_message = "bedrock_standard_model_id deve ser um modelo Claude (anthropic.*) ou perfil de inferência de sistema (us./eu./apac./au./jp./ca./global.)."
  }
}

variable "bedrock_advanced_model_id" {
  description = "Modelo ou perfil de inferência do nível ADVANCED (negociação, proposta, aceite e Pix)."
  type        = string
  default     = "us.anthropic.claude-sonnet-5-5"

  validation {
    condition     = can(regex("^((us|eu|apac|au|jp|ca|global)\\.)?anthropic\\.[a-z0-9.-]+(:[0-9]+)?$", var.bedrock_advanced_model_id))
    error_message = "bedrock_advanced_model_id deve ser um modelo Claude (anthropic.*) ou perfil de inferência de sistema (us./eu./apac./au./jp./ca./global.)."
  }
}

variable "bedrock_international_transfer_approved" {
  description = "O responsável aprovou a transferência internacional dos dados do turno (LGPD art. 33) para a região/perfil configurados. Sem isso a aplicação recusa subir com o Bedrock."
  type        = bool
  default     = false
}
