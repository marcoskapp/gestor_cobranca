variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "partition" {
  description = "Partição AWS."
  type        = string
  default     = "aws"
}

variable "subnet_ids" {
  description = "Sub-redes privadas de dados (pelo menos 2 AZs)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "O subnet group do RDS exige sub-redes em pelo menos 2 AZs."
  }
}

variable "security_group_id" {
  description = "Security group do banco (sg-db)."
  type        = string
}

variable "engine_version" {
  description = "Versão do PostgreSQL. Informar só a major (\"16\") deixa as minors a cargo do auto minor upgrade."
  type        = string
  default     = "16"

  validation {
    condition     = can(regex("^16(\\.[0-9]+)?$", var.engine_version))
    error_message = "O projeto usa PostgreSQL 16 (\"16\" ou \"16.x\")."
  }
}

variable "instance_class" {
  description = "Classe da instância (fase 1: db.t4g.medium; fase 2: db.t4g.large ou db.m7g.large)."
  type        = string
  default     = "db.t4g.medium"

  validation {
    condition     = startswith(var.instance_class, "db.")
    error_message = "instance_class deve ser uma classe RDS (db.*)."
  }
}

variable "multi_az" {
  description = "Multi-AZ com failover automático (fase 2)."
  type        = bool
  default     = false
}

variable "ca_cert_identifier" {
  description = "Autoridade certificadora do certificado TLS do servidor."
  type        = string
  default     = "rds-ca-rsa2048-g1"
}

variable "db_name" {
  description = "Nome do banco da aplicação."
  type        = string
  default     = "cobranca"
}

variable "master_username" {
  description = "Usuário mestre do RDS (usado apenas no bootstrap de papéis)."
  type        = string
  default     = "cobranca_master"

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{2,15}$", var.master_username)) && !contains(["postgres", "app_owner", "app_runtime", "app_runtime_login"], var.master_username)
    error_message = "master_username deve ter 3-16 caracteres [a-z0-9_] e não pode coincidir com os papéis da aplicação."
  }
}

variable "master_user_secret_kms_key_id" {
  description = "Chave KMS do segredo da senha mestre no Secrets Manager. null usa a chave gerenciada aws/secretsmanager."
  type        = string
  default     = null
}

variable "storage_kms_key_arn" {
  description = "Chave KMS do armazenamento e do Performance Insights. null usa a chave gerenciada aws/rds."
  type        = string
  default     = null
}

variable "allocated_storage_gib" {
  description = "Armazenamento inicial gp3 (GiB)."
  type        = number
  default     = 50

  validation {
    condition     = var.allocated_storage_gib >= 20
    error_message = "allocated_storage_gib mínimo é 20."
  }
}

variable "max_allocated_storage_gib" {
  description = "Teto do autoscaling de armazenamento (GiB)."
  type        = number
  default     = 200
}

variable "max_connections" {
  description = "max_connections do PostgreSQL. Dimensionar: (pool por instância x instâncias máximas) + migrations + folga administrativa."
  type        = number
  default     = 300

  validation {
    condition     = var.max_connections >= 50 && var.max_connections <= 5000
    error_message = "max_connections deve estar entre 50 e 5000."
  }
}

variable "log_min_duration_statement_ms" {
  description = "Registra no log do PostgreSQL instruções mais lentas que este valor (ms)."
  type        = number
  default     = 1000
}

variable "log_retention_days" {
  description = "Retenção dos logs do PostgreSQL exportados para o CloudWatch."
  type        = number
  default     = 30
}

variable "logs_kms_key_arn" {
  description = "Chave KMS dos grupos de log (opcional)."
  type        = string
  default     = null
}

variable "backup_retention_days" {
  description = "Retenção dos backups automáticos (restauração ponto-a-ponto)."
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "backup_retention_days deve estar entre 7 e 35."
  }
}

variable "backup_window" {
  description = "Janela de backup diária (UTC). Padrão: 02:00–03:00 em Brasília."
  type        = string
  default     = "05:00-06:00"
}

variable "maintenance_window" {
  description = "Janela de manutenção semanal (UTC), sem sobreposição com a de backup."
  type        = string
  default     = "sun:06:30-sun:07:30"
}

variable "deletion_protection" {
  description = "Impede a exclusão acidental da instância."
  type        = bool
  default     = true
}

variable "performance_insights_retention_days" {
  description = "Retenção do Performance Insights (7 dias não tem custo adicional)."
  type        = number
  default     = 7
}

variable "database_insights_mode" {
  description = "Modo do CloudWatch Database Insights: standard (incluso) ou advanced (pago, retenção de 15 meses)."
  type        = string
  default     = "standard"

  validation {
    condition     = contains(["standard", "advanced"], var.database_insights_mode)
    error_message = "database_insights_mode deve ser standard ou advanced."
  }
}

variable "monitoring_interval" {
  description = "Intervalo do Enhanced Monitoring em segundos (0 desliga)."
  type        = number
  default     = 60

  validation {
    condition     = contains([0, 1, 5, 10, 15, 30, 60], var.monitoring_interval)
    error_message = "monitoring_interval deve ser 0, 1, 5, 10, 15, 30 ou 60."
  }
}

variable "apply_immediately" {
  description = "Aplica mudanças imediatamente em vez de na janela de manutenção."
  type        = bool
  default     = false
}
