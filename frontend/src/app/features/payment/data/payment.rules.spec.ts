import { allocation, payment, pixAccount, statementImport } from '../../../../testing/payment-fixtures';
import { parseFingerprints } from '../pix-accounts/pix-account-dialog';
import {
  certificateProblem,
  certificateState,
  evidenceProblem,
  expirationText,
  importIndicators,
  isFingerprint,
  paidAtInstant,
  parseMoney,
  paymentActions,
  paymentTimeline,
  reallocationSources,
  requiresSecondReview,
  requestedTargetText,
  statementFileProblem,
  toTargetRequest,
} from './payment.rules';

describe('regras de pagamentos', () => {
  it('valida o comprovante: PDF, PNG ou JPEG até 10 MB', () => {
    expect(evidenceProblem({ name: 'comprovante.pdf', type: 'application/pdf', size: 1000 })).toBeNull();
    expect(evidenceProblem({ name: 'foto.JPG', type: 'image/jpeg', size: 1000 })).toBeNull();
    expect(evidenceProblem({ name: 'vazio.pdf', type: 'application/pdf', size: 0 })).toContain('vazio');
    expect(evidenceProblem({ name: 'grande.pdf', type: 'application/pdf', size: 10 * 1024 * 1024 + 1 })).toContain('10 MB');
    expect(evidenceProblem({ name: 'planilha.xlsx', type: 'application/vnd.ms-excel', size: 10 })).toContain('PDF, PNG ou JPEG');
    // tipo declarado não basta: a extensão também precisa conferir
    expect(evidenceProblem({ name: 'script.exe', type: 'application/pdf', size: 10 })).toContain('PDF, PNG ou JPEG');
  });

  it('valida extrato e certificado PKCS#12 (limite do Base64 aceito pelo servidor)', () => {
    expect(statementFileProblem({ name: 'extrato.csv', size: 10 })).toBeNull();
    expect(statementFileProblem({ name: 'extrato.ofx', size: 10 })).toContain('CSV');
    expect(certificateProblem({ name: 'cliente.p12', size: 4000 })).toBeNull();
    expect(certificateProblem({ name: 'cliente.pfx', size: 16_500 })).toBeNull();
    expect(certificateProblem({ name: 'cliente.pfx', size: 16_501 })).toContain('16 KB');
    expect(certificateProblem({ name: 'cliente.pem', size: 100 })).toContain('PKCS#12');
  });

  it('interpreta valores digitados em pt-BR', () => {
    expect(parseMoney('1.234,56')).toBe(1234.56);
    expect(parseMoney('R$ 350')).toBe(350);
    expect(parseMoney('99.9')).toBe(99.9);
    expect(parseMoney('12,345')).toBeNull();
    expect(parseMoney('abc')).toBeNull();
    expect(parseMoney('')).toBeNull();
  });

  it('acima do limite exige segunda pessoa; no limite, não', () => {
    expect(requiresSecondReview(500.01, 500)).toBe(true);
    expect(requiresSecondReview(500, 500)).toBe(false);
    expect(requiresSecondReview(null, 500)).toBe(false);
    expect(requiresSecondReview(800, null)).toBe(false);
  });

  it('quem informou não revisa; revisor vê as ações; estorno e realocação só em pagamento efetivo', () => {
    const informed = payment({ status: 'INFORMED', informedBy: 'u-1', reviewedAt: null, reviewedBy: null, confirmedAt: null });
    const ctx = { userId: 'u-1', canReview: true, canReverse: true, canReallocate: true };
    expect(paymentActions(informed, ctx)).toEqual({ review: false, ownPending: true, reverse: false, reallocate: false });
    expect(paymentActions(informed, { ...ctx, userId: 'u-9' })).toEqual({ review: true, ownPending: false, reverse: false, reallocate: false });
    const confirmed = payment({ pendingReallocation: 50 });
    expect(paymentActions(confirmed, ctx)).toEqual({ review: false, ownPending: false, reverse: true, reallocate: true });
    expect(paymentActions(payment({ status: 'REVERSED', pendingReallocation: 50 }), ctx).reverse).toBe(false);
  });

  it('linha do tempo em ordem: informado, aprovado, confirmado, realocado, estornado', () => {
    const p = payment({
      reversedAt: '2026-10-24T10:00:00Z',
      status: 'REVERSED',
      reversal: { id: 'r-1', amount: 1350, reason: 'BANK_RETURN', source: 'MANUAL', note: 'TED devolvida', reversedAt: '2026-10-24T10:00:00Z', reversedBy: 'u-3' },
      allocations: [allocation(), allocation({ id: 'al-2', origin: 'REALLOCATION', kind: 'REFUND', refundReference: 'E2E-9', createdAt: '2026-10-23T18:00:00Z' })],
    });
    const timeline = paymentTimeline(p);
    expect(timeline.map((e) => e.title)).toEqual(['Pagamento informado', 'Aprovado na revisão', 'Confirmado', 'Devolução registrada', 'Estornado']);
    expect(timeline[0].userId).toBe('u-2');
    expect(timeline[4].detail).toBe('Devolução bancária · TED devolvida');
  });

  it('origens da realocação: sobra e não aplicado de alocação a acordo ativa', () => {
    const p = payment({
      unallocatedAmount: 20,
      allocations: [
        allocation({ id: 'al-1', availableForReallocation: 100 }),
        allocation({ id: 'al-2', kind: 'TITLES', availableForReallocation: 30 }),
        allocation({ id: 'al-3', status: 'REVERSED', availableForReallocation: 40 }),
      ],
    });
    expect(reallocationSources(p)).toEqual([
      { allocationId: null, label: 'Sobra do pagamento (sem destino)', available: 20 },
      { allocationId: 'al-1', label: 'Não aplicado no acordo', available: 100 },
    ]);
  });

  it('indicadores do lote sobre os créditos tratados', () => {
    const ind = importIndicators(statementImport());
    expect(ind.credits).toBe(110);
    expect(ind.reconciledShare).toBeCloseTo(104 / 110);
    expect(ind.divergentShare).toBeCloseTo(6 / 110);
    expect(importIndicators(statementImport({ totalLines: 0, ignoredLines: 0 })).reconciledShare).toBeNull();
  });

  it('validade do certificado com aviso a 30 dias', () => {
    const now = Date.parse('2026-10-23T00:00:00Z');
    expect(certificateState(pixAccount(), now).tone).toBe('success');
    expect(certificateState(pixAccount({ certificateExpiresAt: '2026-11-10T00:00:00Z' }), now)).toEqual({ tone: 'warning', text: 'Vence em 18 dia(s)' });
    expect(certificateState(pixAccount({ certificateExpiresAt: '2026-10-01T00:00:00Z' }), now).tone).toBe('danger');
    expect(certificateState(pixAccount({ certificateSha256: null }), now).text).toBe('Sem certificado');
  });

  it('tempo até a expiração do Pix', () => {
    const now = Date.parse('2026-10-23T12:00:00Z');
    expect(expirationText({ status: 'ACTIVE', expiresAt: '2026-10-23T12:25:00Z' }, now)).toBe('Expira em 25 min');
    expect(expirationText({ status: 'ACTIVE', expiresAt: '2026-10-24T15:30:00Z' }, now)).toBe('Expira em 27 h 30 min');
    expect(expirationText({ status: 'ACTIVE', expiresAt: '2026-10-30T12:00:00Z' }, now)).toBe('Expira em 7 dias');
    expect(expirationText({ status: 'PAID', expiresAt: '2026-10-30T12:00:00Z' }, now)).toBe('');
  });

  it('impressões digitais SHA-256 aceitam ":" e maiúsculas', () => {
    const fp = 'AB:'.repeat(31) + 'AB';
    expect(isFingerprint(fp)).toBe(true);
    expect(isFingerprint('abc')).toBe(false);
    expect(parseFingerprints(`${fp}\n${'cd'.repeat(32)}`)).toEqual(['ab'.repeat(32), 'cd'.repeat(32)]);
    expect(parseFingerprints('xyz')).toBeNull();
  });

  it('monta destino e instante do pagamento informado', () => {
    expect(toTargetRequest({ kind: 'AGREEMENT', creditorId: 'c-1', agreementId: 'a-1', itemIds: [], balance: 10 })).toEqual({ kind: 'AGREEMENT', agreementId: 'a-1', itemIds: [], strategy: null });
    expect(toTargetRequest({ kind: 'TITLES', creditorId: 'c-1', itemIds: ['t-1'], strategy: 'PROPORTIONAL', balance: 10 })).toEqual({ kind: 'TITLES', agreementId: null, itemIds: ['t-1'], strategy: 'PROPORTIONAL' });
    const at = paidAtInstant(new Date(2026, 9, 20), '09:30');
    expect(new Date(at!).getHours()).toBe(9);
    expect(new Date(paidAtInstant(new Date(2026, 9, 20), '')!).getHours()).toBe(12);
    expect(paidAtInstant(null, '09:30')).toBeNull();
    // hoje, sem hora, antes do meio-dia: usa o momento atual (nunca futuro)
    const morning = new Date(2026, 9, 23, 8, 15);
    expect(paidAtInstant(new Date(2026, 9, 23), '', morning)).toBe(morning.toISOString());
  });

  it('destino solicitado em texto para quem aprova (parcelas, todas em aberto, títulos)', () => {
    const numbers = new Map([['i-3', 3], ['i-4', 4]]);
    expect(requestedTargetText({ kind: 'AGREEMENT', itemIds: ['i-3', 'i-4'], strategy: null }, { agreementLabel: '20/09/2026', installmentNumbers: numbers })).toBe('Acordo de 20/09/2026 · 3ª e 4ª parcelas');
    expect(requestedTargetText({ kind: 'AGREEMENT', itemIds: ['i-3'], strategy: null }, { installmentNumbers: numbers })).toBe('Acordo · 3ª parcela');
    expect(requestedTargetText({ kind: 'AGREEMENT', itemIds: [], strategy: null })).toBe('Acordo · todas as parcelas em aberto, na ordem');
    // sem acesso ao acordo: só a quantidade
    expect(requestedTargetText({ kind: 'AGREEMENT', itemIds: ['i-9'], strategy: null })).toBe('Acordo · 1 parcela(s) escolhida(s)');
    expect(requestedTargetText({ kind: 'TITLES', itemIds: ['t-1', 't-2'], strategy: 'OLDEST_FIRST' }, { titleLabels: new Map([['t-1', 'CRED-1/4'], ['t-2', 'CRED-1/5']]) })).toBe('Títulos CRED-1/4, CRED-1/5 · vencidos primeiro');
    expect(requestedTargetText({ kind: 'TITLES', itemIds: ['t-1'], strategy: 'PROPORTIONAL' })).toBe('1 título(s) avulso(s) · proporcional ao saldo');
  });
});
