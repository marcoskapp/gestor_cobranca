import { CadenceDefinition, CadenceStep, ExperimentInput, SegmentCriteria } from './campaign.models';
import {
  activeFilterKeys,
  allocation,
  breakdown,
  cadenceProblems,
  checkTemplate,
  clearFilter,
  cloneDefinition,
  criteriaProblems,
  criteriaSummary,
  differenceScale,
  differenceValue,
  durationText,
  emptyCriteria,
  enrollmentReason,
  estimateScale,
  experimentProblems,
  inProgress,
  metricValue,
  newExperiment,
  newStep,
  normalizeCriteria,
  pickTemplate,
  position,
  share,
  splitDuration,
  suggestCode,
  toServerDefinition,
  toServerExperiment,
  windowText,
} from './campaign.rules';

const criteria = (overrides: Partial<SegmentCriteria> = {}): SegmentCriteria => ({ ...emptyCriteria(), ...overrides });

function step(overrides: Partial<CadenceStep> = {}): CadenceStep {
  return {
    code: 'D0',
    name: null,
    waitMinutes: 0,
    window: null,
    condition: null,
    action: { type: 'SEND_MESSAGE', channel: 'WHATSAPP', templateCode: 'COBRANCA', variables: { nome: 'DEBTOR_FIRST_NAME' }, taskType: null, taskDueInMinutes: null, taskNote: null, teamId: null, offer: null },
    onReply: null,
    onReplyStepCode: null,
    ...overrides,
  };
}

const definition = (steps: CadenceStep[], extra: Partial<CadenceDefinition> = {}): CadenceDefinition => ({ defaultOnReply: 'STOP', defaultOnReplyStepCode: null, replyTeamId: null, steps, ...extra });

describe('regras de segmento', () => {
  it('normaliza listas vazias e números inválidos como "sem filtro"', () => {
    const c = normalizeCriteria({ caseStatuses: [], portfolioIds: ['p-2', 'p-1'], minDaysOverdue: Number.NaN, eligibleOnly: null });
    expect(c.caseStatuses).toBeNull();
    expect(c.portfolioIds).toEqual(['p-1', 'p-2']);
    expect(c.minDaysOverdue).toBeNull();
    expect(c.eligibleOnly).toBe(true);
  });

  it('valida faixas como SegmentCriteria.validate()', () => {
    expect(criteriaProblems(criteria({ minDaysOverdue: 30, maxDaysOverdue: 90 }))).toEqual([]);
    expect(criteriaProblems(criteria({ minDaysOverdue: 90, maxDaysOverdue: 30 }))).toEqual(['Atraso: o mínimo é maior que o máximo.']);
    expect(criteriaProblems(criteria({ minScore: 1200 }))[0]).toContain('Score');
    expect(criteriaProblems(criteria({ minBalance: -1 }))).toEqual(['Saldo: valores não podem ser negativos.']);
    expect(criteriaProblems(criteria({ minBalance: 500, maxBalance: 100 }))).toEqual(['Saldo: o mínimo é maior que o máximo.']);
    expect(criteriaProblems(criteria({ noInteractionDays: 1.5 }))[0]).toContain('inteiros');
  });

  it('filtro de parcela do acordo vencendo: faixa, exigência de acordo e resumo', () => {
    expect(criteriaProblems(criteria({ minDaysToInstallment: 1, maxDaysToInstallment: 3 }))).toEqual([]);
    expect(criteriaProblems(criteria({ minDaysToInstallment: 5, maxDaysToInstallment: 3 }))).toEqual(['Parcela do acordo: o mínimo é maior que o máximo.']);
    expect(criteriaProblems(criteria({ maxDaysToInstallment: 400 }))[0]).toContain('Parcela do acordo');
    expect(criteriaProblems(criteria({ activeAgreement: false, maxDaysToInstallment: 3 }))).toEqual(['Parcela do acordo: o filtro exige acordo em vigor.']);
    expect(criteriaSummary(criteria({ maxDaysToInstallment: 3 }))).toContain('Parcela do acordo vence em até 3 dias');
    expect(criteriaSummary(criteria({ minDaysToInstallment: 3, maxDaysToInstallment: 3 }))).toContain('Parcela do acordo vence em 3 dias');
    expect(criteriaSummary(criteria({ minDaysToInstallment: 0, maxDaysToInstallment: 0 }))).toContain('Parcela do acordo vence hoje');
    expect(criteriaSummary(criteria({ minDaysToInstallment: 1, maxDaysToInstallment: 5 }))).toContain('Parcela do acordo vence entre 1 e 5 dias');
    expect(activeFilterKeys(criteria({ maxDaysToInstallment: 3 }))).toEqual(['installmentDue']);
  });

  it('resume os filtros combinados em linguagem clara', () => {
    const summary = criteriaSummary(
      criteria({ minDaysOverdue: 30, maxDaysOverdue: 90, caseStatuses: ['OPEN'], contactableChannel: 'WHATSAPP', maxInteractions: 0, activeAgreement: false, minBalance: 100 }),
    );
    expect(summary).toEqual([
      'Somente casos elegíveis',
      'Situação: Em aberto',
      'Atraso de 30 dias a 90 dias',
      'Saldo a partir de R$\u00a0100,00',
      'Contatável por WhatsApp',
      'Nunca trabalhados',
      'Sem acordo em vigor',
    ]);
  });

  it('identifica e limpa os filtros ativos', () => {
    const c = criteria({ minScore: 100, pendingPromise: true });
    expect(activeFilterKeys(c)).toEqual(['pendingPromise', 'score']);
    clearFilter(c, 'score');
    expect(c.minScore).toBeNull();
    expect(activeFilterKeys(c)).toEqual(['pendingPromise']);
  });

  it('sugere código a partir do nome', () => {
    expect(suggestCode('Atraso 30 a 90 — Varejo')).toBe('ATRASO_30_A_90_VAREJO');
    expect(suggestCode('90 dias')).toBe('DIAS');
  });
});

