# Invariantes de segurança do RDS com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/database

mock_provider "aws" {
  override_during = plan
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/x" } }
  mock_resource "aws_db_instance" { defaults = { master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:sa-east-1:123456789012:secret:rds!db-x", kms_key_id = "k", secret_status = "active" }] } }
}

variables {
  name_prefix       = "gestor-cobranca-homologacao"
  subnet_ids        = ["subnet-0000000000000000a", "subnet-0000000000000000b"]
  security_group_id = "sg-0123456789abcdef0"
}

run "banco_privado_criptografado_e_com_tls" {
  command = plan

  assert {
    condition     = !aws_db_instance.this.publicly_accessible
    error_message = "o RDS nunca é público"
  }
  assert {
    condition     = aws_db_instance.this.vpc_security_group_ids == toset(["sg-0123456789abcdef0"])
    error_message = "acesso só pelo sg-db"
  }
  assert {
    condition     = aws_db_instance.this.storage_encrypted
    error_message = "criptografia em repouso"
  }
  assert {
    condition     = aws_db_instance.this.manage_master_user_password && aws_db_instance.this.password == null
    error_message = "senha mestre gerada e guardada pelo próprio RDS; nunca no Terraform nem no state"
  }
  assert {
    condition     = aws_db_instance.this.deletion_protection && !aws_db_instance.this.skip_final_snapshot
    error_message = "proteção contra exclusão e snapshot final por padrão"
  }
  assert {
    condition = (one([for p in aws_db_parameter_group.this.parameter : p.value if p.name == "rds.force_ssl"]) == "1"
    && one([for p in aws_db_parameter_group.this.parameter : p.value if p.name == "ssl_min_protocol_version"]) == "TLSv1.2")
    error_message = "TLS obrigatório, versão mínima 1.2"
  }
  assert {
    condition     = startswith(aws_db_instance.this.engine_version, "16") && !aws_db_instance.this.allow_major_version_upgrade
    error_message = "PostgreSQL 16, sem troca de versão major automática"
  }
}

run "chave_do_cliente" {
  command = plan
  variables { storage_kms_key_arn = "arn:aws:kms:sa-east-1:123456789012:key/1" }

  assert {
    condition = (aws_db_instance.this.kms_key_id == "arn:aws:kms:sa-east-1:123456789012:key/1"
    && aws_db_instance.this.performance_insights_kms_key_id == "arn:aws:kms:sa-east-1:123456789012:key/1")
    error_message = "a chave do cliente cifra o armazenamento e o Performance Insights"
  }
}

run "somente_postgresql_16" {
  command = plan
  variables { engine_version = "17" }
  expect_failures = [var.engine_version]
}

run "subnet_group_em_duas_zonas" {
  command = plan
  variables { subnet_ids = ["subnet-0000000000000000a"] }
  expect_failures = [var.subnet_ids]
}
