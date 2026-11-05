# -----------------------------------------------------------------------------
# Recebimento das respostas dos credores por e-mail (docs/tecnico/operacao/email-entrada.md, decisões 820–836).
#   SES (regra de recebimento do subdomínio, varredura de spam/vírus ligada)
#     → S3 (bucket próprio, criptografado, sem acesso público, ciclo de vida curto)
#     → SNS (notificação do objeto gravado) → SQS (fila + DLQ) → consumidor Java na aplicação.
# A aplicação confere os veredictos SPF/DKIM/DMARC/vírus da notificação, o remetente, os limites e os anexos;
# nada é descartado em silêncio. O papel das instâncias recebe só GetObject no bucket e consumo da fila.
# -----------------------------------------------------------------------------

locals {
  bucket_name = "${var.name_prefix}-inbound-mail-${var.account_id}"
  kms_enabled = var.kms_key_arn != null
}

resource "aws_s3_bucket" "inbound" {
  bucket        = local.bucket_name
  force_destroy = false
  tags          = { Name = local.bucket_name, Purpose = "inbound-mail" }
}

resource "aws_s3_bucket_ownership_controls" "inbound" {
  bucket = aws_s3_bucket.inbound.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "inbound" {
  bucket                  = aws_s3_bucket.inbound.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "inbound" {
  bucket = aws_s3_bucket.inbound.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = local.kms_enabled ? "aws:kms" : "AES256"
      kms_master_key_id = local.kms_enabled ? var.kms_key_arn : null
    }
    bucket_key_enabled = local.kms_enabled
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "inbound" {
  bucket = aws_s3_bucket.inbound.id
  rule {
    id     = "expire-raw-mail"
    status = "Enabled"
    filter {}
    expiration {
      days = var.raw_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "AllowSesPut"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.inbound.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${var.partition}:ses:${var.region}:${var.account_id}:receipt-rule-set/${var.name_prefix}-inbound:receipt-rule/*"]
    }
  }
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.inbound.arn, "${aws_s3_bucket.inbound.arn}/*"]
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
}

resource "aws_s3_bucket_policy" "inbound" {
  bucket = aws_s3_bucket.inbound.id
  policy = data.aws_iam_policy_document.bucket.json
}

# --- SNS → SQS ---------------------------------------------------------------------------

# O tópico só repassa a notificação (metadados e veredictos; o corpo fica no S3). Sem KMS: a chave
# gerenciada da AWS não admite o SES como publicador, e a fila de destino já é criptografada (SSE-SQS).
resource "aws_sns_topic" "inbound" {
  name = "${var.name_prefix}-inbound-mail"
}

data "aws_iam_policy_document" "topic" {
  statement {
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.inbound.arn]
    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "inbound" {
  arn    = aws_sns_topic.inbound.arn
  policy = data.aws_iam_policy_document.topic.json
}

resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name_prefix}-inbound-mail-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "inbound" {
  name                       = "${var.name_prefix}-inbound-mail"
  visibility_timeout_seconds = 300
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 5
  })
}

data "aws_iam_policy_document" "queue" {
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.inbound.arn]
    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_sns_topic.inbound.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "inbound" {
  queue_url = aws_sqs_queue.inbound.id
  policy    = data.aws_iam_policy_document.queue.json
}

resource "aws_sns_topic_subscription" "inbound" {
  topic_arn            = aws_sns_topic.inbound.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.inbound.arn
  raw_message_delivery = false
}

# --- SES ---------------------------------------------------------------------------------

resource "aws_ses_receipt_rule_set" "inbound" {
  rule_set_name = "${var.name_prefix}-inbound"
}

resource "aws_ses_active_receipt_rule_set" "inbound" {
  rule_set_name = aws_ses_receipt_rule_set.inbound.rule_set_name
}

resource "aws_ses_receipt_rule" "replies" {
  name          = "creditor-replies"
  rule_set_name = aws_ses_receipt_rule_set.inbound.rule_set_name
  recipients    = [var.inbound_domain]
  enabled       = true
  scan_enabled  = true
  tls_policy    = "Require"

  s3_action {
    bucket_name       = aws_s3_bucket.inbound.id
    object_key_prefix = "raw/"
    topic_arn         = aws_sns_topic.inbound.arn
    position          = 1
  }

  depends_on = [aws_s3_bucket_policy.inbound, aws_sns_topic_policy.inbound]
}

# --- Papel das instâncias: só leitura do bruto e consumo da fila ------------------------

data "aws_iam_policy_document" "consumer" {
  statement {
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.inbound.arn]
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.inbound.arn}/raw/*"]
  }
  dynamic "statement" {
    for_each = local.kms_enabled ? [1] : []
    content {
      actions   = ["kms:Decrypt"]
      resources = [var.kms_key_arn]
    }
  }
}

resource "aws_iam_role_policy" "consumer" {
  name   = "${var.name_prefix}-inbound-mail-consumer"
  role   = var.instance_role_name
  policy = data.aws_iam_policy_document.consumer.json
}
