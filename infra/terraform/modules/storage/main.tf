# -----------------------------------------------------------------------------
# Armazenamento S3. Três buckets por ambiente, com responsabilidades separadas:
#   app        arquivos da aplicação (prefixo tenants/<tenantId>/...)
#   artifacts  artefatos de implantação (artifacts/<versão>/gestor-cobranca.jar + SHA256SUMS)
#   logs       access logs do ALB, logs de acesso do S3 e arquivo de longo prazo do log de
#              acesso da aplicação exportado do CloudWatch (5 anos)
#   secrets    (somente com enable_secrets_bucket) segredos da aplicação quando o SSM
#              Parameter Store não estiver aprovado; leitura restrita ao papel das
#              instâncias e escrita restrita aos administradores informados
# Todos: ACLs desativadas (BucketOwnerEnforced), bloqueio público total, criptografia padrão,
# versionamento e política que nega qualquer requisição sem TLS.
# -----------------------------------------------------------------------------

data "aws_elb_service_account" "this" {}

locals {
  buckets = merge(
    {
      app       = "${var.name_prefix}-app-${var.account_id}"
      artifacts = "${var.name_prefix}-artifacts-${var.account_id}"
      logs      = "${var.name_prefix}-logs-${var.account_id}"
    },
    var.enable_secrets_bucket ? { secrets = "${var.name_prefix}-secrets-${var.account_id}" } : {},
  )

  # O bucket de logs usa sempre SSE-S3: os access logs do ALB não aceitam SSE-KMS.
  kms_enabled = var.kms_key_arn != null
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket        = each.value
  force_destroy = false

  tags = { Name = each.value, Purpose = each.key }
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = aws_s3_bucket.this

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = local.kms_enabled && each.key != "logs" ? "aws:kms" : "AES256"
      kms_master_key_id = local.kms_enabled && each.key != "logs" ? var.kms_key_arn : null
    }
    bucket_key_enabled = local.kms_enabled && each.key != "logs"
  }
}

# Logs de acesso do S3 ao bucket da aplicação e ao de artefatos (quem leu/gravou o quê).
resource "aws_s3_bucket_logging" "this" {
  for_each = { for k, v in aws_s3_bucket.this : k => v if k != "logs" }

  bucket        = each.value.id
  target_bucket = aws_s3_bucket.this["logs"].id
  target_prefix = "s3-access/${each.key}/"

  target_object_key_format {
    partitioned_prefix {
      partition_date_source = "EventTime"
    }
  }
}

# --- Ciclo de vida -------------------------------------------------------------------

