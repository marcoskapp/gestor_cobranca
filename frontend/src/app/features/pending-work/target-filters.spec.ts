import { agendaPeriodParam } from '../collection/agenda/agenda';
import { inboxTabParam } from '../communication/inbox/inbox';
import { MINE, toProposalFilter } from '../negotiation/proposals/proposal-list';

/** Telas de destino de "Minhas pendências" aceitam o filtro pela URL. */
describe('filtros de destino pela URL', () => {
  it('agenda: período rápido válido ou "hoje"', () => {
    expect(agendaPeriodParam('late')).toBe('late');
    expect(agendaPeriodParam('week')).toBe('week');
    expect(agendaPeriodParam(null)).toBe('today');
    expect(agendaPeriodParam('custom')).toBe('today');
  });

  it('caixa de entrada: aba válida ou "Minhas"', () => {
    expect(inboxTabParam('queue')).toBe('queue');
    expect(inboxTabParam('xyz')).toBe('mine');
    expect(inboxTabParam(null)).toBe('mine');
  });

  it('propostas: "somente as minhas" vira mine=true na API', () => {
    expect(toProposalFilter({ creditor: [], status: 'APPROVED', actorType: MINE })).toEqual({
      creditorId: null,
      debtorId: null,
      status: 'APPROVED',
      actorType: null,
      mine: true,
    });
    expect(toProposalFilter({ creditor: ['c-1'], status: '', actorType: 'USER' })).toEqual({
      creditorId: 'c-1',
      debtorId: null,
      status: null,
      actorType: 'USER',
    });
  });
});
