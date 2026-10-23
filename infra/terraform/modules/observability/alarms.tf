# -----------------------------------------------------------------------------
# Alarmes iniciais (documentação/infraestrutura-aws.md §3, "Alarmes iniciais").
# -----------------------------------------------------------------------------

# --- ALB -------------------------------------------------------------------------------

# Erros 5xx > 1% das requisições em 5 min (alvo + próprio ALB).
resource "aws_cloudwatch_metric_alarm" "alb_5xx_rate" {
  alarm_name          = "${local.alarm_prefix}-alb-5xx-rate"
  alarm_description   = "Erros 5xx acima de ${var.alb_5xx_rate_threshold_percent}% das requisicoes em 5 minutos."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alb_5xx_rate_threshold_percent
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "rate"
    expression  = "IF(requests >= ${var.alb_5xx_min_requests}, 100 * (FILL(target5xx, 0) + FILL(elb5xx, 0)) / requests, 0)"
    label       = "Percentual de 5xx"
    return_data = true
  }

  metric_query {
    id = "requests"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  metric_query {
    id = "target5xx"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  metric_query {
    id = "elb5xx"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      period      = 300
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }
}

# Latência p95 > 1,5 s por 10 min.
resource "aws_cloudwatch_metric_alarm" "alb_latency_p95" {
  alarm_name          = "${local.alarm_prefix}-alb-latency-p95"
  alarm_description   = "Latencia p95 das respostas acima de ${var.latency_p95_threshold_seconds}s por 10 minutos."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.latency_p95_threshold_seconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Instâncias não saudáveis ≥ 1 por 5 min.
resource "aws_cloudwatch_metric_alarm" "unhealthy_hosts" {
  alarm_name          = "${local.alarm_prefix}-unhealthy-hosts"
  alarm_description   = "Ha instancias nao saudaveis no target group ha 5 minutos."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Nenhuma instância saudável (indisponibilidade total) — complementa o alarme anterior.
resource "aws_cloudwatch_metric_alarm" "no_healthy_hosts" {
  alarm_name          = "${local.alarm_prefix}-no-healthy-hosts"
  alarm_description   = "Nenhuma instancia saudavel atras do ALB."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  # Sem dado (ambiente desligado por agendamento) não é indisponibilidade.
  treat_missing_data = "notBreaching"
  alarm_actions      = local.alarm_actions
  ok_actions         = local.alarm_actions
}

# --- RDS -------------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.alarm_prefix}-rds-cpu"
  alarm_description   = "CPU do RDS acima de 80% por 15 minutos."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${local.alarm_prefix}-rds-connections"
  alarm_description   = "Conexoes do RDS acima de 80% de max_connections (${var.db_max_connections}) por 15 minutos."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = floor(var.db_max_connections * 0.8)
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Espaço livre < 20% do armazenamento alocado. Com autoscaling de armazenamento o RDS
# aumenta o disco ao chegar a 10% livres; este alarme avisa antes. O limiar é calculado
# sobre allocated_storage: após um autoscaling, atualize db_allocated_storage_gib.
resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${local.alarm_prefix}-rds-free-storage"
  alarm_description   = "Espaco livre do RDS abaixo de 20% de ${var.db_allocated_storage_gib} GiB."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = var.db_allocated_storage_gib * 1073741824 * 0.2
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "rds_freeable_memory" {
  alarm_name          = "${local.alarm_prefix}-rds-freeable-memory"
  alarm_description   = "Memoria livre do RDS abaixo de 256 MiB por 15 minutos."
  namespace           = "AWS/RDS"
  metric_name         = "FreeableMemory"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 268435456
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# --- Aplicação (namespace GestorCobranca) -------------------------------------------------

# Jobs em DEAD > 0.
resource "aws_cloudwatch_metric_alarm" "jobs_dead" {
  alarm_name          = "${local.alarm_prefix}-jobs-dead"
  alarm_description   = "Ha jobs em estado DEAD (shared.processing_job) que exigem analise."
  namespace           = var.metrics_namespace
  metric_name         = "jobs.dead.value"
  dimensions          = local.app_dimensions
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Eventos pendentes: crescimento contínuo por 15 min (três períodos de 5 min seguidos com
# aumento em relação ao anterior).
resource "aws_cloudwatch_metric_alarm" "events_incomplete_growth" {
  alarm_name          = "${local.alarm_prefix}-events-incomplete-growth"
  alarm_description   = "Publicacoes de eventos incompletas crescendo continuamente por 15 minutos."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "growth"
    expression  = "DIFF(incomplete)"
    label       = "Variacao de events.publications.incomplete"
    return_data = true
  }

  metric_query {
    id = "incomplete"

    metric {
      namespace   = var.metrics_namespace
      metric_name = "events.publications.incomplete.value"
      dimensions  = local.app_dimensions
      period      = 300
      stat        = "Maximum"
    }
  }
}

# Falha ao gravar log de acesso > 0.
resource "aws_cloudwatch_metric_alarm" "access_log_write_failures" {
  alarm_name          = "${local.alarm_prefix}-access-log-write-failures"
  alarm_description   = "Falha ao gravar o log de acesso (rastreabilidade comprometida)."
  namespace           = var.metrics_namespace
  metric_name         = "access.log.write_failures.count"
  dimensions          = local.app_dimensions
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Falhas de selagem da auditoria > 0.
resource "aws_cloudwatch_metric_alarm" "audit_seal_failures" {
  alarm_name          = "${local.alarm_prefix}-audit-seal-failures"
  alarm_description   = "Falha na selagem (encadeamento de hash) da auditoria."
  namespace           = var.metrics_namespace
  metric_name         = "audit.seal.failures.count"
  dimensions          = local.app_dimensions
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Picos de login negado acima do padrão histórico (detecção de anomalia, banda de
# ${var.login_failure_anomaly_band} desvios).
resource "aws_cloudwatch_metric_alarm" "login_failure_anomaly" {
  alarm_name          = "${local.alarm_prefix}-login-failure-anomaly"
  alarm_description   = "Logins negados acima do padrao historico (possivel ataque de credenciais)."
  comparison_operator = "GreaterThanUpperThreshold"
  threshold_metric_id = "band"
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "band"
    expression  = "ANOMALY_DETECTION_BAND(failures, ${var.login_failure_anomaly_band})"
    label       = "Faixa esperada"
    return_data = true
  }

  metric_query {
    id          = "failures"
    return_data = true

    metric {
      namespace   = var.metrics_namespace
      metric_name = "access.events.count"
      dimensions  = merge(local.app_dimensions, { type = "LOGIN_FAILURE", success = "false" })
      period      = 300
      stat        = "Sum"
    }
  }
}

# Reuso de refresh token (indício de roubo de sessão) > 0.
resource "aws_cloudwatch_metric_alarm" "token_reuse" {
  alarm_name          = "${local.alarm_prefix}-token-reuse"
  alarm_description   = "Reuso de refresh token detectado (possivel sessao comprometida)."
  namespace           = local.logs_namespace
  metric_name         = "access.token_reuse"
  dimensions          = { env = var.environment }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Falha no bootstrap de instância (artefato com hash inválido, segredo ausente...).
resource "aws_cloudwatch_metric_alarm" "bootstrap_failures" {
  alarm_name          = "${local.alarm_prefix}-bootstrap-failures"
  alarm_description   = "Falha no bootstrap de instancia; ver /gestor-cobranca/${var.environment}/technical (stream <instancia>/bootstrap)."
  namespace           = local.logs_namespace
  metric_name         = "bootstrap.failures"
  dimensions          = { env = var.environment }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# --- Host (CloudWatch Agent, agregado por ASG) ----------------------------------------------

resource "aws_cloudwatch_metric_alarm" "host_memory" {
  alarm_name          = "${local.alarm_prefix}-host-memory"
  alarm_description   = "Memoria das instancias acima de 90% por 15 minutos."
  namespace           = var.host_metrics_namespace
  metric_name         = "mem_used_percent"
  dimensions          = { AutoScalingGroupName = var.autoscaling_group_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 90
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "host_disk" {
  alarm_name          = "${local.alarm_prefix}-host-disk"
  alarm_description   = "Disco raiz das instancias acima de 85%."
  namespace           = var.host_metrics_namespace
  metric_name         = "disk_used_percent"
  dimensions          = { AutoScalingGroupName = var.autoscaling_group_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 85
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
