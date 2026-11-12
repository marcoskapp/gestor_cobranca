import { NAVIGATION, activeNavRoute, navigationFor } from './navigation';

describe('activeNavRoute', () => {
  const routes = ['/', '/conversas', '/conversas/templates', '/conversas/canais', '/pagamentos', '/pagamentos/instrucoes'];

  it('marca só o item mais específico', () => {
    expect(activeNavRoute('/conversas/templates', routes)).toBe('/conversas/templates');
    expect(activeNavRoute('/conversas', routes)).toBe('/conversas');
    expect(activeNavRoute('/pagamentos/instrucoes/42', routes)).toBe('/pagamentos/instrucoes');
  });

  it('tela interna sem item próprio marca o item pai', () => {
    expect(activeNavRoute('/conversas/0b6c?aba=historico', routes)).toBe('/conversas');
    expect(activeNavRoute('/pagamentos/123#x', routes)).toBe('/pagamentos');
  });

  it('início só corresponde a ele mesmo e prefixo exige segmento inteiro', () => {
    expect(activeNavRoute('/', routes)).toBe('/');
    expect(activeNavRoute('/?q=1', routes)).toBe('/');
    expect(activeNavRoute('/conversasx', routes)).toBeNull();
    expect(activeNavRoute('/desconhecida', routes)).toBeNull();
  });

  it('o menu real não tem rotas repetidas', () => {
    const all = NAVIGATION.flatMap((s) => s.items.map((i) => i.route));
    expect(new Set(all).size).toBe(all.length);
  });
});

/**
 * Permissões dos perfis padrão (espelho de `DefaultRoles.java`; `DefaultRolesTest` confere o lado do servidor).
 * Atualize junto quando um perfil padrão mudar.
 */
