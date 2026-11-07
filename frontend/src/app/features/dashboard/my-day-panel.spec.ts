import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup, text } from '../../../testing/component-harness';
import { KeyboardShortcuts } from '../../core/keyboard/keyboard-shortcuts.service';
import { MyDayView } from '../collection/data/collection.models';
import { MyDayPanel } from './my-day-panel';

const operatorDay = {
  assignedCases: 6,
  callbacksToday: 2,
  callbacksOverdue: 1,
  promisesDueToday: 3,
  promisesAwaitingPayment: 1,
  interactionsToday: 9,
  effectiveContactsToday: 4,
  promisesRecordedToday: 2,
};

const view = (overrides: Partial<MyDayView> = {}): MyDayView => ({ date: '2026-11-07', operator: operatorDay, team: null, ...overrides });

describe('MyDayPanel ("Seu dia" no início)', () => {
  it('operador: próximo caso, casos, retornos (com atrasados), promessas e o resultado do dia', async () => {
    const h = setup(MyDayPanel, ['CASE_WORK']);
    const f = await render(MyDayPanel, h.http, (url) => (url === '/api/v1/collection/my-day' ? view() : undefined));
    const t = text(f).replace(/\s+/g, '');
    expect(t).toContain('Seudia');
    expect(t).toContain('Casoscomvocê6');
    expect(t).toContain('Retornosdehoje21atrasado(s)');
    expect(t).toContain('Promessasvencendohoje31vencida(s)natolerância');
    expect(t).toContain('4contatosefetivos');
    expect(t).toContain('em9interação(ões)·2promessa(s)');
    expect(t).not.toContain('Suaequipehoje');
    // retornos atrasados levam à agenda já em "atrasados"
    const callbacks = (f.nativeElement as HTMLElement).querySelector('a[href^="/cobranca/agenda"]');
    expect(callbacks?.getAttribute('href')).toBe('/cobranca/agenda?periodo=late');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Próximo caso'))!.click();
    expect(navigate).toHaveBeenCalledWith(['/cobranca/fila'], { queryParams: { proximo: 1 } });
    expect(TestBed.inject(KeyboardShortcuts).shortcuts().some((s) => s.id === 'home.next' && s.keys === 'alt+n')).toBe(true);
  });

  it('supervisor: equipes que supervisiona e as filas, sem o botão de próximo caso', async () => {
    const h = setup(MyDayPanel, ['CASE_ASSIGN']);
    const team = { teams: 1, members: 4, assignedCases: 22, callbacksOverdue: 5, promisesDueToday: 3, queues: [{ queueId: 'q-1', name: 'Atraso 61-90', waiting: 12, overdue: 4 }] };
    const f = await render(MyDayPanel, h.http, (url) => (url === '/api/v1/collection/my-day' ? view({ operator: null, team }) : undefined));
    const t = text(f).replace(/\s+/g, '');
    expect(t).toContain('Suaequipehoje');
    expect(t).toContain('4operador(es)em1equipe(s)');
    expect(t).toContain('Retornosatrasados5');
    expect(t).toContain('Atraso61-90124');
    expect(t).not.toContain('Próximocaso');
    expect(TestBed.inject(KeyboardShortcuts).shortcuts().some((s) => s.id === 'home.next')).toBe(false);
  });

  it('sem trabalho de operador nem distribuição de casos: não aparece nem consulta', async () => {
    const h = setup(MyDayPanel, ['DASHBOARD_VIEW']);
    const f = await render(MyDayPanel, h.http, () => undefined);
    expect(text(f).trim()).toBe('');
    h.http.expectNone('/api/v1/collection/my-day');
  });

  it('supervisor sem equipe supervisionada: o bloco some', async () => {
    const h = setup(MyDayPanel, ['CASE_ASSIGN']);
    const f = await render(MyDayPanel, h.http, (url) => (url === '/api/v1/collection/my-day' ? view({ operator: null, team: null }) : undefined));
    expect(text(f)).not.toContain('Seu dia');
  });
});
