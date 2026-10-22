/** Contratos de `GovernanceController` (`GovernanceQueries`). */

export interface AccessLogEntry {
  id: string;
  occurredAt: string;
  eventType: string;
  success: boolean;
  actorType: string | null;
  actorId: string | null;
  actorName: string | null;
  sessionId: string | null;
  /** Identificação já mascarada pelo servidor. */
  principal: string | null;
  clientIp: string | null;
  userAgent: string | null;
  requestId: string | null;
  route: string | null;
  details: Record<string, unknown> | null;
}

export interface AccessLogFilter {
  from?: string | null;
  to?: string | null;
  actorId?: string | null;
  eventType?: string | null;
  success?: boolean | null;
  clientIp?: string | null;
}

export interface AuditLogEntry {
  seq: number;
  id: string;
  occurredAt: string;
  actorType: string | null;
  actorId: string | null;
  actorName: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  creditorId: string | null;
  reason: string | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  metadata: Record<string, unknown> | null;
  requestId: string | null;
  clientIp: string | null;
  sealed: boolean;
}

export interface AuditFilter {
  from?: string | null;
  to?: string | null;
  actorId?: string | null;
  entityType?: string | null;
  entityId?: string | null;
  action?: string | null;
  creditorId?: string | null;
}

export interface IntegrityReport {
  intact: boolean;
  firstInvalidSeq: number | null;
  firstInvalidId: string | null;
  reason: string | null;
  checkedAt: string;
}

/** `shared.logging.access.AccessEventType` com rótulos em português. */
export const ACCESS_EVENT_LABELS: Record<string, string> = {
  LOGIN_SUCCESS: 'Login realizado',
  LOGIN_FAILURE: 'Falha de login',
  LOGIN_BLOCKED: 'Login bloqueado',
  MFA_CHALLENGE: 'Desafio MFA',
  MFA_SUCCESS: 'MFA validado',
  MFA_FAILURE: 'Falha de MFA',
  MFA_ENROLLED: 'MFA cadastrado',
  MFA_RESET: 'MFA redefinido',
  TOKEN_REFRESHED: 'Sessão renovada',
  TOKEN_REUSE_DETECTED: 'Reuso de token detectado',
  LOGOUT: 'Logout',
  SESSION_REVOKED: 'Sessão revogada',
  SESSION_EXPIRED: 'Sessão expirada',
  PASSWORD_CHANGED: 'Senha alterada',
  PASSWORD_RESET_REQUESTED: 'Redefinição de senha solicitada',
  PASSWORD_RESET_COMPLETED: 'Senha redefinida',
  USER_CREATED: 'Usuário criado',
  USER_UPDATED: 'Usuário alterado',
  USER_DISABLED: 'Usuário desativado',
  USER_ENABLED: 'Usuário reativado',
  USER_UNLOCKED: 'Usuário desbloqueado',
  ROLE_CHANGED: 'Perfis alterados',
  PERMISSION_CHANGED: 'Permissões alteradas',
  SCOPE_CHANGED: 'Escopo alterado',
  ACCESS_DENIED: 'Acesso negado',
  SCOPE_DENIED: 'Acesso fora do escopo',
  UNAUTHENTICATED: 'Requisição sem autenticação',
  SENSITIVE_DATA_VIEWED: 'Dado sensível visualizado',
  DATA_EXPORTED: 'Exportação de dados',
  FILE_DOWNLOADED: 'Download de arquivo',
  WEBHOOK_REJECTED: 'Webhook rejeitado',
  PORTAL_LOGIN_SUCCESS: 'Login no portal',
  PORTAL_LOGIN_FAILURE: 'Falha de login no portal',
  RATE_LIMITED: 'Limite de tentativas atingido',
};

/** Tipos de entidade auditados atualmente pelo backend. */
export const AUDIT_ENTITY_LABELS: Record<string, string> = {
  user: 'Usuário',
  role: 'Perfil',
  team: 'Equipe',
  tenant: 'Assessoria',
  business_unit: 'Unidade',
  creditor: 'Credor',
  service_contract: 'Contrato',
  portfolio: 'Carteira',
};
