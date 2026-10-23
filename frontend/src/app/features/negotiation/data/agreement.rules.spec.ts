import { proposal } from '../../../../testing/negotiation-fixtures';
import { AgreementView, InstallmentView } from './agreement.models';
import {
  actorText,
  agreementActions,
  installmentBalance,
  proposalActions,
  proposalTimeline,
  scheduleSummary,
  shortHash,
  submitMode,
  validityLapsed,
  violationText,
} from './agreement.rules';

const installment = (number: number, status: InstallmentView['status'], amount = 100, paidAmount = 0): InstallmentView => ({
  id: `i-${number}`,
  number,
  dueDate: `2026-1${number}-10`,
  amount,
  paidAmount,
  status,
  paidAt: null,
});

const perms = { userId: 'u-1', canPropose: true, canApprove: true, canFormalize: true };

describe('regras de propostas e acordos', () => {
  it('o botão de envio acompanha o resultado da validação (acima da alçada muda, não some)', () => {
    expect(submitMode('WITHIN_AUTHORITY')).toBe('propose');
    expect(submitMode('REQUIRES_APPROVAL')).toBe('request-approval');
    expect(submitMode('REJECTED')).toBeNull();
    expect(submitMode(null)).toBeNull();
  });

  it('validade vencida só para propostas abertas, comparando LocalDate', () => {
    expect(validityLapsed(proposal({ validUntil: '2026-10-22' }), '2026-10-23')).toBe(true);
    expect(validityLapsed(proposal({ validUntil: '2026-10-23' }), '2026-10-23')).toBe(false);
    expect(validityLapsed(proposal({ status: 'FORMALIZED', validUntil: '2026-10-01' }), '2026-10-23')).toBe(false);
  });

  it('ninguém decide a própria proposta e a aprovação some com a validade vencida', () => {
    const pending = proposal({ status: 'PENDING_APPROVAL' });
    expect(proposalActions(pending, perms, '2026-10-23')).toMatchObject({ approve: true, reject: true, accept: false });
    expect(proposalActions(pending, { ...perms, userId: 'u-2' }, '2026-10-23').approve).toBe(false);
    expect(proposalActions(pending, { ...perms, canApprove: false }, '2026-10-23').approve).toBe(false);
    expect(proposalActions(pending, perms, '2026-11-30').approve).toBe(false);
  });

  it('aceite em rascunho ou aprovada, formalização após o aceite, adoção da sugestão transferida', () => {
    expect(proposalActions(proposal({ status: 'APPROVED' }), perms, '2026-10-23').accept).toBe(true);
    expect(proposalActions(proposal({ status: 'DRAFT' }), { ...perms, canFormalize: false }, '2026-10-23').accept).toBe(false);
    const accepted = proposal({ status: 'ACCEPTED', acceptance: { channel: 'PHONE', acceptedAt: '2026-10-23T14:00:00Z', actorType: 'USER', actorId: 'u-1', reference: null, termSha256: 'ab', termSize: 10 } });
    expect(proposalActions(accepted, perms, '2026-10-23')).toMatchObject({ formalize: true, term: true, accept: false });
    expect(proposalActions(proposal({ status: 'HANDED_OFF', actor: { type: 'ASSISTANT', id: 'ap-1', profileId: null, profileCode: null } }), perms, '2026-10-23').adopt).toBe(true);
  });

  it('acordo em vigor pode ser cancelado; quebrado só renegociado', () => {
    const p = { canCancel: true, canRenegotiate: true };
    expect(agreementActions({ status: 'IN_PAYMENT' }, p)).toEqual({ cancel: true, renegotiate: true });
    expect(agreementActions({ status: 'BROKEN' }, p)).toEqual({ cancel: false, renegotiate: true });
    expect(agreementActions({ status: 'SETTLED' }, p)).toEqual({ cancel: false, renegotiate: false });
    expect(agreementActions({ status: 'FORMALIZED' }, { canCancel: false, canRenegotiate: false })).toEqual({ cancel: false, renegotiate: false });
  });

  it('resumo do cronograma: pagas, vencidas, próxima a pagar e percentual', () => {
    const a = {
      installments: [installment(3, 'OPEN'), installment(1, 'PAID', 100, 100), installment(2, 'OVERDUE', 100, 30)],
      totalAmount: 300,
      paidAmount: 130,
    } as Pick<AgreementView, 'installments' | 'totalAmount' | 'paidAmount'>;
    const s = scheduleSummary(a);
    expect(s).toMatchObject({ total: 3, paid: 1, overdue: 1, paidShare: 43.3 });
    expect(s.next?.number).toBe(2);
    expect(installmentBalance(installment(2, 'OVERDUE', 100, 30))).toBe(70);
    expect(installmentBalance(installment(4, 'CANCELLED', 100, 0))).toBe(0);
  });

  it('histórico em ordem cronológica com decisão, aceite e acordo; assistente identificado', () => {
    const p = proposal({
      status: 'FORMALIZED',
      actor: { type: 'ASSISTANT', id: 'ap-1', profileId: null, profileCode: null },
      decision: { decidedBy: 'u-9', profileCode: 'SUPERVISOR', decidedAt: '2026-10-23T13:30:00Z', note: 'ok' },
      acceptance: { channel: 'WHATSAPP', acceptedAt: '2026-10-23T14:00:00Z', actorType: 'USER', actorId: 'u-1', reference: 'wamid.1', termSha256: 'x', termSize: 1 },
      formalizedAt: '2026-10-23T14:05:00Z',
    });
    const names = (type: string, id: string | null) => actorText(type, id, 'u-1', (uid) => `Usuário ${uid}`);
    const t = proposalTimeline(p, names);
    expect(t.map((e) => e.title)).toEqual(['Proposta criada', 'Aprovada', 'Aceite do devedor registrado', 'Acordo formalizado']);
    expect(t[0].detail).toBe('Assistente');
    expect(t[1].detail).toContain('Usuário u-9');
    expect(t[2].detail).toContain('por WhatsApp');
    expect(t[2].detail).toContain('registrado por Você');
  });

  it('textos auxiliares', () => {
    expect(violationText('authority.discount_exceeded')).toBe('Desconto acima da alçada');
    expect(violationText('outro.codigo')).toBe('outro.codigo');
    expect(shortHash('0123456789abcdef0123456789abcdef')).toBe('0123456789ab…abcdef');
    expect(shortHash(null)).toBe('—');
  });
});
