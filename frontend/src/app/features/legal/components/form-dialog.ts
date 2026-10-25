import { Component, Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Observable, filter, finalize, map, startWith } from 'rxjs';
import { EntityPicker, EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';

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
      return typeof v === 'string' ? fromLocalDate(v) : null;
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
      return toLocalDate(raw as Date | null);
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
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    EntityPicker,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
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
          @for (f of visible(); track f.key) {
            @let c = control(f.key);
            @switch (f.type) {
              @case ('textarea') {
                <mat-form-field class="fd-wide">
                  <mat-label>{{ f.label }}</mat-label>
                  <textarea matInput rows="3" [formControl]="c" [required]="!!f.required" [attr.maxlength]="f.maxLength ?? null"></textarea>
                  @if (f.hint) {
                    <mat-hint>{{ f.hint }}</mat-hint>
                  }
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
              @case ('select') {
                <mat-form-field [class.fd-wide]="f.wide">
                  <mat-label>{{ f.label }}</mat-label>
                  <mat-select [formControl]="c" [required]="!!f.required">
                    @if (!f.required) {
                      <mat-option value="">—</mat-option>
                    }
                    @for (o of f.options ?? []; track o.value) {
                      <mat-option [value]="o.value">{{ o.label }}</mat-option>
                    }
                  </mat-select>
                  @if (f.hint) {
                    <mat-hint>{{ f.hint }}</mat-hint>
                  }
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
              @case ('multiselect') {
                <mat-form-field class="fd-wide">
                  <mat-label>{{ f.label }}</mat-label>
                  <mat-select [formControl]="c" multiple [required]="!!f.required">
                    @for (o of f.options ?? []; track o.value) {
                      <mat-option [value]="o.value">{{ o.label }}</mat-option>
                    }
                  </mat-select>
                  @if (f.hint) {
                    <mat-hint>{{ f.hint }}</mat-hint>
                  }
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
              @case ('checkbox') {
                <mat-checkbox class="fd-check" [formControl]="c">{{ f.label }}</mat-checkbox>
              }
              @case ('date') {
                <mat-form-field>
                  <mat-label>{{ f.label }}</mat-label>
                  <input matInput [matDatepicker]="picker" [formControl]="c" [required]="!!f.required" />
                  <mat-datepicker-toggle matIconSuffix [for]="picker" />
                  <mat-datepicker #picker />
                  @if (f.hint) {
                    <mat-hint>{{ f.hint }}</mat-hint>
                  }
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
              @case ('datetime') {
                <mat-form-field>
                  <mat-label>{{ f.label }}</mat-label>
                  <input matInput type="datetime-local" [formControl]="c" [required]="!!f.required" />
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
              @case ('entity') {
                <div class="fd-wide">
                  <app-entity-picker [control]="c" [source]="f.source!" [label]="f.label" [single]="true" [hint]="f.hint" />
                </div>
              }
              @case ('file') {
                <div class="fd-wide fd-file">
                  <label class="fd-file__label" [for]="'fd-' + f.key">{{ f.label }}{{ f.required ? ' *' : '' }}</label>
                  <input type="file" [id]="'fd-' + f.key" accept="application/pdf,image/png,image/jpeg" (change)="pick(f.key, $event)" />
                  @if (f.hint) {
                    <span class="app-muted">{{ f.hint }}</span>
                  }
                </div>
              }
              @default {
                <mat-form-field [class.fd-wide]="f.wide">
                  <mat-label>{{ f.label }}</mat-label>
                  <input
                    matInput
                    [type]="f.type === 'number' || f.type === 'money' ? 'number' : 'text'"
                    [step]="f.type === 'money' ? '0.01' : '1'"
                    [formControl]="c"
                    [required]="!!f.required"
                    [attr.maxlength]="f.maxLength ?? null"
                    [attr.min]="f.min ?? null"
                    [attr.max]="f.max ?? null"
                  />
                  @if (f.type === 'money') {
                    <span matTextPrefix>R$&nbsp;</span>
                  }
                  @if (f.hint) {
                    <mat-hint>{{ f.hint }}</mat-hint>
                  }
                  <mat-error>{{ error(f) }}</mat-error>
                </mat-form-field>
              }
            }
          }
        </div>
        @if (data.warning) {
          <p class="app-alert app-alert--warning" role="note"><mat-icon aria-hidden="true">info</mat-icon><span>{{ data.warning }}</span></p>
        }
        @if (data.confirmationText) {
          <mat-form-field class="app-full-width">
            <mat-label>Digite {{ data.confirmationText }} para confirmar</mat-label>
            <input matInput [formControl]="typed" autocomplete="off" spellcheck="false" />
          </mat-form-field>
        }
        @if (missing()) {
          <p class="app-field-error" role="alert">Revise os campos destacados: há obrigatórios em branco ou valores inválidos.</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving() || !typedMatches()">{{ data.submitLabel ?? 'Salvar' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .fd-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 12px;
    }
    .fd-wide {
      grid-column: 1 / -1;
    }
    .fd-check {
      align-self: center;
      margin-bottom: 16px;
    }
    .fd-details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
    .fd-file {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 16px;
    }
    .fd-file__label {
      font-weight: 600;
    }
  `,
})
export class FormDialog {
  protected readonly data = inject<FormDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<FormDialog, unknown>>(MatDialogRef);
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
// TRANSITÓRIO (migração para Bootstrap): fornecido nas rotas do shell (shell.routes.ts), não na raiz,
// para usar o MatDialog e os padrões do Material daquele injetor. Volta a `providedIn: 'root'`
// quando passar a usar o DialogService.
@Injectable()
export class FormDialogService {
  private readonly dialog = inject(MatDialog);

  open<R>(data: FormDialogData<R>, width = '560px'): Observable<R> {
    return this.dialog
      .open<FormDialog, FormDialogData<R>, R>(FormDialog, { data, width })
      .afterClosed()
      .pipe(filter((r): r is R => r !== undefined && r !== null));
  }
}

/** Opções de seleção a partir de um mapa de rótulos. */
export function options(labels: Record<string, string>, only?: readonly string[]): FieldOption[] {
  return (only ?? Object.keys(labels)).map((value) => ({ value, label: labels[value] ?? value }));
}
