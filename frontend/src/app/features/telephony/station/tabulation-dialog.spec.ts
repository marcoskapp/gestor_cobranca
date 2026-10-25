import { ComponentFixture } from '@angular/core/testing';
import { NgbDateAdapter, NgbDateParserFormatter } from '@ng-bootstrap/ng-bootstrap';
import { button, render, setup, text } from '../../../../testing/component-harness';
import { callView } from '../../../../testing/telephony-fixtures';
import { IsoDateAdapter, PtBrDateParserFormatter } from '../../../core/config/bootstrap.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { TabulationView } from '../../collection/data/collection.models';
import { zonedNow } from '../../collection/data/collection.rules';
import { TabulationDialog } from './tabulation-dialog';
import { TelephonySession } from './telephony-session';

const tabulation = (o: Partial<TabulationView>): TabulationView => ({
  id: 't-1', code: 'CALL_ANSWERED', name: 'Atendida', effectiveContact: true, promise: false, callback: false, requiresNote: false, displayOrder: 1, active: true, version: 1, ...o,
});

const tabulations = [tabulation({}), tabulation({ id: 't-2', code: 'PROMISE', name: 'Promessa de pagamento', promise: true, displayOrder: 2 })];

/** Data local dd/MM/aaaa e ISO, `days` dias depois de hoje (no fuso da operação). */
function dayAhead(days: number): { typed: string; iso: string } {
  const [y, m, d] = zonedNow().date.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  const pad = (n: number) => String(n).padStart(2, '0');
  const iso = `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
  return { iso, typed: `${pad(date.getUTCDate())}/${pad(date.getUTCMonth() + 1)}/${date.getUTCFullYear()}` };
}

describe('TabulationDialog (tabulação obrigatória)', () => {
  const close = vi.fn();
  const session = { tabulated: vi.fn() };
  const endedAt = new Date(Date.now() - 60_000).toISOString();

  function create() {
    return setup(TabulationDialog, ['TELEPHONY_USE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { call: callView({ status: 'COMPLETED', result: 'ANSWERED', endedAt, talkSeconds: 95, interactionState: 'AWAITING_TABULATION' }) } },
      { provide: DialogRef, useValue: { close } },
      { provide: TelephonySession, useValue: session },
      // datas dd/MM/aaaa com modelo ISO, como na aplicação
      { provide: NgbDateAdapter, useClass: IsoDateAdapter },
      { provide: NgbDateParserFormatter, useClass: PtBrDateParserFormatter },
    ]);
  }

  const respond = (url: string) => {
    if (url.endsWith('/tabulations')) {
      return tabulations;
    }
    if (url.endsWith('/settings')) {
      return { promiseMaxDays: 30 };
    }
    if (url.endsWith('/cases/k-1/sheet')) {
      return { collectionCase: { openBalance: 1000 } };
    }
    return undefined;
  };

  function select(fixture: ComponentFixture<unknown>, label: string): HTMLSelectElement {
    const el = fixture.nativeElement as HTMLElement;
    const id = [...el.querySelectorAll('label')].find((l) => l.textContent?.trim() === label)!.getAttribute('for')!;
    return el.querySelector<HTMLSelectElement>(`#${id}`)!;
  }

  afterEach(() => vi.clearAllMocks());

  it('não oferece fechar, mostra a contagem regressiva e pré-seleciona a sugestão', async () => {
    const { http } = create();
    const fixture = await render(TabulationDialog, http, respond);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.btn-close')).toBeNull();
    expect(button(fixture, 'Cancelar')).toBeUndefined();
    expect(text(fixture)).toMatch(/Automática em 1[34]:\d{2}/);
    expect(el.querySelector('[role="timer"]')).toBeTruthy();
    expect(select(fixture, 'Tabulação').value).toBe('t-1');
    fixture.destroy();
  });

  it('Ctrl + Enter registra a tabulação e devolve a chamada tabulada', async () => {
    const { http } = create();
    const fixture = await render(TabulationDialog, http, respond);
    const form = (fixture.nativeElement as HTMLElement).querySelector('form')!;
    form.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }));
    const req = http.expectOne('/api/v1/telephony/calls/call-1/tabulation');
    expect(req.request.body).toEqual(expect.objectContaining({ tabulationCode: 'CALL_ANSWERED', promise: null, callback: null }));
    const done = callView({ tabulationCode: 'CALL_ANSWERED', interactionState: 'RECORDED' });
    req.flush(done);
    expect(session.tabulated).toHaveBeenCalledWith(done);
    expect(close).toHaveBeenCalledWith(done);
    fixture.destroy();
  });

  it('promessa: data digitada em dd/mm/aaaa vai ao servidor em ISO', async () => {
    const { http } = create();
    const fixture = await render(TabulationDialog, http, respond);
    const tab = select(fixture, 'Tabulação');
    tab.value = 't-2';
    tab.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const amount = el.querySelector<HTMLInputElement>('input[formcontrolname="promiseAmount"]')!;
    amount.value = '250,00';
    amount.dispatchEvent(new Event('input'));
    const date = el.querySelector<HTMLInputElement>('input[formcontrolname="promiseDate"]')!;
    const due = dayAhead(5);
    date.value = due.typed;
    date.dispatchEvent(new Event('input'));
    date.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    button(fixture, 'Registrar tabulação')!.click();
    const req = http.expectOne('/api/v1/telephony/calls/call-1/tabulation');
    expect(req.request.body.promise).toEqual({ amount: 250, dueDate: due.iso });
    req.flush(callView({ tabulationCode: 'PROMISE' }));
    fixture.destroy();
  });

  it('sem data da promessa: lista o problema e não envia', async () => {
    const { http } = create();
    const fixture = await render(TabulationDialog, http, respond);
    const tab = select(fixture, 'Tabulação');
    tab.value = 't-2';
    tab.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    button(fixture, 'Registrar tabulação')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Informe a data prometida.');
    http.expectNone('/api/v1/telephony/calls/call-1/tabulation');
    expect(close).not.toHaveBeenCalled();
    fixture.destroy();
  });
});
