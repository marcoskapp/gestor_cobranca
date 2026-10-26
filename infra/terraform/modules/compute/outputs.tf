output "autoscaling_group_name" {
  description = "Nome do Auto Scaling Group."
  value       = aws_autoscaling_group.app.name
}

output "launch_template_id" {
  description = "ID do launch template."
  value       = aws_launch_template.app.id
}

output "launch_template_latest_version" {
  description = "Versão mais recente do launch template."
  value       = aws_launch_template.app.latest_version
}

output "instance_role_arn" {
  description = "ARN do papel IAM das instâncias."
  value       = aws_iam_role.instance.arn
}

output "instance_role_name" {
  description = "Nome do papel IAM das instâncias."
  value       = aws_iam_role.instance.name
}

output "bootstrap_prefix" {
  description = "Prefixo S3 dos scripts de bootstrap vigentes."
  value       = local.bootstrap_prefix
}

output "ami_id" {
  description = "AMI em uso no launch template."
  value       = aws_launch_template.app.image_id
}

output "trusted_proxy_cidrs" {
  description = "Faixas de proxies confiáveis entregues à aplicação (APP_HTTP_TRUSTED_PROXIES)."
  value       = var.trusted_proxy_cidrs
}

output "migration_secret_resources" {
  description = "Recursos (SSM ou S3) dos segredos de migração (migration/*), que o runtime não lê; migrate.sh concede a leitura só à instância da migração e só durante a janela."
  value       = local.migration_secret_resources
}

output "secret_read_actions" {
  description = "Ações IAM de leitura de segredo conforme a origem (SSM ou S3)."
  value       = local.secret_read_actions
}

output "bedrock_invoke_resources" {
  description = "Recursos em que o papel das instâncias pode chamar bedrock:InvokeModel (perfis de inferência, modelos por meio deles e modelos base); vazio com o Bedrock desligado."
  value       = concat(local.bedrock_profile_arns, local.bedrock_profile_model_arns, local.bedrock_base_model_arns)
}

output "bedrock_denies_global_routing" {
  description = "O papel nega o roteamento global do Bedrock (nenhum perfil global.* configurado)."
  value       = var.assistant_bedrock_enabled && !local.bedrock_uses_global
}

output "instance_conf_public" {
  description = "instance.conf entregue às instâncias, sem o e-mail do administrador da plataforma (dado pessoal)."
  value       = local.instance_conf_public
}

output "platform_bootstrap_configured" {
  description = "O primeiro administrador da plataforma está configurado (e-mail e CNPJ informados)."
  value       = nonsensitive(var.platform_admin_email != null) && var.platform_cnpj != null
}
