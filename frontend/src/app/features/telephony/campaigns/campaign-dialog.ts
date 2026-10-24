import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { valueOr } from '../../../shared/data/resource-value';
import { QueueNames } from '../../collection/data/collection.lookups';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { DialerCampaignsApi } from '../data/telephony.api';
import { DIALER_MODE_HINTS, DIALER_MODE_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { CampaignSettings, CampaignView, DialerMode } from '../data/telephony.models';
import { DEFAULT_MAX_ABANDON, campaignProblems, fromLocalTime, toLocalTime } from '../data/telephony.rules';

export interface CampaignDialogData {
  /** Nulo = nova campanha. */
  campaign: CampaignView | null;
}

const num = (v: string | number | null | undefined): number | null => (v === null || v === undefined || v === '' ? null : Number(String(v).replace(',', '.')));

/**
 * Configuração da campanha de discagem: fila de origem, ritmo (progressivo/preditivo com razão
 * máxima), abandono máximo (padrão 3%), retentativas, tempo de toque e janela própria. Parâmetros só
 * mudam com a campanha fora de discagem.
 */
@Component({
  selector: 'app-campaign-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.campaign ? 'Parâmetros da campanha' : 'Nova campanha de discagem' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <div class="app-form-grid">
          @if (!data.campaign) {
            <mat-form-field>
              <mat-label>Código</mat-label>
              <input matInput formControlName="code" maxlength="40" required />
              <mat-hint>Ex.: VAREJO-30-60</mat-hint>
            </mat-form-field>
          }
          <mat-form-field [class.app-span-all]="!!data.campaign">
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
          </mat-form-field>
          @if (!data.campaign) {
            <mat-form-field>
              <mat-label>Fila de origem</mat-label>
              <mat-select formControlName="queueId" required>
                @for (q of queues(); track q.value) {
                  <mat-option [value]="q.value">{{ q.label }}</mat-option>
                }
              </mat-select>
              <mat-hint>Casos aguardando nesta fila formam a lista</mat-hint>
            </mat-form-field>
            <div>
              <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor (o da fila)" [single]="true" />
            </div>
          }
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
          </div>
          <div class="app-span-all">
            <mat-checkbox formControlName="includeAssigned">Incluir casos já atribuídos a operadores</mat-checkbox>
          </div>
          <div class="app-span-all">
            <p class="app-section-title" id="cg-mode">Ritmo</p>
            <mat-button-toggle-group formControlName="mode" aria-labelledby="cg-mode">
              @for (m of modes; track m[0]) {
                <mat-button-toggle [value]="m[0]">{{ m[1] }}</mat-button-toggle>
              }
            </mat-button-toggle-group>
            <p class="app-muted cg-small">{{ hints[value().mode] }}</p>
          </div>
          @if (value().mode === 'PREDICTIVE') {
            <mat-form-field>
              <mat-label>Razão máxima (chamadas por operador livre)</mat-label>
              <input matInput formControlName="maxDialRatio" inputmode="decimal" />
              <mat-hint>1 a 5; o ritmo reduz sozinho quando o abandono sobe</mat-hint>
            </mat-form-field>
          }
          <mat-form-field>
            <mat-label>Abandono máximo (%)</mat-label>
            <input matInput formControlName="maxAbandonRate" inputmode="decimal" />
            <mat-hint>Padrão de mercado: 3% (Ofcom/FTC)</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Tentativas por caso</mat-label>
            <input matInput type="number" formControlName="maxAttempts" min="1" max="20" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Intervalo entre tentativas (min)</mat-label>
            <input matInput type="number" formControlName="retryIntervalMinutes" min="5" max="10080" />
            <mat-hint>Ocupado: metade do intervalo</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Tempo de toque (s)</mat-label>
            <input matInput type="number" formControlName="ringTimeoutSeconds" min="10" max="60" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Janela própria: início</mat-label>
            <input matInput type="time" formControlName="windowStart" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Janela própria: fim</mat-label>
            <input matInput type="time" formControlName="windowEnd" />
            <mat-hint>A política de contato continua valendo</mat-hint>
          </mat-form-field>
        </div>
        @if (problems().length && submitted()) {
          <ul class="app-alert app-alert--error cg-problems" role="alert">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ data.campaign ? 'Salvar parâmetros' : 'Criar campanha' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .cg-small {
      margin: 4px 0 0;
      font-size: var(--app-text-xs);
    }
    .cg-problems {
      display: block;
      padding-left: 28px;
    }
  `,
})
export class CampaignDialog {
  protected readonly data = inject<CampaignDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CampaignDialog, CampaignView>>(MatDialogRef);
  private readonly api = inject(DialerCampaignsApi);
  private readonly queueNames = inject(QueueNames);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly modes = Object.entries(DIALER_MODE_LABELS) as [DialerMode, string][];
  protected readonly hints = DIALER_MODE_HINTS;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly queueList = rxResource({ stream: () => this.queueNames.all() });
  protected readonly queues = valueOr(this.queueList, []);

  private readonly c = this.data.campaign;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: [''],
    name: [this.c?.name ?? ''],
    queueId: [this.c?.queueId ?? ''],
    creditor: this.fb.control<string[]>(this.c?.creditorId ? [this.c.creditorId] : []),
    portfolio: this.fb.control<string[]>(this.c?.portfolioId ? [this.c.portfolioId] : []),
    includeAssigned: [this.c?.includeAssigned ?? false],
    mode: this.fb.control<DialerMode>(this.c?.mode ?? 'PROGRESSIVE'),
    maxDialRatio: [String(this.c?.maxDialRatio ?? 2).replace('.', ',')],
    maxAbandonRate: [String(this.c?.maxAbandonRate ?? DEFAULT_MAX_ABANDON).replace('.', ',')],
    maxAttempts: this.fb.control<number | null>(this.c?.maxAttempts ?? 3),
    retryIntervalMinutes: this.fb.control<number | null>(this.c?.retryIntervalMinutes ?? 120),
    ringTimeoutSeconds: this.fb.control<number | null>(this.c?.ringTimeoutSeconds ?? 25),
    windowStart: [fromLocalTime(this.c?.windowStart)],
    windowEnd: [fromLocalTime(this.c?.windowEnd)],
  });
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });
  protected readonly settings = computed<CampaignSettings>(() => {
    const v = this.value();
    return {
      name: v.name.trim(),
      portfolioId: v.portfolio[0] ?? null,
      includeAssigned: v.includeAssigned,
      mode: v.mode,
      maxDialRatio: v.mode === 'PREDICTIVE' ? num(v.maxDialRatio) : 1,
      maxAbandonRate: num(v.maxAbandonRate),
      maxAttempts: num(v.maxAttempts),
      retryIntervalMinutes: num(v.retryIntervalMinutes),
      ringTimeoutSeconds: num(v.ringTimeoutSeconds),
      windowStart: toLocalTime(v.windowStart),
      windowEnd: toLocalTime(v.windowEnd),
    };
  });
  protected readonly problems = computed(() => {
    const out = campaignProblems(this.settings(), this.c ? null : this.value().code);
    if (!this.c && !this.value().queueId) {
      out.push('Escolha a fila de origem.');
    }
    return out;
  });

  protected save(): void {
    this.submitted.set(true);
    if (this.problems().length || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    const call = this.c
      ? this.api.update(this.c.id, this.settings())
      : this.api.create({ code: v.code.trim().toUpperCase(), creditorId: v.creditor[0] ?? null, queueId: v.queueId, settings: this.settings() });
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => this.ref.close(saved),
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }
}
