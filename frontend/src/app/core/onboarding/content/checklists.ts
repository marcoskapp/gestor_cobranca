import { Permission } from '../../auth/permission';
import { ChecklistItem, ChecklistProfile } from '../onboarding.models';

const MFA: ChecklistItem = {
  id: 'account.mfa',
  label: 'Ativar a verificação em duas etapas',
  description: 'Protege a sua conta mesmo que a senha seja descoberta.',
  route: '/conta/mfa',
  permissions: [],
  completion: { kind: 'mfa' },
};

const WELCOME: ChecklistItem = {
  id: 'app.welcome-tour',
  label: 'Conhecer a navegação',
  description: 'Tour de 1 minuto pelo menu, busca rápida e ajuda.',
  tourId: 'app.welcome',
  permissions: [],
  completion: { kind: 'tour', tourId: 'app.welcome' },
};

/**
 * Primeiros passos por perfil de uso. O usuário vê o primeiro perfil compatível com as suas
 * permissões. Itens cuja rota ainda não existe no menu do usuário ficam ocultos e passam a
 * aparecer quando o módulo correspondente é publicado (ex.: fila de trabalho do negociador).
 */
export const CHECKLISTS: readonly ChecklistProfile[] = [
  {
    id: 'admin',
    title: 'Prepare a assessoria para operar',
    permissions: [Permission.ORG_SETTINGS_MANAGE, Permission.USER_MANAGE],
    items: [
      MFA,
      {
        id: 'admin.settings',
        label: 'Revisar as configurações da assessoria',
        description: 'Dados cadastrais e janela de contato permitida com devedores.',
        route: '/organizacao/configuracoes',
        permissions: [Permission.ORG_SETTINGS_MANAGE],
        completion: { kind: 'visit', screenKey: 'organization.settings' },
      },
      {
        id: 'admin.creditor',
        label: 'Cadastrar o primeiro credor',
        description: 'Empresa contratante, com contrato e regras de comissão.',
        route: '/organizacao/credores',
        permissions: [Permission.CREDITOR_MANAGE],
        completion: { kind: 'manual' },
      },
      {
        id: 'admin.portfolio',
        label: 'Criar uma carteira',
        description: 'Lote de títulos recebido do credor, com vigência.',
        route: '/organizacao/carteiras',
        permissions: [Permission.PORTFOLIO_MANAGE],
        completion: { kind: 'manual' },
      },
      {
        id: 'admin.users',
        label: 'Cadastrar os usuários',
        description: 'Uma conta por pessoa, com o perfil adequado à função.',
        route: '/admin/usuarios',
        permissions: [Permission.USER_MANAGE],
        completion: { kind: 'manual' },
      },
      {
        id: 'admin.teams',
        label: 'Organizar as equipes',
        description: 'Supervisor e operadores de cada equipe de cobrança.',
        route: '/admin/equipes',
        permissions: [Permission.TEAM_MANAGE],
        completion: { kind: 'manual' },
      },
    ],
  },
  {
    id: 'negotiator',
    title: 'Comece a negociar',
    permissions: [Permission.CASE_WORK, Permission.NEGOTIATION_PROPOSE],
    items: [
      MFA,
      WELCOME,
      {
        id: 'negotiator.queue',
        label: 'Abrir a sua fila de trabalho',
        description: 'Casos priorizados para você atender agora.',
        route: '/cobranca/fila',
        permissions: [Permission.CASE_WORK],
        completion: { kind: 'visit', screenKey: 'collection.queue' },
      },
      {
        id: 'negotiator.debtor',
        label: 'Consultar a ficha de um devedor',
        description: 'Títulos, contatos e histórico em um só lugar.',
        route: '/devedores',
        permissions: [Permission.DEBTOR_VIEW],
        completion: { kind: 'visit', screenKey: 'debt.debtors' },
      },
      {
        id: 'negotiator.interaction',
        label: 'Registrar a primeira interação',
        description: 'Resultado do contato, promessa ou agendamento de retorno.',
        route: '/cobranca/fila',
        permissions: [Permission.INTERACTION_REGISTER],
        completion: { kind: 'manual' },
      },
    ],
  },
  {
    id: 'compliance',
    title: 'Prepare o compliance da operação',
    permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE, Permission.CONTACT_RESTRICTION_MANAGE, Permission.RETENTION_MANAGE],
    items: [
      MFA,
      {
        id: 'compliance.policy',
        label: 'Publicar a política de contato',
        description: 'Janela, dias e limites de tentativas por canal.',
        route: '/compliance/politica-de-contato',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
        completion: { kind: 'manual' },
      },
      {
        id: 'compliance.holidays',
        label: 'Cadastrar os feriados',
        description: 'Datas em que canais com bloqueio em feriado não fazem contato.',
        route: '/compliance/feriados',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
        completion: { kind: 'visit', screenKey: 'compliance.holidays' },
      },
      {
        id: 'compliance.legal-bases',
        label: 'Registrar as bases legais dos credores',
        description: 'Fundamento do tratamento e prazo de retenção de cada credor.',
        route: '/compliance/bases-legais',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
        completion: { kind: 'manual' },
      },
      {
        id: 'compliance.requests',
        label: 'Conhecer a fila de solicitações de titulares',
        description: 'Pedidos LGPD com prazo legal de resposta.',
        route: '/compliance/solicitacoes-titulares',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
        completion: { kind: 'visit', screenKey: 'compliance.requests' },
      },
      {
        id: 'compliance.retention',
        label: 'Simular a política de retenção',
        description: 'Veja quem seria anonimizado antes de habilitar a execução.',
        route: '/compliance/retencao',
        permissions: [Permission.RETENTION_MANAGE],
        completion: { kind: 'manual' },
      },
    ],
  },
  {
    id: 'auditor',
    title: 'Acompanhe a governança',
    permissions: [Permission.ACCESS_LOG_VIEW, Permission.AUDIT_VIEW],
    items: [
      MFA,
      {
        id: 'auditor.access-log',
        label: 'Consultar o log de acesso',
        description: 'Logins, sessões e acessos negados.',
        route: '/governanca/log-de-acesso',
        permissions: [Permission.ACCESS_LOG_VIEW],
        completion: { kind: 'visit', screenKey: 'governance.access-log' },
      },
      {
        id: 'auditor.audit',
        label: 'Verificar a trilha de auditoria',
        description: 'Alterações com autor, motivo e integridade por hash.',
        route: '/governanca/auditoria',
        permissions: [Permission.AUDIT_VIEW],
        completion: { kind: 'visit', screenKey: 'governance.audit' },
      },
    ],
  },
  {
    id: 'default',
    title: 'Primeiros passos',
    permissions: [],
    items: [MFA, WELCOME],
  },
];
