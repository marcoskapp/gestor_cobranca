import { Permission } from '../../../core/auth/permission';
import { IconName } from '../icon/icon-names';

/**
 * Destinos do cartão "Relacionados" (revisão de produto 11/2026 — J3, T6). Cada tipo aponta para uma tela e repete
 * as permissões do `permissionGuard` da rota (`related-targets.spec.ts` confere contra a árvore real de rotas): o
 * vínculo só aparece para quem abre a tela — como o menu, tela sem permissão fica oculta (nunca "acesso negado").
 */
export interface RelatedTarget {
  /** Rótulo do grupo no cartão (plural). */
  group: string;
  icon: IconName;
  /** Caminho da rota; nas fichas, o id do registro vai depois (`/titulos/:id`). */
  path: string;
  /** Ficha de um registro (precisa de `id`) ou lista filtrada por `queryParams`. */
  detail: boolean;
  /** Uma delas basta (`data.permissions` da rota). */
  permissions: readonly string[];
  /** Todas obrigatórias, além de uma de `permissions` (`data.allPermissions` da rota). */
  allPermissions?: readonly string[];
}

const P = Permission;

export const RELATED_TARGETS = {
  debtor: { group: 'Devedor', icon: 'person', path: '/devedores', detail: true, permissions: [P.DEBTOR_VIEW] },
  case: { group: 'Casos de cobrança', icon: 'briefcase', path: '/cobranca/casos', detail: true, permissions: [P.CASE_VIEW] },
  caseList: { group: 'Casos de cobrança', icon: 'briefcase', path: '/cobranca/casos', detail: false, permissions: [P.CASE_VIEW] },
  title: { group: 'Títulos', icon: 'file-earmark-text', path: '/titulos', detail: true, permissions: [P.DEBTOR_VIEW, P.CASE_VIEW] },
  titleList: { group: 'Títulos', icon: 'file-earmark-text', path: '/titulos', detail: false, permissions: [P.DEBTOR_VIEW, P.CASE_VIEW] },
  proposal: {
    group: 'Propostas',
    icon: 'clipboard',
    path: '/negociacao/propostas',
    detail: true,
    permissions: [P.AGREEMENT_VIEW, P.NEGOTIATION_PROPOSE, P.NEGOTIATION_APPROVE],
  },
  agreement: { group: 'Acordos', icon: 'patch-check', path: '/negociacao/acordos', detail: true, permissions: [P.AGREEMENT_VIEW] },
  agreementList: { group: 'Acordos', icon: 'patch-check', path: '/negociacao/acordos', detail: false, permissions: [P.AGREEMENT_VIEW] },
  payment: { group: 'Pagamentos', icon: 'cash-stack', path: '/pagamentos', detail: true, permissions: [P.PAYMENT_VIEW] },
  paymentList: { group: 'Pagamentos', icon: 'cash-stack', path: '/pagamentos', detail: false, permissions: [P.PAYMENT_VIEW] },
  dispute: {
    group: 'Contestações',
    icon: 'patch-exclamation',
    path: '/cobranca/contestacoes',
    detail: true,
    permissions: [P.DISPUTE_MANAGE, P.DISPUTE_WORK],
  },
  legalMatter: { group: 'Casos jurídicos', icon: 'folder-symlink', path: '/juridico/casos', detail: true, permissions: [P.LEGAL_VIEW] },
  conversation: { group: 'Conversas', icon: 'chat-left-text', path: '/conversas', detail: true, permissions: [P.INBOX_VIEW] },
  importBatch: { group: 'Importação', icon: 'file-earmark-arrow-up', path: '/importacao', detail: true, permissions: [P.IMPORT_VIEW] },
} as const satisfies Record<string, RelatedTarget>;

export type RelatedKind = keyof typeof RELATED_TARGETS;

/** Vínculo de uma ficha. A tela informa só os que existem; o cartão filtra pela permissão do destino. */
export interface RelatedRecord {
  kind: RelatedKind;
  /** Id do registro (fichas); omitido nas listas filtradas. */
  id?: string;
  label: string;
  /** Texto curto ao lado (situação, data, credor). */
  hint?: string;
  /** Filtros da lista de destino (só ids e filtros de cadastro — nunca dado pessoal; ux.md §16). */
  queryParams?: Record<string, string>;
}

/** O perfil abre o destino? (mesma regra do `permissionGuard`: uma de `permissions` e todas de `allPermissions`). */
export function canOpen(target: RelatedTarget, has: (permission: string) => boolean): boolean {
  return target.permissions.some(has) && (target.allPermissions ?? []).every(has);
}

/** Link do vínculo: ficha (`path/id`) ou lista. */
export function relatedLink(record: RelatedRecord): string[] {
  const target: RelatedTarget = RELATED_TARGETS[record.kind];
  return target.detail && record.id ? [target.path, record.id] : [target.path];
}
