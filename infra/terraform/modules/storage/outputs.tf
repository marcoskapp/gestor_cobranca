output "app_bucket_name" {
  description = "Bucket de arquivos da aplicação (S3_BUCKET)."
  value       = aws_s3_bucket.this["app"].id
}

output "app_bucket_arn" {
  description = "ARN do bucket da aplicação."
  value       = aws_s3_bucket.this["app"].arn
}

output "artifacts_bucket_name" {
  description = "Bucket de artefatos de implantação."
  value       = aws_s3_bucket.this["artifacts"].id
}

output "artifacts_bucket_arn" {
  description = "ARN do bucket de artefatos."
  value       = aws_s3_bucket.this["artifacts"].arn
}

output "logs_bucket_name" {
  description = "Bucket de logs (ALB, S3, arquivo do log de acesso)."
  value       = aws_s3_bucket.this["logs"].id
}

output "logs_bucket_arn" {
  description = "ARN do bucket de logs."
  value       = aws_s3_bucket.this["logs"].arn
}

output "alb_logs_prefix" {
  description = "Prefixo dos access logs do ALB no bucket de logs."
  value       = "alb"
}

output "secrets_bucket_name" {
  description = "Bucket de segredos (vazio se desativado)."
  value       = var.enable_secrets_bucket ? aws_s3_bucket.this["secrets"].id : ""
}

output "secrets_bucket_arn" {
  description = "ARN do bucket de segredos (vazio se desativado)."
  value       = var.enable_secrets_bucket ? aws_s3_bucket.this["secrets"].arn : ""
}
