import { ActivatedRouteSnapshot } from '@angular/router';
import { IconName } from '../../shared/components/icon/icon-names';
import { Permission } from '../auth/permission';

export interface NavItem {
  label: string;
  /** Ícone do Bootstrap Icons (`app-icon`): só contorno (sem `-fill`) e um por item (4-design.md §3.8). */
  icon: IconName;
  route: string;
  /**
   * Quem tem a tarefa: exibido se o usuário tiver ao menos uma das permissões (vazio = todos os autenticados).
   * Menu por tarefa (D6): ver um dado em outra tela não põe a área no menu.
   */
  permissions: readonly string[];
  /**
   * Quem abre a tela, mas não pelo menu: as permissões entram na rota (`data.permissions`), e a tela é alcançada a
   * partir de `via` (outro item do menu, visível para essas permissões). Ex.: o operador vê os próprios extratos a
   * partir de "Meu desempenho".
   */
  openedFrom?: { permissions: readonly string[]; via: string };
  /**
   * Exigidas todas, além de uma de `permissions` (rota: `data.allPermissions`): a área de consulta só abre com a
   * consulta dos dados (D6), para que o menu nunca leve a uma tela que o servidor recusaria.
   */
  alsoRequires?: readonly string[];
  /** Nome do item para quem só tem `permission` entre as do item (ex.: "Meu desempenho" para o negociador). */
  ownLabel?: { permission: string; label: string };
}

export interface NavSection {
  /** Identificador estável da seção (estado recolhido nas preferências do servidor). */
  key: string;
  label: string | null;
  items: readonly NavItem[];
}

const P = Permission;
/** Desempenho dos negociadores (módulo incentive): o próprio, o da equipe ou o de todos. */
const PERFORMANCE = [P.PERFORMANCE_VIEW_OWN, P.PERFORMANCE_VIEW_TEAM, P.PERFORMANCE_VIEW_ALL];
/** Quem acompanha o desempenho de outras pessoas (o negociador abre o próprio a partir de "Meu desempenho"). */
const OTHERS_PERFORMANCE = [P.PERFORMANCE_VIEW_TEAM, P.PERFORMANCE_VIEW_ALL];
const COMMISSION_WORK = [P.COMMISSION_STATEMENT_MANAGE, P.COMMISSION_STATEMENT_APPROVE];
const FROM_MY_PERFORMANCE = { permissions: [P.PERFORMANCE_VIEW_OWN], via: '/remuneracao/desempenho' };

