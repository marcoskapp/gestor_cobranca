# Alarme do retorno recusado de titular eliminado (decisão 1608), com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/observability

mock_provider "aws" {
  override_during = plan
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_sns_topic" { defaults = { arn = "arn:aws:sns:sa-east-1:123456789012:gestor-cobranca-homologacao-alarms" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:sa-east-1:123456789012:log-group:x" } }
  mock_resource "aws_cloudwatch_metric_alarm" { defaults = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:outro" } }
}

variables {
  name_prefix              = "gestor-cobranca-homologacao"
  environment              = "homologacao"
  region                   = "sa-east-1"
  account_id               = "123456789012"
  alb_arn_suffix           = "app/x/1"
  target_group_arn_suffix  = "targetgroup/x/1"
  autoscaling_group_name   = "gestor-cobranca-homologacao-app"
  db_instance_identifier   = "gestor-cobranca-homologacao-db"
  db_allocated_storage_gib = 50
  db_max_connections       = 200
}

run "alarme_do_retorno_de_titular_eliminado" {
  command = plan

  assert {
    condition     = aws_cloudwatch_metric_alarm.erased_subject_returns_refused.threshold == 10
    error_message = "O alarme do retorno recusado dispara acima de 10 por hora."
  }

  assert {
    condition = length([for q in aws_cloudwatch_metric_alarm.erased_subject_returns_refused.metric_query : q
    if q.metric != null && length(q.metric) > 0]) == 3
    error_message = "O alarme soma as três origens da tentativa (carga, cadastro de devedor e coobrigado)."
  }

  assert {
    condition = alltrue([for q in aws_cloudwatch_metric_alarm.erased_subject_returns_refused.metric_query :
    alltrue([for m in q.metric : !contains(keys(m.dimensions), "creditor") && !contains(keys(m.dimensions), "document")])])
    error_message = "Sem dimensão de credor ou documento."
  }
}