describe('regras da régua', () => {
  it('régua válida não tem problemas', () => {
    expect(cadenceProblems(definition([step(), step({ code: 'LIGAR', waitMinutes: 60, action: { ...step().action, type: 'CREATE_TASK', channel: null, templateCode: null, variables: {}, taskType: 'CALLBACK', taskDueInMinutes: 30 } })]))).toEqual([]);
  });

  it('aponta código repetido, espera inválida, janela invertida e destino de resposta ausente', () => {
    const problems = cadenceProblems(
      definition(
        [
          step(),
          step({ waitMinutes: -1 }),
          step({ code: 'X1', window: { start: '18:00', end: '09:00', days: [] }, onReply: 'GOTO_STEP', onReplyStepCode: 'NAO_EXISTE' }),
        ],
        { defaultOnReply: 'GOTO_STEP', defaultOnReplyStepCode: null },
      ),
    );
    expect(problems).toContain('Etapa 2 (D0): código repetido.');
    expect(problems).toContain('Etapa 2 (D0): espera entre 0 e 60 dias.');
    expect(problems.some((p) => p.startsWith('Etapa 3 (X1): na janela'))).toBe(true);
    expect(problems).toContain('Etapa 3 (X1): escolha a etapa de destino da resposta.');
    expect(problems).toContain('Resposta padrão: escolha a etapa de destino.');
  });

  it('variáveis de oferta só em etapa de oferta e texto fixo com 1 a 200 caracteres', () => {
    const problems = cadenceProblems(definition([step({ action: { ...step().action, variables: { link: 'PAYMENT_LINK', obs: 'LITERAL:' } } })]));
    expect(problems).toContain('Etapa 1 (D0): a variável link usa dado de oferta, disponível só em etapa de oferta.');
    expect(problems).toContain('Etapa 1 (D0): texto fixo da variável obs com 1 a 200 caracteres.');
    expect(cadenceProblems(definition([]))).toEqual(['Inclua ao menos uma etapa.']);
  });

  it('parcela do acordo e link do portal em etapa de mensagem; validade do link só junto com o link', () => {
    const ok = cadenceProblems(
      definition([step({ action: { ...step().action, variables: { valor: 'NEXT_INSTALLMENT_AMOUNT', vencimento: 'NEXT_INSTALLMENT_DUE_DATE', link: 'PORTAL_LINK', horas: 'PORTAL_LINK_HOURS' } } })]),
    );
    expect(ok).toEqual([]);
    const problems = cadenceProblems(definition([step({ action: { ...step().action, variables: { horas: 'PORTAL_LINK_HOURS' } } })]));
    expect(problems).toContain('Etapa 1 (D0): a validade do link do portal só vai junto com o link do portal na mesma mensagem.');
  });

  it('confere a etapa com o template publicado (o do credor prevalece)', () => {
    const templates = [
      { code: 'COBRANCA', channel: 'WHATSAPP', creditorId: null, name: 'Assessoria', templateVersion: 1, variables: [{ name: 'nome', type: 'FIRST_NAME', required: true }] },
      { code: 'COBRANCA', channel: 'WHATSAPP', creditorId: 'c-1', name: 'Credor', templateVersion: 3, variables: [{ name: 'nome', type: 'FIRST_NAME', required: true }, { name: 'valor', type: 'MONEY', required: true }] },
    ];
    const t = pickTemplate(templates, 'COBRANCA', 'WHATSAPP', 'c-1');
    expect(t?.name).toBe('Credor');
    expect(checkTemplate(step(), t).errors).toEqual(['Etapa D0: variáveis obrigatórias sem origem: valor.']);
    const typed = checkTemplate(step({ action: { ...step().action, variables: { nome: 'DEBTOR_FIRST_NAME', valor: 'DAYS_OVERDUE', extra: 'CREDITOR_NAME' } } }), t);
    expect(typed.errors).toEqual(['Etapa D0: variáveis fora do template: extra.']);
    expect(typed.warnings[0]).toContain('valor espera valor em reais');
    expect(checkTemplate(step(), pickTemplate(templates, 'COBRANCA', 'EMAIL', 'c-1')).errors[0]).toContain('não está publicado');
  });

  it('envia horários completos e limpa campos sem uso pelo tipo de ação', () => {
    const d = definition([
      step({ window: { start: '09:00', end: '12:00', days: ['MONDAY'] }, onReplyStepCode: 'X' }),
      step({ code: 'FILA', window: { start: '00:00', end: '00:00', days: [] }, action: { ...step().action, type: 'MOVE_TO_QUEUE', teamId: 'tm-1' } }),
    ]);
    const body = toServerDefinition(d);
    expect(body.steps[0].window).toEqual({ start: '09:00:00', end: '12:00:00', days: ['MONDAY'] });
    expect(body.steps[0].onReplyStepCode).toBeNull();
    expect(body.steps[1].window).toBeNull();
    expect(body.steps[1].action).toEqual(expect.objectContaining({ type: 'MOVE_TO_QUEUE', channel: null, templateCode: null, variables: {}, teamId: 'tm-1' }));
  });

  it('cópia editável preenche padrões omitidos pelo servidor', () => {
    const copy = cloneDefinition({ defaultOnReply: null, defaultOnReplyStepCode: null, replyTeamId: null, steps: [{ ...step(), window: { start: '09:00:00', end: '12:00:00', days: null } }] });
    expect(copy.defaultOnReply).toBe('STOP');
    expect(copy.steps[0].window).toEqual({ start: '09:00', end: '12:00', days: [] });
    expect(newStep(copy.steps)).toEqual(expect.objectContaining({ code: 'ETAPA_2', waitMinutes: 1440 }));
  });

  it('descreve espera e janela', () => {
    expect(durationText(0)).toBe('imediatamente');
    expect(durationText(1500)).toBe('1 dia e 1 h');
    expect(splitDuration(2880)).toEqual({ value: 2, unit: 'DAYS' });
    expect(splitDuration(90)).toEqual({ value: 90, unit: 'MINUTES' });
    expect(windowText(null)).toBe('qualquer horário');
    expect(windowText({ start: '09:00', end: '12:00', days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] })).toBe('Seg a Sex, 09:00 às 12:00');
  });
});

