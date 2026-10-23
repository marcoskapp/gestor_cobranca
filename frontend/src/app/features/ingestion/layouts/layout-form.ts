import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { ImportLayoutsApi } from '../data/ingestion.api';
import { CanonicalField, ImportLayoutView, LayoutColumn, LayoutFormat, PublishLayoutRequest } from '../data/ingestion.models';

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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.base ? 'Nova versão do layout ' + data.base.code : 'Novo layout de importação' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira (opcional)" [single]="true" hint="Vazio = vale para todas as carteiras do credor." />
          </div>
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" required [readonly]="!!data.base" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="150" required />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Formato</mat-label>
            <mat-select formControlName="format">
              <mat-option value="CSV">CSV</mat-option>
              <mat-option value="XLSX">XLSX</mat-option>
            </mat-select>
          </mat-form-field>
          @if (form.controls.format.value === 'CSV') {
            <mat-form-field>
              <mat-label>Separador</mat-label>
              <mat-select formControlName="delimiter">
                @for (d of delimiters; track d.value) {
                  <mat-option [value]="d.value">{{ d.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Codificação</mat-label>
              <mat-select formControlName="encoding">
                @for (e of encodings; track e) {
                  <mat-option [value]="e">{{ e }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          } @else {
            <mat-form-field>
              <mat-label>Aba</mat-label>
              <input matInput formControlName="sheetName" maxlength="31" placeholder="Primeira aba" />
            </mat-form-field>
          }
          <mat-checkbox formControlName="hasHeader">Primeira linha é cabeçalho</mat-checkbox>
        </div>

        <h3 class="app-section-title">Colunas do arquivo → campos do sistema</h3>
        <div formArrayName="columns" class="lf-columns">
          @for (c of columns.controls; track c; let i = $index) {
            <div class="lf-row" [formGroupName]="i">
              <mat-form-field class="app-compact">
                <mat-label>Campo</mat-label>
                <mat-select formControlName="field">
                  @for (f of data.fields; track f.field) {
                    <mat-option [value]="f.field">{{ f.field }}{{ f.required ? ' *' : '' }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
              @if (form.controls.hasHeader.value) {
                <mat-form-field class="app-compact">
                  <mat-label>Cabeçalho no arquivo</mat-label>
                  <input matInput formControlName="header" maxlength="120" />
                </mat-form-field>
              } @else {
                <mat-form-field class="app-compact">
                  <mat-label>Posição (1 = primeira coluna)</mat-label>
                  <input matInput type="number" min="1" formControlName="position" />
                </mat-form-field>
              }
              <button mat-icon-button type="button" (click)="remove(i)" [attr.aria-label]="'Remover coluna ' + (i + 1)">
                <mat-icon aria-hidden="true">delete</mat-icon>
              </button>
            </div>
          }
        </div>
        <button mat-button type="button" (click)="add()" [disabled]="columns.length >= 60"><mat-icon aria-hidden="true">add</mat-icon>Coluna</button>
        @for (p of problems(); track p) {
          <p class="app-field-error" role="alert">{{ p }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Publicar layout</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .lf-columns {
      display: grid;
      gap: 0;
    }
    .lf-row {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      mat-form-field {
        flex: 1 1 200px;
        min-width: 0;
      }
    }
  `,
})
export class LayoutForm {
  protected readonly data = inject<LayoutFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<LayoutForm, ImportLayoutView>>(MatDialogRef);
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
