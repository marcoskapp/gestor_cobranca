# -----------------------------------------------------------------------------
# Computação: launch template + Auto Scaling Group atrás do ALB.
#
# Versão do artefato: a tag de instância ArtifactVersion do launch template, lida pelo
# bootstrap via IMDSv2. scripts/deploy/release.sh cria novas versões do launch template
# (copiando a mais recente e trocando só essa tag) e dispara o instance refresh; por isso
# o Terraform ignora mudanças em tag_specifications e referencia sempre a versão mais
# recente (latest_version). Ao concluir o instance refresh com DesiredConfiguration, o
# próprio ASG passa a usar a nova versão. Invariante mantida pelos dois lados:
#     versão mais recente do launch template == versão do ASG == artefato em execução
# Quando o Terraform altera o launch template (AMI, tipo, scripts), a nova versão herda a
# tag ArtifactVersion vigente, lida do estado atual.
# -----------------------------------------------------------------------------

data "aws_ssm_parameter" "al2023_arm64" {
  name = var.ami_ssm_parameter
}

locals {
  scripts_root = "${path.module}/../../../../scripts"

  bootstrap_sources = {
    "gestor-lib.sh"           = { dest = "/usr/local/libexec/gestor-cobranca/gestor-lib.sh", mode = "0644" }
    "user-data.sh"            = { dest = "/usr/local/libexec/gestor-cobranca/user-data.sh", mode = "0755" }
    "run-migrations.sh"       = { dest = "/usr/local/libexec/gestor-cobranca/run-migrations.sh", mode = "0755" }
    "gestor-cobranca.service" = { dest = "/usr/local/share/gestor-cobranca/gestor-cobranca.service", mode = "0644" }
    "cloudwatch-agent.json"   = { dest = "/usr/local/share/gestor-cobranca/cloudwatch-agent.json", mode = "0644" }
    "db-admin.sh"             = { dest = "/usr/local/libexec/gestor-cobranca/db-admin.sh", mode = "0755" }
    "bootstrap-roles.sql"     = { dest = "/usr/local/share/gestor-cobranca/bootstrap-roles.sql", mode = "0644", dir = "db" }
  }

  source_path = { for name, f in local.bootstrap_sources : name => "${local.scripts_root}/${try(f.dir, "instance")}/${name}" }

  bootstrap_hashes = { for name, path in local.source_path : name => filesha256(path) }

  # Prefixo endereçado pelo conteúdo: qualquer mudança nos scripts gera um novo prefixo,
  # uma nova versão do launch template e um instance refresh.
  bootstrap_root    = "bootstrap"
  bootstrap_version = substr(sha256(join(",", [for name in sort(keys(local.bootstrap_hashes)) : "${name}=${local.bootstrap_hashes[name]}"])), 0, 16)
  bootstrap_prefix  = "${local.bootstrap_root}/${local.bootstrap_version}"

  # Configuração não sensível entregue às instâncias. O e-mail do administrador da plataforma
  # (dado pessoal) entra à parte, para que esta parte continue legível no plano e nas saídas.
  instance_conf_public = {
    GC_ENVIRONMENT               = var.environment
    GC_REGION                    = var.region
    GC_ARTIFACTS_BUCKET          = var.artifacts_bucket_name
    GC_ARTIFACT_PREFIX           = var.artifact_prefix
    GC_SECRETS_SOURCE            = var.use_ssm_parameters ? "ssm" : "s3"
    GC_SECRETS_SSM_PREFIX        = var.ssm_parameter_prefix
    GC_SECRETS_BUCKET            = var.use_ssm_parameters ? "" : var.secrets_bucket_name
    GC_APP_BASE_URL              = var.app_base_url
    GC_CORS_ALLOWED_ORIGINS      = join(",", var.cors_allowed_origins)
    GC_TRUSTED_PROXY_CIDRS       = join(",", var.trusted_proxy_cidrs)
    GC_DB_HOST                   = var.db_address
    GC_DB_PORT                   = tostring(var.db_port)
    GC_DB_NAME                   = var.db_name
    GC_DB_POOL_SIZE              = tostring(var.db_pool_size)
    GC_CRYPTO_KEY_IDS            = join(" ", var.crypto_key_ids)
    GC_CRYPTO_ACTIVE_KEY_ID      = var.crypto_active_key_id
    GC_JWT_ISSUER                = var.jwt_issuer
    GC_APP_BUCKET                = var.app_bucket_name
    GC_S3_KMS_KEY_ID             = var.app_bucket_kms_key_arn == null ? "" : var.app_bucket_kms_key_arn
    GC_LOG_LEVEL                 = var.log_level
    GC_SMTP_HOST                 = var.smtp_host
    GC_SMTP_PORT                 = tostring(var.smtp_port)
    GC_JOBS_CONCURRENCY          = tostring(var.jobs_concurrency)
    GC_JVM_MAX_RAM_PERCENTAGE    = tostring(var.jvm_max_ram_percentage)
    GC_JAVA_OPTS_EXTRA           = var.java_opts_extra
    GC_HOST_METRICS_NAMESPACE    = var.host_metrics_namespace
    GC_MIGRATION_TIMEOUT_SECONDS = tostring(var.migration_timeout_seconds)
    GC_DB_MASTER_USERNAME        = var.db_master_username
    GC_DB_MASTER_SECRET_ARN      = var.db_master_secret_arn
    # assistente de IA: Amazon Bedrock (credenciais pelo papel da instância; preços no application.yml)
    GC_BEDROCK_ENABLED                         = tostring(var.assistant_bedrock_enabled)
    GC_BEDROCK_REGION                          = var.bedrock_region
    GC_BEDROCK_STANDARD_MODEL_ID               = var.bedrock_standard_model_id
    GC_BEDROCK_ADVANCED_MODEL_ID               = var.bedrock_advanced_model_id
    GC_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED = tostring(var.bedrock_international_transfer_approved)
    # primeiro administrador da plataforma (decisão 500); vazios = bootstrap desligado
    GC_PLATFORM_ADMIN_NAME = var.platform_admin_name == null ? "" : var.platform_admin_name
    GC_PLATFORM_CNPJ       = var.platform_cnpj == null ? "" : var.platform_cnpj
    # Pix (decisões 313 e 316)
    GC_PAYMENT_PIX_ALLOWED_HOSTS    = join(",", var.payment_pix_allowed_hosts)
    GC_PAYMENT_WEBHOOK_MTLS_ENABLED = tostring(var.payment_webhook_mtls_enabled)
  }

  instance_conf = merge(local.instance_conf_public, {
    GC_PLATFORM_ADMIN_EMAIL = var.platform_admin_email == null ? "" : var.platform_admin_email
  })

  user_data = templatefile("${path.module}/templates/launcher.sh.tftpl", {
    region        = var.region
    bucket        = var.artifacts_bucket_name
    instance_conf = local.instance_conf
    files = [for name, f in local.bootstrap_sources : {
      key    = "${local.bootstrap_prefix}/${name}"
      dest   = f.dest
      mode   = f.mode
      sha256 = local.bootstrap_hashes[name]
    }]
  })

  instance_tags = { Name = "${var.name_prefix}-app", Role = "app" }
}

