import { Component, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize, map, startWith, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { valueOr } from '../../../shared/data/resource-value';
import { Limits } from '../../../shared/validation/validators';
import { PortfoliosApi } from '../../organization/data/organization.api';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { ImportBatchesApi, ImportLayoutsApi } from '../data/ingestion.api';
import { ACCEPT, UPLOAD_ERROR_LABELS, fileProblem, sizeText } from '../data/ingestion.labels';
import { ImportBatchView, ImportMode } from '../data/ingestion.models';

export interface UploadDialogData {
  /** Carteira já escolhida na tela (filtro). */
  portfolioId: string | null;
}

/**
 * Envio de arquivo de carteira (CSV/XLSX) com prévia opcional. Arquivo já importado com sucesso
 * na carteira é recusado; reprocessá-lo exige confirmação com justificativa (auditada).
 */
@Component({
  selector: 'app-upload-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Nova importação</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Os títulos entram nesta carteira (e no seu credor)." />
        <mat-form-field class="app-full-width">
          <mat-label>Layout</mat-label>
          <mat-select formControlName="layoutId">
            <mat-option value="">Layout padrão (formato pela extensão)</mat-option>
            @for (l of layouts(); track l.id) {
              <mat-option [value]="l.id">{{ l.name }} · {{ l.code }} v{{ l.version }} ({{ l.format }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-button-toggle-group formControlName="mode" aria-label="Modo" class="ud-mode">
          <mat-button-toggle value="VALIDATE_ONLY">Prévia (somente validar)</mat-button-toggle>
          <mat-button-toggle value="IMPORT">Importar</mat-button-toggle>
        </mat-button-toggle-group>
        <p class="app-muted ud-small">
          {{ form.controls.mode.value === 'VALIDATE_ONLY' ? 'Nada é gravado: você confere erros e totais e depois importa o mesmo arquivo.' : 'Devedores, contatos e títulos são gravados; reenviar o mesmo arquivo não duplica nada.' }}
        </p>
        <div class="ud-file">
          <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
          <button mat-stroked-button type="button" (click)="input.click()">
            <mat-icon aria-hidden="true">upload_file</mat-icon>
            Escolher arquivo
          </button>
          @if (file(); as f) {
            <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
          } @else {
            <span class="app-muted">CSV ou XLSX, até 50 MB</span>
          }
        </div>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        @if (duplicate()) {
          <p class="app-alert app-alert--warning" role="note">
            <mat-icon aria-hidden="true">content_copy</mat-icon>
            <span>Reprocessar não duplica títulos, mas registra o reenvio na auditoria. Informe o motivo para continuar.</span>
          </p>
          <mat-form-field class="app-full-width">
            <mat-label>Motivo do reprocessamento</mat-label>
            <textarea matInput formControlName="reason" rows="2" [maxlength]="maxReason"></textarea>
          </mat-form-field>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending() || !file() || !portfolioId()">
          {{ duplicate() ? 'Reprocessar mesmo assim' : form.controls.mode.value === 'IMPORT' ? 'Importar' : 'Enviar para prévia' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .ud-mode {
      margin-top: 4px;
    }
    .ud-small {
      margin: 6px 0 12px;
      font-size: var(--app-text-sm);
    }
    .ud-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
    }
  `,
})
export class UploadDialog {
  private readonly data = inject<UploadDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<UploadDialog, ImportBatchView>>(MatDialogRef);
  private readonly api = inject(ImportBatchesApi);
  private readonly layoutsApi = inject(ImportLayoutsApi);
  private readonly portfoliosApi = inject(PortfoliosApi);
  private readonly notifications = inject(NotificationService);
  protected readonly portfolios = inject(PortfolioLookup).source();
  protected readonly accept = ACCEPT;
  protected readonly maxReason = Limits.reason;
  protected readonly size = sizeText;

  protected readonly file = signal<File | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly duplicate = signal(false);
  protected readonly sending = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    portfolio: this.fb.control<string[]>(this.data.portfolioId ? [this.data.portfolioId] : []),
    layoutId: [''],
    mode: this.fb.control<ImportMode>('VALIDATE_ONLY'),
    reason: ['', [Validators.maxLength(Limits.reason)]],
  });
  protected readonly portfolioId = toSignal(this.form.controls.portfolio.valueChanges.pipe(startWith(this.form.controls.portfolio.value), map((v): string | null => v[0] ?? null)), {
    initialValue: this.data.portfolioId as string | null,
  });

  /** Layouts ativos do credor da carteira, gerais ou da própria carteira. */
  private readonly layoutList = rxResource({
    params: () => this.portfolioId() ?? undefined,
    stream: ({ params }) =>
      this.portfoliosApi.get(params).pipe(
        switchMap((p) => this.layoutsApi.list(p.creditorId, false)),
        map((list) => list.filter((l) => l.active && (!l.portfolioId || l.portfolioId === params))),
      ),
  });
  protected readonly layouts = valueOr(this.layoutList, []);

  constructor() {
    // mudar carteira, layout ou modo descarta a confirmação de reprocessamento
    this.form.controls.portfolio.valueChanges.subscribe(() => this.reset());
    this.form.controls.mode.valueChanges.subscribe(() => this.reset());
  }

  protected pick(list: FileList | null): void {
    const f = list?.[0] ?? null;
    this.reset();
    if (f) {
      const problem = fileProblem(f);
      this.error.set(problem);
      this.file.set(problem ? null : f);
    }
  }

  protected send(): void {
    const f = this.file();
    const portfolioId = this.portfolioId();
    const v = this.form.getRawValue();
    if (!f || !portfolioId || this.sending()) {
      return;
    }
    if (this.duplicate() && !v.reason.trim()) {
      this.error.set(UPLOAD_ERROR_LABELS['import.force_reason_required']);
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .upload(f, { portfolioId, layoutId: v.layoutId || null, mode: v.mode, force: this.duplicate(), reason: this.duplicate() ? v.reason.trim() : null })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (batch) => {
          this.notifications.success(v.mode === 'IMPORT' ? 'Arquivo recebido: importação em andamento.' : 'Arquivo recebido: prévia em andamento.');
          this.ref.close(batch);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          if (e.code === 'import.duplicate_file') {
            this.duplicate.set(true);
          }
          this.error.set((e.code && UPLOAD_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  private reset(): void {
    this.duplicate.set(false);
    this.error.set(null);
  }
}