const DEFAULT_ROLES: Record<string, readonly string[]> = {
  ADMIN: ['ACCESS_LOG_VIEW', 'AGREEMENT_CANCEL', 'AGREEMENT_FORMALIZE', 'AGREEMENT_VIEW', 'ASSISTANT_MANAGE', 'ASSISTANT_REVIEW', 'ASSISTANT_USE', 'AUDIT_VIEW', 'AUTHORITY_MANAGE', 'CALL_RECORDING_LISTEN', 'CAMPAIGN_EXECUTE', 'CAMPAIGN_MANAGE', 'CAMPAIGN_VIEW', 'CASE_ASSIGN', 'CASE_MANAGE', 'CASE_VIEW', 'CASE_WORK', 'CHANNEL_MANAGE', 'COLLATERAL_MANAGE', 'COMMISSION_MANAGE', 'COMMISSION_PLAN_MANAGE', 'COMMISSION_STATEMENT_APPROVE', 'COMMISSION_STATEMENT_MANAGE', 'COMMISSION_VIEW', 'CONTACT_RESTRICTION_MANAGE', 'CONTRACT_MANAGE', 'COST_MANAGE', 'COST_RATE_MANAGE', 'COST_VIEW', 'CREDITOR_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DATA_SUBJECT_REQUEST_MANAGE', 'DEBTOR_MANAGE', 'DEBTOR_SENSITIVE_VIEW', 'DEBTOR_VIEW', 'DEBT_TITLE_MANAGE', 'DISPUTE_MANAGE', 'DISPUTE_WORK', 'EXPERIMENT_MANAGE', 'IMPORT_APPROVE', 'IMPORT_EXECUTE', 'IMPORT_VIEW', 'INBOX_MANAGE', 'INBOX_VIEW', 'INTEGRATION_MANAGE', 'INTERACTION_REGISTER', 'LEGAL_AUTHORIZE', 'LEGAL_EXPENSE_MANAGE', 'LEGAL_MANAGE', 'LEGAL_VIEW', 'MESSAGE_SEND', 'NEGOTIATION_APPROVE', 'NEGOTIATION_PROPOSE', 'NEGOTIATION_SIMULATE', 'ORGANIZATION_AREA_VIEW', 'ORG_SETTINGS_MANAGE', 'PAYMENT_INSTRUCTION_CREATE', 'PAYMENT_REGISTER_EXTERNAL', 'PAYMENT_REVERSE', 'PAYMENT_REVIEW', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_ALL', 'PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', 'POLICY_AREA_VIEW', 'POLICY_MANAGE', 'POLICY_VIEW', 'PORTAL_MANAGE', 'PORTFOLIO_MANAGE', 'PORTFOLIO_VIEW', 'PRESCRIPTION_MANAGE', 'PUBLIC_API_BILLING_MANAGE', 'PUBLIC_API_TOKEN_MANAGE', 'PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_USAGE_VIEW', 'QUALITY_EVALUATE', 'QUEUE_MANAGE', 'RECONCILIATION_MANAGE', 'REPORT_EXPORT', 'REPORT_VIEW', 'RETENTION_MANAGE', 'ROLE_MANAGE', 'SCORING_AREA_VIEW', 'SCORING_MANAGE', 'SCORING_VIEW', 'SEGMENT_MANAGE', 'SETTLEMENT_APPROVE', 'SETTLEMENT_CLOSE', 'SETTLEMENT_TRANSFER', 'SETTLEMENT_VIEW', 'TABULATION_MANAGE', 'TASK_MANAGE', 'TEAM_MANAGE', 'TELEPHONY_MANAGE', 'TELEPHONY_SUPERVISE', 'TELEPHONY_USE', 'TEMPLATE_MANAGE', 'TENANT_BILLING_VIEW', 'TIER_MANAGE', 'TIER_REVIEW', 'TITLE_AMOUNT_ADJUST', 'TITLE_AMOUNT_APPROVE', 'USER_MANAGE', 'USER_VIEW'],
  GESTOR: ['ACCESS_LOG_VIEW', 'AGREEMENT_CANCEL', 'AGREEMENT_FORMALIZE', 'AGREEMENT_VIEW', 'ASSISTANT_MANAGE', 'ASSISTANT_REVIEW', 'ASSISTANT_USE', 'AUTHORITY_MANAGE', 'CALL_RECORDING_LISTEN', 'CAMPAIGN_EXECUTE', 'CAMPAIGN_MANAGE', 'CAMPAIGN_VIEW', 'CASE_ASSIGN', 'CASE_MANAGE', 'CASE_VIEW', 'CASE_WORK', 'COLLATERAL_MANAGE', 'COMMISSION_PLAN_MANAGE', 'COMMISSION_STATEMENT_MANAGE', 'COMMISSION_VIEW', 'CONTACT_RESTRICTION_MANAGE', 'CONTRACT_MANAGE', 'COST_VIEW', 'CREDITOR_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_MANAGE', 'DEBTOR_SENSITIVE_VIEW', 'DEBTOR_VIEW', 'DEBT_TITLE_MANAGE', 'DISPUTE_MANAGE', 'DISPUTE_WORK', 'EXPERIMENT_MANAGE', 'IMPORT_APPROVE', 'IMPORT_EXECUTE', 'IMPORT_VIEW', 'INBOX_MANAGE', 'INBOX_VIEW', 'INTERACTION_REGISTER', 'LEGAL_AUTHORIZE', 'LEGAL_EXPENSE_MANAGE', 'LEGAL_MANAGE', 'LEGAL_VIEW', 'MESSAGE_SEND', 'NEGOTIATION_APPROVE', 'NEGOTIATION_PROPOSE', 'NEGOTIATION_SIMULATE', 'ORGANIZATION_AREA_VIEW', 'PAYMENT_INSTRUCTION_CREATE', 'PAYMENT_REGISTER_EXTERNAL', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_ALL', 'PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', 'POLICY_AREA_VIEW', 'POLICY_MANAGE', 'POLICY_VIEW', 'PORTAL_MANAGE', 'PORTFOLIO_MANAGE', 'PORTFOLIO_VIEW', 'PRESCRIPTION_MANAGE', 'PUBLIC_API_BILLING_MANAGE', 'PUBLIC_API_TOKEN_MANAGE', 'PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_USAGE_VIEW', 'QUALITY_EVALUATE', 'QUEUE_MANAGE', 'REPORT_EXPORT', 'REPORT_VIEW', 'SCORING_AREA_VIEW', 'SCORING_MANAGE', 'SCORING_VIEW', 'SEGMENT_MANAGE', 'SETTLEMENT_APPROVE', 'SETTLEMENT_VIEW', 'TABULATION_MANAGE', 'TASK_MANAGE', 'TEAM_MANAGE', 'TELEPHONY_MANAGE', 'TELEPHONY_SUPERVISE', 'TELEPHONY_USE', 'TEMPLATE_MANAGE', 'TIER_MANAGE', 'TIER_REVIEW', 'TITLE_AMOUNT_ADJUST', 'TITLE_AMOUNT_APPROVE', 'USER_VIEW'],
  SUPERVISOR: ['AGREEMENT_CANCEL', 'AGREEMENT_FORMALIZE', 'AGREEMENT_VIEW', 'ASSISTANT_REVIEW', 'ASSISTANT_USE', 'CALL_RECORDING_LISTEN', 'CAMPAIGN_VIEW', 'CASE_ASSIGN', 'CASE_MANAGE', 'CASE_VIEW', 'CASE_WORK', 'CONTACT_RESTRICTION_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_MANAGE', 'DEBTOR_VIEW', 'DISPUTE_MANAGE', 'DISPUTE_WORK', 'IMPORT_VIEW', 'INBOX_MANAGE', 'INBOX_VIEW', 'INTERACTION_REGISTER', 'LEGAL_VIEW', 'MESSAGE_SEND', 'NEGOTIATION_APPROVE', 'NEGOTIATION_PROPOSE', 'NEGOTIATION_SIMULATE', 'PAYMENT_INSTRUCTION_CREATE', 'PAYMENT_REGISTER_EXTERNAL', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'QUALITY_EVALUATE', 'QUEUE_MANAGE', 'REPORT_VIEW', 'SCORING_VIEW', 'TASK_MANAGE', 'TEAM_MANAGE', 'TELEPHONY_SUPERVISE', 'TELEPHONY_USE', 'TIER_REVIEW', 'USER_VIEW'],
  OPERADOR: ['AGREEMENT_FORMALIZE', 'AGREEMENT_VIEW', 'ASSISTANT_USE', 'CASE_VIEW', 'CASE_WORK', 'CREDITOR_VIEW', 'DEBTOR_VIEW', 'DISPUTE_WORK', 'INBOX_VIEW', 'INTERACTION_REGISTER', 'MESSAGE_SEND', 'NEGOTIATION_PROPOSE', 'NEGOTIATION_SIMULATE', 'PAYMENT_INSTRUCTION_CREATE', 'PAYMENT_REGISTER_EXTERNAL', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_OWN', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'SCORING_VIEW', 'TASK_MANAGE', 'TELEPHONY_USE'],
  FINANCEIRO: ['AGREEMENT_VIEW', 'CASE_VIEW', 'COMMISSION_MANAGE', 'COMMISSION_STATEMENT_APPROVE', 'COMMISSION_VIEW', 'COST_MANAGE', 'COST_RATE_MANAGE', 'COST_VIEW', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_VIEW', 'LEGAL_EXPENSE_MANAGE', 'LEGAL_VIEW', 'ORGANIZATION_AREA_VIEW', 'PAYMENT_INSTRUCTION_CREATE', 'PAYMENT_REGISTER_EXTERNAL', 'PAYMENT_REVERSE', 'PAYMENT_REVIEW', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_ALL', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'PUBLIC_API_BILLING_MANAGE', 'PUBLIC_API_USAGE_VIEW', 'RECONCILIATION_MANAGE', 'REPORT_EXPORT', 'REPORT_VIEW', 'SETTLEMENT_CLOSE', 'SETTLEMENT_TRANSFER', 'SETTLEMENT_VIEW', 'TITLE_AMOUNT_ADJUST', 'TITLE_AMOUNT_APPROVE'],
  AUDITOR: ['ACCESS_LOG_VIEW', 'AGREEMENT_VIEW', 'AUDIT_VIEW', 'CAMPAIGN_VIEW', 'CASE_VIEW', 'COMMISSION_VIEW', 'COST_VIEW', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_VIEW', 'IMPORT_VIEW', 'INBOX_VIEW', 'LEGAL_VIEW', 'ORGANIZATION_AREA_VIEW', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_ALL', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_USAGE_VIEW', 'REPORT_VIEW', 'SCORING_AREA_VIEW', 'SCORING_VIEW', 'SETTLEMENT_VIEW', 'USER_VIEW'],
  JURIDICO: ['AGREEMENT_VIEW', 'CASE_VIEW', 'COLLATERAL_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_VIEW', 'LEGAL_AUTHORIZE', 'LEGAL_EXPENSE_MANAGE', 'LEGAL_MANAGE', 'LEGAL_VIEW', 'ORGANIZATION_AREA_VIEW', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'PRESCRIPTION_MANAGE'],
  ADVOGADO_PARCEIRO: ['LEGAL_EXPENSE_MANAGE', 'LEGAL_MANAGE', 'LEGAL_VIEW'],
  ANALISTA_CARTEIRA: ['CASE_VIEW', 'COLLATERAL_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_MANAGE', 'DEBTOR_VIEW', 'DEBT_TITLE_MANAGE', 'IMPORT_EXECUTE', 'IMPORT_VIEW', 'ORGANIZATION_AREA_VIEW', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'REPORT_VIEW'],
  ESTRATEGIA: ['AGREEMENT_VIEW', 'CAMPAIGN_EXECUTE', 'CAMPAIGN_MANAGE', 'CAMPAIGN_VIEW', 'CASE_VIEW', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'EXPERIMENT_MANAGE', 'ORGANIZATION_AREA_VIEW', 'PAYMENT_VIEW', 'PERFORMANCE_VIEW_ALL', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'REPORT_EXPORT', 'REPORT_VIEW', 'SCORING_AREA_VIEW', 'SCORING_MANAGE', 'SCORING_VIEW', 'SEGMENT_MANAGE', 'TEMPLATE_MANAGE'],
  QUALIDADE: ['AGREEMENT_VIEW', 'ASSISTANT_REVIEW', 'CALL_RECORDING_LISTEN', 'CASE_VIEW', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DEBTOR_VIEW', 'INBOX_VIEW', 'PERFORMANCE_VIEW_ALL', 'PORTFOLIO_VIEW', 'QUALITY_EVALUATE', 'REPORT_VIEW', 'USER_VIEW'],
  COMPLIANCE: ['ACCESS_LOG_VIEW', 'AUDIT_VIEW', 'CASE_VIEW', 'CONTACT_RESTRICTION_MANAGE', 'CREDITOR_VIEW', 'DASHBOARD_VIEW', 'DATA_SUBJECT_REQUEST_MANAGE', 'DEBTOR_SENSITIVE_VIEW', 'DEBTOR_VIEW', 'INBOX_VIEW', 'ORGANIZATION_AREA_VIEW', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'PORTFOLIO_VIEW', 'REPORT_VIEW', 'RETENTION_MANAGE', 'USER_VIEW'],
  INTEGRACAO: ['CHANNEL_MANAGE', 'CREDITOR_VIEW', 'IMPORT_EXECUTE', 'IMPORT_VIEW', 'INTEGRATION_MANAGE', 'ORGANIZATION_AREA_VIEW', 'PORTFOLIO_VIEW', 'PUBLIC_API_TOKEN_MANAGE', 'PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_USAGE_VIEW'],
};

/**
 * Itens do menu por perfil padrão (margem por carteira, 12/11/2026: +1 painel para quem tem COST_VIEW e +1 configuração
 * para quem tem COST_MANAGE ou COST_RATE_MANAGE; classificação de desempenho, 12/11/2026: +1 para TIER_REVIEW ou
 * TIER_MANAGE). Antes da D6: operador 31, supervisor 56, qualidade 27; os demais não mudaram
 * (quem tem a tarefa da área continua com ela). As seções recolhíveis cuidam dos perfis amplos (gestor, administrador).
 */
const MENU_COUNTS: Record<string, number> = {
  ADMIN: 100,
  GESTOR: 82,
  SUPERVISOR: 52,
  OPERADOR: 16,
  FINANCEIRO: 44,
  AUDITOR: 52,
  JURIDICO: 19,
  ADVOGADO_PARCEIRO: 4,
  ANALISTA_CARTEIRA: 17,
  ESTRATEGIA: 35,
  QUALIDADE: 26,
  COMPLIANCE: 27,
  INTEGRACAO: 11,
};

function menuOf(role: string) {
  const permissions = new Set(DEFAULT_ROLES[role]);
  return navigationFor((p) => permissions.has(p));
}

function labelsOf(role: string): string[] {
  return menuOf(role).flatMap((s) => s.items.map((i) => i.label));
}

describe('menu por tarefa (D6)', () => {
  it('o operador vê a própria operação, não as áreas de consulta nem o desempenho alheio', () => {
    expect(labelsOf('OPERADOR')).toEqual([
      'Início',
      'Minhas pendências',
      'Estação de cobrança',
      'Agenda de retornos',
      'Casos',
      'Devedores',
      'Títulos',
      'Caixa de entrada',
      'Simulador',
      'Propostas',
      'Acordos',
      'Contestações',
      'Pedidos do portal',
      'Pagamentos',
      'Instruções de pagamento',
      'Meu desempenho',
    ]);
  });

  it('o supervisor consulta políticas, mas não a área do score nem os cadastros de credores e carteiras', () => {
    const labels = labelsOf('SUPERVISOR');
    expect(labels).toContain('Políticas de cobrança');
    expect(labels).toContain('Produtividade');
    expect(labels).toContain('Desempenho');
    expect(labels).not.toContain('Meu desempenho');
    expect(labels.filter((l) => l.includes('score'))).toEqual([]);
    expect(labels).not.toContain('Credores');
  });

  it('quem tem a área como tarefa continua com ela no menu', () => {
    for (const role of ['GESTOR', 'ESTRATEGIA', 'AUDITOR', 'ADMIN']) {
      expect(labelsOf(role), role).toContain('Modelos do score');
    }
    for (const role of ['GESTOR', 'FINANCEIRO', 'AUDITOR', 'JURIDICO', 'ANALISTA_CARTEIRA', 'ESTRATEGIA', 'COMPLIANCE', 'INTEGRACAO']) {
      expect(labelsOf(role), role).toContain('Carteiras');
    }
    expect(labelsOf('QUALIDADE')).toContain('Formulários de monitoria');
  });

  it('nenhum perfil vê seção vazia nem item repetido', () => {
    for (const role of Object.keys(DEFAULT_ROLES)) {
      expect(menuOf(role).every((s) => s.items.length > 0), role).toBe(true);
      const labels = labelsOf(role);
      expect(new Set(labels).size, role).toBe(labels.length);
    }
  });

  it('quantidade de itens do menu por perfil', () => {
    const counts = Object.fromEntries(Object.keys(DEFAULT_ROLES).map((role) => [role, labelsOf(role).length]));
    expect(counts).toEqual(MENU_COUNTS);
  });
});

describe('ícones e seções do menu', () => {
  const items = NAVIGATION.flatMap((s) => s.items);

  it('ícones só de contorno (sem `-fill`) e um por item', () => {
    expect(items.filter((i) => i.icon.endsWith('-fill')).map((i) => i.label)).toEqual([]);
    const repeated = items.filter((i, index) => items.findIndex((o) => o.icon === i.icon) !== index).map((i) => `${i.label} (${i.icon})`);
    expect(repeated).toEqual([]);
  });

  it('seções com identificador estável e único', () => {
    const keys = NAVIGATION.map((s) => s.key);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.every((k) => /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(k))).toBe(true);
  });
});
