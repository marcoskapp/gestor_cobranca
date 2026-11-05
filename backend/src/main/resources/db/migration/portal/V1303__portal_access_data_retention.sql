-- Retenção dos dados de acesso ao portal (LGPD, minimização — docs/tecnico/desenho/portal.md §5):
--   * desafios de acesso vencidos são apagados depois do prazo (app.portal.retention.challenges, 30 dias), inclusive
--     os fictícios, que guardam o HMAC do documento digitado por quem não é devedor;
--   * contadores do bloqueio progressivo sem uso e sem bloqueio vigente são apagados (app.portal.retention.throttles);
--   * IP e agente do navegador das sessões encerradas são apagados (app.portal.retention.session-client-data,
--     180 dias); a sessão continua, para o funil e a trilha.
-- A V1301 revogou o delete dos desafios ("encerrados, nunca removidos"); a retenção passa a removê-los — a trilha do
-- acesso fica no log de acesso (shared.access_log), sem o documento.

grant delete on portal.access_challenge to app_runtime;

create index ix_access_challenge_expires on portal.access_challenge (tenant_id, expires_at);
create index ix_access_throttle_idle on portal.access_throttle (tenant_id, updated_at);
create index ix_portal_session_client_data on portal.portal_session (tenant_id, ended_at)
    where client_ip is not null or user_agent is not null;
