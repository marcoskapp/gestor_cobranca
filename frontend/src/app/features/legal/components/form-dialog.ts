import { Component, Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Observable, filter, finalize, map, startWith } from 'rxjs';
import { EntityPicker, EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { isoDate } from '../../../shared/models/dates';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogConfig, DialogService } from '../../../shared/dialog/dialog.service';

export type FieldType = 'text' | 'textarea' | 'number' | 'money' | 'date' | 'datetime' | 'select' | 'multiselect' | 'checkbox' | 'entity' | 'file';

export interface FieldOption {
  value: string;
  label: string;
}

/** Campo declarativo do formulário (valores convertidos para o contrato da API no envio). */
export interface FormField {
  key: string;
  label: string;
  type: FieldType;
  required?: boolean;
  hint?: string;
  options?: readonly FieldOption[];
  maxLength?: number;
  min?: number;
  max?: number;
  source?: EntitySource;
  /** Valor inicial no formato da API (`LocalDate`, `Instant`, número, texto, lista). */
  value?: unknown;
  /** Validação própria: devolve a mensagem de erro ou nulo. */
  validate?: (value: unknown) => string | null;
  /** Exibe o campo só quando a condição é atendida (o valor oculto não é enviado). */
  showIf?: (values: Record<string, unknown>) => boolean;
  /** Ocupa a linha inteira (textos longos). */
  wide?: boolean;
}

export interface FormDialogData<R = unknown> {
  title: string;
  message?: string;
  submitLabel?: string;
  details?: readonly { label: string; value: string }[];
  warning?: string;
  /** Ação irreversível: exige digitar este texto para habilitar o envio (ex.: "ENCERRAR"). */
  confirmationText?: string;
  fields: readonly FormField[];
  submit: (values: Record<string, unknown>) => Observable<R>;
}

function customValidator(field: FormField): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!field.validate || control.value === null || control.value === '' || control.value === undefined) {
      return null;
    }
    const message = field.validate(control.value);
    return message ? { custom: message } : null;
  };
}

function initial(field: FormField): unknown {
  const v = field.value;
  switch (field.type) {
    case 'date':
      return isoDate(v);
    case 'datetime':
      return typeof v === 'string' ? toDatetimeLocal(new Date(v)) : '';
    case 'checkbox':
      return !!v;
    case 'multiselect':
      return Array.isArray(v) ? v : [];
    case 'entity':
      return typeof v === 'string' && v ? [v] : [];
    case 'number':
    case 'money':
      return typeof v === 'number' ? v : null;
    case 'file':
      return null;
    default:
      return v === null || v === undefined ? '' : String(v);
  }
}

