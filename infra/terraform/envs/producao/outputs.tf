# Saídas do ambiente (arquivo idêntico em homologacao e producao). Nenhuma contém segredo.
# Os scripts de deploy leem estas saídas com `terraform output -json`.

output "environment" {
  description = "Nome do ambiente."
  value       = var.environment
}

output "region" {
  description = "Região AWS."
  value       = var.region
}

output "app_url" {
  description = "URL pública da aplicação."
  value       = local.app_base_url
}

output "alb_dns_name" {
  description = "DNS do ALB (alvo do CNAME/alias do domínio quando o Route 53 não é usado)."
  value       = module.loadbalancer.alb_dns_name
}

output "nat_public_ips" {
  description = "IPs de saída (lista de permissão em provedores)."
  value       = module.network.nat_public_ips
}

output "app_bucket_name" {
  description = "Bucket de arquivos da aplicação."
  value       = module.storage.app_bucket_name
}

output "artifacts_bucket_name" {
  description = "Bucket de artefatos."
  value       = module.storage.artifacts_bucket_name
}

output "logs_bucket_name" {
  description = "Bucket de logs."
  value       = module.storage.logs_bucket_name
}

output "secrets_bucket_name" {
  description = "Bucket de segredos (somente sem SSM)."
  value       = module.storage.secrets_bucket_name
}

output "secrets_source" {
  description = "Origem dos segredos nas instâncias (ssm ou s3)."
  value       = var.use_ssm_parameters ? "ssm" : "s3"
}

output "ssm_parameter_prefix" {
  description = "Prefixo dos parâmetros SSM do ambiente."
  value       = local.ssm_parameter_prefix
}

output "secrets_kms_key_arn" {
  description = "Chave KMS das SecureStrings (com SSM)."
  value       = var.use_ssm_parameters ? aws_kms_key.secrets[0].arn : null
}

output "db_address" {
  description = "Endpoint do RDS."
  value       = module.database.address
}

output "db_identifier" {
  description = "Identificador do RDS."
  value       = module.database.identifier
}

output "db_master_user_secret_arn" {
  description = "ARN do segredo da senha mestre (gerenciado pelo RDS no Secrets Manager; o valor não está no state)."
  value       = module.database.master_user_secret_arn
}

output "autoscaling_group_name" {
  description = "Nome do ASG."
  value       = var.enable_compute ? module.compute[0].autoscaling_group_name : null
}

output "launch_template_id" {
  description = "ID do launch template."
  value       = var.enable_compute ? module.compute[0].launch_template_id : null
}

output "instance_role_arn" {
  description = "Papel IAM das instâncias."
  value       = var.enable_compute ? module.compute[0].instance_role_arn : null
}

output "instance_role_name" {
  description = "Nome do papel IAM das instâncias (migrate.sh cria nele a política temporária da janela de migração)."
  value       = var.enable_compute ? module.compute[0].instance_role_name : null
}

output "migration_secret_resources" {
  description = "Recursos dos segredos de migração (migration/*), fora do alcance do runtime."
  value       = var.enable_compute ? module.compute[0].migration_secret_resources : null
}

output "secret_read_actions" {
  description = "Ações IAM de leitura de segredo da origem configurada."
  value       = var.enable_compute ? module.compute[0].secret_read_actions : null
}

output "log_group_names" {
  description = "Grupos de log da aplicação."
  value       = module.observability.log_group_names
}

output "run_command_log_group_name" {
  description = "Grupo de log da saída do SSM Run Command."
  value       = module.observability.run_command_log_group_name
}

output "dashboard_name" {
  description = "Painel operacional do CloudWatch."
  value       = module.observability.dashboard_name
}

output "rollback_alarm_names" {
  description = "Alarmes de reversão automática do instance refresh."
  value       = module.observability.rollback_alarm_names
}

output "artifact_prefix" {
  description = "Prefixo dos artefatos no bucket."
  value       = "artifacts"
}

output "acm_validation_records" {
  description = "Registros DNS de validação do ACM (criar no provedor de DNS quando o Route 53 não é usado)."
  value = var.enable_acm_certificate ? [
    for o in aws_acm_certificate.app[0].domain_validation_options : {
      name  = o.resource_record_name
      type  = o.resource_record_type
      value = o.resource_record_value
    }
  ] : []
}

output "bedrock_invoke_resources" {
  description = "Recursos em que o papel das instâncias chama bedrock:InvokeModel (vazio com o Bedrock desligado)."
  value       = var.enable_compute ? module.compute[0].bedrock_invoke_resources : null
}

output "bedrock_denies_global_routing" {
  description = "O papel das instâncias nega o roteamento global do Bedrock."
  value       = var.enable_compute ? module.compute[0].bedrock_denies_global_routing : null
}