/** Menu principal. Cada rota repete as permissões (`permissions` + `openedFrom`) em `data.permissions` (permissionGuard). */
export const NAVIGATION: readonly NavSection[] = [
  {
    key: 'inicio',
    label: null,
    items: [
      { label: 'Início', icon: 'house', route: '/', permissions: [] },
      { label: 'Minhas pendências', icon: 'inbox', route: '/pendencias', permissions: [] },
    ],
  },
  {
    key: 'paineis',
    label: 'Painéis',
    items: [
      { label: 'Painel operacional', icon: 'grid-1x2', route: '/paineis', permissions: [P.DASHBOARD_VIEW, P.REPORT_VIEW] },
      { label: 'Recuperação', icon: 'graph-up-arrow', route: '/paineis/recuperacao', permissions: [P.REPORT_VIEW] },
      { label: 'Conversão', icon: 'funnel', route: '/paineis/conversao', permissions: [P.REPORT_VIEW] },
      {
        label: 'Produtividade',
        icon: 'speedometer2',
        route: '/paineis/produtividade',
        permissions: [...OTHERS_PERFORMANCE, ...COMMISSION_WORK],
        openedFrom: FROM_MY_PERFORMANCE,
      },
      {
        label: 'Painel de contestações',
        icon: 'flag',
        route: '/paineis/contestacoes',
        permissions: [P.DASHBOARD_VIEW, P.REPORT_VIEW, P.DISPUTE_MANAGE],
      },
      { label: 'Confiabilidade', icon: 'activity', route: '/paineis/confiabilidade', permissions: [P.DASHBOARD_VIEW, P.REPORT_VIEW] },
      { label: 'Margem por carteira', icon: 'pie-chart', route: '/paineis/margem', permissions: [P.COST_VIEW] },
    ],
  },
  {
    key: 'cobranca',
    label: 'Cobrança',
    items: [
      { label: 'Estação de cobrança', icon: 'headset', route: '/cobranca/fila', permissions: [P.CASE_WORK] },
      { label: 'Agenda de retornos', icon: 'calendar-week', route: '/cobranca/agenda', permissions: [P.CASE_WORK, P.TASK_MANAGE] },
      { label: 'Casos', icon: 'briefcase', route: '/cobranca/casos', permissions: [P.CASE_VIEW] },
      { label: 'Devedores', icon: 'person', route: '/devedores', permissions: [P.DEBTOR_VIEW] },
      { label: 'Títulos', icon: 'file-earmark-text', route: '/titulos', permissions: [P.DEBTOR_VIEW, P.CASE_VIEW] },
      {
        label: 'Correções de valor',
        icon: 'pencil-square',
        route: '/titulos/correcoes-de-valor',
        permissions: [P.TITLE_AMOUNT_ADJUST, P.TITLE_AMOUNT_APPROVE],
      },
    ],
  },
  {
    key: 'comunicacao',
    label: 'Comunicação',
    items: [
      { label: 'Caixa de entrada', icon: 'chat-left-text', route: '/conversas', permissions: [P.INBOX_VIEW] },
      { label: 'Templates', icon: 'envelope', route: '/conversas/templates', permissions: [P.TEMPLATE_MANAGE] },
      { label: 'Canais', icon: 'broadcast-pin', route: '/conversas/canais', permissions: [P.CHANNEL_MANAGE] },
    ],
  },
  {
    key: 'negociacao',
    label: 'Negociação',
    items: [
      { label: 'Simulador', icon: 'calculator', route: '/negociacao/simulador', permissions: [P.NEGOTIATION_SIMULATE] },
      {
        label: 'Propostas',
        icon: 'clipboard',
        route: '/negociacao/propostas',
        permissions: [P.AGREEMENT_VIEW, P.NEGOTIATION_PROPOSE, P.NEGOTIATION_APPROVE],
      },
      { label: 'Aprovações', icon: 'person-check', route: '/negociacao/aprovacoes', permissions: [P.NEGOTIATION_APPROVE] },
      { label: 'Acordos', icon: 'patch-check', route: '/negociacao/acordos', permissions: [P.AGREEMENT_VIEW] },
      { label: 'Políticas de cobrança', icon: 'shield-check', route: '/negociacao/politicas', permissions: [P.POLICY_AREA_VIEW], alsoRequires: [P.POLICY_VIEW] },
      { label: 'Saúde das políticas', icon: 'clipboard-data', route: '/negociacao/politicas/saude', permissions: [P.POLICY_MANAGE, P.REPORT_VIEW] },
      { label: 'Índices de correção', icon: 'graph-down-arrow', route: '/negociacao/indices', permissions: [P.POLICY_AREA_VIEW], alsoRequires: [P.POLICY_VIEW] },
      { label: 'Alçadas', icon: 'shield-lock', route: '/negociacao/alcadas', permissions: [P.AUTHORITY_MANAGE] },
    ],
  },
  {
    key: 'gestao-da-cobranca',
    label: 'Gestão da cobrança',
    items: [
      { label: 'Filas e distribuição', icon: 'view-list', route: '/cobranca/filas', permissions: [P.QUEUE_MANAGE, P.CASE_ASSIGN] },
      { label: 'Contestações', icon: 'patch-exclamation', route: '/cobranca/contestacoes', permissions: [P.DISPUTE_MANAGE, P.DISPUTE_WORK] },
      { label: 'Tabulações', icon: 'tag', route: '/cobranca/tabulacoes', permissions: [P.TABULATION_MANAGE] },
      { label: 'Parâmetros da operação', icon: 'sliders', route: '/cobranca/parametros', permissions: [P.QUEUE_MANAGE] },
    ],
  },
  {
    key: 'portal-do-devedor',
    label: 'Portal do devedor',
    items: [
      { label: 'Portal de autonegociação', icon: 'shop-window', route: '/autonegociacao', permissions: [P.PORTAL_MANAGE] },
      { label: 'Pedidos do portal', icon: 'mailbox', route: '/autonegociacao/pedidos', permissions: [P.INBOX_VIEW] },
    ],
  },
  {
    key: 'pagamentos',
    label: 'Pagamentos',
    items: [
      { label: 'Pagamentos', icon: 'cash-stack', route: '/pagamentos', permissions: [P.PAYMENT_VIEW] },
      { label: 'Instruções de pagamento', icon: 'qr-code', route: '/pagamentos/instrucoes', permissions: [P.PAYMENT_VIEW] },
      { label: 'Aprovação de pagamentos', icon: 'check2-circle', route: '/pagamentos/aprovacoes', permissions: [P.PAYMENT_REVIEW] },
      { label: 'Sobras e realocação', icon: 'piggy-bank', route: '/pagamentos/sobras', permissions: [P.RECONCILIATION_MANAGE] },
      { label: 'Conciliação', icon: 'bank', route: '/pagamentos/conciliacao', permissions: [P.RECONCILIATION_MANAGE] },
      { label: 'Divergências', icon: 'arrow-left-right', route: '/pagamentos/divergencias', permissions: [P.RECONCILIATION_MANAGE] },
      { label: 'Contas Pix', icon: 'wallet2', route: '/pagamentos/contas-pix', permissions: [P.INTEGRATION_MANAGE] },
      {
        label: 'Parâmetros de pagamentos',
        icon: 'credit-card',
        route: '/pagamentos/parametros',
        permissions: [P.ORG_SETTINGS_MANAGE, P.PAYMENT_REVIEW, P.RECONCILIATION_MANAGE],
      },
    ],
  },
  {
    key: 'remuneracao-variavel',
    label: 'Remuneração variável',
    items: [
      {
        label: 'Desempenho',
        icon: 'bar-chart',
        route: '/remuneracao/desempenho',
        permissions: PERFORMANCE,
        ownLabel: { permission: P.PERFORMANCE_VIEW_OWN, label: 'Meu desempenho' },
      },
      {
        label: 'Extratos de comissão',
        icon: 'file-earmark-check',
        route: '/remuneracao/extratos',
        permissions: [...OTHERS_PERFORMANCE, ...COMMISSION_WORK],
        openedFrom: FROM_MY_PERFORMANCE,
      },
      {
        label: 'Lançamentos de comissão',
        icon: 'journal-text',
        route: '/remuneracao/lancamentos',
        permissions: [...OTHERS_PERFORMANCE, ...COMMISSION_WORK],
        openedFrom: FROM_MY_PERFORMANCE,
      },
      { label: 'Planos de remuneração', icon: 'list-task', route: '/remuneracao/planos', permissions: [P.COMMISSION_PLAN_MANAGE] },
      { label: 'Metas por período', icon: 'record-circle', route: '/remuneracao/metas', permissions: [P.COMMISSION_PLAN_MANAGE] },
      { label: 'Períodos de apuração', icon: 'calendar-check', route: '/remuneracao/periodos', permissions: [P.COMMISSION_STATEMENT_MANAGE] },
      { label: 'Exportação para a folha', icon: 'download', route: '/remuneracao/exportacao', permissions: [P.COMMISSION_STATEMENT_APPROVE] },
    ],
  },
  {
    key: 'qualidade',
    label: 'Qualidade',
    items: [
      {
        label: 'Monitoria de qualidade',
        icon: 'clipboard-check',
        route: '/remuneracao/qualidade/avaliacoes',
        permissions: [...OTHERS_PERFORMANCE, P.QUALITY_EVALUATE],
        openedFrom: FROM_MY_PERFORMANCE,
      },
      {
        label: 'Ocorrências de conduta',
        icon: 'exclamation-octagon',
        route: '/remuneracao/qualidade/ocorrencias',
        permissions: [...OTHERS_PERFORMANCE, P.QUALITY_EVALUATE],
        openedFrom: FROM_MY_PERFORMANCE,
      },
      {
        label: 'Formulários de monitoria',
        icon: 'card-checklist',
        route: '/remuneracao/qualidade/formularios',
        permissions: [...OTHERS_PERFORMANCE, P.QUALITY_EVALUATE],
      },
    ],
  },
  {
    key: 'campanhas',
    label: 'Campanhas',
    items: [
      {
        label: 'Campanhas',
        icon: 'megaphone',
        route: '/campanhas',
        permissions: [P.CAMPAIGN_VIEW, P.CAMPAIGN_MANAGE, P.CAMPAIGN_EXECUTE],
      },
      {
        label: 'Segmentos',
        icon: 'tags',
        route: '/campanhas/segmentos',
        permissions: [P.SEGMENT_MANAGE, P.CAMPAIGN_MANAGE, P.CAMPAIGN_VIEW],
      },
      { label: 'Testes A/B', icon: 'eyedropper', route: '/campanhas/testes', permissions: [P.EXPERIMENT_MANAGE, P.CAMPAIGN_VIEW] },
    ],
  },
  {
    key: 'score-de-propensao',
    label: 'Score de propensão',
    items: [
      { label: 'Modelos do score', icon: 'diagram-3', route: '/score/modelos', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
      { label: 'Execuções do score', icon: 'arrow-repeat', route: '/score/execucoes', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
      { label: 'Monitoramento do score', icon: 'bar-chart-steps', route: '/score/monitoramento', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
      { label: 'Inteligência de contato', icon: 'grid-3x3', route: '/score/contato', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
      { label: 'Decisões e variáveis', icon: 'card-list', route: '/score/transparencia', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
      { label: 'Parâmetros do score', icon: 'toggle-on', route: '/score/parametros', permissions: [P.SCORING_AREA_VIEW], alsoRequires: [P.SCORING_VIEW] },
    ],
  },
  {
    key: 'assistente-de-ia',
    label: 'Assistente de IA',
    items: [
      { label: 'Políticas do assistente', icon: 'robot', route: '/assistente/politicas', permissions: [P.ASSISTANT_MANAGE, P.ASSISTANT_REVIEW] },
      { label: 'Revisão de turnos', icon: 'chat-square-text', route: '/assistente/revisao', permissions: [P.ASSISTANT_REVIEW] },
      { label: 'Qualidade do assistente', icon: 'stars', route: '/assistente/qualidade', permissions: [P.ASSISTANT_REVIEW] },
    ],
  },
  {
    key: 'telefonia',
    label: 'Telefonia',
    items: [
      { label: 'Campanhas de discagem', icon: 'telephone-forward', route: '/telefonia/campanhas', permissions: [P.TELEPHONY_SUPERVISE] },
      { label: 'Chamadas e gravações', icon: 'telephone', route: '/telefonia/chamadas', permissions: [P.TELEPHONY_SUPERVISE] },
      { label: 'Operadores e ramais', icon: 'person-badge', route: '/telefonia/operadores', permissions: [P.TELEPHONY_SUPERVISE] },
      { label: 'Plataformas de telefonia', icon: 'plug', route: '/telefonia/plataformas', permissions: [P.TELEPHONY_MANAGE] },
    ],
  },
  {
    key: 'prestacao-de-contas',
    label: 'Prestação de contas',
    items: [
      { label: 'Posição do credor', icon: 'graph-up', route: '/posicao-credor', permissions: [P.SETTLEMENT_VIEW, P.PUBLIC_API_USAGE_VIEW] },
      {
        label: 'Demonstrativos',
        icon: 'file-earmark-ruled',
        route: '/prestacao-de-contas/demonstrativos',
        permissions: [P.SETTLEMENT_VIEW, P.SETTLEMENT_CLOSE, P.SETTLEMENT_APPROVE],
      },
      { label: 'Repasses ao credor', icon: 'send', route: '/prestacao-de-contas/repasses', permissions: [P.SETTLEMENT_VIEW, P.SETTLEMENT_TRANSFER] },
      { label: 'Razão de comissão', icon: 'receipt', route: '/prestacao-de-contas/razao', permissions: [P.COMMISSION_VIEW, P.SETTLEMENT_VIEW] },
      { label: 'Regras de comissão', icon: 'percent', route: '/prestacao-de-contas/regras', permissions: [P.COMMISSION_VIEW, P.COMMISSION_MANAGE] },
    ],
  },
  {
    key: 'juridico',
    label: 'Jurídico',
    items: [
      { label: 'Painel jurídico', icon: 'bank2', route: '/juridico', permissions: [P.LEGAL_VIEW] },
      { label: 'Casos jurídicos', icon: 'folder-symlink', route: '/juridico/casos', permissions: [P.LEGAL_VIEW] },
      { label: 'Regras de elegibilidade', icon: 'list-check', route: '/juridico/regras', permissions: [P.LEGAL_AUTHORIZE] },
      { label: 'Escritórios parceiros', icon: 'building', route: '/juridico/escritorios', permissions: [P.LEGAL_AUTHORIZE] },
      { label: 'Parâmetros do jurídico', icon: 'hourglass-split', route: '/juridico/parametros', permissions: [P.LEGAL_AUTHORIZE] },
    ],
  },
  {
    key: 'compliance',
    label: 'Compliance',
    items: [
      { label: 'Restrições de contato', icon: 'telephone-x', route: '/compliance/restricoes', permissions: [P.CONTACT_RESTRICTION_MANAGE] },
      { label: 'Política de contato', icon: 'clock', route: '/compliance/politica-de-contato', permissions: [P.CONTACT_RESTRICTION_MANAGE] },
      { label: 'Feriados', icon: 'calendar-event', route: '/compliance/feriados', permissions: [P.CONTACT_RESTRICTION_MANAGE] },
      {
        label: 'Solicitações de titulares',
        icon: 'shield-exclamation',
        route: '/compliance/solicitacoes-titulares',
        permissions: [P.DATA_SUBJECT_REQUEST_MANAGE],
      },
      { label: 'Bases legais', icon: 'shield', route: '/compliance/bases-legais', permissions: [P.DATA_SUBJECT_REQUEST_MANAGE] },
      { label: 'Operadores e suboperadores', icon: 'share', route: '/compliance/operadores', permissions: [P.DATA_SUBJECT_REQUEST_MANAGE] },
      { label: 'Retenção de dados', icon: 'trash3', route: '/compliance/retencao', permissions: [P.RETENTION_MANAGE] },
      { label: 'Reclamações', icon: 'exclamation-triangle', route: '/compliance/reclamacoes', permissions: [P.COMPLAINT_REGISTER, P.COMPLAINT_MANAGE] },
      {
        label: 'Vulnerabilidade',
        icon: 'person-hearts',
        route: '/compliance/vulnerabilidade',
        permissions: [P.VULNERABILITY_REGISTER, P.VULNERABILITY_MANAGE],
      },
    ],
  },
  {
    key: 'organizacao',
    label: 'Organização',
    items: [
      { label: 'Credores', icon: 'buildings', route: '/organizacao/credores', permissions: [P.ORGANIZATION_AREA_VIEW], alsoRequires: [P.CREDITOR_VIEW] },
      { label: 'Carteiras', icon: 'folder2-open', route: '/organizacao/carteiras', permissions: [P.ORGANIZATION_AREA_VIEW], alsoRequires: [P.PORTFOLIO_VIEW] },
      { label: 'Importação de carteiras', icon: 'file-earmark-arrow-up', route: '/importacao', permissions: [P.IMPORT_VIEW] },
      { label: 'Configurações', icon: 'gear', route: '/organizacao/configuracoes', permissions: [P.ORG_SETTINGS_MANAGE] },
      { label: 'Custos operacionais', icon: 'receipt-cutoff', route: '/organizacao/custos', permissions: [P.COST_MANAGE, P.COST_RATE_MANAGE] },
      { label: 'Contatos recebidos', icon: 'bookmark-plus', route: '/devedores/contatos-recebidos', permissions: [P.DEBTOR_MANAGE] },
    ],
  },
  {
    key: 'administracao',
    label: 'Administração',
    items: [
      { label: 'Usuários', icon: 'people', route: '/admin/usuarios', permissions: [P.USER_VIEW] },
      { label: 'Perfis', icon: 'person-vcard', route: '/admin/perfis', permissions: [P.ROLE_MANAGE] },
      { label: 'Equipes', icon: 'layout-three-columns', route: '/admin/equipes', permissions: [P.TEAM_MANAGE] },
      { label: 'Unidades', icon: 'window-stack', route: '/admin/unidades', permissions: [P.ORG_SETTINGS_MANAGE] },
    ],
  },
  {
    key: 'api-publica',
    label: 'API pública',
    items: [
      { label: 'Tokens de integração', icon: 'key', route: '/integracoes/tokens', permissions: [P.PUBLIC_API_TOKEN_VIEW] },
      { label: 'Consumo da API', icon: 'lightning-charge', route: '/integracoes/consumo', permissions: [P.PUBLIC_API_USAGE_VIEW] },
      { label: 'Extratos da API', icon: 'table', route: '/integracoes/extratos', permissions: [P.PUBLIC_API_USAGE_VIEW] },
      { label: 'Cobrança da plataforma', icon: 'coin', route: '/integracoes/cobranca-plataforma', permissions: [P.TENANT_BILLING_VIEW] },
    ],
  },
  {
    key: 'governanca',
    label: 'Governança',
    items: [
      { label: 'Log de acesso', icon: 'box-arrow-in-right', route: '/governanca/log-de-acesso', permissions: [P.ACCESS_LOG_VIEW] },
      { label: 'Trilha de auditoria', icon: 'clock-history', route: '/governanca/auditoria', permissions: [P.AUDIT_VIEW] },
    ],
  },
  {
    key: 'plataforma',
    label: 'Plataforma',
    items: [
      { label: 'Assessorias', icon: 'globe', route: '/plataforma/assessorias', permissions: [P.TENANT_MANAGE] },
      { label: 'Faturamento da plataforma', icon: 'cash-coin', route: '/plataforma/faturamento', permissions: [P.PLATFORM_BILLING_VIEW] },
    ],
  },
];

/** Permissões que abrem a tela do item (basta uma): as da tarefa (menu) e as de quem chega por outra tela. */
export function routePermissionsOf(item: NavItem): readonly string[] {
  return [...item.permissions, ...(item.openedFrom?.permissions ?? [])];
}

/**
 * O usuário consegue abrir a tela do item (pelo menu ou por outra tela): uma das permissões da rota e todas as
 * exigidas. Para links de novidades e do manual; o menu usa `navigationFor` (só a tarefa).
 */
export function canOpen(item: NavItem, has: (permission: string) => boolean): boolean {
  const any = routePermissionsOf(item);
  return (any.length === 0 || any.some(has)) && (item.alsoRequires ?? []).every(has);
}

/** Permissões exigidas todas pela rota do item (`data.allPermissions`). */
export function routeRequiredPermissionsOf(item: NavItem): readonly string[] {
  return item.alsoRequires ?? [];
}

/**
 * Menu do usuário: só os itens da tarefa dele (nada exibido para depois negar) e só as seções com algum item; o
 * nome pessoal ("Meu desempenho") para quem só tem a permissão própria.
 */
export function navigationFor(has: (permission: string) => boolean): NavSection[] {
  return NAVIGATION.map((section) => ({
    ...section,
    items: section.items
      .filter((item) => (item.permissions.length === 0 || item.permissions.some(has)) && (item.alsoRequires ?? []).every(has))
      .map((item) => {
        const own = item.ownLabel;
        return own && has(own.permission) && !item.permissions.some((p) => p !== own.permission && has(p))
          ? { ...item, label: own.label }
          : item;
      }),
  })).filter((section) => section.items.length > 0);
}

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

/**
 * Item do menu de uma tela cujo endereço fica fora dele (`data.menuRoute` da rota mais interna que o declara): menu e
 * trilha marcam esse item. Ex.: o caso aberto por quem opera (`/cobranca/casos/:id`, endereço único do caso) é a
 * estação de cobrança; para quem só consulta, o mesmo endereço continua sob "Casos".
 */
export function menuRouteOf(root: ActivatedRouteSnapshot | null): string | null {
  let found: string | null = null;
  for (let r: ActivatedRouteSnapshot | null = root; r; r = r.firstChild) {
    const route = r.routeConfig?.data?.['menuRoute'];
    if (typeof route === 'string') {
      found = route;
    }
  }
  return found;
}
