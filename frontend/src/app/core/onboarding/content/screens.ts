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
  {
    key: 'collection.queue',
    version: 5,
    path: '/cobranca/fila',
    tourId: 'collection.station',
    changes: [
      { version: 2, items: ['"Posso contatar agora?": decisão do sistema por canal, com o próximo horário permitido.', 'Histórico mostra o nome de quem registrou cada interação.'] },
      { version: 3, items: ['Aba Negociação (Alt + 6): propostas em andamento e acordos do caso, com o cronograma.'] },
      { version: 4, items: ['Gerar Pix da próxima parcela do acordo (aba Negociação) ou de um título avulso (aba Títulos).'] },
      { version: 5, items: ['Ligar (Alt + L) com a decisão do porteiro por telefone antes de discar.', 'Barra de presença na telefonia (disponível, pausa com motivo) e tabulação obrigatória ao fim da chamada.'] },
    ],
  },
  {
    key: 'collection.case',
    version: 5,
    path: '/cobranca/casos/:id',
    tourId: 'collection.station',
    changes: [
      { version: 2, items: ['"Posso contatar agora?": decisão do sistema por canal, com o próximo horário permitido.', 'Histórico mostra o nome de quem registrou cada interação.'] },
      { version: 3, items: ['Aba Negociação (Alt + 6): propostas em andamento e acordos do caso, com o cronograma.'] },
      { version: 4, items: ['Gerar Pix da próxima parcela do acordo (aba Negociação) ou de um título avulso (aba Títulos).'] },
      { version: 5, items: ['Ligar (Alt + L) com a decisão do porteiro por telefone antes de discar.', 'Barra de presença na telefonia (disponível, pausa com motivo) e tabulação obrigatória ao fim da chamada.'] },
    ],
  },
  { key: 'collection.cases', version: 1, path: '/cobranca/casos', tourId: 'collection.cases' },
  { key: 'collection.agenda', version: 1, path: '/cobranca/agenda', tourId: 'collection.agenda' },
  { key: 'collection.queues', version: 1, path: '/cobranca/filas', tourId: 'collection.queues' },
  { key: 'collection.tabulations', version: 1, path: '/cobranca/tabulacoes' },
  { key: 'collection.settings', version: 1, path: '/cobranca/parametros' },
  {
    key: 'collection.disputes',
    version: 2,
    path: '/cobranca/contestacoes',
    tourId: 'collection.disputes',
    changes: [{ version: 2, items: ['Detalhe da contestação com número, vencimento e valor dos títulos e quem anexou cada evidência.'] }],
  },
  {
    key: 'negotiation.simulator',
    version: 2,
    path: '/negociacao/simulador',
    tourId: 'negotiation.simulator',
    changes: [{ version: 2, items: ['Condições validadas viram proposta: "Propor" dentro da alçada ou "Pedir aprovação" acima dela.', 'Renegociação de acordo a partir da ficha do acordo.'] }],
  },
  { key: 'negotiation.proposals', version: 1, path: '/negociacao/propostas', tourId: 'negotiation.proposals' },
  { key: 'negotiation.proposal-detail', version: 1, path: '/negociacao/propostas/:id', tourId: 'negotiation.proposal-detail' },
  { key: 'negotiation.approvals', version: 1, path: '/negociacao/aprovacoes', tourId: 'negotiation.approvals' },
  { key: 'negotiation.agreements', version: 1, path: '/negociacao/acordos' },
  {
    key: 'negotiation.agreement-detail',
    version: 2,
    path: '/negociacao/acordos/:id',
    tourId: 'negotiation.agreement-detail',
    changes: [{ version: 2, items: ['Gerar Pix por parcela (QR Code e copia e cola) e informar pagamento recebido fora do Pix.'] }],
  },
  { key: 'payment.list', version: 1, path: '/pagamentos', tourId: 'payment.list' },
  { key: 'payment.instructions', version: 1, path: '/pagamentos/instrucoes', tourId: 'payment.instructions' },
  { key: 'payment.instruction', version: 1, path: '/pagamentos/instrucoes/:id', tourId: 'payment.instruction' },
  {
    key: 'payment.review',
    version: 2,
    path: '/pagamentos/aprovacoes',
    tourId: 'payment.review',
    changes: [{ version: 2, items: ['Coluna "Destino solicitado": para onde o valor vai (parcelas do acordo ou títulos) antes de aprovar.'] }],
  },
  { key: 'payment.reallocation', version: 1, path: '/pagamentos/sobras', tourId: 'payment.reallocation' },
  { key: 'payment.reconciliation', version: 1, path: '/pagamentos/conciliacao', tourId: 'payment.reconciliation' },
  { key: 'payment.statement', version: 1, path: '/pagamentos/conciliacao/:id', tourId: 'payment.statement' },
  { key: 'payment.divergences', version: 1, path: '/pagamentos/divergencias', tourId: 'payment.divergences' },
  { key: 'payment.pix-accounts', version: 1, path: '/pagamentos/contas-pix', tourId: 'payment.pix-accounts' },
  { key: 'payment.settings', version: 1, path: '/pagamentos/parametros' },
  {
    key: 'payment.detail',
    version: 2,
    path: '/pagamentos/:id',
    tourId: 'payment.detail',
    changes: [{ version: 2, items: ['Pagamento aguardando aprovação mostra o destino solicitado por quem informou.'] }],
  },
  { key: 'settlement.rules', version: 1, path: '/prestacao-de-contas/regras', tourId: 'settlement.rules' },
  { key: 'settlement.rule-set', version: 1, path: '/prestacao-de-contas/regras/:id', tourId: 'settlement.rule-set' },
  { key: 'settlement.ledger', version: 1, path: '/prestacao-de-contas/razao', tourId: 'settlement.ledger' },
  { key: 'settlement.statements', version: 1, path: '/prestacao-de-contas/demonstrativos', tourId: 'settlement.statements' },
  { key: 'settlement.statement', version: 1, path: '/prestacao-de-contas/demonstrativos/:id', tourId: 'settlement.statement' },
  { key: 'settlement.transfers', version: 1, path: '/prestacao-de-contas/repasses', tourId: 'settlement.transfers' },
  { key: 'telephony.campaigns', version: 1, path: '/telefonia/campanhas', tourId: 'telephony.campaigns' },
  { key: 'telephony.campaign', version: 1, path: '/telefonia/campanhas/:id', tourId: 'telephony.campaign' },
  { key: 'telephony.calls', version: 1, path: '/telefonia/chamadas', tourId: 'telephony.calls' },
  { key: 'telephony.agents', version: 1, path: '/telefonia/operadores' },
  { key: 'assistant.policies', version: 1, path: '/assistente/politicas', tourId: 'assistant.policies' },
  { key: 'assistant.policy', version: 1, path: '/assistente/politicas/:id', tourId: 'assistant.policy' },
  { key: 'assistant.reviews', version: 1, path: '/assistente/revisao', tourId: 'assistant.reviews' },
  { key: 'assistant.turn', version: 1, path: '/assistente/turnos/:id', tourId: 'assistant.turn' },
  { key: 'assistant.quality', version: 1, path: '/assistente/qualidade', tourId: 'assistant.quality' },
  { key: 'communication.inbox', version: 1, path: '/conversas', tourId: 'communication.inbox' },
  { key: 'communication.templates', version: 1, path: '/conversas/templates', tourId: 'communication.templates' },
  { key: 'communication.channels', version: 1, path: '/conversas/canais' },
  { key: 'communication.conversation', version: 1, path: '/conversas/:id', tourId: 'communication.inbox' },
  { key: 'campaign.list', version: 1, path: '/campanhas', tourId: 'campaign.list' },
  { key: 'campaign.segments', version: 1, path: '/campanhas/segmentos' },
  { key: 'campaign.segment-new', version: 1, path: '/campanhas/segmentos/novo', tourId: 'campaign.segment' },
  { key: 'campaign.segment', version: 1, path: '/campanhas/segmentos/:id', tourId: 'campaign.segment' },
  { key: 'campaign.experiments', version: 1, path: '/campanhas/testes' },
  { key: 'campaign.detail', version: 1, path: '/campanhas/:id', tourId: 'campaign.detail' },
  { key: 'campaign.cadence', version: 1, path: '/campanhas/:id/regua', tourId: 'campaign.cadence' },
  { key: 'campaign.execution', version: 1, path: '/campanhas/:id/execucao', tourId: 'campaign.execution' },
  { key: 'campaign.experiment', version: 1, path: '/campanhas/:id/testes', tourId: 'campaign.experiment' },
  { key: 'legal.dashboard', version: 1, path: '/juridico', tourId: 'legal.dashboard' },
  { key: 'legal.matters', version: 1, path: '/juridico/casos' },
  { key: 'legal.matter-detail', version: 1, path: '/juridico/casos/:id', tourId: 'legal.matter-detail' },
  { key: 'legal.rules', version: 1, path: '/juridico/regras' },
  { key: 'legal.firms', version: 1, path: '/juridico/escritorios' },
  { key: 'legal.settings', version: 1, path: '/juridico/parametros' },
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
