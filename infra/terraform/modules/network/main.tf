# -----------------------------------------------------------------------------
# Rede: VPC em 2 AZs com três camadas de sub-rede.
#   públicas         ALB e NAT Gateway (rota para o Internet Gateway)
#   privadas de app  instâncias EC2 (saída pela NAT; S3 pelo endpoint gateway)
#   privadas de dados RDS (sem nenhuma rota para fora da VPC)
# -----------------------------------------------------------------------------

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # /16 dividido em blocos /20 (4.096 endereços cada):
  #   0..3   públicas | 4..7  app | 8..11 dados  (espaço reservado para mais AZs)
  public_cidrs = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i)]
  app_cidrs    = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i + 4)]
  data_cidrs   = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, i + 8)]

  nat_count = var.nat_gateway_mode == "per_az" ? var.az_count : 1
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name_prefix}-vpc" }
}

# O security group padrão da VPC fica sem nenhuma regra: nada deve usá-lo.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-default-nao-usar" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-igw" }
}

# --- Sub-redes -----------------------------------------------------------------

resource "aws_subnet" "public" {
  count = var.az_count

  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-public-${local.azs[count.index]}", Tier = "public" }
}

resource "aws_subnet" "app" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.app_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${var.name_prefix}-app-${local.azs[count.index]}", Tier = "app" }
}

resource "aws_subnet" "data" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.data_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${var.name_prefix}-data-${local.azs[count.index]}", Tier = "data" }
}

# --- NAT -----------------------------------------------------------------------

resource "aws_eip" "nat" {
  count = local.nat_count

  domain = "vpc"

  tags = { Name = "${var.name_prefix}-nat-${local.azs[count.index]}" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count = local.nat_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = { Name = "${var.name_prefix}-nat-${local.azs[count.index]}" }

  depends_on = [aws_internet_gateway.this]
}

# --- Tabelas de rota -------------------------------------------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = var.az_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Uma tabela por AZ na camada de app: com NAT por AZ, cada uma sai pela NAT da própria
# zona (falha de uma AZ não derruba a saída da outra); com NAT único, todas usam a mesma.
resource "aws_route_table" "app" {
  count = var.az_count

  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-app-${local.azs[count.index]}" }
}

resource "aws_route" "app_nat" {
  count = var.az_count

  route_table_id         = aws_route_table.app[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.nat_gateway_mode == "per_az" ? count.index : 0].id
}

resource "aws_route_table_association" "app" {
  count = var.az_count

  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# Camada de dados: apenas a rota local da VPC.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-rt-data" }
}

resource "aws_route_table_association" "data" {
  count = var.az_count

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# --- Endpoints -----------------------------------------------------------------

# Gateway S3 (sem custo): tráfego das instâncias ao S3 não passa pela NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id

  tags = { Name = "${var.name_prefix}-vpce-s3" }
}

# Endpoints de interface opcionais (SSM, CloudWatch): tiram da NAT o tráfego de gestão e
# observabilidade. Custam por hora e por AZ; ver README (custos por fase).
locals {
  interface_endpoint_services = var.enable_interface_endpoints ? toset([
    "ssm", "ssmmessages", "ec2messages", "logs", "monitoring",
  ]) : toset([])
}

resource "aws_security_group" "endpoints" {
  count = var.enable_interface_endpoints ? 1 : 0

  name        = "${var.name_prefix}-sg-vpce"
  description = "Endpoints de interface: HTTPS a partir da VPC"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-sg-vpce" }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  count = var.enable_interface_endpoints ? 1 : 0

  security_group_id = aws_security_group.endpoints[0].id
  description       = "HTTPS da VPC"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = var.vpc_cidr
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_endpoint_services

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.app[*].id
  security_group_ids  = [aws_security_group.endpoints[0].id]

  tags = { Name = "${var.name_prefix}-vpce-${each.value}" }
}

# --- VPC Flow Logs (rastreabilidade de rede) -----------------------------------------

resource "aws_cloudwatch_log_group" "flow_logs" {
  count = var.flow_logs_traffic_type == null ? 0 : 1

  name              = "/gestor-cobranca/${var.environment}/vpc-flow-logs"
  retention_in_days = var.flow_logs_retention_days
  kms_key_id        = var.logs_kms_key_arn
}

data "aws_iam_policy_document" "flow_logs_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  count = var.flow_logs_traffic_type == null ? 0 : 1

  name               = "${var.name_prefix}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume.json
}

data "aws_iam_policy_document" "flow_logs" {
  count = var.flow_logs_traffic_type == null ? 0 : 1

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.flow_logs[0].arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  count = var.flow_logs_traffic_type == null ? 0 : 1

  name   = "flow-logs"
  role   = aws_iam_role.flow_logs[0].id
  policy = data.aws_iam_policy_document.flow_logs[0].json
}

resource "aws_flow_log" "this" {
  count = var.flow_logs_traffic_type == null ? 0 : 1

  vpc_id                   = aws_vpc.this.id
  traffic_type             = var.flow_logs_traffic_type
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs[0].arn
  iam_role_arn             = aws_iam_role.flow_logs[0].arn
  max_aggregation_interval = 60

  tags = { Name = "${var.name_prefix}-flow-logs" }
}
