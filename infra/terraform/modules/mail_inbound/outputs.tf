output "bucket_name" {
  description = "Bucket do e-mail bruto (APP_INBOUND_MAIL_BUCKET)."
  value       = aws_s3_bucket.inbound.id
}

output "queue_url" {
  description = "Fila das notificações (APP_INBOUND_MAIL_QUEUE_URL)."
  value       = aws_sqs_queue.inbound.id
}

output "dlq_url" {
  description = "Fila de mensagens que falharam 5 vezes (alarme)."
  value       = aws_sqs_queue.dlq.id
}

output "mx_record" {
  description = "Registro MX do subdomínio de entrada."
  value       = "10 inbound-smtp.${var.region}.amazonaws.com"
}
