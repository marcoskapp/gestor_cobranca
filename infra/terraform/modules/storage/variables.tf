variable "name_prefix" {
  description = "Prefixo dos nomes dos buckets (o ID da conta é acrescentado para unicidade global)."
  type        = string
}

variable "account_id" {
  description = "ID da conta AWS."
  type        = string
}

variable "region" {
  description = "Região AWS."
  type        = string
}

variable "partition" {
  description = "Partição AWS (aws)."
  type        = string
  default     = "aws"
}

variable "kms_key_arn" {
  description = "Chave KMS gerenciada pelo cliente para SSE-KMS dos buckets app e artifacts (REQUER APROVAÇÃO do uso de KMS). null usa SSE-S3 (AES-256)."
  type        = string
  default     = null
}

variable "app_ia_transition_days" {
  description = "Dias até mover arquivos de tenants para Standard-IA."
  type        = number
  default     = 90

  validation {
    condition     = var.app_ia_transition_days >= 30
    error_message = "Standard-IA exige no mínimo 30 dias."
  }
}

variable "app_evidence_glacier_days" {
  description = "Dias até mover objetos com tag retention-class=evidence para Glacier Flexible Retrieval."
  type        = number
  default     = 365
}

variable "app_noncurrent_expiration_days" {
  description = "Dias de retenção de versões não correntes no bucket da aplicação (recuperação de exclusões/sobrescritas)."
  type        = number
  default     = 365
}

variable "alb_logs_expiration_days" {
  description = "Retenção dos access logs do ALB e dos logs de acesso do S3 (dias)."
  type        = number
  default     = 400
}

variable "access_log_archive_days" {
  description = "Retenção do arquivo de log de acesso da aplicação exportado do CloudWatch (padrão: 5 anos)."
  type        = number
  default     = 1827

  validation {
    condition     = var.access_log_archive_days >= 1827
    error_message = "O arquivo do log de acesso deve ser mantido por pelo menos 5 anos (1827 dias)."
  }
}

variable "enable_secrets_bucket" {
  description = "Cria o bucket de segredos (alternativa ao SSM Parameter Store quando use_ssm_parameters = false)."
  type        = bool
  default     = false
}

variable "secrets_reader_role_arns" {
  description = "Papéis que podem ler segredos (papel das instâncias)."
  type        = list(string)
  default     = []
}

variable "secrets_admin_principal_arns" {
  description = "Principais (papéis de administração/SSO) que podem gravar e ler segredos. Aceita curingas do aws:PrincipalArn."
  type        = list(string)
  default     = []
}
