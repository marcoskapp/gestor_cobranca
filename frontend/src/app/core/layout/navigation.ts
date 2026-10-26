import { IconName } from '../../shared/components/icon/icon-names';
import { Permission } from '../auth/permission';

export interface NavItem {
  label: string;
  /** Ícone do Bootstrap Icons (`app-icon`). */
  icon: IconName;
  route: string;
  /** Exibido se o usuário tiver ao menos uma das permissões (vazio = todos os autenticados). */
  permissions: readonly string[];
}

export interface NavSection {
  label: string | null;
  items: readonly NavItem[];
}

/** Remuneração variável (módulo incentive): mesmas listas de `INCENTIVE_PERMISSIONS`. */
const PERFORMANCE = [Permission.PERFORMANCE_VIEW_OWN, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL];
const COMMISSION_STATEMENTS = [...PERFORMANCE, Permission.COMMISSION_STATEMENT_MANAGE, Permission.COMMISSION_STATEMENT_APPROVE];

/** Menu principal. Cada rota repete as permissões em `data.permissions` (permissionGuard). */
export const NAVIGATION: readonly NavSection[] = [
  {
    label: null,
    items: [
      { label: 'Início', icon: 'house', route: '/', permissions: [] },
      { label: 'Minhas pendências', icon: 'inbox', route: '/pendencias', permissions: [] },
    ],
  },
  {
    label: 'Painéis',
    items: [
      { label: 'Painel operacional', icon: 'grid-1x2', route: '/paineis', permissions: [Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW] },
      { label: 'Recuperação', icon: 'cash-stack', route: '/paineis/recuperacao', permissions: [Permission.REPORT_VIEW] },
      { label: 'Conversão', icon: 'funnel', route: '/paineis/conversao', permissions: [Permission.REPORT_VIEW] },
      {
        label: 'Produtividade',
        icon: 'people-fill',
        route: '/paineis/produtividade',
        permissions: [Permission.REPORT_VIEW, Permission.PERFORMANCE_VIEW_OWN, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL],
      },
      { label: 'Confiabilidade', icon: 'activity', route: '/paineis/confiabilidade', permissions: [Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW] },
    ],
  },
  {
    label: 'Cobrança',
    items: [
      { label: 'Estação de cobrança', icon: 'headset', route: '/cobranca/fila', permissions: [Permission.CASE_WORK] },
      {
        label: 'Agenda de retornos',
        icon: 'calendar-week',
        route: '/cobranca/agenda',
        permissions: [Permission.CASE_WORK, Permission.TASK_MANAGE],
      },
      { label: 'Casos', icon: 'briefcase', route: '/cobranca/casos', permissions: [Permission.CASE_VIEW] },
      { label: 'Devedores', icon: 'person-bounding-box', route: '/devedores', permissions: [Permission.DEBTOR_VIEW] },
      { label: 'Títulos', icon: 'file-earmark-text', route: '/titulos', permissions: [Permission.DEBTOR_VIEW, Permission.CASE_VIEW] },
    ],
  },
  {
    label: 'Comunicação',
    items: [
      { label: 'Caixa de entrada', icon: 'chat-left-text', route: '/conversas', permissions: [Permission.INBOX_VIEW] },
      { label: 'Templates', icon: 'file-earmark-text', route: '/conversas/templates', permissions: [Permission.TEMPLATE_MANAGE] },
      { label: 'Canais', icon: 'broadcast-pin', route: '/conversas/canais', permissions: [Permission.CHANNEL_MANAGE] },
    ],
  },
  {
    label: 'Assistente de IA',
    items: [
      { label: 'Políticas do assistente', icon: 'robot', route: '/assistente/politicas', permissions: [Permission.ASSISTANT_MANAGE, Permission.ASSISTANT_REVIEW] },
      { label: 'Revisão de turnos', icon: 'chat-square-text', route: '/assistente/revisao', permissions: [Permission.ASSISTANT_REVIEW] },
      { label: 'Qualidade do assistente', icon: 'graph-up', route: '/assistente/qualidade', permissions: [Permission.ASSISTANT_REVIEW] },
    ],
  },
  {
    label: 'Telefonia',
    items: [
      { label: 'Campanhas de discagem', icon: 'telephone-forward', route: '/telefonia/campanhas', permissions: [Permission.TELEPHONY_SUPERVISE] },
      { label: 'Chamadas e gravações', icon: 'telephone', route: '/telefonia/chamadas', permissions: [Permission.TELEPHONY_SUPERVISE] },
      { label: 'Operadores e ramais', icon: 'headset', route: '/telefonia/operadores', permissions: [Permission.TELEPHONY_SUPERVISE] },
    ],
  },
  {
    label: 'Campanhas',
    items: [
      {
        label: 'Campanhas',
        icon: 'megaphone',
        route: '/campanhas',
        permissions: [Permission.CAMPAIGN_VIEW, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_EXECUTE],
      },
      {
        label: 'Segmentos',
        icon: 'funnel',
        route: '/campanhas/segmentos',
        permissions: [Permission.SEGMENT_MANAGE, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_VIEW],
      },
      { label: 'Testes A/B', icon: 'eyedropper', route: '/campanhas/testes', permissions: [Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW] },
    ],
  },
  {
    label: 'Gestão da cobrança',
    items: [
      { label: 'Filas e distribuição', icon: 'view-list', route: '/cobranca/filas', permissions: [Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN] },
      { label: 'Contestações', icon: 'hammer', route: '/cobranca/contestacoes', permissions: [Permission.DISPUTE_MANAGE] },
      { label: 'Tabulações', icon: 'tag', route: '/cobranca/tabulacoes', permissions: [Permission.TABULATION_MANAGE] },
      { label: 'Parâmetros da operação', icon: 'sliders', route: '/cobranca/parametros', permissions: [Permission.QUEUE_MANAGE] },
    ],
  },
  {
    label: 'Score de propensão',
    items: [
      { label: 'Modelos do score', icon: 'diagram-3', route: '/score/modelos', permissions: [Permission.SCORING_VIEW] },
      { label: 'Execuções do score', icon: 'arrow-repeat', route: '/score/execucoes', permissions: [Permission.SCORING_VIEW] },
      { label: 'Monitoramento do score', icon: 'activity', route: '/score/monitoramento', permissions: [Permission.SCORING_VIEW] },
      { label: 'Decisões e variáveis', icon: 'card-checklist', route: '/score/transparencia', permissions: [Permission.SCORING_VIEW] },
      { label: 'Parâmetros do score', icon: 'sliders', route: '/score/parametros', permissions: [Permission.SCORING_VIEW] },
    ],
  },
  {
    label: 'Negociação',
    items: [
      { label: 'Simulador', icon: 'calculator', route: '/negociacao/simulador', permissions: [Permission.NEGOTIATION_SIMULATE] },
      {
        label: 'Propostas',
        icon: 'clipboard',
        route: '/negociacao/propostas',
        permissions: [Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE],
      },
      { label: 'Aprovações', icon: 'person-check', route: '/negociacao/aprovacoes', permissions: [Permission.NEGOTIATION_APPROVE] },
      { label: 'Acordos', icon: 'person-hearts', route: '/negociacao/acordos', permissions: [Permission.AGREEMENT_VIEW] },
      { label: 'Políticas de cobrança', icon: 'shield-check', route: '/negociacao/politicas', permissions: [Permission.POLICY_VIEW] },
      { label: 'Índices de correção', icon: 'graph-up-arrow', route: '/negociacao/indices', permissions: [Permission.POLICY_VIEW] },
      { label: 'Alçadas', icon: 'shield-check', route: '/negociacao/alcadas', permissions: [Permission.AUTHORITY_MANAGE] },
    ],
  },
  {
    label: 'Portal do devedor',
    items: [
      { label: 'Portal de autonegociação', icon: 'shop-window', route: '/autonegociacao', permissions: [Permission.PORTAL_MANAGE] },
      { label: 'Pedidos do portal', icon: 'headset', route: '/autonegociacao/pedidos', permissions: [Permission.INBOX_VIEW] },
    ],
  },
  {
    label: 'Pagamentos',
    items: [
      { label: 'Pagamentos', icon: 'cash-stack', route: '/pagamentos', permissions: [Permission.PAYMENT_VIEW] },
      { label: 'Instruções de pagamento', icon: 'qr-code', route: '/pagamentos/instrucoes', permissions: [Permission.PAYMENT_VIEW] },
      { label: 'Aprovação de pagamentos', icon: 'person-check', route: '/pagamentos/aprovacoes', permissions: [Permission.PAYMENT_REVIEW] },
      { label: 'Sobras e realocação', icon: 'piggy-bank', route: '/pagamentos/sobras', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Conciliação', icon: 'bank', route: '/pagamentos/conciliacao', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Divergências', icon: 'list-check', route: '/pagamentos/divergencias', permissions: [Permission.RECONCILIATION_MANAGE] },
      { label: 'Contas Pix', icon: 'wallet2', route: '/pagamentos/contas-pix', permissions: [Permission.INTEGRATION_MANAGE] },
      {
        label: 'Parâmetros de pagamentos',
        icon: 'sliders',
        route: '/pagamentos/parametros',
        permissions: [Permission.ORG_SETTINGS_MANAGE, Permission.PAYMENT_REVIEW, Permission.RECONCILIATION_MANAGE],
      },
    ],
  },
  {
    label: 'Prestação de contas',
    items: [
      { label: 'Demonstrativos', icon: 'file-earmark-ruled', route: '/prestacao-de-contas/demonstrativos', permissions: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE] },
      { label: 'Repasses ao credor', icon: 'bank', route: '/prestacao-de-contas/repasses', permissions: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER] },
      { label: 'Razão de comissão', icon: 'receipt', route: '/prestacao-de-contas/razao', permissions: [Permission.COMMISSION_VIEW, Permission.SETTLEMENT_VIEW] },
      { label: 'Regras de comissão', icon: 'percent', route: '/prestacao-de-contas/regras', permissions: [Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE] },
    ],
  },
  {
    label: 'Remuneração variável',
    items: [
      { label: 'Desempenho', icon: 'speedometer2', route: '/remuneracao/desempenho', permissions: PERFORMANCE },
      { label: 'Extratos de comissão', icon: 'receipt', route: '/remuneracao/extratos', permissions: COMMISSION_STATEMENTS },
      { label: 'Lançamentos de comissão', icon: 'journal-text', route: '/remuneracao/lancamentos', permissions: COMMISSION_STATEMENTS },
      { label: 'Planos de remuneração', icon: 'sliders', route: '/remuneracao/planos', permissions: [Permission.COMMISSION_PLAN_MANAGE] },
      { label: 'Metas por período', icon: 'flag', route: '/remuneracao/metas', permissions: [Permission.COMMISSION_PLAN_MANAGE] },
      { label: 'Períodos de apuração', icon: 'calendar-check', route: '/remuneracao/periodos', permissions: [Permission.COMMISSION_STATEMENT_MANAGE] },
      { label: 'Exportação para a folha', icon: 'download', route: '/remuneracao/exportacao', permissions: [Permission.COMMISSION_STATEMENT_APPROVE] },
      { label: 'Monitoria de qualidade', icon: 'clipboard-check', route: '/remuneracao/qualidade/avaliacoes', permissions: [...PERFORMANCE, Permission.QUALITY_EVALUATE] },
      { label: 'Ocorrências de conduta', icon: 'shield-exclamation', route: '/remuneracao/qualidade/ocorrencias', permissions: [...PERFORMANCE, Permission.QUALITY_EVALUATE] },
      { label: 'Formulários de monitoria', icon: 'card-checklist', route: '/remuneracao/qualidade/formularios', permissions: [...PERFORMANCE, Permission.QUALITY_EVALUATE] },
    ],
  },
  {
    label: 'Jurídico',
    items: [
      { label: 'Painel jurídico', icon: 'bank2', route: '/juridico', permissions: [Permission.LEGAL_VIEW] },
      { label: 'Casos jurídicos', icon: 'folder-symlink', route: '/juridico/casos', permissions: [Permission.LEGAL_VIEW] },
      { label: 'Regras de elegibilidade', icon: 'list-check', route: '/juridico/regras', permissions: [Permission.LEGAL_AUTHORIZE] },
      { label: 'Escritórios parceiros', icon: 'building', route: '/juridico/escritorios', permissions: [Permission.LEGAL_AUTHORIZE] },
      { label: 'Parâmetros do jurídico', icon: 'sliders', route: '/juridico/parametros', permissions: [Permission.LEGAL_AUTHORIZE] },
    ],
  },
  {
    label: 'Compliance',
    items: [
      {
        label: 'Restrições de contato',
        icon: 'telephone-x',
        route: '/compliance/restricoes',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
      },
      {
        label: 'Política de contato',
        icon: 'clock',
        route: '/compliance/politica-de-contato',
        permissions: [Permission.CONTACT_RESTRICTION_MANAGE],
      },
      { label: 'Feriados', icon: 'calendar-event', route: '/compliance/feriados', permissions: [Permission.CONTACT_RESTRICTION_MANAGE] },
      {
        label: 'Solicitações de titulares',
        icon: 'shield-exclamation',
        route: '/compliance/solicitacoes-titulares',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
      },
      {
        label: 'Bases legais',
        icon: 'hammer',
        route: '/compliance/bases-legais',
        permissions: [Permission.DATA_SUBJECT_REQUEST_MANAGE],
      },
      { label: 'Retenção de dados', icon: 'trash3', route: '/compliance/retencao', permissions: [Permission.RETENTION_MANAGE] },
    ],
  },
  {
    label: 'Organização',
    items: [
      { label: 'Credores', icon: 'buildings', route: '/organizacao/credores', permissions: [Permission.CREDITOR_VIEW] },
      { label: 'Carteiras', icon: 'folder2-open', route: '/organizacao/carteiras', permissions: [Permission.PORTFOLIO_VIEW] },
      { label: 'Importação de carteiras', icon: 'file-earmark-arrow-up', route: '/importacao', permissions: [Permission.IMPORT_VIEW] },
      {
        label: 'Configurações',
        icon: 'sliders',
        route: '/organizacao/configuracoes',
        permissions: [Permission.ORG_SETTINGS_MANAGE],
      },
    ],
  },
  {
    label: 'Administração',
    items: [
      { label: 'Usuários', icon: 'people', route: '/admin/usuarios', permissions: [Permission.USER_VIEW] },
      { label: 'Perfis', icon: 'person-vcard', route: '/admin/perfis', permissions: [Permission.ROLE_MANAGE] },
      { label: 'Equipes', icon: 'people-fill', route: '/admin/equipes', permissions: [Permission.TEAM_MANAGE] },
      { label: 'Unidades', icon: 'diagram-3', route: '/admin/unidades', permissions: [Permission.ORG_SETTINGS_MANAGE] },
    ],
  },
  {
    label: 'Governança',
    items: [
      { label: 'Log de acesso', icon: 'box-arrow-in-right', route: '/governanca/log-de-acesso', permissions: [Permission.ACCESS_LOG_VIEW] },
      { label: 'Trilha de auditoria', icon: 'card-checklist', route: '/governanca/auditoria', permissions: [Permission.AUDIT_VIEW] },
    ],
  },
  {
    label: 'Plataforma',
    items: [
      { label: 'Assessorias', icon: 'building', route: '/plataforma/assessorias', permissions: [Permission.TENANT_MANAGE] },
    ],
  },
];

/**
 * Rota do menu que corresponde ao endereço atual: a mais específica (maior prefixo por segmento).
 * Assim `/conversas/templates` marca só "Templates", e não também "Caixa de entrada" (`/conversas`),
 * enquanto telas internas sem item próprio (`/conversas/123`) continuam marcando o item pai.
 * O início (`/`) só corresponde a ele mesmo.
 */
export function activeNavRoute(url: string, routes: readonly string[]): string | null {
  const path = url.split(/[?#]/, 1)[0].replace(/\/+$/, '') || '/';
  let best: string | null = null;
  for (const route of routes) {
    const matches = route === '/' ? path === '/' : path === route || path.startsWith(route + '/');
    if (matches && (best === null || route.length > best.length)) {
      best = route;
    }
  }
  return best;
}
