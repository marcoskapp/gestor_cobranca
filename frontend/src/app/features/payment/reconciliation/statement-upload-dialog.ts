import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { ReconciliationApi } from '../data/payment.api';
import { PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { StatementImportView } from '../data/payment.models';
import { STATEMENT_ACCEPT, statementFileProblem } from '../data/payment.rules';
import { StatementPreview, decodeUtf8, previewStatement } from '../data/statement-preview';

/**
 * Importação de extrato CSV em duas etapas: prévia local (mesmas regras do servidor, nada é
 * enviado) e envio. Linhas inválidas impedem o envio, porque o servidor recusaria o arquivo
 * inteiro. O processamento é assíncrono; o resultado aparece na ficha do lote.
 */
@Component({
  selector: 'app-statement-upload-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, Amount, AppDatePipe, EntityPicker],
  template: `
    <h2 mat-dialog-title>Importar extrato</h2>
    <mat-dialog-content>
      <p class="app-dialog-message">CSV genérico com separador ponto e vírgula: <code>data;valor;tipo;id_transacao;referencia;descricao</code>. Importar de novo um período não gera baixa em dobro.</p>
      <div class="su-row">
        <div class="su-grow">
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (opcional)" [single]="true" hint="Vazio = conta da assessoria (todos os credores)." />
        </div>
        <mat-form-field class="su-format">
          <mat-label>Formato</mat-label>
          <mat-select [formControl]="form.controls.format">
            <mat-option value="CSV_GENERIC">CSV genérico</mat-option>
            <mat-option value="CNAB_240" disabled>CNAB 240 (em breve)</mat-option>
            <mat-option value="CNAB_400" disabled>CNAB 400 (em breve)</mat-option>
          </mat-select>
        </mat-form-field>
      </div>
      <div class="su-file">
        <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
        <button mat-stroked-button type="button" (click)="input.click()">
          <mat-icon aria-hidden="true">upload_file</mat-icon>
          Escolher arquivo
        </button>
        @if (file(); as f) {
          <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
        } @else {
          <span class="app-muted">CSV em UTF-8, até 10 MB e 50.000 linhas</span>
        }
      </div>
      @if (error()) {
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }

      @if (preview(); as p) {
        <section class="su-preview" aria-labelledby="su-preview-title" data-tour="statement-preview">
          <h3 class="app-section-title" id="su-preview-title">Prévia</h3>
          <div class="su-kpis">
            <div class="su-kpi"><span class="su-kpi__label">Linhas</span><span class="su-kpi__value">{{ p.lines }}</span></div>
            <div class="su-kpi"><span class="su-kpi__label">Créditos</span><span class="su-kpi__value">{{ p.credits }}</span></div>
            <div class="su-kpi"><span class="su-kpi__label">Total creditado</span><span class="su-kpi__value"><app-amount [value]="p.creditTotal" /></span></div>
            <div class="su-kpi"><span class="su-kpi__label">Débitos (ignorados)</span><span class="su-kpi__value">{{ p.debits }}</span></div>
          </div>
          @if (p.firstDate) {
            <p class="app-muted su-small">
              Período {{ p.firstDate | appDate: 'date' }} a {{ p.lastDate | appDate: 'date' }} · {{ p.withTransactionId }} com id da transação · {{ p.withReference }} com referência
              @if (p.ignoredColumns.length) {
                · colunas ignoradas: {{ p.ignoredColumns.join(', ') }}
              }
            </p>
          }
          @if (p.issues.length) {
            <div class="app-alert app-alert--error" role="alert">
              <mat-icon aria-hidden="true">error_outline</mat-icon>
              <div>
                <strong>Corrija o arquivo antes de enviar: o servidor recusa o extrato inteiro na primeira linha inválida.</strong>
                <ul class="su-issues">
                  @for (i of p.issues; track $index) {
                    <li>{{ i.line ? 'Linha ' + i.line + ': ' : '' }}{{ i.message }}</li>
                  }
                </ul>
                @if (p.truncatedIssues) {
                  <span>Há mais linhas com problema além destas.</span>
                }
              </div>
            </div>
          } @else if (p.sample.length) {
            <div class="su-scroll">
              <table class="su-table" aria-label="Primeiras linhas do extrato">
                <thead>
                  <tr>
                    <th scope="col">Linha</th>
                    <th scope="col">Data</th>
                    <th scope="col" class="su-end">Valor</th>
                    <th scope="col">Tipo</th>
                    <th scope="col">Id da transação</th>
                    <th scope="col">Referência</th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of p.sample; track l.line) {
                    <tr>
                      <td>{{ l.line }}</td>
                      <td>{{ l.date | appDate: 'date' }}</td>
                      <td class="su-end"><app-amount [value]="l.amount" /></td>
                      <td>{{ l.credit ? 'Crédito' : 'Débito' }}</td>
                      <td class="app-mono">{{ l.transactionId ?? '—' }}</td>
                      <td class="app-mono">{{ l.reference ?? '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancelar</button>
      <button mat-flat-button type="button" (click)="send()" [disabled]="sending() || !file() || !preview()?.ok">Importar extrato</button>
    </mat-dialog-actions>
  `,
  styles: `
    .su-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0 12px;
    }
    .su-grow {
      flex: 1 1 260px;
      min-width: 0;
    }
    .su-format {
      width: 200px;
    }
    .su-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
    }
    .su-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
      gap: 8px;
      margin-bottom: 8px;
    }
    .su-kpi {
      display: flex;
      flex-direction: column;
      padding: 8px 10px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
    .su-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--mat-sys-on-surface-variant);
    }
    .su-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .su-small {
      font-size: var(--app-text-sm);
    }
    .su-issues {
      margin: 6px 0;
      padding-left: 18px;
    }
    .su-scroll {
      overflow-x: auto;
    }
    .su-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
      th,
      td {
        padding: 4px 8px;
        border-bottom: 1px solid var(--app-border);
        text-align: left;
        white-space: nowrap;
      }
    }
    .su-end {
      text-align: right !important;
    }
  `,
})
export class StatementUploadDialog {
  private readonly ref = inject<MatDialogRef<StatementUploadDialog, StatementImportView>>(MatDialogRef);
  private readonly api = inject(ReconciliationApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly accept = STATEMENT_ACCEPT;
  protected readonly size = sizeText;

  protected readonly file = signal<File | null>(null);
  protected readonly preview = signal<StatementPreview | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly sending = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ creditor: this.fb.control<string[]>([]), format: this.fb.control<'CSV_GENERIC'>('CSV_GENERIC') });

  protected async pick(list: FileList | null): Promise<void> {
    const f = list?.[0] ?? null;
    this.error.set(null);
    this.preview.set(null);
    this.file.set(null);
    if (!f) {
      return;
    }
    const problem = statementFileProblem(f);
    if (problem) {
      this.error.set(problem);
      return;
    }
    this.file.set(f);
    this.preview.set(previewStatement(decodeUtf8(await f.arrayBuffer())));
  }

  protected send(): void {
    const f = this.file();
    if (!f || !this.preview()?.ok || this.sending()) {
      return;
    }
    const v = this.form.getRawValue();
    this.sending.set(true);
    this.error.set(null);
    this.api
      .importStatement(f, v.format, v.creditor[0] ?? null)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (imported) => {
          this.notifications.success('Extrato recebido: conciliação em andamento.');
          this.ref.close(imported);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
