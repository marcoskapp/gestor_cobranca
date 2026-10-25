import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.campaign ? 'Parâmetros da campanha' : 'Nova campanha de discagem' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="row g-3">
          @if (!data.campaign) {
            <app-field class="col-md-6" label="Código" hint="Ex.: VAREJO-30-60">
              <input appInput formControlName="code" maxlength="40" required />
            </app-field>
          }
          <app-field [class]="data.campaign ? 'col-12' : 'col-md-6'" label="Nome">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          @if (!data.campaign) {
            <app-field class="col-md-6" label="Fila de origem" hint="Casos aguardando nesta fila formam a lista">
              <select appInput formControlName="queueId" required>
                <option value="" disabled>Selecione</option>
                @for (q of queues(); track q.value) {
                  <option [value]="q.value">{{ q.label }}</option>
                }
              </select>
            </app-field>
            <div class="col-md-6">
              <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor (o da fila)" [single]="true" />
            </div>
          }
          <div class="col-12">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
          </div>
          <div class="col-12">
            <div class="form-check">
              <input class="form-check-input" type="checkbox" id="cg-assigned" formControlName="includeAssigned" />
              <label class="form-check-label" for="cg-assigned">Incluir casos já atribuídos a operadores</label>
            </div>
          </div>
          <div class="col-12">
            <p class="app-section-title" id="cg-mode">Ritmo</p>
            <div class="btn-group" role="group" aria-labelledby="cg-mode">
              @for (m of modes; track m[0]) {
                <input class="btn-check" type="radio" formControlName="mode" [id]="'cg-mode-' + m[0]" [value]="m[0]" />
                <label class="btn btn-outline-primary" [for]="'cg-mode-' + m[0]">{{ m[1] }}</label>
              }
            </div>
            <p class="app-muted small mt-1 mb-0">{{ hints[value().mode] }}</p>
          </div>
          @if (value().mode === 'PREDICTIVE') {
            <app-field class="col-md-6" label="Razão máxima (chamadas por operador livre)" hint="1 a 5; o ritmo reduz sozinho quando o abandono sobe">
              <input appInput formControlName="maxDialRatio" inputmode="decimal" />
            </app-field>
          }
          <app-field class="col-md-6" label="Abandono máximo (%)" hint="Padrão de mercado: 3% (Ofcom/FTC)">
            <input appInput formControlName="maxAbandonRate" inputmode="decimal" />
          </app-field>
          <app-field class="col-md-6" label="Tentativas por caso">
            <input appInput type="number" formControlName="maxAttempts" min="1" max="20" />
          </app-field>
          <app-field class="col-md-6" label="Intervalo entre tentativas (min)" hint="Ocupado: metade do intervalo">
            <input appInput type="number" formControlName="retryIntervalMinutes" min="5" max="10080" />
          </app-field>
          <app-field class="col-md-6" label="Tempo de toque (s)">
            <input appInput type="number" formControlName="ringTimeoutSeconds" min="10" max="60" />
          </app-field>
          <app-field class="col-md-6" label="Janela própria: início">
            <input appInput type="time" formControlName="windowStart" />
          </app-field>
          <app-field class="col-md-6" label="Janela própria: fim" hint="A política de contato continua valendo">
            <input appInput type="time" formControlName="windowEnd" />
          </app-field>
        </div>
        @if (problems().length && submitted()) {
          <ul class="alert alert-danger mt-3 mb-0 ps-4" role="alert">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ data.campaign ? 'Salvar parâmetros' : 'Criar campanha' }}</button>
      </div>
    </form>
  `,
})
export class CampaignDialog {
  protected readonly data = inject<CampaignDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CampaignView>>(DialogRef);
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
