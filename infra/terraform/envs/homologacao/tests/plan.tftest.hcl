# Testes de plano com provider simulado (sem credenciais AWS): combinações de fase e de
# serviços que requerem aprovação, e as regras de guarda entre variáveis.
#   terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_data "aws_partition" { defaults = { partition = "aws" } }
  mock_data "aws_availability_zones" { defaults = { names = ["sa-east-1a", "sa-east-1b", "sa-east-1c"] } }
  mock_data "aws_ssm_parameter" { defaults = { insecure_value = "ami-0123456789abcdef0" } }
  mock_data "aws_elb_service_account" { defaults = { arn = "arn:aws:iam::507241528517:root" } }
  mock_data "aws_iam_policy_document" { defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" } }
  mock_resource "aws_db_instance" { defaults = { master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:sa-east-1:123456789012:secret:rds!db-x", kms_key_id = "k", secret_status = "active" }] } }
  mock_resource "aws_acm_certificate" { defaults = { arn = "arn:aws:acm:sa-east-1:123456789012:certificate/abc", domain_validation_options = [{ domain_name = "homologacao.gestorcobranca.com.br", resource_record_name = "_x.homologacao.gestorcobranca.com.br.", resource_record_type = "CNAME", resource_record_value = "_y.acm-validations.aws." }] } }
  mock_resource "aws_lb" { defaults = { arn_suffix = "app/x/1", arn = "arn:aws:elasticloadbalancing:sa-east-1:123456789012:loadbalancer/app/x/1" } }
  mock_resource "aws_lb_target_group" { defaults = { arn_suffix = "targetgroup/x/1", arn = "arn:aws:elasticloadbalancing:sa-east-1:123456789012:targetgroup/x/1" } }
  mock_resource "aws_lb_listener" { defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:123456789012:listener/app/x/1/2" } }
  mock_resource "aws_iam_role" { defaults = { arn = "arn:aws:iam::123456789012:role/x" } }
  mock_resource "aws_iam_instance_profile" { defaults = { arn = "arn:aws:iam::123456789012:instance-profile/x" } }
  mock_resource "aws_cloudwatch_log_group" { defaults = { arn = "arn:aws:logs:sa-east-1:123456789012:log-group:x" } }
  mock_resource "aws_s3_bucket" { defaults = { arn = "arn:aws:s3:::x" } }
  mock_resource "aws_sns_topic" { defaults = { arn = "arn:aws:sns:sa-east-1:123456789012:x" } }
  mock_resource "aws_wafv2_web_acl" { defaults = { arn = "arn:aws:wafv2:sa-east-1:123456789012:regional/webacl/x/1" } }
  mock_resource "aws_cloudwatch_metric_alarm" { defaults = { arn = "arn:aws:cloudwatch:sa-east-1:123456789012:alarm:x" } }
  mock_resource "aws_launch_template" { defaults = { latest_version = 1, id = "lt-0123456789abcdef0" } }
  mock_resource "aws_kms_key" { defaults = { arn = "arn:aws:kms:sa-east-1:123456789012:key/1" } }
}

variables {
  environment                  = "homologacao"
  domain_name                  = "homologacao.gestorcobranca.com.br"
  vpc_cidr                     = "10.20.0.0/16"
  certificate_arn              = "arn:aws:acm:sa-east-1:123456789012:certificate/abc"
  secrets_admin_principal_arns = ["arn:aws:iam::123456789012:role/admin*"]
  initial_artifact_version     = "0.1.0"
  asg_min_size                 = 1
  asg_max_size                 = 2
  db_max_connections           = 200
  db_pool_size                 = 20
  smtp_host                    = "smtp.example"
  business_hours_schedule      = { start_cron = "0 8 * * 1-5", stop_cron = "0 20 * * 1-5", time_zone = "America/Sao_Paulo" }
}

run "fase1_sem_aprovacoes" {
  command = plan
  assert {
    condition     = length(module.network.app_subnet_ids) == 2
    error_message = "duas sub-redes de app"
  }
  assert {
    condition     = output.secrets_source == "s3"
    error_message = "sem SSM usa bucket"
  }
  assert {
    condition     = endswith(output.migration_secret_resources[0], "/migration/*") && length(output.migration_secret_resources) == 1
    error_message = "o segredo de migração fica num caminho próprio, fora do alcance do runtime (app/*)"
  }
}

run "tudo_aprovado_fase2" {
  command = plan
  variables {
    enable_acm_certificate     = true
    enable_route53             = true
    route53_zone_id            = "Z123"
    enable_waf                 = true
    use_ssm_parameters         = true
    enable_sns_notifications   = true
    alarm_email_endpoints      = ["ops@example.com"]
    nat_gateway_mode           = "per_az"
    enable_interface_endpoints = true
    db_multi_az                = true
    asg_min_size               = 2
    asg_max_size               = 6
    db_max_connections         = 500
    db_pool_size               = 15
    bootstrap_mode             = true
    db_admin_access            = true
    crypto_key_ids             = ["k1", "k2"]
    crypto_active_key_id       = "k2"
  }
  assert {
    condition     = output.secrets_source == "ssm"
    error_message = "ssm"
  }
  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.app[0].rule : r.name == "limite-de-taxa-por-ip"
    && r.statement[0].rate_based_statement[0].aggregate_key_type == "IP"])
    error_message = "o limite de taxa do WAF agrega pelo IP da conexão, nunca pelo X-Forwarded-For do cliente"
  }
  assert {
    condition     = length(module.network.public_subnet_cidrs) == 2 && module.compute[0].trusted_proxy_cidrs == module.network.public_subnet_cidrs
    error_message = "a aplicação confia somente nas sub-redes do ALB"
  }
}