/** `Date` → valor de `<input type="datetime-local">` no fuso do navegador. */
export function toDatetimeLocal(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Converte o valor do controle para o contrato da API. */
export function apiValue(field: FormField, raw: unknown): unknown {
  switch (field.type) {
    case 'date':
      return isoDate(raw);
    case 'datetime':
      return raw ? new Date(raw as string).toISOString() : null;
    case 'number':
    case 'money':
      return raw === null || raw === '' || raw === undefined ? null : Number(raw);
    case 'entity':
      return (raw as string[])[0] ?? null;
    case 'checkbox':
      return !!raw;
    case 'multiselect':
      return raw as string[];
    case 'file':
      return raw ?? null;
    default: {
      const text = String(raw ?? '').trim();
      return text === '' ? null : text;
    }
  }
}

/**
 * Formulário em diálogo descrito por campos (texto, número, valor, data, data e hora, seleção,
 * entidade, arquivo), usado pelos comandos do jurídico. Valida no cliente (obrigatório, limites,
 * regra própria do campo) e exibe o resumo do impacto quando informado.
 */
@Component({
  selector: 'app-form-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (data.message) {
          <p class="app-dialog-message">{{ data.message }}</p>
        }
        @if (data.details?.length) {
          <dl class="app-definition-list fd-details">
            @for (d of data.details; track d.label) {
              <dt>{{ d.label }}</dt>
              <dd>{{ d.value }}</dd>
            }
          </dl>
        }
        <div class="fd-grid">
          @for (f of visible(); track f.key; let first = $first) {
            @let c = control(f.key);
            @switch (f.type) {
              @case ('textarea') {
                <app-field class="fd-wide" [label]="f.label" [hint]="f.hint" [error]="error(f)">
                  <textarea appInput rows="3" [attr.ngbAutofocus]="first ? '' : null" [formControl]="c" [required]="!!f.required" [attr.maxlength]="f.maxLength ?? null"></textarea>
                </app-field>
              }
              @case ('select') {
                <app-field [class.fd-wide]="f.wide" [label]="f.label" [hint]="f.hint" [error]="error(f)">
                  <select appInput [attr.ngbAutofocus]="first ? '' : null" [formControl]="c" [required]="!!f.required">
                    @if (!f.required) {
                      <option value="">—</option>
                    }
                    @for (o of f.options ?? []; track o.value) {
                      <option [value]="o.value">{{ o.label }}</option>
                    }
                  </select>
                </app-field>
              }
              @case ('multiselect') {
                <fieldset class="fd-wide" [attr.aria-describedby]="f.hint || (c.invalid && c.touched) ? 'fd-' + f.key + '-help' : null">
                  <legend class="form-label fs-5">{{ f.label }}</legend>
                  @for (o of f.options ?? []; track o.value) {
                    <div class="form-check">
                      <input
                        class="form-check-input"
                        type="checkbox"
                        [id]="'fd-' + f.key + '-' + o.value"
                        [checked]="isChecked(c, o.value)"
                        [class.is-invalid]="c.invalid && c.touched"
                        (change)="toggle(c, o.value, $event)"
                      />
                      <label class="form-check-label" [for]="'fd-' + f.key + '-' + o.value">{{ o.label }}</label>
                    </div>
                  }
                  @if (c.invalid && c.touched) {
                    <div class="invalid-feedback d-block" [id]="'fd-' + f.key + '-help'">{{ error(f) }}</div>
                  } @else if (f.hint) {
                    <div class="form-text" [id]="'fd-' + f.key + '-help'">{{ f.hint }}</div>
                  }
                </fieldset>
              }
              @case ('checkbox') {
                <div class="form-check fd-check">
                  <input class="form-check-input" type="checkbox" [id]="'fd-' + f.key" [formControl]="c" />
                  <label class="form-check-label" [for]="'fd-' + f.key">{{ f.label }}</label>
                </div>
              }
              @case ('date') {
                <app-field [label]="f.label" [hint]="f.hint" [error]="error(f)">
                  <div class="input-group">
                    <input appInput ngbDatepicker #picker="ngbDatepicker" [attr.ngbAutofocus]="first ? '' : null" [formControl]="c" [required]="!!f.required" placeholder="dd/mm/aaaa" autocomplete="off" />
                    <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()">
                      <app-icon name="calendar-event" />
                    </button>
                  </div>
                </app-field>
              }
              @case ('datetime') {
                <app-field [label]="f.label" [hint]="f.hint" [error]="error(f)">
                  <input appInput type="datetime-local" [attr.ngbAutofocus]="first ? '' : null" [formControl]="c" [required]="!!f.required" />
                </app-field>
              }
              @case ('entity') {
                <div class="fd-wide">
                  <app-entity-picker [control]="c" [source]="f.source!" [label]="f.label" [single]="true" [hint]="f.hint" />
                </div>
              }
              @case ('file') {
                <div class="fd-wide">
                  <label class="form-label" [for]="'fd-' + f.key">{{ f.label }}{{ f.required ? ' *' : '' }}</label>
                  <input
                    class="form-control"
                    type="file"
                    [id]="'fd-' + f.key"
                    accept="application/pdf,image/png,image/jpeg"
                    [attr.aria-describedby]="f.hint ? 'fd-' + f.key + '-hint' : null"
                    (change)="pick(f.key, $event)"
                  />
                  @if (f.hint) {
                    <div class="form-text" [id]="'fd-' + f.key + '-hint'">{{ f.hint }}</div>
                  }
                </div>
              }
              @default {
                <app-field [class.fd-wide]="f.wide" [label]="f.label" [hint]="f.hint" [error]="error(f)">
                  <div [class.input-group]="f.type === 'money'">
                    @if (f.type === 'money') {
                      <span class="input-group-text" aria-hidden="true">R$</span>
                    }
                    <input
                      appInput
                      [attr.ngbAutofocus]="first ? '' : null"
                      [type]="f.type === 'number' || f.type === 'money' ? 'number' : 'text'"
                      [step]="f.type === 'money' ? '0.01' : '1'"
                      [formControl]="c"
                      [required]="!!f.required"
                      [attr.maxlength]="f.maxLength ?? null"
                      [attr.min]="f.min ?? null"
                      [attr.max]="f.max ?? null"
                    />
                  </div>
                </app-field>
              }
            }
          }
        </div>
        @if (data.warning) {
          <div class="alert alert-warning d-flex align-items-start gap-2 mt-3" role="note">
            <app-icon name="info-circle" />
            <span>{{ data.warning }}</span>
          </div>
        }
        @if (data.confirmationText) {
          <app-field class="mt-3" [label]="'Digite ' + data.confirmationText + ' para confirmar'">
            <input appInput [formControl]="typed" autocomplete="off" spellcheck="false" />
          </app-field>
        }
        @if (missing()) {
          <div class="invalid-feedback d-block mt-3" role="alert">Revise os campos destacados: há obrigatórios em branco ou valores inválidos.</div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || !typedMatches()">{{ data.submitLabel ?? 'Salvar' }}</button>
      </div>
    </form>
  `,
  styles: `
    .fd-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
    }
    .fd-wide {
      grid-column: 1 / -1;
    }
    .fd-check {
      align-self: center;
    }
    .fd-details {
      margin: 0 0 16px;
      padding: 12px 16px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
  `,
})
export class FormDialog {
  protected readonly data = inject<FormDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<unknown>>(DialogRef);
  protected readonly saving = signal(false);
  protected readonly missing = signal(false);
  protected readonly typed = new FormControl('', { nonNullable: true });
  private readonly typedValue = toSignal(this.typed.valueChanges, { initialValue: '' });
  protected readonly typedMatches = computed(
    () => !this.data.confirmationText || this.typedValue().trim().toUpperCase() === this.data.confirmationText.toUpperCase(),
  );

  protected readonly form = new FormGroup<Record<string, FormControl>>(
    Object.fromEntries(
      this.data.fields.map((f) => {
        const validators: ValidatorFn[] = [customValidator(f)];
        if (f.required && f.type !== 'checkbox' && f.type !== 'entity' && f.type !== 'file') {
          validators.push(f.type === 'multiselect' ? Validators.minLength(1) : Validators.required);
        }
        if (f.maxLength) {
          validators.push(Validators.maxLength(f.maxLength));
        }
        if (f.min !== undefined) {
          validators.push(Validators.min(f.min));
        }
        if (f.max !== undefined) {
          validators.push(Validators.max(f.max));
        }
        return [f.key, new FormControl(initial(f), validators)];
      }),
    ),
  );
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null), map(() => this.apiValues(false))), { initialValue: this.apiValues(false) });
  protected readonly visible = computed(() => {
    const v = this.values();
    return this.data.fields.filter((f) => !f.showIf || f.showIf(v));
  });

  protected control(key: string): FormControl {
    return this.form.controls[key];
  }

  protected error(f: FormField): string {
    const e = this.control(f.key).errors;
    if (!e) {
      return '';
    }
    if (e['custom']) {
      return e['custom'];
    }
    if (e['required'] || e['minlength']) {
      return 'Campo obrigatório.';
    }
    if (e['maxlength']) {
      return `Até ${f.maxLength} caracteres.`;
    }
    if (e['min']) {
      return `Mínimo ${f.min}.`;
    }
    if (e['max']) {
      return `Máximo ${f.max}.`;
    }
    return 'Valor inválido.';
  }

  protected isChecked(c: FormControl, value: string): boolean {
    return ((c.value as string[] | null) ?? []).includes(value);
  }

  /** Seleção múltipla em caixas de seleção: mantém a ordem das opções. */
  protected toggle(c: FormControl, value: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const current = new Set((c.value as string[] | null) ?? []);
    if (checked) {
      current.add(value);
    } else {
      current.delete(value);
    }
    const field = this.data.fields.find((f) => this.form.controls[f.key] === c);
    const order = (field?.options ?? []).map((o) => o.value);
    c.setValue(order.filter((v) => current.has(v)));
    c.markAsTouched();
  }

  protected pick(key: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.control(key).setValue(input.files?.[0] ?? null);
  }

  private apiValues(onlyVisible: boolean): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const f of this.data.fields) {
      out[f.key] = apiValue(f, this.form.controls[f.key].value);
    }
    if (onlyVisible) {
      for (const f of this.data.fields) {
        if (f.showIf && !f.showIf(out)) {
          out[f.key] = f.type === 'multiselect' ? [] : f.type === 'checkbox' ? false : null;
        }
      }
    }
    return out;
  }

  protected save(): void {
    const shown = this.visible();
    const invalid = shown.some((f) => {
      const c = this.control(f.key);
      const empty = f.type === 'entity' ? !(c.value as string[]).length : f.type === 'file' ? !c.value : false;
      return c.invalid || (f.required && empty);
    });
    if (invalid || this.saving() || !this.typedMatches()) {
      shown.forEach((f) => this.control(f.key).markAsTouched());
      this.missing.set(invalid);
      return;
    }
    this.missing.set(false);
    this.saving.set(true);
    this.data
      .submit(this.apiValues(true))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((result) => this.ref.close(result ?? true));
  }
}

/** Abre o formulário em diálogo; emite o resultado do envio somente quando o usuário conclui. */
@Injectable({ providedIn: 'root' })
export class FormDialogService {
  private readonly dialog = inject(DialogService);

  open<R>(data: FormDialogData<R>, size: DialogConfig['size'] = 'md'): Observable<R> {
    return this.dialog
      .open<FormDialog, FormDialogData<R>, R>(FormDialog, { data, size })
      .afterClosed()
      .pipe(filter((r): r is R => r !== undefined && r !== null));
  }
}

/** Opções de seleção a partir de um mapa de rótulos. */
export function options(labels: Record<string, string>, only?: readonly string[]): FieldOption[] {
  return (only ?? Object.keys(labels)).map((value) => ({ value, label: labels[value] ?? value }));
}