describe('regras de execução', () => {
  it('explica o estado da inscrição', () => {
    const base = { status: 'ACTIVE', nextStepCode: 'D3', nextRunAt: '2026-10-25T12:00:00Z' } as never;
    expect(enrollmentReason(base, () => '25/10')).toBe('Próxima: D3 em 25/10');
    expect(enrollmentReason({ status: 'PAUSED', pauseReason: 'DISPUTE_OPENED', resumeAt: null } as never, String)).toBe('Contestação aberta');
    expect(enrollmentReason({ status: 'FAILED', lastErrorCode: 'message.template_not_found' } as never, String)).toBe('Template não encontrado ou retirado');
    expect(enrollmentReason({ status: 'FAILED', lastErrorCode: 'xpto.erro' } as never, String)).toBe('Erro xpto.erro');
  });

  it('ordena motivos e calcula partes com denominador', () => {
    expect(breakdown({ NO_CONTACT: 3, CONTACT_DENIED: 9, X: 0 }, { NO_CONTACT: 'Sem contato', CONTACT_DENIED: 'Bloqueada' })).toEqual([
      { key: 'CONTACT_DENIED', label: 'Bloqueada', count: 9, ratio: 1 },
      { key: 'NO_CONTACT', label: 'Sem contato', count: 3, ratio: 3 / 9 },
    ]);
    expect(share(12, 340)).toBe('12 de 340 (3,5%)');
    expect(share(0, 0)).toBe('0 de 0');
    expect(inProgress({ ACTIVE: 10, PAUSED: 2, FAILED: 1, HOLDOUT: 3, ENDED: 50 })).toBe(16);
  });
});