resource "aws_s3_bucket_lifecycle_configuration" "app" {
  bucket = aws_s3_bucket.this["app"].id

  rule {
    id     = "tenants-standard-ia"
    status = "Enabled"

    filter {
      prefix = "tenants/"
    }

    transition {
      days          = var.app_ia_transition_days
      storage_class = "STANDARD_IA"
    }
  }

  # Arquivos de evidência antigos vão para Glacier. A aplicação identifica a classe com a
  # tag de objeto retention-class=evidence (contrato com o backend; ver README).
  rule {
    id     = "evidence-glacier"
    status = "Enabled"

    filter {
      tag {
        key   = "retention-class"
        value = "evidence"
      }
    }

    transition {
      days          = var.app_evidence_glacier_days
      storage_class = "GLACIER"
    }
  }

  rule {
    id     = "noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "STANDARD_IA"
    }

    noncurrent_version_expiration {
      noncurrent_days = var.app_noncurrent_expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.this["artifacts"].id

  rule {
    id     = "artifacts-standard-ia"
    status = "Enabled"

    filter {
      prefix = "artifacts/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }

  rule {
    id     = "noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.this["logs"].id

  rule {
    id     = "alb-access-logs"
    status = "Enabled"

    filter {
      prefix = "alb/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = var.alb_logs_expiration_days
    }
  }

  rule {
    id     = "s3-access-logs"
    status = "Enabled"

    filter {
      prefix = "s3-access/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = var.alb_logs_expiration_days
    }
  }

  # Arquivo do log de acesso da aplicação: Glacier após 30 dias, mantido por 5 anos.
  rule {
    id     = "access-log-archive"
    status = "Enabled"

    filter {
      prefix = "cloudwatch-export/"
    }

    transition {
      days          = 30
      storage_class = "GLACIER"
    }

    expiration {
      days = var.access_log_archive_days
    }
  }

  rule {
    id     = "noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# --- Políticas de bucket ---------------------------------------------------------------

data "aws_iam_policy_document" "deny_insecure" {
  for_each = local.buckets

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      "arn:${var.partition}:s3:::${each.value}",
      "arn:${var.partition}:s3:::${each.value}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid     = "DenyOutdatedTLS"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      "arn:${var.partition}:s3:::${each.value}",
      "arn:${var.partition}:s3:::${each.value}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "NumericLessThan"
      variable = "s3:TlsVersion"
      values   = ["1.2"]
    }
  }
}

data "aws_iam_policy_document" "logs_delivery" {
  # Access logs do ALB (conta de serviço regional do ELB).
  statement {
    sid       = "AlbAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this["logs"].arn}/alb*/AWSLogs/${var.account_id}/*"]

    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.this.arn]
    }
  }

  # Access logs do ALB (principal de serviço, usado pelas regiões mais novas e aceito em
  # todas; mantém a política válida se a AWS migrar sa-east-1 para este modelo).
  statement {
    sid       = "AlbAccessLogsService"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this["logs"].arn}/alb*/AWSLogs/${var.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }
  }

  # Logs de acesso do S3.
  statement {
    sid       = "S3ServerAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this["logs"].arn}/s3-access/*"]

    principals {
      type        = "Service"
      identifiers = ["logging.s3.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }

  # Exportação do CloudWatch Logs (arquivo do log de acesso).
  statement {
    sid       = "CloudWatchExportAcl"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.this["logs"].arn]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }

  statement {
    sid       = "CloudWatchExportPut"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this["logs"].arn}/cloudwatch-export/*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
  }
}

# Bucket de segredos: nega operações de objeto a qualquer principal fora das listas,
# inclusive administradores da conta com permissões amplas de S3. A configuração do bucket
# (versionamento, política) continua gerida pelo Terraform.
data "aws_iam_policy_document" "secrets" {
  count = var.enable_secrets_bucket ? 1 : 0

  statement {
    sid       = "DenyReadOutsideAllowedPrincipals"
    effect    = "Deny"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["arn:${var.partition}:s3:::${local.buckets["secrets"]}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringNotLike"
      variable = "aws:PrincipalArn"
      values   = concat(var.secrets_reader_role_arns, var.secrets_admin_principal_arns)
    }
  }

  statement {
    sid    = "DenyWriteOutsideAdmins"
    effect = "Deny"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:PutObjectTagging",
      "s3:RestoreObject",
    ]
    resources = ["arn:${var.partition}:s3:::${local.buckets["secrets"]}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringNotLike"
      variable = "aws:PrincipalArn"
      values   = var.secrets_admin_principal_arns
    }
  }

  # Somente SSE (S3 ou KMS) — nunca objetos sem criptografia declarada.
  statement {
    sid       = "DenyUnencryptedUploads"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["arn:${var.partition}:s3:::${local.buckets["secrets"]}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Null"
      variable = "s3:x-amz-server-side-encryption"
      values   = ["true"]
    }
  }
}

data "aws_iam_policy_document" "combined" {
  for_each = local.buckets

  source_policy_documents = compact([
    data.aws_iam_policy_document.deny_insecure[each.key].json,
    each.key == "logs" ? data.aws_iam_policy_document.logs_delivery.json : "",
    each.key == "secrets" ? data.aws_iam_policy_document.secrets[0].json : "",
  ])
}

resource "aws_s3_bucket_lifecycle_configuration" "secrets" {
  count = var.enable_secrets_bucket ? 1 : 0

  bucket = aws_s3_bucket.this["secrets"].id

  # Versões antigas (segredos rotacionados) ficam 30 dias para reversão e depois expiram.
  rule {
    id     = "noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_policy" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  policy = data.aws_iam_policy_document.combined[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}
