# Alarmes do módulo observability com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/observability

mock_provider "aws" {
  override_during = plan
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_sns_topic" { defaults = { arn = "arn:aws:sns:sa-east-1:123456789012:gestor-cobranca-homologacao-alarms" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:sa-east-1:123456789012:log-group:x" } }
  # ARN comum aos demais alarmes (sem ele o painel fica desconhecido no plano); os conferidos têm ARN próprio abaixo.
  mock_resource "aws_cloudwatch_metric_alarm" { defaults = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:outro" } }
}

override_resource {
  target          = aws_cloudwatch_metric_alarm.telephony_bridge_interrupted
  override_during = plan
  values          = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:telephony-bridge-interrupted" }
}

override_resource {
  target          = aws_cloudwatch_metric_alarm.inbox_sla_breaches
  override_during = plan
  values          = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:inbox-sla-breaches" }
}

override_resource {
  target          = aws_cloudwatch_metric_alarm.inbox_realtime_listener
  override_during = plan
  values          = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:inbox-realtime-listener" }
}

override_resource {
  target          = aws_cloudwatch_metric_alarm.jobs_dead
  override_during = plan
  values          = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:jobs-dead" }
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

# Decisão 906: qualquer conexão ao operador interrompida (outcome=interrupted) em 5 min dispara.
run "alarme_da_conexao_interrompida" {
  command = plan

  assert {
    condition     = aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.alarm_name == "gestor-cobranca-homologacao-telephony-bridge-interrupted"
    error_message = "nome no padrão gestor-cobranca-<env>-<alarme>"
  }
  assert {
    condition = (aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.comparison_operator == "GreaterThanThreshold"
      && aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.threshold == 0
      && aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.evaluation_periods == 1
    && aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.treat_missing_data == "notBreaching")
    error_message = "uma única ocorrência num período dispara; sem dado (nenhuma interrupção) não é alarme"
  }
  assert {
    condition     = one([for q in aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.metric_query : q.period if q.return_data]) == 300
    error_message = "janela de 5 minutos"
  }
  # Micrometer → CloudWatch: contador com sufixo .count e as tags comuns (application, env) mais as do contador.
  assert {
    condition = alltrue([for fragment in [
      "SUM(\"telephony.provider.commands.count\")",
      "SCHEMA(\"GestorCobranca\", application, env, provider, operation, outcome)",
      "application = 'gestor-cobranca'",
      "env = 'homologacao'",
      "operation = 'bridge'",
      "outcome = 'interrupted'",
    ] : strcontains(one(aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.metric_query).expression, fragment)])
    error_message = "a consulta soma telephony.provider.commands{operation=bridge,outcome=interrupted} de todos os provedores, só neste ambiente"
  }
  assert {
    condition     = length(aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.alarm_actions) == 0
    error_message = "sem SNS aprovado, o alarme fica só no console e no painel, como os demais"
  }
  assert {
    condition = (aws_cloudwatch_query_definition.telephony_bridge_interrupted.name == "gestor-cobranca-homologacao/tecnico/conexoes-interrompidas"
    && aws_cloudwatch_query_definition.telephony_bridge_interrupted.log_group_names == tolist(["/gestor-cobranca/homologacao/technical"]))
    error_message = "consulta salva do runbook sobre o log técnico"
  }
  # O mock não gera o ARN dos alarmes no plano: os quatro conferidos recebem ARNs distintos pelos override_resource
  # do início do arquivo, e cada um precisa aparecer no widget de alarmes do painel.
  assert {
    condition = alltrue([for arn in [
      aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.arn,
      aws_cloudwatch_metric_alarm.inbox_sla_breaches.arn,
      aws_cloudwatch_metric_alarm.inbox_realtime_listener.arn,
      aws_cloudwatch_metric_alarm.jobs_dead.arn,
    ] : strcontains(aws_cloudwatch_dashboard.operational.dashboard_body, arn)])
    error_message = "os alarmes da aplicação e da telefonia aparecem no painel operacional"
  }
}

run "alarmes_notificam_pelo_canal_existente" {
  command = plan
  variables {
    enable_sns_notifications = true
    alarm_email_endpoints    = ["operacao@gestorcobranca.example"]
  }

  assert {
    condition = (aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.alarm_actions == toset([aws_sns_topic.alarms[0].arn])
    && aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.ok_actions == toset([aws_sns_topic.alarms[0].arn]))
    error_message = "o alarme da conexão interrompida usa o mesmo tópico SNS dos demais alarmes"
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.jobs_dead.alarm_actions == aws_cloudwatch_metric_alarm.telephony_bridge_interrupted.alarm_actions
    error_message = "mesmas ações do alarme jobs-dead (compensação da conexão que falhou)"
  }
}

# Logs: retenções distintas e preservação dos grupos de evidência num destroy.
run "grupos_de_log" {
  command = plan

  assert {
    condition = (aws_cloudwatch_log_group.app["technical"].retention_in_days < aws_cloudwatch_log_group.app["access"].retention_in_days
    && aws_cloudwatch_log_group.app["business"].retention_in_days == aws_cloudwatch_log_group.app["access"].retention_in_days)
    error_message = "técnico com retenção curta; negócio e acesso mais longos"
  }
  assert {
    condition     = alltrue([for g in aws_cloudwatch_log_group.app : g.skip_destroy])
    error_message = "logs de negócio e de acesso são evidência: um destroy não apaga os grupos"
  }
}
