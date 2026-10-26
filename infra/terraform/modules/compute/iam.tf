# -----------------------------------------------------------------------------
# Perfil IAM das instâncias (menor privilégio; docs/arquitetura/infraestrutura-aws.md §2).
# Nenhuma chave de acesso fica na instância: o SDK usa as credenciais temporárias do perfil.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name                 = var.instance_role_name
  description          = "Instancias da aplicacao ${var.name_prefix}"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600
}

resource "aws_iam_instance_profile" "instance" {
  name = var.instance_role_name
  role = aws_iam_role.instance.name
}

# Session Manager e Run Command (sem porta 22, sem bastion).
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:${var.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

locals {
  log_group_arns = [for arn in values(var.log_group_arns) : "${arn}:*"]

  # Segredos separados por finalidade (revisão de segurança A6):
  #   app/*        lidos pelas instâncias em execução (runtime);
  #   migration/*  senha do dono do schema (app_owner): NUNCA legível pelo runtime. Só durante a
  #                janela de migração (política temporária criada por scripts/deploy/migrate.sh,
  #                restrita à instância escolhida e com prazo) ou com db_admin_access.
  ssm_parameter_arn_prefix = "arn:${var.partition}:ssm:${var.region}:${var.account_id}:parameter${var.ssm_parameter_prefix}"
  app_secret_resources = var.use_ssm_parameters ? ["${local.ssm_parameter_arn_prefix}/app/*"] : [
    "${var.secrets_bucket_arn}/${var.environment}/app/*"
  ]
  migration_secret_resources = var.use_ssm_parameters ? ["${local.ssm_parameter_arn_prefix}/migration/*"] : [
    "${var.secrets_bucket_arn}/${var.environment}/migration/*"
  ]
  secret_read_actions = var.use_ssm_parameters ? ["ssm:GetParameter", "ssm:GetParameters"] : ["s3:GetObject"]

  # Amazon Bedrock (assistente de IA e chat de suporte): somente bedrock:InvokeModel (a Converse API é autorizada por essa ação;
  # não existe ação IAM "bedrock:Converse") nos modelos configurados. Perfil de inferência de sistema
  # (us./eu./global....): o ARN do perfil na região chamada + o modelo base em cada região de destino, este
  # só por meio do perfil (condição bedrock:InferenceProfileArn). Modelo base sem prefixo: só na região.
  bedrock_profile_pattern = "^(us|eu|apac|au|jp|ca|global)\\."
  bedrock_enabled         = var.assistant_bedrock_enabled || var.helpdesk_bedrock_enabled
  bedrock_model_ids = distinct(concat(
    var.assistant_bedrock_enabled ? [var.bedrock_standard_model_id, var.bedrock_advanced_model_id] : [],
    var.helpdesk_bedrock_enabled ? [var.helpdesk_bedrock_model_id] : [],
  ))
  bedrock_profile_ids = [for id in local.bedrock_model_ids : id if can(regex(local.bedrock_profile_pattern, id))]
  bedrock_base_ids    = [for id in local.bedrock_model_ids : id if !can(regex(local.bedrock_profile_pattern, id))]
  bedrock_profile_arns = [
    for id in local.bedrock_profile_ids : "arn:${var.partition}:bedrock:${var.bedrock_region}:${var.account_id}:inference-profile/${id}"
  ]
  bedrock_profile_models = distinct([for id in local.bedrock_profile_ids : replace(id, "/^[a-z]+\\./", "")])
  bedrock_uses_global    = anytrue([for id in local.bedrock_profile_ids : startswith(id, "global.")])
  bedrock_profile_model_arns = concat(
    [for m in local.bedrock_profile_models : "arn:${var.partition}:bedrock:*::foundation-model/${m}"],
    # perfil global: o modelo também é autorizado sem região (roteamento a qualquer região comercial)
    local.bedrock_uses_global ? [for m in local.bedrock_profile_models : "arn:${var.partition}:bedrock:::foundation-model/${m}"] : [],
  )
  bedrock_base_model_arns = [
    for id in local.bedrock_base_ids : "arn:${var.partition}:bedrock:${var.bedrock_region}::foundation-model/${id}"
  ]
}

data "aws_iam_policy_document" "instance" {
  # --- S3: arquivos da aplicação, somente sob tenants/ ---
  statement {
    sid = "AppFilesObjects"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:GetObjectTagging",
      "s3:PutObjectTagging",
    ]
    resources = ["${var.app_bucket_arn}/tenants/*"]
  }

  statement {
    sid       = "AppFilesList"
    actions   = ["s3:ListBucket"]
    resources = [var.app_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["tenants/*"]
    }
  }

  # --- S3: leitura de artefatos de implantação e scripts de bootstrap ---
  statement {
    sid     = "DeployArtifactsRead"
    actions = ["s3:GetObject"]
    resources = [
      "${var.artifacts_bucket_arn}/${var.artifact_prefix}/*",
      "${var.artifacts_bucket_arn}/${local.bootstrap_root}/*",
    ]
  }

  # --- CloudWatch Logs: somente os grupos do ambiente ---
  statement {
    sid       = "LogsWrite"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = local.log_group_arns
  }

  # O agente do SSM consulta a existência do grupo antes de enviar a saída de Run Command.
  statement {
    sid       = "LogsDescribeGroups"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }

  # --- CloudWatch Metrics: namespaces da aplicação e do host ---
  statement {
    sid       = "MetricsPublish"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.metrics_namespace, var.host_metrics_namespace]
    }
  }

  # O CloudWatch Agent resolve a dimensão AutoScalingGroupName pelas tags da instância.
  statement {
    sid       = "AgentDescribeTags"
    actions   = ["ec2:DescribeTags"]
    resources = ["*"]
  }

  # --- Segredos: SSM Parameter Store (REQUER APROVAÇÃO) ---
  dynamic "statement" {
    for_each = var.use_ssm_parameters ? [1] : []

    content {
      sid       = "SecretsRead"
      actions   = ["ssm:GetParameter", "ssm:GetParameters"]
      resources = local.app_secret_resources
    }
  }

  dynamic "statement" {
    for_each = var.use_ssm_parameters && var.ssm_kms_key_arn != null ? [1] : []

    content {
      sid       = "SecretsDecrypt"
      actions   = ["kms:Decrypt"]
      resources = [var.ssm_kms_key_arn]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["ssm.${var.region}.amazonaws.com"]
      }
    }
  }

  # --- Segredos: alternativa sem SSM (bucket de segredos dedicado) ---
  dynamic "statement" {
    for_each = var.use_ssm_parameters ? [] : [1]

    content {
      sid       = "SecretsReadS3"
      actions   = ["s3:GetObject"]
      resources = local.app_secret_resources
    }
  }

  # --- Senha mestre do RDS: SOMENTE com db_admin_access (criação de papéis e rotação de
  # senhas do banco via scripts/deploy/db-admin.sh). Desligar logo após o uso. ---
  dynamic "statement" {
    for_each = var.db_admin_access ? [1] : []

    content {
      sid       = "DbMasterSecretTemporary"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [var.db_master_secret_arn]
    }
  }

  # db-admin.sh cria/rotaciona também a senha de app_owner: com db_admin_access (temporário) o
  # papel lê migration/*. Fora disso, só a política de janela de migrate.sh concede essa leitura.
  dynamic "statement" {
    for_each = var.db_admin_access ? [1] : []

    content {
      sid       = "MigrationSecretWithDbAdmin"
      actions   = local.secret_read_actions
      resources = local.migration_secret_resources
    }
  }

  # --- Amazon Bedrock: assistente de IA (aprovado — issue #12) e chat de suporte (Claude Haiku 5.5) ---
  dynamic "statement" {
    for_each = length(local.bedrock_profile_arns) > 0 ? [1] : []

    content {
      sid       = "BedrockInvokeInferenceProfiles"
      actions   = ["bedrock:InvokeModel"]
      resources = local.bedrock_profile_arns
    }
  }

  dynamic "statement" {
    for_each = length(local.bedrock_profile_arns) > 0 ? [1] : []

    content {
      sid       = "BedrockInvokeModelsThroughProfiles"
      actions   = ["bedrock:InvokeModel"]
      resources = local.bedrock_profile_model_arns

      condition {
        test     = "StringLike"
        variable = "bedrock:InferenceProfileArn"
        values   = local.bedrock_profile_arns
      }
    }
  }

  dynamic "statement" {
    for_each = length(local.bedrock_base_model_arns) > 0 ? [1] : []

    content {
      sid       = "BedrockInvokeBaseModels"
      actions   = ["bedrock:InvokeModel"]
      resources = local.bedrock_base_model_arns
    }
  }

  # Sem perfil global configurado, o roteamento global (processamento em qualquer região comercial) é negado
  # explicitamente: trocar para global.* exige mudar esta configuração (decisão de residência de dados).
  dynamic "statement" {
    for_each = local.bedrock_enabled && !local.bedrock_uses_global ? [1] : []

    content {
      sid       = "BedrockDenyGlobalRouting"
      effect    = "Deny"
      actions   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      resources = ["*"]

      condition {
        test     = "StringEquals"
        variable = "aws:RequestedRegion"
        values   = ["unspecified"]
      }
    }
  }

  # --- KMS do bucket da aplicação (SSE-KMS, REQUER APROVAÇÃO) ---
  dynamic "statement" {
    for_each = var.app_bucket_kms_key_arn != null ? [1] : []

    content {
      sid       = "AppFilesKms"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
      resources = [var.app_bucket_kms_key_arn]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${var.region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_role_policy" "instance" {
  name   = "app-least-privilege"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.instance.json
}
