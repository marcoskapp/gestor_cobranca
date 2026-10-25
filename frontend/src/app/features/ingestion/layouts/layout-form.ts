import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { ImportLayoutsApi } from '../data/ingestion.api';
import { CanonicalField, ImportLayoutView, LayoutColumn, LayoutFormat, PublishLayoutRequest } from '../data/ingestion.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

export interface LayoutFormData {
  fields: readonly CanonicalField[];
  /** Nova versão a partir de um layout existente (mesmo código). */
  base: ImportLayoutView | null;
}

export const DELIMITERS = [
  { value: ';', label: 'Ponto e vírgula (;)' },
  { value: ',', label: 'Vírgula (,)' },
  { value: '|', label: 'Barra vertical (|)' },
  { value: '\t', label: 'Tabulação' },
];
export const ENCODINGS = ['UTF-8', 'ISO-8859-1', 'WINDOWS-1252'];

/** Problemas no mapeamento: campos obrigatórios ausentes, repetidos e coluna sem cabeçalho/posição. */
export function columnProblems(columns: readonly LayoutColumn[], fields: readonly CanonicalField[], hasHeader: boolean): string[] {
  const problems: string[] = [];
  const used = columns.map((c) => c.field).filter(Boolean);
  for (const f of fields.filter((x) => x.required)) {
    if (!used.includes(f.field)) {
      problems.push(`Mapeie a coluna obrigatória "${f.field}".`);
    }
  }
  const repeated = used.filter((f, i) => used.indexOf(f) !== i);
  for (const f of new Set(repeated)) {
    problems.push(`"${f}" mapeado mais de uma vez.`);
  }
  columns.forEach((c) => {
    if (c.field && !(hasHeader ? c.header?.trim() : c.position)) {
      problems.push(`Informe ${hasHeader ? 'o cabeçalho' : 'a posição'} de "${c.field}".`);
    }
  });
  return problems;
}

