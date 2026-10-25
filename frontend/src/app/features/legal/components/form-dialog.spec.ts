import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { cnjValid } from '../data/legal.rules';
import { FormDialog, FormDialogData } from './form-dialog';

describe('FormDialog', () => {
  const close = vi.fn();
  let submitted: Record<string, unknown> | null;

  function render(data: Omit<FormDialogData, 'submit'>): ComponentFixture<FormDialog> {
    submitted = null;
    close.mockClear();
    TestBed.configureTestingModule({
      imports: [FormDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        { provide: DialogRef, useValue: { close } },
        {
          provide: DIALOG_DATA,
          useValue: {
            ...data,
            submit: (v: Record<string, unknown>) => {
              submitted = v;
              return of({ ok: true });
            },
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(FormDialog);
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const submit = (f: ComponentFixture<FormDialog>) => {
    el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    f.detectChanges();
  };

  it('campos condicionais aparecem conforme o valor e o oculto não é enviado', () => {
    const fixture = render({
      title: 'Encerrar',
      fields: [
        { key: 'stage', label: 'Fase', type: 'select', required: true, options: [{ value: 'CLOSED', label: 'Encerrado' }, { value: 'FILED', label: 'Ajuizado' }], value: 'FILED' },
        { key: 'closure', label: 'Motivo do encerramento', type: 'select', required: true, options: [{ value: 'PAID', label: 'Pago' }], showIf: (v) => v['stage'] === 'CLOSED' },
        { key: 'reason', label: 'Motivo', type: 'textarea', required: true, maxLength: 500 },
      ],
    });
    expect(el(fixture).textContent).not.toContain('Motivo do encerramento');
    submit(fixture);
    expect(submitted).toBeNull();
    expect(el(fixture).textContent).toContain('Revise os campos destacados');

    fixture.componentInstance['form'].patchValue({ reason: '  ajuizado hoje ' });
    submit(fixture);
    expect(submitted).toEqual({ stage: 'FILED', closure: null, reason: 'ajuizado hoje' });
    expect(close).toHaveBeenCalledWith({ ok: true });
  });

  it('validação própria do campo bloqueia o envio (número CNJ)', () => {
    const fixture = render({
      title: 'Processo',
      fields: [{ key: 'cnj', label: 'Número CNJ', type: 'text', required: true, validate: (v) => (cnjValid(String(v)) ? null : 'Número CNJ inválido.') }],
    });
    fixture.componentInstance['form'].patchValue({ cnj: '0000000-00.2026.8.26.0100' });
    submit(fixture);
    expect(submitted).toBeNull();
    expect(el(fixture).textContent).toContain('Número CNJ inválido.');
  });

  it('ação irreversível só envia com a palavra de confirmação', () => {
    const fixture = render({ title: 'Encerrar', confirmationText: 'ENCERRAR', fields: [{ key: 'reason', label: 'Motivo', type: 'text', required: true }] });
    fixture.componentInstance['form'].patchValue({ reason: 'Pago' });
    fixture.detectChanges();
    expect(el(fixture).querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
    fixture.componentInstance['typed'].setValue('encerrar');
    fixture.detectChanges();
    submit(fixture);
    expect(submitted).toEqual({ reason: 'Pago' });
  });

  it('data digitada em dd/mm/aaaa vira LocalDate e vias marcadas seguem a ordem das opções', () => {
    const fixture = render({
      title: 'Autorizar',
      fields: [
        { key: 'date', label: 'Data da decisão', type: 'date', required: true },
        { key: 'routes', label: 'Vias', type: 'multiselect', required: true, options: [{ value: 'PROTEST', label: 'Protesto' }, { value: 'LAWSUIT', label: 'Ação judicial' }] },
      ],
    });
    const date = el(fixture).querySelector<HTMLInputElement>('input[placeholder="dd/mm/aaaa"]')!;
    expect(el(fixture).querySelector('label[for="' + date.id + '"]')?.textContent).toContain('Data da decisão');
    date.value = '23/10/2026';
    date.dispatchEvent(new Event('input'));
    date.dispatchEvent(new Event('change'));
    const boxes = el(fixture).querySelectorAll<HTMLInputElement>('fieldset input[type="checkbox"]');
    expect(el(fixture).querySelector('fieldset legend')?.textContent).toContain('Vias');
    boxes[1].click();
    boxes[0].click();
    fixture.detectChanges();
    submit(fixture);
    expect(submitted).toEqual({ date: '2026-10-23', routes: ['PROTEST', 'LAWSUIT'] });
  });

  it('data inválida bloqueia o envio com a mensagem do campo', () => {
    const fixture = render({ title: 'Prazo', fields: [{ key: 'date', label: 'Vencimento', type: 'date', required: true }] });
    const date = el(fixture).querySelector<HTMLInputElement>('input[placeholder="dd/mm/aaaa"]')!;
    date.value = '31/02/2026';
    date.dispatchEvent(new Event('input'));
    date.dispatchEvent(new Event('change'));
    submit(fixture);
    expect(submitted).toBeNull();
    expect(date.classList).toContain('is-invalid');
    expect(el(fixture).textContent).toContain('Revise os campos destacados');
  });
});
