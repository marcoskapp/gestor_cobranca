variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos (ex.: gestor-cobranca-producao)."
  type        = string
}

variable "environment" {
  description = "Nome do ambiente (homologacao, producao)."
  type        = string
}

variable "region" {
  description = "Região AWS (usada nos nomes de serviço dos endpoints)."
  type        = string
}

variable "account_id" {
  description = "ID da conta AWS (condição de confiança do papel dos flow logs)."
  type        = string
}

variable "vpc_cidr" {
  description = "Bloco CIDR da VPC. Deve ser /16 para comportar a divisão em /20 por camada e AZ."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr)) && endswith(var.vpc_cidr, "/16")
    error_message = "vpc_cidr deve ser um bloco IPv4 /16 válido (ex.: 10.0.0.0/16)."
  }
}

variable "az_count" {
  description = "Número de zonas de disponibilidade (o RDS exige pelo menos 2 no subnet group)."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 4
    error_message = "az_count deve estar entre 2 e 4."
  }
}

variable "nat_gateway_mode" {
  description = "single: um NAT Gateway (fase 1, menor custo). per_az: um por AZ (saída resiliente à perda de uma AZ)."
  type        = string
  default     = "single"

  validation {
    condition     = contains(["single", "per_az"], var.nat_gateway_mode)
    error_message = "nat_gateway_mode deve ser \"single\" ou \"per_az\"."
  }
}

variable "enable_interface_endpoints" {
  description = "Cria endpoints de interface para SSM e CloudWatch (custo por hora/AZ). O tráfego passa a não depender da NAT."
  type        = bool
  default     = false
}

variable "flow_logs_traffic_type" {
  description = "Tráfego registrado nos VPC Flow Logs: ALL, REJECT, ACCEPT ou null para desligar."
  type        = string
  default     = "REJECT"

  validation {
    condition     = var.flow_logs_traffic_type == null || contains(["ALL", "REJECT", "ACCEPT"], coalesce(var.flow_logs_traffic_type, "ALL"))
    error_message = "flow_logs_traffic_type deve ser ALL, REJECT, ACCEPT ou null."
  }
}

variable "flow_logs_retention_days" {
  description = "Retenção dos VPC Flow Logs no CloudWatch (dias)."
  type        = number
  default     = 30
}

variable "logs_kms_key_arn" {
  description = "Chave KMS gerenciada pelo cliente para os grupos de log (requer aprovação do uso de KMS). null usa a criptografia padrão do CloudWatch Logs."
  type        = string
  default     = null
}
