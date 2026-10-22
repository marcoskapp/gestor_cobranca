# =============================================================================
# Composição do ambiente. Este arquivo, variables.tf, outputs.tf e optional.tf são
# IDÊNTICOS em envs/homologacao e envs/producao: as diferenças entre ambientes ficam
# exclusivamente em terraform.tfvars e backend.hcl (ver infra/terraform/README.md).
# =============================================================================

provider "aws" {
  region = var.region

  default_tags {
    tags = local.standard_tags
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  project     = "gestor-cobranca"
  name_prefix = "${local.project}-${var.environment}"
  account_id  = data.aws_caller_identity.current.account_id
  partition   = data.aws_partition.current.partition

  standard_tags = {
    Project     = local.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  app_base_url         = "https://${var.domain_name}"
  cors_allowed_origins = length(var.cors_allowed_origins) > 0 ? var.cors_allowed_origins : [local.app_base_url]
  jwt_issuer           = coalesce(var.jwt_issuer, local.app_base_url)

  ssm_parameter_prefix = "/${local.project}/${var.environment}"

  # Nome determinístico do papel das instâncias: a política do bucket de segredos o
  # referencia sem criar dependência circular entre os módulos storage e compute.
  instance_role_name = "${local.name_prefix}-app-instance"
  instance_role_arn  = "arn:${local.partition}:iam::${local.account_id}:role/${local.instance_role_name}"

  autoscaling_group_name = "${local.name_prefix}-app"

  certificate_arn = var.enable_acm_certificate ? aws_acm_certificate_validation.app[0].certificate_arn : var.certificate_arn
}

# Regras que envolvem mais de uma variável.
resource "terraform_data" "guards" {
  lifecycle {
    precondition {
      condition     = var.enable_acm_certificate || var.certificate_arn != null
      error_message = "Informe certificate_arn (certificado existente) ou habilite enable_acm_certificate (requer aprovação do ACM)."
    }

    precondition {
      condition     = !var.enable_route53 || var.route53_zone_id != null
      error_message = "enable_route53 exige route53_zone_id."
    }

    precondition {
      condition     = var.use_ssm_parameters || length(var.secrets_admin_principal_arns) > 0
      error_message = "Sem SSM Parameter Store, informe secrets_admin_principal_arns (quem provisiona os segredos no bucket dedicado)."
    }

    precondition {
      condition     = contains(var.crypto_key_ids, var.crypto_active_key_id)
      error_message = "crypto_active_key_id deve constar de crypto_key_ids."
    }

    # scripts/db/bootstrap-roles.sql limita app_runtime_login a 200 conexões.
    precondition {
      condition     = var.db_pool_size * var.asg_max_size * 2 <= 200
      error_message = "db_pool_size x asg_max_size x 2 (instance refresh dobra a frota) excede o connection limit 200 de app_runtime_login (scripts/db/bootstrap-roles.sql)."
    }

    precondition {
      condition     = var.db_pool_size * var.asg_max_size * 2 + 20 <= var.db_max_connections
      error_message = "db_max_connections insuficiente: considere pool x instâncias máximas x 2 (instance refresh dobra a frota) + 20 de folga."
    }
  }
}

# --- Rede ----------------------------------------------------------------------------------

module "network" {
  source = "../../modules/network"

  name_prefix                = local.name_prefix
  environment                = var.environment
  region                     = var.region
  account_id                 = local.account_id
  vpc_cidr                   = var.vpc_cidr
  az_count                   = 2
  nat_gateway_mode           = var.nat_gateway_mode
  enable_interface_endpoints = var.enable_interface_endpoints
  flow_logs_traffic_type     = var.flow_logs_traffic_type
  logs_kms_key_arn           = var.logs_kms_key_arn
}

module "security" {
  source = "../../modules/security"

  name_prefix            = local.name_prefix
  vpc_id                 = module.network.vpc_id
  app_port               = 8080
  s3_prefix_list_id      = module.network.s3_prefix_list_id
  alb_ingress_cidrs_ipv4 = var.alb_ingress_cidrs_ipv4
  smtp_egress_port       = var.smtp_port
}

# --- Armazenamento ---------------------------------------------------------------------------

module "storage" {
  source = "../../modules/storage"

  name_prefix                  = local.name_prefix
  account_id                   = local.account_id
  region                       = var.region
  partition                    = local.partition
  kms_key_arn                  = var.data_kms_key_arn
  enable_secrets_bucket        = !var.use_ssm_parameters
  secrets_reader_role_arns     = [local.instance_role_arn]
  secrets_admin_principal_arns = var.secrets_admin_principal_arns
}

# --- Banco ------------------------------------------------------------------------------------

module "database" {
  source = "../../modules/database"

  name_prefix               = local.name_prefix
  partition                 = local.partition
  subnet_ids                = module.network.data_subnet_ids
  security_group_id         = module.security.db_security_group_id
  instance_class            = var.db_instance_class
  multi_az                  = var.db_multi_az
  allocated_storage_gib     = var.db_allocated_storage_gib
  max_allocated_storage_gib = var.db_max_allocated_storage_gib
  max_connections           = var.db_max_connections
  backup_retention_days     = var.db_backup_retention_days
  deletion_protection       = var.db_deletion_protection
  apply_immediately         = var.db_apply_immediately
  storage_kms_key_arn       = var.data_kms_key_arn
  logs_kms_key_arn          = var.logs_kms_key_arn
}

# --- Balanceamento ------------------------------------------------------------------------------

module "loadbalancer" {
  source = "../../modules/loadbalancer"

  name_prefix         = local.name_prefix
  vpc_id              = module.network.vpc_id
  public_subnet_ids   = module.network.public_subnet_ids
  security_group_id   = module.security.alb_security_group_id
  certificate_arn     = local.certificate_arn
  deletion_protection = var.alb_deletion_protection
  access_logs_bucket  = module.storage.logs_bucket_name
  access_logs_prefix  = module.storage.alb_logs_prefix

  depends_on = [terraform_data.guards]
}

# --- Observabilidade ------------------------------------------------------------------------------

module "observability" {
  source = "../../modules/observability"

  name_prefix              = local.name_prefix
  environment              = var.environment
  region                   = var.region
  account_id               = local.account_id
  alb_arn_suffix           = module.loadbalancer.alb_arn_suffix
  target_group_arn_suffix  = module.loadbalancer.target_group_arn_suffix
  autoscaling_group_name   = local.autoscaling_group_name
  db_instance_identifier   = module.database.identifier
  db_allocated_storage_gib = var.db_allocated_storage_gib
  db_max_connections       = var.db_max_connections
  logs_kms_key_arn         = var.logs_kms_key_arn
  enable_sns_notifications = var.enable_sns_notifications
  alarm_email_endpoints    = var.alarm_email_endpoints
}

# --- Computação ----------------------------------------------------------------------------------

module "compute" {
  source = "../../modules/compute"
  count  = var.enable_compute ? 1 : 0

  name_prefix        = local.name_prefix
  environment        = var.environment
  region             = var.region
  account_id         = local.account_id
  partition          = local.partition
  standard_tags      = local.standard_tags
  instance_role_name = local.instance_role_name

  app_subnet_ids          = module.network.app_subnet_ids
  app_security_group_id   = module.security.app_security_group_id
  target_group_arn        = module.loadbalancer.target_group_arn
  alb_arn_suffix          = module.loadbalancer.alb_arn_suffix
  target_group_arn_suffix = module.loadbalancer.target_group_arn_suffix

  ami_id                   = var.ami_id
  instance_type            = var.instance_type
  min_size                 = var.asg_min_size
  max_size                 = var.asg_max_size
  bootstrap_mode           = var.bootstrap_mode
  db_admin_access          = var.db_admin_access
  cpu_target_percent       = var.cpu_target_percent
  requests_per_target      = var.requests_per_target
  business_hours_schedule  = var.business_hours_schedule
  ebs_kms_key_arn          = var.data_kms_key_arn
  initial_artifact_version = var.initial_artifact_version

  artifacts_bucket_name = module.storage.artifacts_bucket_name
  artifacts_bucket_arn  = module.storage.artifacts_bucket_arn

  app_base_url           = local.app_base_url
  cors_allowed_origins   = local.cors_allowed_origins
  jwt_issuer             = local.jwt_issuer
  app_bucket_name        = module.storage.app_bucket_name
  app_bucket_arn         = module.storage.app_bucket_arn
  app_bucket_kms_key_arn = var.data_kms_key_arn
  db_address             = module.database.address
  db_port                = module.database.port
  db_name                = module.database.db_name
  db_master_username     = module.database.master_username
  db_master_secret_arn   = module.database.master_user_secret_arn
  db_pool_size           = var.db_pool_size
  crypto_key_ids         = var.crypto_key_ids
  crypto_active_key_id   = var.crypto_active_key_id
  log_level              = var.log_level
  smtp_host              = var.smtp_host
  smtp_port              = var.smtp_port
  jobs_concurrency       = var.jobs_concurrency
  jvm_max_ram_percentage = var.jvm_max_ram_percentage

  log_group_arns = module.observability.log_group_arns

  use_ssm_parameters   = var.use_ssm_parameters
  ssm_parameter_prefix = local.ssm_parameter_prefix
  ssm_kms_key_arn      = var.use_ssm_parameters ? aws_kms_key.secrets[0].arn : null
  secrets_bucket_name  = module.storage.secrets_bucket_name
  secrets_bucket_arn   = module.storage.secrets_bucket_arn

  depends_on = [terraform_data.guards]
}
