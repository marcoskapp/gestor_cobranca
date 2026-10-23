# -----------------------------------------------------------------------------
# Cadeia de confiança dos security groups (docs/arquitetura/infraestrutura-aws.md §2):
#   sg-alb  entrada 443/80 da internet          saída 8080 → sg-app
#   sg-app  entrada 8080 somente de sg-alb      saída 5432 → sg-db; 443 → internet (NAT); S3 (endpoint)
#   sg-db   entrada 5432 somente de sg-app      saída nenhuma
#
# Os grupos são criados sem regras inline (o provider remove a regra "allow all" de saída
# padrão da AWS) e cada regra é um recurso próprio, referenciando o grupo de origem/destino.
# DNS (Route 53 Resolver), IMDS e Amazon Time Sync usam endereços link-local/da VPC que não
# passam pelos security groups.
# -----------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-sg-alb"
  description = "ALB: HTTPS/HTTP da internet; encaminha somente para as instancias da aplicacao"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-sg-alb" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-sg-app"
  description = "Instancias da aplicacao: 8080 somente do ALB"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-sg-app" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-sg-db"
  description = "RDS PostgreSQL: 5432 somente das instancias da aplicacao"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-sg-db" }

  lifecycle {
    create_before_destroy = true
  }
}

# --- sg-alb ------------------------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = toset(var.alb_ingress_cidrs_ipv4)

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS de clientes"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each = toset(var.alb_ingress_cidrs_ipv4)

  security_group_id = aws_security_group.alb.id
  description       = "HTTP de clientes (apenas redirecionamento para HTTPS)"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Encaminhamento e health check para a aplicacao"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port
  to_port                      = var.app_port
  referenced_security_group_id = aws_security_group.app.id
}

# --- sg-app ------------------------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Trafego do ALB"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port
  to_port                      = var.app_port
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  description                  = "PostgreSQL"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.db.id
}

resource "aws_vpc_security_group_egress_rule" "app_https_internet" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS para provedores externos e APIs AWS (via NAT)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "app_s3_endpoint" {
  security_group_id = aws_security_group.app.id
  description       = "S3 pelo endpoint gateway"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = var.s3_prefix_list_id
}

# SMTP de submissão (STARTTLS) para o provedor de e-mail. Não consta da tabela original do
# documento porque o envio de e-mail usa SMTP_PORT (587 por padrão); null desliga a regra.
resource "aws_vpc_security_group_egress_rule" "app_smtp" {
  count = var.smtp_egress_port == null ? 0 : 1

  security_group_id = aws_security_group.app.id
  description       = "SMTP de submissao (STARTTLS)"
  ip_protocol       = "tcp"
  from_port         = var.smtp_egress_port
  to_port           = var.smtp_egress_port
  cidr_ipv4         = "0.0.0.0/0"
}

# --- sg-db -------------------------------------------------------------------------

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL das instancias da aplicacao"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.app.id
}
# sg-db não possui regra de saída.
