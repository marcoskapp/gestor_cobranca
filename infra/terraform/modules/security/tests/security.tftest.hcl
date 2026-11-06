# Cadeia sg-alb → sg-app → sg-db com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/security

mock_provider "aws" {
  override_during = plan
  mock_resource "aws_security_group" { defaults = { arn = "arn:aws:ec2:sa-east-1:123456789012:security-group/sg-x" } }
}

variables {
  name_prefix       = "gestor-cobranca-homologacao"
  vpc_id            = "vpc-0123456789abcdef0"
  s3_prefix_list_id = "pl-6aa54003"
}

run "somente_o_alb_recebe_da_internet" {
  command = plan

  assert {
    condition = alltrue([for r in concat(values(aws_vpc_security_group_ingress_rule.alb_https), values(aws_vpc_security_group_ingress_rule.alb_http)) :
    r.security_group_id == aws_security_group.alb.id && contains([80, 443], r.from_port) && r.from_port == r.to_port])
    error_message = "entrada da internet só no sg-alb, só 80 e 443"
  }
  assert {
    condition = (aws_vpc_security_group_ingress_rule.app_from_alb.cidr_ipv4 == null
      && aws_vpc_security_group_ingress_rule.app_from_alb.referenced_security_group_id == aws_security_group.alb.id
    && aws_vpc_security_group_ingress_rule.app_from_alb.from_port == 8080 && aws_vpc_security_group_ingress_rule.app_from_alb.to_port == 8080)
    error_message = "sg-app recebe só do sg-alb, só na porta da aplicação"
  }
  assert {
    condition = (aws_vpc_security_group_ingress_rule.db_from_app.cidr_ipv4 == null
      && aws_vpc_security_group_ingress_rule.db_from_app.referenced_security_group_id == aws_security_group.app.id
    && aws_vpc_security_group_ingress_rule.db_from_app.from_port == 5432 && aws_vpc_security_group_ingress_rule.db_from_app.to_port == 5432)
    error_message = "sg-db recebe só do sg-app, só na 5432"
  }
  assert {
    condition = (aws_vpc_security_group_egress_rule.alb_to_app.referenced_security_group_id == aws_security_group.app.id
    && aws_vpc_security_group_egress_rule.alb_to_app.cidr_ipv4 == null)
    error_message = "o ALB só encaminha para o sg-app"
  }
  assert {
    condition = (aws_vpc_security_group_egress_rule.app_to_db.referenced_security_group_id == aws_security_group.db.id
    && aws_vpc_security_group_egress_rule.app_to_db.cidr_ipv4 == null)
    error_message = "o PostgreSQL só é alcançado pelo sg-db, nunca por faixa de IP"
  }
  # Saída para a internet do sg-app: só HTTPS (provedores, APIs AWS) e SMTP de submissão.
  assert {
    condition = (aws_vpc_security_group_egress_rule.app_https_internet.from_port == 443
      && aws_vpc_security_group_egress_rule.app_https_internet.to_port == 443
    && aws_vpc_security_group_egress_rule.app_smtp[0].from_port == 587 && aws_vpc_security_group_egress_rule.app_smtp[0].to_port == 587)
    error_message = "saída do sg-app para 0.0.0.0/0 limitada a 443 e 587"
  }
  # Protocolo "-1" (todos) ignoraria as portas: toda regra precisa ser TCP.
  assert {
    condition = alltrue([for r in concat(
      values(aws_vpc_security_group_ingress_rule.alb_https), values(aws_vpc_security_group_ingress_rule.alb_http),
      [aws_vpc_security_group_ingress_rule.app_from_alb, aws_vpc_security_group_ingress_rule.db_from_app],
      [aws_vpc_security_group_egress_rule.alb_to_app, aws_vpc_security_group_egress_rule.app_to_db],
      [aws_vpc_security_group_egress_rule.app_https_internet, aws_vpc_security_group_egress_rule.app_s3_endpoint],
      aws_vpc_security_group_egress_rule.app_smtp,
    ) : r.ip_protocol == "tcp"])
    error_message = "todas as regras são TCP (com protocolo -1 as portas seriam ignoradas)"
  }
}

run "origens_restritas_no_alb" {
  command = plan
  variables {
    alb_ingress_cidrs_ipv4 = ["203.0.113.0/24"]
    smtp_egress_port       = null
  }

  assert {
    condition = (toset([for r in aws_vpc_security_group_ingress_rule.alb_https : r.cidr_ipv4]) == toset(["203.0.113.0/24"])
    && toset([for r in aws_vpc_security_group_ingress_rule.alb_http : r.cidr_ipv4]) == toset(["203.0.113.0/24"]))
    error_message = "com origens restritas, nenhuma regra 0.0.0.0/0 no ALB"
  }
  assert {
    condition     = length(aws_vpc_security_group_egress_rule.app_smtp) == 0
    error_message = "smtp_egress_port = null remove a saída SMTP"
  }
}
