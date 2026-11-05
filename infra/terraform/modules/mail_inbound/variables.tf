variable "name_prefix" {
  description = "Prefixo dos nomes (bucket, fila, tópico, regra)."
  type        = string
}

variable "account_id" {
  description = "ID da conta AWS."
  type        = string
}

variable "region" {
  description = "Região do recebimento pelo SES (o SES recebe e-mail em sa-east-1; bucket, tópico e fila ficam na mesma região)."
  type        = string
}

variable "partition" {
  description = "Partição AWS."
  type        = string
  default     = "aws"
}

variable "inbound_domain" {
  description = "Subdomínio dedicado às respostas dos credores (ex.: respostas.gestorcobranca.com.br). O MX dele aponta para inbound-smtp.<região>.amazonaws.com."
  type        = string
}

variable "instance_role_name" {
  description = "Papel das instâncias da aplicação: recebe só leitura do bucket de entrada e consumo da fila."
  type        = string
}

variable "raw_retention_days" {
  description = "Dias até apagar o e-mail bruto do bucket de entrada (a aplicação guarda a sua cópia com SHA-256 na categoria INBOUND_MAIL)."
  type        = number
  default     = 30

  validation {
    condition     = var.raw_retention_days >= 7 && var.raw_retention_days <= 365
    error_message = "Entre 7 e 365 dias."
  }
}

variable "kms_key_arn" {
  description = "Chave KMS gerenciada pelo cliente para o bucket (REQUER APROVAÇÃO do uso de KMS); null usa SSE-S3."
  type        = string
  default     = null
}
