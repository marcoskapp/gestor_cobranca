output "alb_security_group_id" {
  description = "ID do sg-alb."
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "ID do sg-app."
  value       = aws_security_group.app.id
}

output "db_security_group_id" {
  description = "ID do sg-db."
  value       = aws_security_group.db.id
}
