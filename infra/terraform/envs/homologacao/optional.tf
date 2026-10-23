# =============================================================================
# Recursos que REQUEREM APROVAÇÃO do responsável pelo projeto. Todos desligados por padrão;
# cada um é ativado por uma variável booleana após a aprovação registrada no PR.
#   enable_acm_certificate   ACM          certificado TLS do ALB
#   enable_route53           Route 53     registro DNS do domínio e validação do ACM
#   enable_waf               AWS WAF      regras gerenciadas e limite de taxa no ALB
#   use_ssm_parameters       SSM + KMS    segredos em SecureString com chave KMS dedicada
#   enable_sns_notifications SNS          notificação dos alarmes (módulo observability)
# =============================================================================

# --- ACM ---------------------------------------------------------------------------------

resource "aws_acm_certificate" "app" {
  count = var.enable_acm_certificate ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"
  key_algorithm     = "RSA_2048"

  options {
    certificate_transparency_logging_preference = "ENABLED"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "acm_validation" {
  for_each = var.enable_acm_certificate && var.enable_route53 ? {
    for o in aws_acm_certificate.app[0].domain_validation_options : o.domain_name => o
  } : {}

  zone_id         = var.route53_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

# Sem Route 53, o registro de validação (output acm_validation_records) deve ser criado no
# provedor de DNS durante o apply; este recurso aguarda a emissão.
resource "aws_acm_certificate_validation" "app" {
  count = var.enable_acm_certificate ? 1 : 0

  certificate_arn         = aws_acm_certificate.app[0].arn
  validation_record_fqdns = var.enable_route53 ? [for r in aws_route53_record.acm_validation : r.fqdn] : null

  timeouts {
    create = "2h"
  }
}

# --- Route 53 ---------------------------------------------------------------------------------

resource "aws_route53_record" "app" {
  count = var.enable_route53 ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = module.loadbalancer.alb_dns_name
    zone_id                = module.loadbalancer.alb_zone_id
    evaluate_target_health = true
  }
}

# --- SSM Parameter Store + KMS --------------------------------------------------------------
# A chave cifra as SecureStrings em /gestor-cobranca/<env>/. Os VALORES nunca passam pelo
# Terraform: são gravados por scripts/ops/provision-secrets.sh direto no Parameter Store.

resource "aws_kms_key" "secrets" {
  count = var.use_ssm_parameters ? 1 : 0

  description             = "Segredos da aplicacao ${local.name_prefix} (SSM Parameter Store)"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "secrets" {
  count = var.use_ssm_parameters ? 1 : 0

  name          = "alias/${local.project}/${var.environment}/secrets"
  target_key_id = aws_kms_key.secrets[0].key_id
}

# --- AWS WAF ------------------------------------------------------------------------------------

resource "aws_wafv2_web_acl" "app" {
  count = var.enable_waf ? 1 : 0

  name        = "${local.name_prefix}-alb"
  description = "Protecao do ALB ${local.name_prefix}"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "limite-de-taxa-por-ip"
    priority = 0

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.waf_rate_limit_per_5min
        aggregate_key_type    = "FORWARDED_IP"
        evaluation_window_sec = 300

        forwarded_ip_config {
          header_name       = "X-Forwarded-For"
          fallback_behavior = "MATCH"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        # Importação de carteiras e anexos têm corpo acima de 8 KB: a regra de tamanho só
        # contabiliza (o limite real é aplicado pela aplicação, 100 MB).
        rule_action_override {
          name = "SizeRestrictions_BODY"

          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-sqli"
    priority = 40

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "sqli"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name_prefix}-alb"
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "app" {
  count = var.enable_waf ? 1 : 0

  resource_arn = module.loadbalancer.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.app[0].arn
}

# O nome do grupo precisa começar com aws-waf-logs-.
resource "aws_cloudwatch_log_group" "waf" {
  count = var.enable_waf ? 1 : 0

  name              = "aws-waf-logs-${local.name_prefix}"
  retention_in_days = 90
  kms_key_id        = var.logs_kms_key_arn
}

resource "aws_wafv2_web_acl_logging_configuration" "app" {
  count = var.enable_waf ? 1 : 0

  resource_arn            = aws_wafv2_web_acl.app[0].arn
  log_destination_configs = [aws_cloudwatch_log_group.waf[0].arn]

  # Credenciais e tokens nunca vão para o log do WAF.
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }
}
