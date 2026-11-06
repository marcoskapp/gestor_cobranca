# Launch template, user-data e papel das instâncias com provider simulado (sem credenciais AWS).
#   scripts/infra/terraform-check.sh modules/compute

mock_provider "aws" {
  override_during = plan
  mock_data "aws_ssm_parameter" { defaults = { insecure_value = "ami-0123456789abcdef0" } }
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/x" } }
  mock_resource "aws_iam_instance_profile" { defaults = { arn = "arn:aws:iam::123456789012:instance-profile/x" } }
  mock_resource "aws_launch_template" { defaults = { latest_version = 1, id = "lt-0123456789abcdef0" } }
}

variables {
  name_prefix              = "gestor-cobranca-homologacao"
  environment              = "homologacao"
  region                   = "sa-east-1"
  account_id               = "123456789012"
  standard_tags            = { Project = "gestor-cobranca", Environment = "homologacao" }
  instance_role_name       = "gestor-cobranca-homologacao-app"
  app_subnet_ids           = ["subnet-0000000000000000a", "subnet-0000000000000000b"]
  app_security_group_id    = "sg-0123456789abcdef0"
  trusted_proxy_cidrs      = ["10.20.0.0/24", "10.20.1.0/24"]
  target_group_arn         = "arn:aws:elasticloadbalancing:sa-east-1:123456789012:targetgroup/x/1"
  alb_arn_suffix           = "app/x/1"
  target_group_arn_suffix  = "targetgroup/x/1"
  min_size                 = 1
  max_size                 = 2
  artifacts_bucket_name    = "gestor-cobranca-homologacao-artifacts-123456789012"
  artifacts_bucket_arn     = "arn:aws:s3:::gestor-cobranca-homologacao-artifacts-123456789012"
  initial_artifact_version = "0.1.0"
  app_base_url             = "https://homologacao.gestorcobranca.example"
  cors_allowed_origins     = ["https://homologacao.gestorcobranca.example"]
  jwt_issuer               = "https://homologacao.gestorcobranca.example"
  app_bucket_name          = "gestor-cobranca-homologacao-app-123456789012"
  app_bucket_arn           = "arn:aws:s3:::gestor-cobranca-homologacao-app-123456789012"
  db_address               = "gestor-cobranca-homologacao-db.x.sa-east-1.rds.amazonaws.com"
  db_name                  = "cobranca"
  db_master_username       = "cobranca_master"
  db_master_secret_arn     = "arn:aws:secretsmanager:sa-east-1:123456789012:secret:rds!db-x"
  smtp_host                = "smtp.example"
  log_group_arns           = { technical = "arn:aws:logs:sa-east-1:123456789012:log-group:/gestor-cobranca/homologacao/technical" }
  ssm_parameter_prefix     = "/gestor-cobranca/homologacao"
  secrets_bucket_name      = "gestor-cobranca-homologacao-secrets-123456789012"
  secrets_bucket_arn       = "arn:aws:s3:::gestor-cobranca-homologacao-secrets-123456789012"
}

run "launch_template_seguro" {
  command = plan

  assert {
    condition = (one(aws_launch_template.app.metadata_options).http_tokens == "required"
    && one(aws_launch_template.app.metadata_options).http_put_response_hop_limit == 1)
    error_message = "IMDSv2 obrigatório (http_tokens = required) com hop limit 1"
  }
  assert {
    condition     = alltrue([for b in aws_launch_template.app.block_device_mappings : one(b.ebs).encrypted == "true"])
    error_message = "todo volume do launch template com encrypted = true"
  }
  # Só confere o launch template: o IP público depende também das sub-redes de app (módulo network).
  assert {
    condition     = length(aws_launch_template.app.network_interfaces) == 0
    error_message = "o launch template não define interface de rede (nem associate_public_ip_address); usa só o sg-app"
  }
}

# A configuração da instância (instance.conf) vai no user-data; os segredos não. A busca por marcadores
# pega regressões óbvias (um segredo copiado para o instance.conf), não prova a ausência de todo valor sensível.
run "user_data_com_a_configuracao_e_sem_segredos" {
  command = plan
  variables {
    jobs_concurrency          = 6
    jobs_dispatch_concurrency = 12
  }

  assert {
    condition     = strcontains(base64decode(aws_launch_template.app.user_data), "GC_JOBS_DISPATCH_CONCURRENCY='12'")
    error_message = "jobs_dispatch_concurrency chega ao instance.conf (JOBS_DISPATCH_CONCURRENCY no app.env; decisão 907)"
  }
  assert {
    condition     = strcontains(base64decode(aws_launch_template.app.user_data), "GC_JOBS_CONCURRENCY='6'")
    error_message = "jobs_concurrency chega ao instance.conf (JOBS_CONCURRENCY)"
  }
  assert {
    condition     = strcontains(base64decode(aws_launch_template.app.user_data), "GC_TRUSTED_PROXY_CIDRS='10.20.0.0/24,10.20.1.0/24'")
    error_message = "a aplicação confia só nas sub-redes do ALB (APP_HTTP_TRUSTED_PROXIES)"
  }
  assert {
    condition = alltrue([for marker in ["PASSWORD", "PRIVATE KEY", "CRYPTO_KEY_K", "BLIND_INDEX_KEY="] :
    !strcontains(base64decode(aws_launch_template.app.user_data), marker)])
    error_message = "o user-data não contém marcadores de segredo (senha, chave privada, chaves de cifra); os segredos são lidos do cofre no boot"
  }
}

run "execucao_imediata_exige_ao_menos_uma_vaga" {
  command = plan
  variables { jobs_dispatch_concurrency = 0 }
  expect_failures = [var.jobs_dispatch_concurrency]
}

# O runtime lê só app/*; migration/* e a senha mestre só com db_admin_access (ou na janela do migrate.sh).
run "runtime_sem_segredo_de_migracao" {
  command = plan

  assert {
    condition = alltrue([for s in data.aws_iam_policy_document.instance.statement :
    alltrue([for r in s.resources : !strcontains(r, "/migration/") && r != var.db_master_secret_arn])])
    error_message = "o papel das instâncias não lê migration/* nem a senha mestre do RDS"
  }
  assert {
    condition = one([for s in data.aws_iam_policy_document.instance.statement : s.resources if s.sid == "SecretsReadS3"]) == toset([
      "arn:aws:s3:::gestor-cobranca-homologacao-secrets-123456789012/homologacao/app/*",
    ])
    error_message = "sem SSM, leitura só de <env>/app/* no bucket de segredos"
  }
}

run "acesso_temporario_de_administracao_do_banco" {
  command = plan
  variables { db_admin_access = true }

  assert {
    condition = (contains([for s in data.aws_iam_policy_document.instance.statement : s.sid], "DbMasterSecretTemporary")
    && contains([for s in data.aws_iam_policy_document.instance.statement : s.sid], "MigrationSecretWithDbAdmin"))
    error_message = "db_admin_access libera a senha mestre e migration/* enquanto ligado"
  }
}
