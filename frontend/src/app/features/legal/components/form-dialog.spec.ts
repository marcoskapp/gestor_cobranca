import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { provideLocale } from '../../../core/config/locale.config';
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
        provideLocale(),
        { provide: MatDialogRef, useValue: { close } },
        {
          provide: MAT_DIALOG_DATA,
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
});
