# -----------------------------------------------------------------------------
# Application Load Balancer: único ponto de entrada.
#   80  → redireciona para 443
#   443 → TLS 1.2+/1.3 (política moderna) → target group 8080 das instâncias
# Certificado: ACM (REQUER APROVAÇÃO; certificado criado e validado no env) ou ARN externo
# importado, informado por variável. Access logs no bucket de logs.
# -----------------------------------------------------------------------------

resource "aws_lb" "this" {
  name               = substr("${var.name_prefix}-alb", 0, 32)
  load_balancer_type = "application"
  internal           = false
  security_groups    = [var.security_group_id]
  subnets            = var.public_subnet_ids
  ip_address_type    = "ipv4"

  enable_deletion_protection = var.deletion_protection
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  enable_http2               = true
  idle_timeout               = var.idle_timeout_seconds
  preserve_host_header       = true
  xff_header_processing_mode = "append"

  access_logs {
    enabled = true
    bucket  = var.access_logs_bucket
    prefix  = var.access_logs_prefix
  }

  connection_logs {
    enabled = var.connection_logs_enabled
    bucket  = var.access_logs_bucket
    prefix  = "${var.access_logs_prefix}-connections"
  }

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_lb_target_group" "app" {
  name                 = substr("${var.name_prefix}-app", 0, 32)
  port                 = var.app_port
  protocol             = "HTTP"
  protocol_version     = "HTTP1"
  target_type          = "instance"
  vpc_id               = var.vpc_id
  deregistration_delay = var.deregistration_delay_seconds
  slow_start           = var.slow_start_seconds

  load_balancing_algorithm_type     = "least_outstanding_requests"
  load_balancing_cross_zone_enabled = "true"

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Sem afinidade de sessão: a aplicação é stateless (JWT).
  stickiness {
    type    = "lb_cookie"
    enabled = false
  }

  tags = { Name = "${var.name_prefix}-app" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = var.ssl_policy
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# Os endpoints do actuator (exceto health) não são expostos pela internet: métricas e info
# ficam acessíveis apenas dentro da VPC.
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }
}
