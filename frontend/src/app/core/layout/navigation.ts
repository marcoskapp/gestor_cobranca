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
