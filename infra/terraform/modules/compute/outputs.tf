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
