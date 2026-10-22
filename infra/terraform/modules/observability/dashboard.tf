# -----------------------------------------------------------------------------
# Painel operacional: tráfego e erros, latência, capacidade, banco, filas e segurança.
# -----------------------------------------------------------------------------

locals {
  alb_dim = ["LoadBalancer", var.alb_arn_suffix]
  tg_dim  = ["LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix]
  rds_dim = ["DBInstanceIdentifier", var.db_instance_identifier]
  app_dim = ["application", var.application_name, "env", var.environment]
  asg_dim = ["AutoScalingGroupName", var.autoscaling_group_name]

  widget_defaults = { region = var.region, view = "timeSeries", stacked = false, period = 300 }

  widgets = [
    { title = "Requisicoes e erros (ALB)", x = 0, y = 0, w = 12, metrics = [
      concat(["AWS/ApplicationELB", "RequestCount"], local.alb_dim, [{ stat = "Sum", label = "Requisicoes" }]),
      concat(["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"], local.alb_dim, [{ stat = "Sum", label = "5xx aplicacao", yAxis = "right" }]),
      concat(["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count"], local.alb_dim, [{ stat = "Sum", label = "5xx ALB", yAxis = "right" }]),
      concat(["AWS/ApplicationELB", "HTTPCode_Target_4XX_Count"], local.alb_dim, [{ stat = "Sum", label = "4xx", yAxis = "right" }]),
    ] },
    { title = "Latencia (ALB TargetResponseTime)", x = 12, y = 0, w = 12, metrics = [
      concat(["AWS/ApplicationELB", "TargetResponseTime"], local.tg_dim, [{ stat = "p50", label = "p50" }]),
      concat(["AWS/ApplicationELB", "TargetResponseTime"], local.tg_dim, [{ stat = "p95", label = "p95" }]),
      concat(["AWS/ApplicationELB", "TargetResponseTime"], local.tg_dim, [{ stat = "p99", label = "p99" }]),
    ], annotations = { horizontal = [{ label = "Alarme p95", value = var.latency_p95_threshold_seconds }] } },
    { title = "Instancias (saude e capacidade)", x = 0, y = 6, w = 8, metrics = [
      concat(["AWS/ApplicationELB", "HealthyHostCount"], local.tg_dim, [{ stat = "Minimum", label = "Saudaveis" }]),
      concat(["AWS/ApplicationELB", "UnHealthyHostCount"], local.tg_dim, [{ stat = "Maximum", label = "Nao saudaveis" }]),
      concat(["AWS/AutoScaling", "GroupDesiredCapacity"], local.asg_dim, [{ stat = "Maximum", label = "Desejadas" }]),
    ] },
    { title = "CPU e memoria das instancias", x = 8, y = 6, w = 8, metrics = [
      concat(["AWS/EC2", "CPUUtilization"], local.asg_dim, [{ stat = "Average", label = "CPU media %" }]),
      concat([var.host_metrics_namespace, "mem_used_percent"], local.asg_dim, [{ stat = "Maximum", label = "Memoria max %" }]),
      concat([var.host_metrics_namespace, "disk_used_percent"], local.asg_dim, [{ stat = "Maximum", label = "Disco max %" }]),
    ] },
    { title = "Pool de conexoes (Hikari)", x = 16, y = 6, w = 8, metrics = [
      concat([var.metrics_namespace, "hikaricp.connections.active.value"], local.app_dim, ["pool", "app-pool", { stat = "Maximum", label = "Ativas" }]),
      concat([var.metrics_namespace, "hikaricp.connections.pending.value"], local.app_dim, ["pool", "app-pool", { stat = "Maximum", label = "Aguardando" }]),
    ] },
    { title = "RDS: CPU e conexoes", x = 0, y = 12, w = 8, metrics = [
      concat(["AWS/RDS", "CPUUtilization"], local.rds_dim, [{ stat = "Average", label = "CPU %" }]),
      concat(["AWS/RDS", "DatabaseConnections"], local.rds_dim, [{ stat = "Maximum", label = "Conexoes", yAxis = "right" }]),
    ] },
    { title = "RDS: latencia de I/O", x = 8, y = 12, w = 8, metrics = [
      concat(["AWS/RDS", "ReadLatency"], local.rds_dim, [{ stat = "Average", label = "Leitura" }]),
      concat(["AWS/RDS", "WriteLatency"], local.rds_dim, [{ stat = "Average", label = "Escrita" }]),
    ] },
    { title = "RDS: armazenamento e memoria livres", x = 16, y = 12, w = 8, metrics = [
      concat(["AWS/RDS", "FreeStorageSpace"], local.rds_dim, [{ stat = "Minimum", label = "Disco livre" }]),
      concat(["AWS/RDS", "FreeableMemory"], local.rds_dim, [{ stat = "Minimum", label = "Memoria livre", yAxis = "right" }]),
    ] },
    { title = "Jobs e eventos", x = 0, y = 18, w = 12, metrics = [
      concat([var.metrics_namespace, "jobs.pending.value"], local.app_dim, [{ stat = "Maximum", label = "Jobs pendentes" }]),
      concat([var.metrics_namespace, "jobs.dead.value"], local.app_dim, [{ stat = "Maximum", label = "Jobs DEAD" }]),
      concat([var.metrics_namespace, "events.publications.incomplete.value"], local.app_dim, [{ stat = "Maximum", label = "Eventos incompletos" }]),
    ] },
    { title = "Seguranca e rastreabilidade", x = 12, y = 18, w = 12, metrics = [
      concat([var.metrics_namespace, "access.events.count"], local.app_dim, ["type", "LOGIN_FAILURE", "success", "false", { stat = "Sum", label = "Logins negados" }]),
      concat([var.metrics_namespace, "access.log.write_failures.count"], local.app_dim, [{ stat = "Sum", label = "Falhas log de acesso" }]),
      concat([var.metrics_namespace, "audit.seal.failures.count"], local.app_dim, [{ stat = "Sum", label = "Falhas selagem" }]),
      [local.logs_namespace, "access.token_reuse", "env", var.environment, { stat = "Sum", label = "Reuso de token" }],
      [local.logs_namespace, "technical.errors", "env", var.environment, { stat = "Sum", label = "Erros tecnicos (log)" }],
    ] },
  ]
}

resource "aws_cloudwatch_dashboard" "operational" {
  dashboard_name = "${var.name_prefix}-operacional"

  dashboard_body = jsonencode({
    widgets = concat(
      [for w in local.widgets : {
        type   = "metric"
        x      = w.x
        y      = w.y
        width  = w.w
        height = 6
        properties = merge(local.widget_defaults, {
          title   = w.title
          metrics = w.metrics
          }, try({ annotations = w.annotations }, {})
        )
      }],
      [{
        type   = "alarm"
        x      = 0
        y      = 24
        width  = 24
        height = 4
        properties = {
          title  = "Alarmes"
          alarms = local.dashboard_alarm_arns
        }
      }]
    )
  })
}

locals {
  dashboard_alarm_arns = [
    aws_cloudwatch_metric_alarm.alb_5xx_rate.arn,
    aws_cloudwatch_metric_alarm.alb_latency_p95.arn,
    aws_cloudwatch_metric_alarm.unhealthy_hosts.arn,
    aws_cloudwatch_metric_alarm.no_healthy_hosts.arn,
    aws_cloudwatch_metric_alarm.rds_cpu.arn,
    aws_cloudwatch_metric_alarm.rds_connections.arn,
    aws_cloudwatch_metric_alarm.rds_free_storage.arn,
    aws_cloudwatch_metric_alarm.jobs_dead.arn,
    aws_cloudwatch_metric_alarm.events_incomplete_growth.arn,
    aws_cloudwatch_metric_alarm.access_log_write_failures.arn,
    aws_cloudwatch_metric_alarm.audit_seal_failures.arn,
    aws_cloudwatch_metric_alarm.login_failure_anomaly.arn,
    aws_cloudwatch_metric_alarm.token_reuse.arn,
    aws_cloudwatch_metric_alarm.bootstrap_failures.arn,
    aws_cloudwatch_metric_alarm.host_memory.arn,
    aws_cloudwatch_metric_alarm.host_disk.arn,
  ]
}
