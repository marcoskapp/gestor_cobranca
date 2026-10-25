import { TestBed } from '@angular/core/testing';
import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../dialog/dialog.service';
import { ConfirmService } from './confirm.service';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('ConfirmDialog (Bootstrap)', () => {
  let confirm: ConfirmService;

  beforeEach(() => {
    TestBed.inject(NgbConfig).animation = false;
    confirm = TestBed.inject(ConfirmService);
  });

  afterEach(() => TestBed.inject(DialogService).closeAll());

  const modal = () => document.querySelector('ngb-modal-window') as HTMLElement;
  const button = (label: string) =>
    Array.from(modal().querySelectorAll<HTMLButtonElement>('.modal-footer button')).find((b) => b.textContent?.trim() === label)!;
  function type(el: HTMLInputElement | HTMLTextAreaElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('input'));
  }

  it('mostra o impacto, foca em "Cancelar" e cancelar não emite', async () => {
    const results: string[] = [];
    confirm
      .confirm({ title: 'Estornar pagamento', message: 'O valor volta ao saldo.', details: [{ label: 'Valor', value: 'R$ 825,00' }], destructive: true })
      .subscribe((r) => results.push(r));
    await tick();
    expect(modal().getAttribute('role')).toBe('alertdialog');
    expect(modal().textContent).toContain('R$ 825,00');
    expect(document.activeElement?.textContent?.trim()).toBe('Cancelar');
    expect(button('Confirmar').classList).toContain('btn-danger');
    button('Cancelar').click();
    await tick();
    expect(results).toEqual([]);
  });

  it('ação irreversível só confirma depois de digitar a palavra', async () => {
    const results: string[] = [];
    confirm.confirm({ title: 'Cancelar título', message: 'Irreversível.', confirmationText: 'CANCELAR' }).subscribe((r) => results.push(r));
    await tick();
    expect(button('Confirmar').disabled).toBe(true);
    type(modal().querySelector<HTMLInputElement>('input')!, 'cancelar');
    TestBed.tick();
    expect(button('Confirmar').disabled).toBe(false);
    button('Confirmar').click();
    await tick();
    expect(results).toEqual(['']);
  });

  it('motivo obrigatório: mostra o erro acessível e só confirma com motivo', async () => {
    const results: string[] = [];
    confirm.confirm({ title: 'Suspender', message: 'Suspende a cobrança.', reason: 'required' }).subscribe((r) => results.push(r));
    await tick();
    button('Confirmar').click();
    await tick();
    TestBed.tick();
    const textarea = modal().querySelector<HTMLTextAreaElement>('textarea')!;
    expect(textarea.classList).toContain('is-invalid');
    expect(textarea.getAttribute('aria-invalid')).toBe('true');
    const error = modal().querySelector('.invalid-feedback')!;
    expect(error.textContent).toContain('Campo obrigatório.');
    expect(textarea.getAttribute('aria-describedby')).toBe(error.id);
    expect(results).toEqual([]);

    type(textarea, '  Devedor pediu revisão  ');
    TestBed.tick();
    button('Confirmar').click();
    await tick();
    expect(results).toEqual(['Devedor pediu revisão']);
  });
});
