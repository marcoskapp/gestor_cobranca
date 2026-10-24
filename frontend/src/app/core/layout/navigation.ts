import { Permission } from '../auth/permission';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Exibido se o usuário tiver ao menos uma das permissões (vazio = todos os autenticados). */
  permissions: readonly string[];
  exact?: boolean;
}

export interface NavSection {
  label: string | null;
  items: readonly NavItem[];
}

/** Menu principal. Cada rota repete as permissões em `data.permissions` (permissionGuard). */
export const NAVIGATION: readonly NavSection[] = [
  {
    label: null,
    items: [{ label: 'Início', icon: 'home', route: '/', permissions: [], exact: true }],
  },
  {
    label: 'Cobrança',
    items: [
      { label: 'Estação de cobrança', icon: 'support_agent', route: '/cobranca/fila', permissions: [Permission.CASE_WORK] },
      {
        label: 'Agenda de retornos',
        icon: 'event_note',
        route: '/cobranca/agenda',
        permissions: [Permission.CASE_WORK, Permission.TASK_MANAGE],
      },
      { label: 'Casos', icon: 'cases', route: '/cobranca/casos', permissions: [Permission.CASE_VIEW] },
      { label: 'Devedores', icon: 'person_search', route: '/devedores', permissions: [Permission.DEBTOR_VIEW] },
      { label: 'Títulos', icon: 'request_quote', route: '/titulos', permissions: [Permission.DEBTOR_VIEW, Permission.CASE_VIEW] },
    ],
  },
  {
    label: 'Comunicação',
    items: [
      { label: 'Caixa de entrada', icon: 'forum', route: '/conversas', permissions: [Permission.INBOX_VIEW] },
      { label: 'Templates', icon: 'article', route: '/conversas/templates', permissions: [Permission.TEMPLATE_MANAGE] },
      { label: 'Canais', icon: 'settings_input_antenna', route: '/conversas/canais', permissions: [Permission.CHANNEL_MANAGE] },
    ],
  },
  {
    label: 'Assistente de IA',
    items: [
      { label: 'Políticas do assistente', icon: 'smart_toy', route: '/assistente/politicas', permissions: [Permission.ASSISTANT_MANAGE, Permission.ASSISTANT_REVIEW] },
      { label: 'Revisão de turnos', icon: 'rate_review', route: '/assistente/revisao', permissions: [Permission.ASSISTANT_REVIEW] },
      { label: 'Qualidade do assistente', icon: 'insights', route: '/assistente/qualidade', permissions: [Permission.ASSISTANT_REVIEW] },
    ],
  },
  {
    label: 'Telefonia',
    items: [
      { label: 'Campanhas de discagem', icon: 'phone_forwarded', route: '/telefonia/campanhas', permissions: [Permission.TELEPHONY_SUPERVISE] },
      { label: 'Chamadas e gravações', icon: 'call', route: '/telefonia/chamadas', permissions: [Permission.TELEPHONY_SUPERVISE] },
      { label: 'Operadores e ramais', icon: 'headset_mic', route: '/telefonia/operadores', permissions: [Permission.TELEPHONY_SUPERVISE] },
    ],
  },
  {
    label: 'Campanhas',
    items: [
      {
        label: 'Campanhas',
        icon: 'campaign',
        route: '/campanhas',
        permissions: [Permission.CAMPAIGN_VIEW, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_EXECUTE],
      },
      {
        label: 'Segmentos',
        icon: 'filter_alt',
        route: '/campanhas/segmentos',
        permissions: [Permission.SEGMENT_MANAGE, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_VIEW],
      },
      { label: 'Testes A/B', icon: 'science', route: '/campanhas/testes', permissions: [Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW] },
    ],
  },
  {
    label: 'Gestão da cobrança',
    items: [
      { label: 'Filas e distribuição', icon: 'view_list', route: '/cobranca/filas', permissions: [Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN] },
      { label: 'Contestações', icon: 'gavel', route: '/cobranca/contestacoes', permissions: [Permission.DISPUTE_MANAGE] },
      { label: 'Tabulações', icon: 'label', route: '/cobranca/tabulacoes', permissions: [Permission.TABULATION_MANAGE] },
      { label: 'Parâmetros da operação', icon: 'tune', route: '/cobranca/parametros', permissions: [Permission.QUEUE_MANAGE] },
    ],
  },
  {
    label: 'Negociação',
    items: [
      { label: 'Simulador', icon: 'calculate', route: '/negociacao/simulador', permissions: [Permission.NEGOTIATION_SIMULATE] },
      {
        label: 'Propostas',
        icon: 'assignment',
        route: '/negociacao/propostas',
        permissions: [Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE],
      },
      { label: 'Aprovações', icon: 'how_to_reg', route: '/negociacao/aprovacoes', permissions: [Permission.NEGOTIATION_APPROVE] },
      { label: 'Acordos', icon: 'handshake', route: '/negociacao/acordos', permissions: [Permission.AGREEMENT_VIEW] },
      { label: 'Políticas de cobrança', icon: 'policy', route: '/negociacao/politicas', permissions: [Permission.POLICY_VIEW] },
      { label: 'Índices de correção', icon: 'trending_up', route: '/negociacao/indices', permissions: [Permission.POLICY_VIEW] },
      { label: 'Alçadas', icon: 'verified_user', route: '/negociacao/alcadas', permissions: [Permission.AUTHORITY_MANAGE] },
    ],
  },
  {
    label: 'Portal do devedor',
    items: [
      { label: 'Portal de autonegociação', icon: 'storefront', route: '/autonegociacao', permissions: [Permission.PORTAL_MANAGE], exact: true },
      { label: 'Pedidos do portal', icon: 'support_agent', route: '/autonegociacao/pedidos', permissions: [Permission.INBOX_VIEW] },
    ],
  },
  {
    label: 'Pagamentos',
    items: [
      { label: 'Pagamentos', icon: 'payments', route: '/pagamentos', permissions: [Permission.PAYMENT_VIEW], exact: true },
      { label: 'Instruções de pagamento', icon: 'qr_code_2', route: '/pagamentos/instrucoes', permissions: [Permission.PAYMENT_VIEW] },
      { label: 'Aprovação de pagamentos', icon: 'how_to_reg', route: '/pagamentos/aprovacoes', permissions: [Permission.PAYMENT_REVIEW] },
      { label: 'Sobras e realocação', icon: 'savings', route: '/pagamentos/sobras', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Conciliação', icon: 'account_balance', route: '/pagamentos/conciliacao', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Divergências', icon: 'rule', route: '/pagamentos/divergencias', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Contas Pix', icon: 'account_balance_wallet', route: '/pagamentos/contas-pix', permissions: [Permission.INTEGRATION_MANAGE] },
      {
        label: 'Parâmetros de pagamentos',
        icon: 'tune',
        route: '/pagamentos/parametros',
        permissions: [Permission.ORG_SETTINGS_MANAGE, Permission.PAYMENT_REVIEW, Permission.RECONCILIATION_MANAGE],
      },
    ],
  },
  {
    label: 'Prestação de contas',
    items: [
      { label: 'Demonstrativos', icon: 'request_page', route: '/prestacao-de-contas/demonstrativos', permissions: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE] },
      { label: 'Repasses ao credor', icon: 'account_balance', route: '/prestacao-de-contas/repasses', permissions: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER] },
      { label: 'Razão de comissão', icon: 'receipt_long', route: '/prestacao-de-contas/razao', permissions: [Permission.COMMISSION_VIEW, Permission.SETTLEMENT_VIEW] },
      { label: 'Regras de comissão', icon: 'percent', route: '/prestacao-de-contas/regras', permissions: [Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE] },
    ],
  },
  {
    label: 'Jurídico',
    items: [
      { label: 'Painel jurídico', icon: 'balance', route: '/juridico', permissions: [Permission.LEGAL_VIEW] },
      { label: 'Casos jurídicos', icon: 'folder_shared', route: '/juridico/casos', permissions: [Permission.LEGAL_VIEW] },
      { label: 'Regras de elegibilidade', icon: 'rule', route: '/juridico/regras', permissions: [Permission.LEGAL_AUTHORIZE] },
      { label: 'Escritórios parceiros', icon: 'business', route: '/juridico/escritorios', permissions: [Permission.LEGAL_AUTHORIZE] },
      { label: 'Parâmetros do jurídico', icon: 'tune', route: '/juridico/parametros', permissions: [Permission.LEGAL_AUTHORIZE] },
    ],
  },
  {
    label: 'Compliance',
    items: [
      {
        label: 'Restrições de contato',
        icon: 'phone_disabled',
        route: '/compliance/restricoes',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
      },
      {
        label: 'Política de contato',
        icon: 'schedule',
        route: '/compliance/politica-de-contato',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
      },
      { label: 'Feriados', icon: 'event', route: '/compliance/feriados', permissions: [Permission.CONTACT_RESTRICTION_MANAGE] },
      {
        label: 'Solicitações de titulares',
        icon: 'privacy_tip',
        route: '/compliance/solicitacoes-titulares',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
      },
      {
        label: 'Bases legais',
        icon: 'gavel',
        route: '/compliance/bases-legais',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
      },
      { label: 'Retenção de dados', icon: 'auto_delete', route: '/compliance/retencao', permissions: [Permission.RETENTION_MANAGE] },
    ],
  },
  {
    label: 'Organização',
    items: [
      { label: 'Credores', icon: 'apartment', route: '/organizacao/credores', permissions: [Permission.CREDITOR_VIEW] },
      { label: 'Carteiras', icon: 'folder_open', route: '/organizacao/carteiras', permissions: [Permission.PORTFOLIO_VIEW] },
      { label: 'Importação de carteiras', icon: 'upload_file', route: '/importacao', permissions: [Permission.IMPORT_VIEW] },
      {
        label: 'Configurações',
        icon: 'tune',
        route: '/organizacao/configuracoes',
        permissions: [Permission.ORG_SETTINGS_MANAGE],
      },
    ],
  },
  {
    label: 'Administração',
    items: [
      { label: 'Usuários', icon: 'group', route: '/admin/usuarios', permissions: [Permission.USER_VIEW] },
      { label: 'Perfis', icon: 'badge', route: '/admin/perfis', permissions: [Permission.ROLE_MANAGE] },
      { label: 'Equipes', icon: 'groups', route: '/admin/equipes', permissions: [Permission.TEAM_MANAGE] },
      { label: 'Unidades', icon: 'account_tree', route: '/admin/unidades', permissions: [Permission.ORG_SETTINGS_MANAGE] },
    ],
  },
  {
    label: 'Governança',
    items: [
      { label: 'Log de acesso', icon: 'login', route: '/governanca/log-de-acesso', permissions: [Permission.ACCESS_LOG_VIEW] },
      { label: 'Trilha de auditoria', icon: 'fact_check', route: '/governanca/auditoria', permissions: [Permission.AUDIT_VIEW] },
    ],
  },
  {
    label: 'Plataforma',
    items: [
      { label: 'Assessorias', icon: 'domain', route: '/plataforma/assessorias', permissions: [Permission.TENANT_MANAGE] },
    ],
  },
];