# Scripts de bootstrap publicados no bucket de artefatos (leitura permitida pelo perfil).
resource "aws_s3_object" "bootstrap" {
  for_each = local.bootstrap_sources

  bucket       = var.artifacts_bucket_name
  key          = "${local.bootstrap_prefix}/${each.key}"
  source       = local.source_path[each.key]
  source_hash  = local.bootstrap_hashes[each.key]
  content_type = endswith(each.key, ".json") ? "application/json" : "text/plain"
}

resource "aws_launch_template" "app" {
  name                   = "${var.name_prefix}-app"
  description            = "Instancias da aplicacao ${var.name_prefix}"
  update_default_version = true

  image_id      = coalesce(var.ami_id, data.aws_ssm_parameter.al2023_arm64.insecure_value)
  instance_type = var.instance_type
  user_data     = base64encode(local.user_data)
  ebs_optimized = true

  iam_instance_profile {
    arn = aws_iam_instance_profile.instance.arn
  }

  vpc_security_group_ids = [var.app_security_group_id]

  # IMDSv2 obrigatório; tags de instância expostas no IMDS (ArtifactVersion).
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "enabled"
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_type           = "gp3"
      volume_size           = var.root_volume_size_gib
      encrypted             = true
      kms_key_id            = var.ebs_kms_key_arn
      delete_on_termination = true
    }
  }

  monitoring {
    enabled = var.detailed_monitoring
  }

  credit_specification {
    cpu_credits = var.cpu_credits
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { ArtifactVersion = var.initial_artifact_version }
  }

  tag_specifications {
    resource_type = "volume"
    tags          = merge(var.standard_tags, { Name = "${var.name_prefix}-app-root" })
  }

  lifecycle {
    # ArtifactVersion é gerida por scripts/deploy/release.sh.
    ignore_changes = [tag_specifications]

    precondition {
      condition     = alltrue([for v in values(local.instance_conf) : !strcontains(v, "'") && !strcontains(v, "\n")])
      error_message = "Valores de instance.conf não podem conter aspas simples nem quebras de linha."
    }
  }

  depends_on = [aws_s3_object.bootstrap]
}

