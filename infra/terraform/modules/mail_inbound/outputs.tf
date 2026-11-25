output "bucket_name" {
  description = "Bucket do e-mail bruto (APP_INBOUND_MAIL_BUCKET)."
  value       = aws_s3_bucket.inbound.id
}

output "queue_url" {
  description = "Fila das notificações (APP_INBOUND_MAIL_QUEUE_URL)."
  value       = aws_sqs_queue.inbound.id
}

output "dlq_url" {
  description = "Fila de mensagens que falharam 5 vezes (alarme `<prefixo>-inbound-mail-dlq`)."
  value       = aws_sqs_queue.dlq.id
}

output "mx_record" {
  description = "Registro MX do subdomínio de entrada."
  value       = "10 inbound-smtp.${var.region}.amazonaws.com"
}

output "dlq_alarm_name" {
  description = "Alarme de mensagem na DLQ do e-mail recebido."
  value       = aws_cloudwatch_metric_alarm.dlq_messages.alarm_name
}

output "queue_age_alarm_name" {
  description = "Alarme de e-mail parado na fila principal há mais de 15 min."
  value       = aws_cloudwatch_metric_alarm.queue_oldest_age.alarm_name
}
