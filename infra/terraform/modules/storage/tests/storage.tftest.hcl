# Invariantes de segurança dos buckets com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/storage

mock_provider "aws" {
  override_during = plan
  mock_data "aws_elb_service_account" { defaults = { arn = "arn:aws:iam::507241528517:root" } }
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
}

variables {
  name_prefix = "gestor-cobranca-homologacao"
  account_id  = "123456789012"
  region      = "sa-east-1"
}

run "buckets_privados_e_criptografados" {
  command = plan

  assert {
    condition     = toset(keys(aws_s3_bucket.this)) == toset(["app", "artifacts", "logs"])
    error_message = "sem enable_secrets_bucket: app, artifacts e logs"
  }
  assert {
    condition = length(aws_s3_bucket_public_access_block.this) == length(aws_s3_bucket.this) && alltrue([
      for b in aws_s3_bucket_public_access_block.this :
      b.block_public_acls && b.block_public_policy && b.ignore_public_acls && b.restrict_public_buckets
    ])
    error_message = "todo bucket com o bloqueio de acesso público completo"
  }
  assert {
    condition     = length(aws_s3_bucket_ownership_controls.this) == length(aws_s3_bucket.this) && alltrue([for c in aws_s3_bucket_ownership_controls.this : one(c.rule).object_ownership == "BucketOwnerEnforced"])
    error_message = "ACLs desativadas (BucketOwnerEnforced) em todo bucket"
  }
  assert {
    condition     = length(aws_s3_bucket_server_side_encryption_configuration.this) == length(aws_s3_bucket.this) && alltrue([for e in aws_s3_bucket_server_side_encryption_configuration.this : one(one(e.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"])
    error_message = "sem chave do cliente, criptografia padrão SSE-S3 em todo bucket"
  }
  assert {
    condition     = alltrue([for v in aws_s3_bucket_versioning.this : one(v.versioning_configuration).status == "Enabled"])
    error_message = "versionamento ligado em todo bucket"
  }
  assert {
    condition     = alltrue([for b in aws_s3_bucket.this : !b.force_destroy])
    error_message = "nenhum bucket é apagado com conteúdo por um destroy"
  }
  assert {
    condition     = toset(keys(aws_s3_bucket_policy.this)) == toset(keys(aws_s3_bucket.this))
    error_message = "todo bucket com política"
  }
  # A política de cada bucket nega requisição sem TLS e TLS < 1.2. Limitação do mock: o documento combinado
  # (aws_iam_policy_document.combined, aplicado em aws_s3_bucket_policy) depende do ARN do bucket de logs e só
  # é lido depois do apply; o plano não o avalia, então os testes conferem os documentos de origem e que todo
  # bucket tem política. O JSON das políticas é simulado (o mock não gera o JSON real do provider).
  assert {
    condition = alltrue([for k, d in data.aws_iam_policy_document.deny_insecure : anytrue([
      for s in d.statement : s.effect == "Deny" && anytrue([for c in s.condition : c.variable == "aws:SecureTransport" && c.values == tolist(["false"])])
    ])])
    error_message = "todo bucket nega requisições sem TLS"
  }
  assert {
    condition = alltrue([for k, d in data.aws_iam_policy_document.deny_insecure : anytrue([
      for s in d.statement : s.effect == "Deny" && anytrue([for c in s.condition : c.variable == "s3:TlsVersion" && c.test == "NumericLessThan" && c.values == tolist(["1.2"])])
    ])])
    error_message = "todo bucket nega TLS anterior a 1.2"
  }
}

run "chave_do_cliente_menos_no_bucket_de_logs" {
  command = plan
  variables { kms_key_arn = "arn:aws:kms:sa-east-1:123456789012:key/1" }

  assert {
    condition = alltrue([for k, e in aws_s3_bucket_server_side_encryption_configuration.this :
      one(one(e.rule).apply_server_side_encryption_by_default).sse_algorithm == (k == "logs" ? "AES256" : "aws:kms")
    ])
    error_message = "SSE-KMS com a chave do cliente, exceto no bucket de logs (os access logs do ALB não aceitam SSE-KMS)"
  }
}

run "bucket_de_segredos" {
  command = plan
  variables {
    enable_secrets_bucket        = true
    secrets_reader_role_arns     = ["arn:aws:iam::123456789012:role/gestor-cobranca-homologacao-app"]
    secrets_admin_principal_arns = ["arn:aws:iam::123456789012:role/admin*"]
  }

  assert {
    condition     = contains(keys(aws_s3_bucket.this), "secrets") && aws_s3_bucket_public_access_block.this["secrets"].restrict_public_buckets
    error_message = "bucket de segredos criado com o mesmo bloqueio público"
  }
  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.secrets[0].statement :
      s.sid == "DenyWriteOutsideAdmins" && s.effect == "Deny"
      && one(s.condition).values == tolist(["arn:aws:iam::123456789012:role/admin*"])
    ])
    error_message = "só os administradores informados gravam segredos (o papel das instâncias não)"
  }
  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.secrets[0].statement :
      s.sid == "DenyReadOutsideAllowedPrincipals"
      && toset(one(s.condition).values) == toset(["arn:aws:iam::123456789012:role/gestor-cobranca-homologacao-app", "arn:aws:iam::123456789012:role/admin*"])
    ])
    error_message = "leitura negada a todos fora do papel das instâncias e dos administradores"
  }
}
