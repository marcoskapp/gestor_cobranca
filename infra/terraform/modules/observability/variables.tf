variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "environment" {
  description = "Nome do ambiente (dimensão env das métricas e nome dos grupos de log)."
  type        = string
}

variable "region" {
  description = "Região AWS (painel)."
  type        = string
}

variable "account_id" {
  description = "ID da conta AWS."
  type        = string
}

variable "application_name" {
  description = "Valor da dimensão application das métricas (spring.application.name)."
  type        = string
  default     = "gestor-cobranca"
}

variable "metrics_namespace" {
  description = "Namespace das métricas da aplicação."
  type        = string
  default     = "GestorCobranca"
}

variable "host_metrics_namespace" {
  description = "Namespace das métricas do CloudWatch Agent."
  type        = string
  default     = "GestorCobranca/Host"
}

# --- Retenções ---------------------------------------------------------------------------

variable "technical_retention_days" {
  description = "Retenção do log técnico (dias)."
  type        = number
  default     = 30
}

variable "business_retention_days" {
  description = "Retenção do log de negócio (13 meses ≈ 400 dias)."
  type        = number
  default     = 400
}

variable "access_retention_days" {
  description = "Retenção do log de acesso no CloudWatch (13 meses); o arquivo de 5 anos fica no S3."
  type        = number
  default     = 400
}

variable "logs_kms_key_arn" {
  description = "Chave KMS gerenciada pelo cliente para os grupos de log (REQUER APROVAÇÃO). null usa a criptografia padrão."
  type        = string
  default     = null
}

variable "retain_log_groups_on_destroy" {
  description = "Mantém os grupos de log na conta quando removidos do Terraform (evidência de rastreabilidade)."
  type        = bool
  default     = true
}

# --- Recursos monitorados ----------------------------------------------------------------

variable "alb_arn_suffix" {
  description = "Sufixo do ARN do ALB."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Sufixo do ARN do target group."
  type        = string
}

variable "autoscaling_group_name" {
  description = "Nome do ASG."
  type        = string
}

variable "db_instance_identifier" {
  description = "Identificador da instância RDS."
  type        = string
}

variable "db_allocated_storage_gib" {
  description = "Armazenamento alocado do RDS (limiar de 20% livre)."
  type        = number
}

variable "db_max_connections" {
  description = "max_connections do RDS (limiar de 80%)."
  type        = number
}

# --- Limiares ------------------------------------------------------------------------------

variable "alb_5xx_rate_threshold_percent" {
  description = "Percentual de respostas 5xx que dispara o alarme."
  type        = number
  default     = 1
}

variable "alb_5xx_min_requests" {
  description = "Mínimo de requisições em 5 min para avaliar a taxa de 5xx (evita alarme com tráfego irrisório)."
  type        = number
  default     = 50
}

variable "latency_p95_threshold_seconds" {
  description = "Latência p95 que dispara o alarme (segundos)."
  type        = number
  default     = 1.5
}

variable "login_failure_anomaly_band" {
  description = "Largura da banda de detecção de anomalia (desvios-padrão) para logins negados."
  type        = number
  default     = 3
}

variable "inbox_sla_breaches_threshold" {
  description = "Conversas que estouraram a meta de primeira resposta em 15 min que disparam o alarme (todas as assessorias e canais)."
  type        = number
  default     = 10
}

# --- Notificação ---------------------------------------------------------------------------

variable "enable_sns_notifications" {
  description = "REQUER APROVAÇÃO (SNS). Cria tópico SNS e o associa a todos os alarmes."
  type        = bool
  default     = false
}

variable "sns_kms_key_id" {
  description = "Chave KMS do tópico SNS (alias/aws/sns ou chave do cliente)."
  type        = string
  default     = "alias/aws/sns"
}

variable "alarm_email_endpoints" {
  description = "E-mails assinantes do tópico de alarmes (cada assinatura precisa ser confirmada)."
  type        = list(string)
  default     = []
}

variable "extra_alarm_action_arns" {
  description = "Ações adicionais dos alarmes (ARNs existentes)."
  type        = list(string)
  default     = []
}
