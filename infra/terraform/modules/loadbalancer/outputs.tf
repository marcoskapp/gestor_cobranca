output "alb_arn" {
  description = "ARN do ALB."
  value       = aws_lb.this.arn
}

output "alb_arn_suffix" {
  description = "Sufixo do ARN do ALB (dimensão LoadBalancer do CloudWatch)."
  value       = aws_lb.this.arn_suffix
}

output "alb_dns_name" {
  description = "Nome DNS do ALB (alvo do registro CNAME/alias do domínio)."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Hosted zone do ALB (registro alias no Route 53)."
  value       = aws_lb.this.zone_id
}

output "target_group_arn" {
  description = "ARN do target group."
  value       = aws_lb_target_group.app.arn
}

output "target_group_arn_suffix" {
  description = "Sufixo do ARN do target group (dimensão TargetGroup do CloudWatch)."
  value       = aws_lb_target_group.app.arn_suffix
}