/** Publica um layout de credor (nova versão quando o código já existe; a anterior é desativada). */
@Component({
  selector: 'app-layout-form',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.base ? 'Nova versão do layout ' + data.base.code : 'Novo layout de importação' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid">
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira (opcional)" [single]="true" hint="Vazio = vale para todas as carteiras do credor." />
          </div>
          <app-field label="Código">
            <input appInput formControlName="code" maxlength="40" required [readonly]="!!data.base" />
          </app-field>
          <app-field label="Nome">
            <input appInput formControlName="name" maxlength="150" required />
          </app-field>
          <app-field label="Formato">
            <select appInput formControlName="format">
              <option value="CSV">CSV</option>
              <option value="XLSX">XLSX</option>
            </select>
          </app-field>
          @if (form.controls.format.value === 'CSV') {
            <app-field label="Separador">
              <select appInput formControlName="delimiter">
                @for (d of delimiters; track d.value) {
                  <option [ngValue]="d.value">{{ d.label }}</option>
                }
              </select>
            </app-field>
            <app-field label="Codificação">
              <select appInput formControlName="encoding">
                @for (e of encodings; track e) {
                  <option [ngValue]="e">{{ e }}</option>
                }
              </select>
            </app-field>
          } @else {
            <app-field label="Aba">
              <input appInput formControlName="sheetName" maxlength="31" placeholder="Primeira aba" />
            </app-field>
          }
          <div class="form-check align-self-end">
            <input class="form-check-input" type="checkbox" id="layout-hasHeader" formControlName="hasHeader" />
            <label class="form-check-label" for="layout-hasHeader">Primeira linha é cabeçalho</label>
          </div>
        </div>

        <h3 class="app-section-title">Colunas do arquivo → campos do sistema</h3>
        <div formArrayName="columns" class="lf-columns">
          @for (c of columns.controls; track c; let i = $index) {
            <div class="lf-row" [formGroupName]="i">
              <app-field label="Campo">
                <select appInput formControlName="field">
                  @for (f of data.fields; track f.field) {
                    <option [ngValue]="f.field">{{ f.field }}{{ f.required ? ' *' : '' }}</option>
                  }
                </select>
              </app-field>
              @if (form.controls.hasHeader.value) {
                <app-field label="Cabeçalho no arquivo">
                  <input appInput formControlName="header" maxlength="120" />
                </app-field>
              } @else {
                <app-field label="Posição (1 = primeira coluna)">
                  <input appInput type="number" min="1" formControlName="position" />
                </app-field>
              }
              <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="remove(i)" [attr.aria-label]="'Remover coluna ' + (i + 1)">
                <app-icon name="trash3" />
              </button>
            </div>
          }
        </div>
        <button class="btn btn-link" type="button" (click)="add()" [disabled]="columns.length >= 60"><app-icon name="plus-lg" />Coluna</button>
        @for (p of problems(); track p) {
          <div class="invalid-feedback d-block" role="alert">{{ p }}</div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Publicar layout</button>
      </div>
    </form>
  `,
  styles: `
    .lf-columns {
      display: grid;
      gap: 8px;
      margin-bottom: 8px;
    }
    .lf-row {
      display: flex;
      align-items: flex-end;
      gap: 8px;
      app-field {
        flex: 1 1 200px;
        min-width: 0;
      }
    }
  `,
})
export class LayoutForm {
  protected readonly data = inject<LayoutFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ImportLayoutView>>(DialogRef);
  private readonly api = inject(ImportLayoutsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly delimiters = DELIMITERS;
  protected readonly encodings = ENCODINGS;
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly problems = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly b = this.data.base;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>(this.b ? [this.b.creditorId] : [], [AppValidators.notBlank]),
    portfolio: this.fb.control<string[]>(this.b?.portfolioId ? [this.b.portfolioId] : []),
    code: [this.b?.code ?? '', [AppValidators.notBlank, Validators.maxLength(40)]],
    name: [this.b?.name ?? '', [AppValidators.notBlank, Validators.maxLength(150)]],
    format: this.fb.control<LayoutFormat>(this.b?.format ?? 'CSV'),
    delimiter: [this.b?.delimiter ?? ';'],
    encoding: [this.b?.encoding ?? 'UTF-8'],
    sheetName: [this.b?.sheetName ?? ''],
    hasHeader: [this.b?.hasHeader ?? true],
    columns: this.fb.array<FormGroup>(
      (this.b?.columns ?? this.data.fields.filter((f) => f.required).map((f) => ({ field: f.field, header: f.field, position: null }))).map((c) => this.column(c)),
    ),
  });
  protected readonly portfolios = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);

  protected get columns(): FormArray<FormGroup> {
    return this.form.controls.columns;
  }

  private column(c: Partial<LayoutColumn>): FormGroup {
    return this.fb.group({ field: [c.field ?? ''], header: [c.header ?? ''], position: this.fb.control<number | null>(c.position ?? null) });
  }

  protected add(): void {
    this.columns.push(this.column({}));
  }

  protected remove(i: number): void {
    this.columns.removeAt(i);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const columns: LayoutColumn[] = (v.columns as LayoutColumn[]).map((c) => ({
      field: c.field,
      header: v.hasHeader ? c.header?.trim() || null : null,
      position: v.hasHeader ? null : c.position ? Number(c.position) : null,
    }));
    this.problems.set(columnProblems(columns, this.data.fields, v.hasHeader));
    if (this.form.invalid || this.problems().length || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const request: PublishLayoutRequest = {
      creditorId: v.creditor[0],
      portfolioId: v.portfolio[0] ?? null,
      code: v.code.trim().toUpperCase(),
      name: v.name.trim(),
      format: v.format,
      delimiter: v.format === 'CSV' ? v.delimiter : null,
      encoding: v.format === 'CSV' ? v.encoding : null,
      hasHeader: v.hasHeader,
      sheetName: v.format === 'XLSX' ? v.sheetName.trim() || null : null,
      columns,
    };
    this.saving.set(true);
    this.api
      .publish(request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(`Layout ${saved.code} v${saved.version} publicado.`);
          this.ref.close(saved);
        },
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
