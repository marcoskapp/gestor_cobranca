output "vpc_id" {
  description = "ID da VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "CIDR da VPC."
  value       = aws_vpc.this.cidr_block
}

output "azs" {
  description = "Zonas de disponibilidade utilizadas."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "Sub-redes públicas (ALB, NAT)."
  value       = aws_subnet.public[*].id
}

output "public_subnet_cidrs" {
  description = "CIDRs das sub-redes públicas: de onde partem as conexões do ALB às instâncias (proxies confiáveis da aplicação)."
  value       = aws_subnet.public[*].cidr_block
}

output "app_subnet_ids" {
  description = "Sub-redes privadas de aplicação (EC2)."
  value       = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  description = "Sub-redes privadas de dados (RDS)."
  value       = aws_subnet.data[*].id
}

output "s3_prefix_list_id" {
  description = "Prefix list gerenciada do S3 na região (regra de saída do sg-app pelo endpoint gateway)."
  value       = aws_vpc_endpoint.s3.prefix_list_id
}

output "nat_public_ips" {
  description = "IPs públicos de saída (informar a provedores que exigem lista de IPs permitidos)."
  value       = aws_eip.nat[*].public_ip
}
