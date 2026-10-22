# -----------------------------------------------------------------------------
# RDS PostgreSQL 16.
#   - Sub-redes de dados (sem rota para fora da VPC), acesso só pelo sg-db.
#   - TLS obrigatório (rds.force_ssl=1, TLS >= 1.2); a aplicação usa sslmode=verify-full.
#   - Senha mestre gerenciada pelo próprio RDS no Secrets Manager
#     (manage_master_user_password): o Terraform nunca conhece a senha e ela não vai para o
#     state. A conta mestre só é usada no bootstrap de papéis (scripts/db/bootstrap-roles.sql).
#   - Criptografia em repouso (KMS), backups de 14 dias, deletion protection, snapshot final.
# -----------------------------------------------------------------------------

locals {
  identifier = "${var.name_prefix}-db"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-db"
  description = "Sub-redes privadas de dados do ${var.name_prefix}"
  subnet_ids  = var.subnet_ids

  tags = { Name = "${var.name_prefix}-db" }
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-pg16-"
  family      = "postgres16"
  description = "Parametros do PostgreSQL 16 do ${var.name_prefix}"

  # TLS obrigatório para qualquer conexão.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "ssl_min_protocol_version"
    value        = "TLSv1.2"
    apply_method = "pending-reboot"
  }

  # Diagnóstico de desempenho: consultas lentas, esperas por lock e arquivos temporários.
  parameter {
    name  = "log_min_duration_statement"
    value = tostring(var.log_min_duration_statement_ms)
  }

  parameter {
    name  = "log_lock_waits"
    value = "1"
  }

  parameter {
    name  = "log_temp_files"
    value = "10240"
  }

  parameter {
    name  = "log_autovacuum_min_duration"
    value = "10000"
  }

  parameter {
    name  = "track_io_timing"
    value = "1"
  }

  # Rastreabilidade: conexões e desconexões ficam no log do PostgreSQL, exportado para o
  # CloudWatch Logs.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # log_statement fica em "none": com "ddl", CREATE/ALTER ROLE ... PASSWORD do bootstrap
  # e da rotação de senhas iriam em texto claro para o CloudWatch. Mudanças de schema são
  # rastreadas pelo histórico do Flyway (shared.flyway_schema_history).
  parameter {
    name  = "log_statement"
    value = "none"
  }

  # Valores de bind parameters nunca vão para o log (podem conter dados pessoais).
  parameter {
    name  = "log_parameter_max_length"
    value = "0"
  }

  parameter {
    name  = "log_parameter_max_length_on_error"
    value = "0"
  }

  # Limite explícito de conexões: torna o alarme de conexões (% do máximo) determinístico.
  parameter {
    name         = "max_connections"
    value        = tostring(var.max_connections)
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Grupos de log exportados pelo RDS, criados antes da instância para aplicar retenção
# (o RDS os criaria com retenção infinita).
resource "aws_cloudwatch_log_group" "rds" {
  for_each = toset(["postgresql", "upgrade"])

  name              = "/aws/rds/instance/${local.identifier}/${each.value}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
}

# --- Enhanced Monitoring (métricas de SO do banco no CloudWatch) --------------------------

data "aws_iam_policy_document" "monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  count = var.monitoring_interval > 0 ? 1 : 0

  name               = "${var.name_prefix}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume.json
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  count = var.monitoring_interval > 0 ? 1 : 0

  role       = aws_iam_role.monitoring[0].name
  policy_arn = "arn:${var.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# --- Instância ---------------------------------------------------------------------------

resource "aws_db_instance" "this" {
  identifier = local.identifier

  engine                      = "postgres"
  engine_version              = var.engine_version
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  instance_class              = var.instance_class
  multi_az                    = var.multi_az
  ca_cert_identifier          = var.ca_cert_identifier

  db_name                       = var.db_name
  username                      = var.master_username
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.master_user_secret_kms_key_id
  port                          = 5432

  storage_type          = "gp3"
  allocated_storage     = var.allocated_storage_gib
  max_allocated_storage = var.max_allocated_storage_gib
  storage_encrypted     = true
  kms_key_id            = var.storage_kms_key_arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  publicly_accessible    = false
  network_type           = "IPV4"
  parameter_group_name   = aws_db_parameter_group.this.name

  backup_retention_period  = var.backup_retention_days
  backup_window            = var.backup_window
  maintenance_window       = var.maintenance_window
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.identifier}-final"

  performance_insights_enabled          = true
  performance_insights_retention_period = var.performance_insights_retention_days
  performance_insights_kms_key_id       = var.storage_kms_key_arn
  database_insights_mode                = var.database_insights_mode
  monitoring_interval                   = var.monitoring_interval
  monitoring_role_arn                   = var.monitoring_interval > 0 ? aws_iam_role.monitoring[0].arn : null
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  apply_immediately = var.apply_immediately

  tags = { Name = local.identifier }

  depends_on = [aws_cloudwatch_log_group.rds]
}
