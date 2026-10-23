output "log_group_names" {
  description = "Grupos de log da aplicação por fluxo."
  value       = { for k, g in aws_cloudwatch_log_group.app : k => g.name }
}

output "log_group_arns" {
  description = "ARNs dos grupos de log em que as instâncias escrevem (inclui o de Run Command)."
  value = merge(
    { for k, g in aws_cloudwatch_log_group.app : k => g.arn },
    { run_command = aws_cloudwatch_log_group.run_command.arn },
  )
}

output "run_command_log_group_name" {
  description = "Grupo de log da saída do SSM Run Command (migrations)."
  value       = aws_cloudwatch_log_group.run_command.name
}

output "dashboard_name" {
  description = "Nome do painel operacional."
  value       = aws_cloudwatch_dashboard.operational.dashboard_name
}

output "alarm_topic_arn" {
  description = "Tópico SNS dos alarmes (null se desligado)."
  value       = var.enable_sns_notifications ? aws_sns_topic.alarms[0].arn : null
}

output "rollback_alarm_names" {
  description = "Alarmes que, em estado ALARM durante um instance refresh, revertem a implantação (release.sh)."
  value = [
    aws_cloudwatch_metric_alarm.alb_5xx_rate.alarm_name,
    aws_cloudwatch_metric_alarm.unhealthy_hosts.alarm_name,
  ]
}
