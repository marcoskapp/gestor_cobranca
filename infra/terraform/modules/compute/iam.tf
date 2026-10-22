# -----------------------------------------------------------------------------
# Perfil IAM das instâncias (menor privilégio; documentação/infraestrutura-aws.md §2).
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
      resources = ["arn:${var.partition}:ssm:${var.region}:${var.account_id}:parameter${var.ssm_parameter_prefix}/*"]
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
      resources = ["${var.secrets_bucket_arn}/${var.environment}/*"]
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
