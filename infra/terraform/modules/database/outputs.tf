output "identifier" {
  description = "Identificador da instância RDS."
  value       = aws_db_instance.this.identifier
}

output "address" {
  description = "Endereço (hostname) do endpoint do RDS, usado no DB_URL com sslmode=verify-full."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Porta do PostgreSQL."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Nome do banco da aplicação."
  value       = aws_db_instance.this.db_name
}

output "master_username" {
  description = "Usuário mestre (bootstrap de papéis)."
  value       = aws_db_instance.this.username
}

output "master_user_secret_arn" {
  description = "ARN do segredo da senha mestre, gerenciado pelo RDS no Secrets Manager (o valor não passa pelo Terraform)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "allocated_storage_gib" {
  description = "Armazenamento alocado (GiB)."
  value       = aws_db_instance.this.allocated_storage
}

output "max_connections" {
  description = "max_connections configurado."
  value       = var.max_connections
}
