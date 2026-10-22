variable "name_prefix" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "vpc_id" {
  description = "VPC onde os security groups são criados."
  type        = string
}

variable "app_port" {
  description = "Porta HTTP da aplicação nas instâncias."
  type        = number
  default     = 8080
}

variable "s3_prefix_list_id" {
  description = "Prefix list do S3 (saída do sg-app pelo endpoint gateway)."
  type        = string
}

variable "alb_ingress_cidrs_ipv4" {
  description = "Origens IPv4 permitidas no ALB. Padrão: internet. Em homologação pode ser restrito à rede corporativa."
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = length(var.alb_ingress_cidrs_ipv4) > 0 && alltrue([for c in var.alb_ingress_cidrs_ipv4 : can(cidrnetmask(c))])
    error_message = "alb_ingress_cidrs_ipv4 deve conter ao menos um CIDR IPv4 válido."
  }
}

variable "smtp_egress_port" {
  description = "Porta SMTP de saída liberada para o provedor de e-mail (587 STARTTLS). null remove a regra."
  type        = number
  default     = 587
}
