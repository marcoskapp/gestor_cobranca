# -----------------------------------------------------------------------------
# Alarmes do portal do credor (fatia 8; docs/tecnico/logs-e-metricas.md).
# Dimensões só application/env (métricas da aplicação, com source/operation/cache somadas aqui) e env/eventType
# (filtros do log de acesso, main.tf): nunca assessoria, credor, pessoa, e-mail ou IP (RS-93).
# -----------------------------------------------------------------------------

locals {
  creditor_home_sources      = ["indicators", "transfer", "disputes", "statements", "payments"]
  creditor_overview_caches   = ["hit", "miss", "none"]
  creditor_read_operations   = ["overview", "awaiting", "activity"]
  creditor_upload_operations = ["payment_report", "payment_title_search", "dispute_response"]

  creditor_auth_alarms = {
    login-failure = {
      event       = "LOGIN_FAILURE"
      threshold   = 20
      period      = 300
      description = "Senhas recusadas no portal do credor (20 ou mais em 5 minutos): possivel tentativa de adivinhar senhas."
    }
    login-blocked = {
      event       = "LOGIN_BLOCKED"
      threshold   = 3
      period      = 900
      description = "Entradas recusadas por bloqueio no portal do credor (3 ou mais em 15 minutos): conta o bloqueio e cada nova tentativa da pessoa bloqueada."
    }
    mfa-failure = {
      event       = "MFA_FAILURE"
      threshold   = 5
      period      = 900
      description = "Codigos do autenticador recusados no portal do credor (5 ou mais em 15 minutos)."
    }
  }
}

# Fontes do Início indisponíveis: falhas de fonte (overview e aguardando) acima de metade das aberturas do Início
# (mínimo de 3) ou 3+ falhas, em cada um de 3 períodos de 5 minutos seguidos. Uma falha pontual não dispara; uma fonte
# fora do ar por 15 minutos dispara.
resource "aws_cloudwatch_metric_alarm" "creditor_home_sources_unavailable" {
  alarm_name          = "${local.alarm_prefix}-creditor-home-sources-unavailable"
  alarm_description   = "Falhas de fonte do Inicio do portal do credor (overview e aguardando) acima de metade das aberturas (minimo 3) ou 3+ falhas, por 15 minutos (creditor_portal.sources.unavailable). Ver o log tecnico do CreditorHomeService e app.creditor-portal.source-timeout."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "breach"
    expression  = "IF((opens >= 3 AND unavailable > 0.5 * opens) OR unavailable >= 3, 1, 0)"
    label       = "Fontes do Inicio indisponiveis"
    return_data = true
  }

  metric_query {
    id         = "unavailable"
    expression = join(" + ", [for s in local.creditor_home_sources : "FILL(src_${s}, 0)"])
    label      = "creditor_portal.sources.unavailable (soma das fontes)"
  }

  metric_query {
    id         = "opens"
    expression = join(" + ", [for c in local.creditor_overview_caches : "FILL(open_${c}, 0)"])
    label      = "Aberturas do Inicio (creditor_portal.overview.duration.count)"
  }

  dynamic "metric_query" {
    for_each = toset(local.creditor_home_sources)
    content {
      id = "src_${metric_query.value}"
      metric {
        namespace   = var.metrics_namespace
        metric_name = "creditor_portal.sources.unavailable.count"
        dimensions  = merge(local.app_dimensions, { source = metric_query.value })
        period      = 300
        stat        = "Sum"
      }
    }
  }

  dynamic "metric_query" {
    for_each = toset(local.creditor_overview_caches)
    content {
      id = "open_${metric_query.value}"
      metric {
        namespace   = var.metrics_namespace
        metric_name = "creditor_portal.overview.duration.count"
        dimensions  = merge(local.app_dimensions, { cache = metric_query.value })
        period      = 300
        stat        = "Sum"
      }
    }
  }
}

# Picos de RATE_LIMITED pela MÉTRICA (o log de acesso registra só a 1ª recusa da janela, decisão 1563): leituras e
# envios recusados por limite, somados, acima de 50 em 5 minutos.
resource "aws_cloudwatch_metric_alarm" "creditor_rate_limited" {
  alarm_name          = "${local.alarm_prefix}-creditor-rate-limited"
  alarm_description   = "Mais de 50 leituras ou envios recusados por limite no portal do credor em 5 minutos (creditor_portal.reads.rejected + creditor_portal.document_uploads.rejected): uso automatizado ou tela em laco."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 50
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id = "rejected"
    expression = join(" + ", concat(
      [for o in local.creditor_read_operations : "FILL(read_${o}, 0)"],
      [for o in local.creditor_upload_operations : "FILL(up_${o}, 0)"],
    ))
    label       = "Recusas por limite no portal do credor (soma)"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = toset(local.creditor_read_operations)
    content {
      id = "read_${metric_query.value}"
      metric {
        namespace   = var.metrics_namespace
        metric_name = "creditor_portal.reads.rejected.count"
        dimensions  = merge(local.app_dimensions, { operation = metric_query.value, reason = "rate_limited" })
        period      = 300
        stat        = "Sum"
      }
    }
  }

  dynamic "metric_query" {
    for_each = toset(local.creditor_upload_operations)
    content {
      id = "up_${metric_query.value}"
      metric {
        namespace   = var.metrics_namespace
        metric_name = "creditor_portal.document_uploads.rejected.count"
        dimensions  = merge(local.app_dimensions, { operation = metric_query.value })
        period      = 300
        stat        = "Sum"
      }
    }
  }
}

# Falhas de autenticação da pessoa do credor (filtro creditor-auth-failures do log de acesso, main.tf).
resource "aws_cloudwatch_metric_alarm" "creditor_auth_failures" {
  for_each = local.creditor_auth_alarms

  alarm_name          = "${local.alarm_prefix}-creditor-${each.key}"
  alarm_description   = each.value.description
  namespace           = local.logs_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.creditor_auth_failures.metric_transformation[0].name
  dimensions          = { env = var.environment, eventType = each.value.event }
  statistic           = "Sum"
  period              = each.value.period
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Sondagem de IDOR: 5 ou mais recusas de escopo do ator CREDITOR em 5 minutos (registro de outro credor → 404 com
# SCOPE_DENIED no log de acesso).
resource "aws_cloudwatch_metric_alarm" "creditor_scope_denied" {
  alarm_name          = "${local.alarm_prefix}-creditor-scope-denied"
  alarm_description   = "Pessoa do credor pedindo registros de outro credor (5 ou mais SCOPE_DENIED em 5 minutos): possivel sondagem de ids. Ver o log de acesso (eventType SCOPE_DENIED, actorType CREDITOR)."
  namespace           = local.logs_namespace
  metric_name         = aws_cloudwatch_log_metric_filter.creditor_scope_denied.metric_transformation[0].name
  dimensions          = { env = var.environment }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
