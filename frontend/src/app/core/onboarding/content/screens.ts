import { ScreenGuide } from '../onboarding.models';

/**
 * Telas acompanhadas pelo onboarding. Ao publicar mudança relevante em uma tela: incremente
 * `version` e acrescente um item em `changes` com a mesma versão (o usuário que já conhecia a
 * tela vê o destaque das novidades uma vez).
 */
export const SCREENS: readonly ScreenGuide[] = [
  { key: 'app.home', version: 1, path: '/' },
  { key: 'admin.users', version: 1, path: '/admin/usuarios', tourId: 'admin.users' },
  { key: 'admin.user-detail', version: 1, path: '/admin/usuarios/:id' },
  { key: 'admin.roles', version: 1, path: '/admin/perfis', tourId: 'admin.roles' },
  { key: 'admin.teams', version: 1, path: '/admin/equipes', tourId: 'admin.teams' },
  { key: 'admin.units', version: 1, path: '/admin/unidades' },
  { key: 'debt.debtors', version: 1, path: '/devedores', tourId: 'debt.debtors' },
  { key: 'debt.debtor-detail', version: 1, path: '/devedores/:id', tourId: 'debt.debtor-detail' },
  { key: 'debt.titles', version: 1, path: '/titulos', tourId: 'debt.titles' },
  { key: 'debt.title-detail', version: 1, path: '/titulos/:id', tourId: 'debt.title-detail' },
  { key: 'collection.queue', version: 1, path: '/cobranca/fila', tourId: 'collection.station' },
  { key: 'collection.case', version: 1, path: '/cobranca/casos/:id', tourId: 'collection.station' },
  { key: 'collection.cases', version: 1, path: '/cobranca/casos', tourId: 'collection.cases' },
  { key: 'collection.agenda', version: 1, path: '/cobranca/agenda', tourId: 'collection.agenda' },
  { key: 'collection.queues', version: 1, path: '/cobranca/filas', tourId: 'collection.queues' },
  { key: 'collection.tabulations', version: 1, path: '/cobranca/tabulacoes' },
  { key: 'collection.settings', version: 1, path: '/cobranca/parametros' },
  { key: 'collection.disputes', version: 1, path: '/cobranca/contestacoes', tourId: 'collection.disputes' },
  { key: 'negotiation.simulator', version: 1, path: '/negociacao/simulador', tourId: 'negotiation.simulator' },
  { key: 'negotiation.policies', version: 1, path: '/negociacao/politicas', tourId: 'negotiation.policies' },
  { key: 'negotiation.policy-detail', version: 1, path: '/negociacao/politicas/:id', tourId: 'negotiation.policy-detail' },
  { key: 'negotiation.indexes', version: 1, path: '/negociacao/indices' },
  { key: 'negotiation.authority', version: 1, path: '/negociacao/alcadas', tourId: 'negotiation.authority' },
  { key: 'ingestion.batches', version: 1, path: '/importacao', tourId: 'ingestion.batches' },
  { key: 'ingestion.layouts', version: 1, path: '/importacao/layouts' },
  { key: 'ingestion.batch-detail', version: 1, path: '/importacao/:id', tourId: 'ingestion.batch-detail' },
  { key: 'compliance.restrictions', version: 1, path: '/compliance/restricoes', tourId: 'compliance.restrictions' },
  { key: 'compliance.contact-policy', version: 1, path: '/compliance/politica-de-contato', tourId: 'compliance.contact-policy' },
  { key: 'compliance.holidays', version: 1, path: '/compliance/feriados' },
  { key: 'compliance.legal-bases', version: 1, path: '/compliance/bases-legais', tourId: 'compliance.legal-bases' },
  { key: 'compliance.requests', version: 1, path: '/compliance/solicitacoes-titulares', tourId: 'compliance.requests' },
  { key: 'compliance.request-detail', version: 1, path: '/compliance/solicitacoes-titulares/:id', tourId: 'compliance.request-detail' },
  { key: 'compliance.retention', version: 1, path: '/compliance/retencao', tourId: 'compliance.retention' },
  { key: 'organization.creditors', version: 1, path: '/organizacao/credores', tourId: 'organization.creditors' },
  { key: 'organization.creditor-detail', version: 1, path: '/organizacao/credores/:id' },
  { key: 'organization.portfolios', version: 1, path: '/organizacao/carteiras', tourId: 'organization.portfolios' },
  { key: 'organization.settings', version: 1, path: '/organizacao/configuracoes' },
  { key: 'governance.access-log', version: 1, path: '/governanca/log-de-acesso', tourId: 'governance.access-log' },
  { key: 'governance.audit', version: 1, path: '/governanca/auditoria', tourId: 'governance.audit' },
  { key: 'account.profile', version: 1, path: '/conta/perfil' },
  { key: 'account.mfa', version: 1, path: '/conta/mfa' },
  { key: 'platform.tenants', version: 1, path: '/plataforma/assessorias' },
];

/** Encontra a tela de uma URL (`/admin/usuarios/123?x=1` → `admin.user-detail`). */
export function findScreen(url: string, screens: readonly ScreenGuide[] = SCREENS): ScreenGuide | undefined {
  const path = normalize(url);
  const segments = path.split('/');
  return screens.find((screen) => {
    const pattern = normalize(screen.path).split('/');
    return pattern.length === segments.length && pattern.every((part, i) => part.startsWith(':') || part === segments[i]);
  });
}

function normalize(url: string): string {
  const path = url.split(/[?#]/)[0] ?? '/';
  const trimmed = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return trimmed || '/';
}
