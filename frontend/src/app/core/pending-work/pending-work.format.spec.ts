import { pendingItem, pendingSummary } from '../../../testing/pending-work-fixtures';
import { ageText, badgeText, changeAnnouncement, indicatorLabel, itemMeta, queryParamsOf, severityText } from './pending-work.format';

describe('pending-work.format', () => {
  const now = new Date('2026-10-26T15:00:00Z');

  it('destaca vencidos, os de hoje e o próximo prazo', () => {
    expect(severityText(pendingItem({ severity: 'OVERDUE', overdue: 1 }))).toBe('1 vencido');
    expect(severityText(pendingItem({ severity: 'OVERDUE', overdue: 3, count: 4 }))).toBe('3 vencidos');
    expect(severityText(pendingItem({ severity: 'DUE_TODAY', dueToday: 1 }))).toBe('1 vence hoje');
    expect(severityText(pendingItem({ severity: 'DUE_TODAY', dueToday: 2 }))).toBe('2 vencem hoje');
    expect(severityText(pendingItem({ nextDueDate: '2026-10-30' }))).toBe('próximo prazo 30/10');
    expect(severityText(pendingItem())).toBeNull();
  });

  it('descreve a idade do item mais antigo', () => {
    expect(ageText(null, now)).toBeNull();
    expect(ageText('2026-10-26T14:30:00Z', now)).toBe('há menos de 1 hora');
    expect(ageText('2026-10-26T12:00:00Z', now)).toBe('há 3 horas');
    expect(ageText('2026-10-25T14:00:00Z', now)).toBe('há 1 dia');
    expect(ageText('2026-10-20T15:00:00Z', now)).toBe('há 6 dias');
  });

  it('linha de apoio sem repetir o selo de vencido', () => {
    expect(itemMeta(pendingItem({ oldestAt: '2026-10-24T15:00:00Z' }), now)).toBe('Pagamentos · mais antigo há 2 dias');
    expect(itemMeta(pendingItem({ severity: 'OVERDUE', overdue: 1, nextDueDate: '2026-10-20', oldestAt: null }), now)).toBe(
      'Pagamentos',
    );
    expect(itemMeta(pendingItem({ nextDueDate: '2026-10-29', oldestAt: null }), now)).toBe('Pagamentos · próximo prazo 29/10');
  });

  it('rótulo acessível e selo do indicador', () => {
    expect(indicatorLabel(null)).toBe('Suas pendências');
    expect(indicatorLabel(pendingSummary())).toBe('Suas pendências: nada pendente');
    const s = pendingSummary([pendingItem({ count: 3, overdue: 1, severity: 'OVERDUE' }), pendingItem({ type: 'x.y', count: 1, dueToday: 1, severity: 'DUE_TODAY' })]);
    expect(indicatorLabel(s)).toBe('Suas pendências: 4 itens, 1 vencido, 1 vence hoje');
    expect(badgeText(7)).toBe('7');
    expect(badgeText(120)).toBe('99+');
  });

  it('anuncia só quando as contagens mudam (nunca na primeira carga)', () => {
    const before = pendingSummary([pendingItem()]);
    expect(changeAnnouncement(null, before)).toBeNull();
    expect(changeAnnouncement(before, pendingSummary([pendingItem()]))).toBeNull();
    expect(changeAnnouncement(before, pendingSummary([pendingItem({ count: 3 })]))).toBe('Suas pendências: 3 itens.');
  });

  it('filtro da tela de destino só quando houver', () => {
    expect(queryParamsOf(pendingItem())).toBeNull();
    expect(queryParamsOf(pendingItem({ queryParams: { situacao: 'OPEN' } }))).toEqual({ situacao: 'OPEN' });
  });
});
