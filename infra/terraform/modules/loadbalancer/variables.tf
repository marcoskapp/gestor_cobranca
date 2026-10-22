variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos (nomes do ALB e do target group são truncados em 32 caracteres)."
  type        = string
}

variable "vpc_id" {
  description = "VPC do target group."
  type        = string
}

variable "public_subnet_ids" {
  description = "Sub-redes públicas do ALB (pelo menos 2 AZs)."
  type        = list(string)

  validation {
    condition     = length(var.public_subnet_ids) >= 2
    error_message = "O ALB exige sub-redes em pelo menos 2 AZs."
  }
}

variable "security_group_id" {
  description = "Security group do ALB (sg-alb)."
  type        = string
}

variable "app_port" {
  description = "Porta da aplicação nas instâncias."
  type        = number
  default     = 8080
}

variable "certificate_arn" {
  description = "ARN do certificado TLS do listener 443 (ACM emitido ou importado)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:[a-z0-9-]+:[0-9]{12}:certificate/.+$", var.certificate_arn))
    error_message = "certificate_arn deve ser o ARN de um certificado ACM."
  }
}

variable "ssl_policy" {
  description = "Política TLS do listener HTTPS (TLS 1.3 + 1.2 com cifras modernas)."
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
}

variable "deregistration_delay_seconds" {
  description = "Tempo de drenagem de conexões ao remover uma instância (instance refresh, scale-in)."
  type        = number
  default     = 60

  validation {
    condition     = var.deregistration_delay_seconds >= 0 && var.deregistration_delay_seconds <= 3600
    error_message = "deregistration_delay_seconds deve estar entre 0 e 3600."
  }
}

variable "slow_start_seconds" {
  description = "Rampa de tráfego para instâncias recém-registradas (aquecimento da JVM). 0 desliga."
  type        = number
  default     = 60
}

variable "idle_timeout_seconds" {
  description = "Tempo ocioso de conexão do ALB (uploads de carteira e exportações longas)."
  type        = number
  default     = 120
}

variable "deletion_protection" {
  description = "Proteção contra exclusão do ALB."
  type        = bool
  default     = true
}

variable "access_logs_bucket" {
  description = "Bucket dos access logs do ALB."
  type        = string
}

variable "access_logs_prefix" {
  description = "Prefixo dos access logs no bucket."
  type        = string
  default     = "alb"
}

variable "connection_logs_enabled" {
  description = "Registra também os logs de conexão (handshake TLS) do ALB."
  type        = bool
  default     = false
}