run "sem_computacao" {
  command = plan
  variables { enable_compute = false }
}

run "guarda_conexoes" {
  command = plan
  variables {
    asg_max_size       = 6
    db_max_connections = 200
  }
  expect_failures = [terraform_data.guards]
}

run "guarda_limite_do_papel" {
  command = plan
  variables {
    asg_max_size       = 6
    db_pool_size       = 30
    db_max_connections = 1000
  }
  expect_failures = [terraform_data.guards]
}

run "guarda_certificado" {
  command = plan
  variables { certificate_arn = null }
  expect_failures = [terraform_data.guards]
}

run "bedrock_desligado_por_padrao" {
  command = plan
  assert {
    condition     = length(output.bedrock_invoke_resources) == 0 && !output.bedrock_denies_global_routing
    error_message = "sem o assistente no Bedrock, o papel não chama nenhum modelo"
  }
}

run "bedrock_exige_aprovacao_da_transferencia" {
  command = plan
  variables { assistant_bedrock_enabled = true }
  expect_failures = [terraform_data.guards]
}

run "bedrock_perfis_geograficos_us" {
  command = plan
  variables {
    assistant_bedrock_enabled               = true
    bedrock_international_transfer_approved = true
  }
  assert {
    condition = toset(output.bedrock_invoke_resources) == toset([
      "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-haiku-5-5",
      "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-5",
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-5-5",
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-sonnet-5-5",
    ])
    error_message = "só os perfis us.* configurados e os modelos base por meio deles"
  }
  assert {
    condition     = output.bedrock_denies_global_routing
    error_message = "sem perfil global, o roteamento global é negado"
  }
}

run "bedrock_perfil_global_em_sao_paulo" {
  command = plan
  variables {
    assistant_bedrock_enabled               = true
    bedrock_international_transfer_approved = true
    bedrock_region                          = "sa-east-1"
    bedrock_standard_model_id               = "global.anthropic.claude-haiku-5-5"
    bedrock_advanced_model_id               = "global.anthropic.claude-sonnet-5-5"
  }
  assert {
    condition     = contains(output.bedrock_invoke_resources, "arn:aws:bedrock:sa-east-1:123456789012:inference-profile/global.anthropic.claude-sonnet-5-5") && contains(output.bedrock_invoke_resources, "arn:aws:bedrock:::foundation-model/anthropic.claude-haiku-5-5")
    error_message = "perfil global: ARN do perfil em sa-east-1 e o modelo sem região"
  }
  assert {
    condition     = !output.bedrock_denies_global_routing
    error_message = "com perfil global configurado, o roteamento global não é negado"
  }
}

run "chat_suporte_exige_aprovacao_da_transferencia" {
  command = plan
  variables { helpdesk_bedrock_enabled = true }
  expect_failures = [terraform_data.guards]
}

run "chat_suporte_so_com_o_haiku" {
  command = plan
  variables {
    helpdesk_bedrock_enabled                = true
    bedrock_international_transfer_approved = true
  }
  assert {
    condition = toset(output.bedrock_invoke_resources) == toset([
      "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-haiku-5-5",
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-5-5",
    ])
    error_message = "o chat de suporte sozinho libera só o perfil do Haiku (sem o Sonnet do assistente)"
  }
  assert {
    condition     = output.bedrock_denies_global_routing
    error_message = "sem perfil global, o roteamento global é negado também para o chat"
  }
}