resource "aws_autoscaling_group" "app" {
  name                = "${var.name_prefix}-app"
  vpc_zone_identifier = var.app_subnet_ids
  target_group_arns   = [var.target_group_arn]

  min_size = var.min_size
  max_size = var.max_size

  # bootstrap_mode: health check só do EC2 enquanto o banco ainda não tem papéis nem schema
  # (a aplicação não fica "ready" e o ELB faria o ASG substituir as instâncias em ciclo).
  health_check_type         = var.bootstrap_mode ? "EC2" : "ELB"
  health_check_grace_period = var.health_check_grace_period_seconds
  default_instance_warmup   = var.instance_warmup_seconds
  wait_for_capacity_timeout = "15m"
  capacity_rebalance        = false
  termination_policies      = ["OldestLaunchTemplate", "OldestInstance"]
  protect_from_scale_in     = false

  metrics_granularity = "1Minute"
  enabled_metrics = [
    "GroupDesiredCapacity", "GroupInServiceInstances", "GroupPendingInstances",
    "GroupStandbyInstances", "GroupTerminatingInstances", "GroupTotalInstances",
    "GroupMinSize", "GroupMaxSize",
  ]

  launch_template {
    id      = aws_launch_template.app.id
    version = aws_launch_template.app.latest_version
  }

  # Substituições (inclusive por perda de saúde) lançam a nova instância antes de terminar
  # a antiga.
  instance_maintenance_policy {
    min_healthy_percentage = 100
    max_healthy_percentage = 200
  }

  # Instance refresh disparado pelo Terraform quando o launch template muda (ex.: AMI,
  # tipo de instância, scripts de bootstrap). Implantações de versão usam release.sh.
  instance_refresh {
    strategy = "Rolling"

    preferences {
      min_healthy_percentage = 90
      max_healthy_percentage = 200
      instance_warmup        = var.instance_warmup_seconds
      auto_rollback          = true
      skip_matching          = false
    }
  }

  dynamic "tag" {
    for_each = merge(var.standard_tags, local.instance_tags)

    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }

  lifecycle {
    # A capacidade desejada é gerida pelas políticas de escala.
    ignore_changes = [desired_capacity]

    precondition {
      condition     = var.max_size >= var.min_size
      error_message = "max_size deve ser maior ou igual a min_size."
    }
  }
}

# --- Políticas de escala (target tracking) ------------------------------------------

resource "aws_autoscaling_policy" "cpu" {
  name                   = "cpu-target"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    target_value = var.cpu_target_percent

    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
  }
}

resource "aws_autoscaling_policy" "requests" {
  count = var.requests_per_target == null ? 0 : 1

  name                   = "alb-requests-per-target"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    target_value = var.requests_per_target

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.alb_arn_suffix}/${var.target_group_arn_suffix}"
    }
  }
}

# --- Agendamento (homologação desligada fora do horário comercial) ----------------------

resource "aws_autoscaling_schedule" "stop" {
  count = var.business_hours_schedule == null ? 0 : 1

  scheduled_action_name  = "fora-do-horario"
  autoscaling_group_name = aws_autoscaling_group.app.name
  recurrence             = var.business_hours_schedule.stop_cron
  time_zone              = var.business_hours_schedule.time_zone
  min_size               = 0
  max_size               = 0
  desired_capacity       = 0
}

resource "aws_autoscaling_schedule" "start" {
  count = var.business_hours_schedule == null ? 0 : 1

  scheduled_action_name  = "horario-comercial"
  autoscaling_group_name = aws_autoscaling_group.app.name
  recurrence             = var.business_hours_schedule.start_cron
  time_zone              = var.business_hours_schedule.time_zone
  min_size               = var.min_size
  max_size               = var.max_size
  desired_capacity       = var.min_size
}
