terraform {
  # use_lockfile (trava nativa no S3, sem DynamoDB) exige Terraform >= 1.10.
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Configuração parcial: bucket, key e region vêm de backend.hcl (ver README).
  #   terraform init -backend-config=backend.hcl
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}
