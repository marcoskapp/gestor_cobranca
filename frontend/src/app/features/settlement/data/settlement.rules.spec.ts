import { adjustment, rules, statement, totals } from '../../../../testing/settlement-fixtures';
import { latestByFormat } from '../statements/statement-dialogs';
import {
  canDecideAdjustment,
  criteriaText,
  cycleIndex,
  groupedHash,
  needsSecondApproval,
  receiptsEffect,
  rulesProblems,
  sha256Hex,
  specificity,
  statementActions,
} from './settlement.rules';

const ctx = (o: Partial<Parameters<typeof statementActions>[1]> = {}) => ({
  userId: 'u-1',
  canClose: true,
  canApprove: true,
  canTransfer: true,
  today: '2026-10-24',
  adjustments: [],
  exportsCount: 0,
  ...o,
});

describe('regras da prestação de contas', () => {
  it('valida as regras de comissão como o domínio (percentual, código, faixa, critério, repetição)', () => {
    expect(rulesProblems(rules())).toEqual([]);
    expect(rulesProblems(rules({ defaultRate: 101 })).join()).toContain('Percentual padrão');
    expect(rulesProblems(rules({ defaultRate: 10.12345 })).join()).toContain('4 casas');
    expect(rulesProblems(rules({ fixedFeePerAgreement: 1.234 })).join()).toContain('Valor fixo');
    expect(rulesProblems(rules({ honorarium: { ownership: 'AGENCY', agencySharePercent: null } })).join()).toContain('Fração dos honorários');
    expect(rulesProblems(rules({ honorarium: { ownership: 'CREDITOR', agencySharePercent: null } }))).toEqual([]);
    const base = rules().rateRules[0];
    expect(rulesProblems(rules({ rateRules: [{ ...base, code: 'PADRAO' }] })).join()).toContain('diferente de PADRAO');
    expect(rulesProblems(rules({ rateRules: [{ ...base, code: 'parc' }] })).join()).toContain('letras maiúsculas');
    expect(rulesProblems(rules({ rateRules: [{ ...base, minDaysPastDue: 180, maxDaysPastDue: 90 }] })).join()).toContain('faixa de atraso');
    expect(rulesProblems(rules({ rateRules: [{ ...base, minDaysPastDue: null, paymentType: null }] })).join()).toContain('sem critério');
    expect(rulesProblems(rules({ rateRules: [base, { ...base }] })).join()).toContain('repetido');
  });

  it('especificidade e critérios em texto', () => {
    const r = rules().rateRules[0];
    expect(specificity(r)).toBe(2);
    expect(criteriaText(r)).toBe('a partir de 91 dias de atraso · parcelado');
    expect(criteriaText({ ...r, minDaysPastDue: 31, maxDaysPastDue: 60, paymentType: null, receivedBy: 'CREDITOR', renegotiation: true })).toBe('31 a 60 dias de atraso · pago direto ao credor · renegociação');
  });

  it('segregação na aprovação: quem calculou ou pediu ajuste aplicado não aprova; ajuste pendente bloqueia', () => {
    expect(statementActions(statement(), ctx()).approve).toBe(true);
    const own = statementActions(statement({ calculatedBy: 'u-1' }), ctx());
    expect(own.approve).toBe(false);
    expect(own.approveBlocked).toContain('calculou');
    const requested = statementActions(statement(), ctx({ adjustments: [adjustment({ requestedBy: 'u-1' })] }));
    expect(requested.approveBlocked).toContain('ajuste');
    // ajuste recusado não conta para a segregação
    expect(statementActions(statement(), ctx({ adjustments: [adjustment({ requestedBy: 'u-1', status: 'REJECTED' })] })).approve).toBe(true);
    expect(statementActions(statement(), ctx({ adjustments: [adjustment({ status: 'PENDING_SECOND_APPROVAL' })] })).approveBlocked).toContain('segunda aprovação');
    // sem permissão de aprovar, nada a explicar
    expect(statementActions(statement({ calculatedBy: 'u-1' }), ctx({ canApprove: false })).approveBlocked).toBeNull();
  });

  it('ciclo: calcular só depois do fim do período; envio exige arquivo; repasse com saldo', () => {
    const draft = statement({ status: 'DRAFT', totals: null, periodEnd: '2026-10-31' });
    expect(statementActions(draft, ctx()).calculate).toBe(false);
    expect(statementActions(draft, ctx()).periodOpen).toBe(true);
    expect(statementActions(statement({ status: 'DRAFT', totals: null }), ctx()).calculate).toBe(true);
    expect(statementActions(statement({ status: 'CALCULATED' }), ctx()).review).toBe(true);
    expect(statementActions(statement({ status: 'CALCULATED' }), ctx()).adjust).toBe(true);
    const approved = statement({ status: 'APPROVED' });
    expect(statementActions(approved, ctx()).send).toBe(false);
    expect(statementActions(approved, ctx({ exportsCount: 1 })).send).toBe(true);
    expect(statementActions(approved, ctx()).transfer).toBe(true);
    expect(statementActions(statement({ status: 'APPROVED', totals: totals({ outstanding: 0 }) }), ctx()).transfer).toBe(false);
    expect(statementActions(statement({ status: 'SENT' }), ctx()).contest).toBe(true);
    expect(statementActions(statement({ status: 'CONTESTED' }), ctx()).respond).toBe(true);
    expect(statementActions(approved, ctx()).cancel).toBe(false);
  });

  it('ajuste acima de R$ 1.000,00 exige segunda pessoa; quem pediu não decide', () => {
    expect(needsSecondApproval(1000)).toBe(false);
    expect(needsSecondApproval(-1000.01)).toBe(true);
    const pending = adjustment({ status: 'PENDING_SECOND_APPROVAL', requestedBy: 'u-1' });
    expect(canDecideAdjustment(pending, 'u-1', true)).toBe(false);
    expect(canDecideAdjustment(pending, 'u-2', true)).toBe(true);
    expect(canDecideAdjustment(pending, 'u-2', false)).toBe(false);
  });

  it('conferência do líquido: efeitos − custas + ajustes = líquido', () => {
    const t = totals();
    expect(receiptsEffect(t) - t.expenses + t.adjustments).toBeCloseTo(t.netTransfer, 2);
    expect(receiptsEffect(t)).toBe(855);
  });

  it('etapa atual e impressão digital', async () => {
    expect(cycleIndex('DRAFT')).toBe(0);
    expect(cycleIndex('APPROVED')).toBe(3);
    expect(cycleIndex('CONTESTED')).toBe(5);
    expect(groupedHash('a'.repeat(16))).toBe('aaaaaaaa aaaaaaaa');
    const hash = await sha256Hex(new TextEncoder().encode('abc').buffer as ArrayBuffer);
    expect(hash).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });

  it('envio sugere o último arquivo de cada formato', () => {
    const x = (id: string, format: 'CSV' | 'PDF', createdAt: string) => ({ id, statementId: 'st-1', format, fileName: id, fileSize: 1, sha256: '', rows: 1, generatedBy: 'u-1', createdAt });
    expect(latestByFormat([x('a', 'CSV', '2026-10-01'), x('b', 'CSV', '2026-10-02'), x('c', 'PDF', '2026-10-01')]).map((e) => e.id).sort()).toEqual(['b', 'c']);
  });
});
