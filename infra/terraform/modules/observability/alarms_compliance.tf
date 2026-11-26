# --- Compliance: titular eliminado a pedido enviado de novo (decisões 1471, 1498 e 1608) ---------------------------
# A recusa é neutra ("pessoa não aceita"), mas ainda diz 1 bit a quem envia. Muitas recusas numa hora sugerem sondagem
# de documentos pela carga (ou um credor reenviando a base antiga): alerta para quem atende titulares revisar as
# tentativas no compliance. Sem dimensão de pessoa, documento ou credor (o credor está no evento de negócio).
locals {
  erased_subject_return_sources = ["import", "debtor_form", "co_obligor_form"]
}

resource "aws_cloudwatch_metric_alarm" "erased_subject_returns_refused" {
  alarm_name          = "${local.alarm_prefix}-erased-subject-returns-refused"
  alarm_description   = "Mais de 10 tentativas de trazer de volta titulares eliminados a pedido em 1 hora (compliance.erased_subject.returns_refused): possivel sondagem de documentos pela carga ou base antiga reenviada. Revise em Minhas pendencias > Titulares eliminados enviados de novo e o evento erased_subject.return_refused (credor e lote)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 10
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "refused"
    expression  = join(" + ", [for s in local.erased_subject_return_sources : "FILL(src_${s}, 0)"])
    label       = "Retornos recusados de titular eliminado (soma)"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = toset(local.erased_subject_return_sources)
    content {
      id = "src_${metric_query.value}"
      metric {
        namespace   = var.metrics_namespace
        metric_name = "compliance.erased_subject.returns_refused.count"
        dimensions  = merge(local.app_dimensions, { source = metric_query.value })
        period      = 3600
        stat        = "Sum"
      }
    }
  }
}
