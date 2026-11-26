# Alarmes e filtros do portal do credor (fatia 8), com provider simulado (sem credenciais AWS).
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

run "filtros_do_log_de_acesso_do_credor" {
  command = plan

  assert {
    condition = alltrue([for fragment in [
      "$.route = \"/api/creditor/v1/*\"",
      "$.eventType = \"LOGIN_FAILURE\"",
      "$.eventType = \"MFA_FAILURE\"",
      "$.eventType = \"LOGIN_BLOCKED\"",
      "$.eventType = \"RATE_LIMITED\"",
    ] : strcontains(aws_cloudwatch_log_metric_filter.creditor_auth_failures.pattern, fragment)])
    error_message = "falhas de autenticação do credor: só as rotas /api/creditor/v1/* e os quatro tipos de evento"
  }
  assert {
    condition = (aws_cloudwatch_log_metric_filter.creditor_auth_failures.log_group_name == "/gestor-cobranca/homologacao/access"
    && aws_cloudwatch_log_metric_filter.creditor_scope_denied.log_group_name == "/gestor-cobranca/homologacao/access")
    error_message = "os filtros leem o grupo de log de acesso"
  }
  assert {
    condition = (strcontains(aws_cloudwatch_log_metric_filter.creditor_scope_denied.pattern, "$.eventType = \"SCOPE_DENIED\"")
    && strcontains(aws_cloudwatch_log_metric_filter.creditor_scope_denied.pattern, "$.actorType = \"CREDITOR\""))
    error_message = "sondagem de escopo: SCOPE_DENIED só do ator CREDITOR"
  }
  # RS-93: dimensões só de ambiente e tipo de evento (nada de assessoria, credor, pessoa, e-mail ou IP)
  assert {
    condition = (toset(keys(one(aws_cloudwatch_log_metric_filter.creditor_auth_failures.metric_transformation).dimensions)) == toset(["env", "eventType"])
    && toset(keys(one(aws_cloudwatch_log_metric_filter.creditor_scope_denied.metric_transformation).dimensions)) == toset(["env"]))
    error_message = "filtros do credor só com as dimensões env e eventType"
  }
}

run "alarmes_do_portal_do_credor" {
  command = plan

  assert {
    condition = (aws_cloudwatch_metric_alarm.creditor_auth_failures["login-failure"].threshold == 20
      && aws_cloudwatch_metric_alarm.creditor_auth_failures["login-failure"].period == 300
      && aws_cloudwatch_metric_alarm.creditor_auth_failures["login-blocked"].threshold == 3
      && aws_cloudwatch_metric_alarm.creditor_auth_failures["login-blocked"].period == 900
      && aws_cloudwatch_metric_alarm.creditor_auth_failures["mfa-failure"].threshold == 5
    && aws_cloudwatch_metric_alarm.creditor_auth_failures["mfa-failure"].period == 900)
    error_message = "LOGIN_FAILURE ≥ 20/5 min, LOGIN_BLOCKED ≥ 3/15 min, MFA_FAILURE ≥ 5/15 min"
  }
  assert {
    condition = alltrue([for a in aws_cloudwatch_metric_alarm.creditor_auth_failures :
    a.metric_name == "access.creditor_auth_failures" && toset(keys(a.dimensions)) == toset(["env", "eventType"]) && a.comparison_operator == "GreaterThanOrEqualToThreshold"])
    error_message = "alarmes de autenticação sobre o filtro do credor, por env e eventType"
  }
  assert {
    condition = (aws_cloudwatch_metric_alarm.creditor_scope_denied.threshold == 5
      && aws_cloudwatch_metric_alarm.creditor_scope_denied.period == 300
    && toset(keys(aws_cloudwatch_metric_alarm.creditor_scope_denied.dimensions)) == toset(["env"]))
    error_message = "sondagem de IDOR: 5 ou mais SCOPE_DENIED do credor em 5 minutos, só por env"
  }
  assert {
    condition = (aws_cloudwatch_metric_alarm.creditor_rate_limited.threshold == 50
      && aws_cloudwatch_metric_alarm.creditor_rate_limited.comparison_operator == "GreaterThanThreshold"
    && length([for q in aws_cloudwatch_metric_alarm.creditor_rate_limited.metric_query : q if q.return_data == true]) == 1)
    error_message = "picos de RATE_LIMITED: soma das recusas > 50 em 5 minutos"
  }
  assert {
    condition = (aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable.evaluation_periods == 3
      && aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable.datapoints_to_alarm == 3
      && aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable.treat_missing_data == "notBreaching"
    && strcontains(one([for q in aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable.metric_query : q.expression if q.return_data == true]), "opens >= 3"))
    error_message = "fontes do Início: 3 de 3 períodos, mínimo de 3 aberturas, sem dado não é alarme"
  }
  # RS-93: as métricas da aplicação usadas pelos alarmes só têm application, env e a dimensão técnica (fonte, cache,
  # operação, motivo)
  assert {
    condition = alltrue(flatten([for alarm in [aws_cloudwatch_metric_alarm.creditor_rate_limited, aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable] : [
      for q in alarm.metric_query : [for m in q.metric : alltrue([for k in keys(m.dimensions) : contains(["application", "env", "source", "cache", "operation", "reason"], k)])]
    ]]))
    error_message = "nenhuma dimensão de assessoria, credor, pessoa, e-mail ou IP nas métricas dos alarmes do credor"
  }
  assert {
    condition     = alltrue([for a in concat(values(aws_cloudwatch_metric_alarm.creditor_auth_failures), [aws_cloudwatch_metric_alarm.creditor_scope_denied, aws_cloudwatch_metric_alarm.creditor_rate_limited, aws_cloudwatch_metric_alarm.creditor_home_sources_unavailable]) : length(a.alarm_actions) == 0])
    error_message = "sem SNS aprovado, os alarmes ficam só no console, como os demais"
  }
}