describe('regras de teste A/B', () => {
  const input = (overrides: Partial<ExperimentInput> = {}): ExperimentInput => ({ ...newExperiment(), name: 'Horário', ...overrides });

  it('reparte controle e variantes pelos pesos', () => {
    expect(allocation(10, [{ code: 'A', name: 'A', weight: 1, overrides: [] }, { code: 'B', name: 'B', weight: 2, overrides: [] }]).map((a) => [a.arm, a.percent])).toEqual([
      ['CONTROL', 10],
      ['A', 30],
      ['B', 60],
    ]);
    expect(allocation(0, [{ code: 'A', name: 'A', weight: 1, overrides: [] }]).map((a) => a.arm)).toEqual(['A']);
  });

  it('valida como o servidor (controle, códigos, alterações contra a régua publicada)', () => {
    const steps = [step(), step({ code: 'LIGAR', action: { ...step().action, type: 'CREATE_TASK' } })];
    expect(experimentProblems(input(), steps)).toEqual([]);
    expect(experimentProblems(input({ controlShare: 60 }), steps)).toContain('Grupo de controle entre 0% e 50%.');
    expect(experimentProblems(input({ controlShare: 0, variants: [{ code: 'A', name: 'A', weight: 1, overrides: [] }] }), steps)).toContain(
      'Sem grupo de controle, inclua ao menos duas variantes para comparar.',
    );
    const bad = input({
      variants: [
        { code: 'CONTROL', name: 'X', weight: 1, overrides: [] },
        { code: 'B', name: 'B', weight: 1, overrides: [{ stepCode: 'LIGAR', channel: 'SMS', templateCode: null, window: null, offer: null }, { stepCode: 'NOVA', channel: null, templateCode: 'T2', window: null, offer: null }] },
      ],
    });
    const problems = experimentProblems(bad, steps);
    expect(problems).toContain('Variante 1 (CONTROL): código com letras maiúsculas, dígitos e _ (CONTROL é reservado).');
    expect(problems).toContain('Variante 2 (B): template e canal só em etapas de mensagem (LIGAR).');
    expect(problems).toContain('Variante 2 (B): a etapa NOVA não existe na régua publicada.');
  });

  it('envia horários completos nas alterações de janela', () => {
    const body = toServerExperiment(input({ variants: [{ code: 'B', name: ' B ', weight: null, overrides: [{ stepCode: 'D0', channel: null, templateCode: ' ', window: { start: '09:00', end: '11:00', days: [] }, offer: null }] }] }));
    expect(body.variants[0]).toEqual({ code: 'B', name: 'B', weight: 1, overrides: [{ stepCode: 'D0', channel: null, templateCode: null, window: { start: '09:00:00', end: '11:00:00', days: [] }, offer: null }] });
  });

  it('formata estimativas, diferenças com sinal explícito e escalas', () => {
    expect(metricValue('PAYMENT', 0.124)).toBe('12,4%');
    expect(metricValue('RECOVERED_PER_DEBTOR', 35.1)).toBe('R$\u00a035,10');
    expect(differenceValue('PAYMENT', 0.021)).toBe('+2,1 p.p.');
    expect(differenceValue('PAYMENT', -0.004)).toBe('−0,4 p.p.');
    expect(differenceValue('RECOVERED_PER_DEBTOR', -3.2)).toBe('−R$\u00a03,20');
    expect(estimateScale('PAYMENT', [0.15, 0.12])).toEqual({ min: 0, max: 0.2 });
    const d = differenceScale([-0.01, 0.04]);
    expect(d).toEqual({ min: -0.05, max: 0.05 });
    expect(position(0, d)).toBe(50);
  });
});
