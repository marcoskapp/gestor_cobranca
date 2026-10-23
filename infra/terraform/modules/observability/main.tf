# -----------------------------------------------------------------------------
# Observabilidade (docs/arquitetura/infraestrutura-aws.md §3).
#   - 3 grupos de log com retenções distintas (technical 30 d; business e access 13 meses)
#   - metric filters sobre os logs JSON
#   - alarmes iniciais do documento + saúde do host
#   - painel operacional
#   - SNS para notificação (REQUER APROVAÇÃO; desligado por padrão)
#
# Métricas da aplicação: Micrometer → CloudWatch, namespace GestorCobranca, com as
# dimensões comuns application=gestor-cobranca e env=<ambiente>. O registro do Micrometer
# acrescenta um sufixo por tipo: gauges ".value", contadores ".count".
# -----------------------------------------------------------------------------

locals {
  log_groups = {
    technical = { retention = var.technical_retention_days, class = "STANDARD" }
    business  = { retention = var.business_retention_days, class = "STANDARD" }
    access    = { retention = var.access_retention_days, class = "STANDARD" }
  }

  app_dimensions = {
    application = var.application_name
    env         = var.environment
  }

  logs_namespace = "${var.metrics_namespace}/Logs"

  alarm_actions = concat(
    var.enable_sns_notifications ? [aws_sns_topic.alarms[0].arn] : [],
    var.extra_alarm_action_arns,
  )

  alarm_prefix = var.name_prefix
}

resource "aws_cloudwatch_log_group" "app" {
  for_each = local.log_groups

  name              = "/gestor-cobranca/${var.environment}/${each.key}"
  retention_in_days = each.value.retention
  log_group_class   = each.value.class
  kms_key_id        = var.logs_kms_key_arn

  # Logs de negócio e de acesso são evidência de rastreabilidade: por padrão um destroy
  # remove o grupo apenas do state, preservando os eventos na conta.
  skip_destroy = var.retain_log_groups_on_destroy

  tags = { Stream = each.key }
}

# Saída do SSM Run Command (migrations) — rastreabilidade de quem executou o quê.
resource "aws_cloudwatch_log_group" "run_command" {
  name              = "/gestor-cobranca/${var.environment}/run-command"
  retention_in_days = var.business_retention_days
  kms_key_id        = var.logs_kms_key_arn
  skip_destroy      = var.retain_log_groups_on_destroy

  tags = { Stream = "run-command" }
}

# --- SNS (REQUER APROVAÇÃO) -----------------------------------------------------------------

resource "aws_sns_topic" "alarms" {
  count = var.enable_sns_notifications ? 1 : 0

  name              = "${var.name_prefix}-alarms"
  kms_master_key_id = var.sns_kms_key_id
}

data "aws_iam_policy_document" "sns" {
  count = var.enable_sns_notifications ? 1 : 0

  statement {
    sid       = "CloudWatchAlarms"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms[0].arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms[0].arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  count = var.enable_sns_notifications ? 1 : 0

  arn    = aws_sns_topic.alarms[0].arn
  policy = data.aws_iam_policy_document.sns[0].json
}

resource "aws_sns_topic_subscription" "email" {
  for_each = var.enable_sns_notifications ? toset(var.alarm_email_endpoints) : toset([])

  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = each.value
}

# --- Metric filters ---------------------------------------------------------------------
# Os três logs são JSON (JsonLogFormatter); "env" vem de APP_ENV e vira dimensão.

resource "aws_cloudwatch_log_metric_filter" "technical_errors" {
  name           = "technical-errors"
  log_group_name = aws_cloudwatch_log_group.app["technical"].name
  pattern        = "{ $.level = \"ERROR\" }"

  metric_transformation {
    name       = "technical.errors"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "technical_warnings" {
  name           = "technical-warnings"
  log_group_name = aws_cloudwatch_log_group.app["technical"].name
  pattern        = "{ $.level = \"WARN\" }"

  metric_transformation {
    name       = "technical.warnings"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "bootstrap_failures" {
  name           = "bootstrap-failures"
  log_group_name = aws_cloudwatch_log_group.app["technical"].name
  pattern        = "{ $.logger = \"bootstrap\" && $.level = \"ERROR\" }"

  metric_transformation {
    name       = "bootstrap.failures"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "access_token_reuse" {
  name           = "access-token-reuse"
  log_group_name = aws_cloudwatch_log_group.app["access"].name
  pattern        = "{ $.eventType = \"TOKEN_REUSE_DETECTED\" }"

  metric_transformation {
    name       = "access.token_reuse"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "access_denied" {
  name           = "access-denied"
  log_group_name = aws_cloudwatch_log_group.app["access"].name
  pattern        = "{ ($.eventType = \"ACCESS_DENIED\") || ($.eventType = \"SCOPE_DENIED\") }"

  metric_transformation {
    name       = "access.denied"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "access_failures_by_type" {
  name           = "access-failures-by-type"
  log_group_name = aws_cloudwatch_log_group.app["access"].name
  pattern        = "{ $.success IS FALSE }"

  metric_transformation {
    name       = "access.failures"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env", eventType = "$.eventType" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "business_events_by_type" {
  name           = "business-events-by-type"
  log_group_name = aws_cloudwatch_log_group.app["business"].name
  pattern        = "{ $.eventType = * }"

  metric_transformation {
    name       = "business.events"
    namespace  = local.logs_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { env = "$.env", eventType = "$.eventType" }
  }
}

# --- Consultas salvas do Logs Insights --------------------------------------------------------

resource "aws_cloudwatch_query_definition" "slow_requests" {
  name            = "${var.name_prefix}/tecnico/erros-por-rota"
  log_group_names = [aws_cloudwatch_log_group.app["technical"].name]

  query_string = <<-EOT
    fields @timestamp, level, logger, requestId, tenantId, message, `error.type`
    | filter level = "ERROR"
    | stats count(*) as erros by logger, `error.type`
    | sort erros desc
    | limit 50
  EOT
}

resource "aws_cloudwatch_query_definition" "access_by_actor" {
  name            = "${var.name_prefix}/acesso/falhas-por-tipo"
  log_group_names = [aws_cloudwatch_log_group.app["access"].name]

  query_string = <<-EOT
    fields @timestamp, eventType, tenantId, actorId, success, instance
    | filter success = 0
    | stats count(*) as falhas by eventType, tenantId
    | sort falhas desc
  EOT
}

resource "aws_cloudwatch_query_definition" "business_by_type" {
  name            = "${var.name_prefix}/negocio/eventos-por-tipo"
  log_group_names = [aws_cloudwatch_log_group.app["business"].name]

  query_string = <<-EOT
    fields @timestamp, eventType, tenantId
    | stats count(*) as eventos by eventType, tenantId, bin(1h)
    | sort eventos desc
  EOT
}
