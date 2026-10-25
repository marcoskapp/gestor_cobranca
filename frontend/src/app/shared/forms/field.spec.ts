import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { FORM_FIELD } from './field';

@Component({
  imports: [ReactiveFormsModule, ...FORM_FIELD],
  template: `
    <form [formGroup]="form" (ngSubmit)="0">
      <app-field label="E-mail" hint="Usado para entrar">
        <input appInput formControlName="email" />
      </app-field>
      <app-field label="Situação">
        <select appInput formControlName="status"><option value="A">Ativo</option></select>
      </app-field>
      <button type="submit">Salvar</button>
    </form>
  `,
})
class Host {
  readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    status: new FormControl('A', { nonNullable: true }),
  });
}

describe('app-field + appInput', () => {
  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('rótulo ligado ao campo, classes do Bootstrap e ajuda descrita', () => {
    const { el } = render();
    const input = el.querySelector('input')!;
    const label = el.querySelector('label')!;
    expect(label.getAttribute('for')).toBe(input.id);
    expect(input.classList).toContain('form-control');
    expect(el.querySelector('select')!.classList).toContain('form-select');
    const hint = el.querySelector('.form-text')!;
    expect(hint.textContent).toContain('Usado para entrar');
    expect(input.getAttribute('aria-describedby')).toBe(hint.id);
    expect(input.classList).not.toContain('is-invalid');
  });

  it('erro só depois de tocar ou submeter, com aria-invalid e mensagem em português', () => {
    const { fixture, el } = render();
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    const input = el.querySelector('input')!;
    expect(input.classList).toContain('is-invalid');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const error = el.querySelector('.invalid-feedback')!;
    expect(error.textContent).toContain('Campo obrigatório.');
    expect(input.getAttribute('aria-describedby')).toBe(error.id);

    input.value = 'ana@exemplo.com.br';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(input.classList).not.toContain('is-invalid');
    expect(el.querySelector('.invalid-feedback')).toBeNull();
  });
});
