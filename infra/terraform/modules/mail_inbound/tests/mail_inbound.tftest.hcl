# Alarme da DLQ do e-mail recebido com provider simulado (sem credenciais AWS; SEG-20261125-I07).
#   scripts/infra/terraform-check.sh modules/mail_inbound

mock_provider "aws" {
  override_during = plan
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
}

override_resource {
  target          = aws_sqs_queue.dlq
  override_during = plan
  values          = { arn = "arn:aws:sqs:sa-east-1:123456789012:gestor-cobranca-homologacao-inbound-mail-dlq" }
}

variables {
  name_prefix        = "gestor-cobranca-homologacao"
  account_id         = "123456789012"
  region             = "sa-east-1"
  inbound_domain     = "respostas.gestorcobranca.example"
  instance_role_name = "gestor-cobranca-homologacao-app"
  kms_key_arn        = "arn:aws:kms:sa-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"
  alarm_action_arns  = ["arn:aws:sns:sa-east-1:123456789012:gestor-cobranca-homologacao-alarms"]
}

run "qualquer_mensagem_na_dlq_dispara_alarme" {
  command = plan

  assert {
    condition     = aws_cloudwatch_metric_alarm.dlq_messages.alarm_name == "gestor-cobranca-homologacao-inbound-mail-dlq"
    error_message = "nome no padrão gestor-cobranca-<env>-inbound-mail-dlq"
  }
  assert {
    condition = (aws_cloudwatch_metric_alarm.dlq_messages.namespace == "AWS/SQS"
      && aws_cloudwatch_metric_alarm.dlq_messages.metric_name == "ApproximateNumberOfMessagesVisible"
      && aws_cloudwatch_metric_alarm.dlq_messages.comparison_operator == "GreaterThanThreshold"
      && aws_cloudwatch_metric_alarm.dlq_messages.threshold == 0
    && aws_cloudwatch_metric_alarm.dlq_messages.treat_missing_data == "notBreaching")
    error_message = "uma mensagem na DLQ basta; sem dado não é alarme"
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.dlq_messages.alarm_actions == toset(["arn:aws:sns:sa-east-1:123456789012:gestor-cobranca-homologacao-alarms"])
    error_message = "o alarme notifica pelo tópico de alarmes do ambiente"
  }
}

run "email_parado_na_fila_principal_dispara_alarme" {
  command = plan

  assert {
    condition = (aws_cloudwatch_metric_alarm.queue_oldest_age.alarm_name == "gestor-cobranca-homologacao-inbound-mail-age"
      && aws_cloudwatch_metric_alarm.queue_oldest_age.metric_name == "ApproximateAgeOfOldestMessage"
      && aws_cloudwatch_metric_alarm.queue_oldest_age.dimensions["QueueName"] == "gestor-cobranca-homologacao-inbound-mail"
    && aws_cloudwatch_metric_alarm.queue_oldest_age.threshold == 900)
    error_message = "idade da mensagem mais antiga da fila principal acima de 15 min"
  }
}

run "sem_topico_de_alarmes_o_alarme_fica_so_no_console" {
  command = plan
  variables {
    alarm_action_arns = []
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.dlq_messages.alarm_actions) == 0 && length(aws_cloudwatch_metric_alarm.queue_oldest_age.alarm_actions) == 0
    error_message = "sem notificação ligada, nenhum destino"
  }
}
