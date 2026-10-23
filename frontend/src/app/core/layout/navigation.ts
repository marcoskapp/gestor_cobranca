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
      { label: 'Políticas de cobrança', icon: 'policy', route: '/negociacao/politicas', permissions: [Permission.POLICY_VIEW] },
      { label: 'Índices de correção', icon: 'trending_up', route: '/negociacao/indices', permissions: [Permission.POLICY_VIEW] },
      { label: 'Alçadas', icon: 'verified_user', route: '/negociacao/alcadas', permissions: [Permission.AUTHORITY_MANAGE] },
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
