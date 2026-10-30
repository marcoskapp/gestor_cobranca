import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { PublicApiUsageApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS } from '../data/public-api.labels';
import { PricePlanView } from '../data/public-api.models';

export interface PricePlanDialogData {
  /** Rascunho a editar; ausente = nova versão (opcionalmente a partir de uma existente). */
  plan?: PricePlanView;
  /** Base para nova versão (copia código, credor e valores). */
  base?: PricePlanView;
}

function nextMonthStart(): string {
  const now = new Date();
  const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
  return `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-01`;
}

/**
 * Rascunho de versão da tabela de preços: mensalidade, franquias e preço do excedente por requisição, por MB recebido,
 * por MB enviado e por registro. Publicada, a versão não muda (nova vigência = nova versão).
 */
@Component({
  selector: 'app-price-plan-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>{{ data.plan ? 'Editar rascunho' : 'Nova versão da tabela de preços' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <div class="app-form-grid">
          <app-field label="Código" hint="Ex.: PADRAO. Versões do mesmo código formam o histórico.">
            <input appInput formControlName="code" maxlength="40" required [readonly]="!!data.plan || !!data.base" />
          </app-field>
          <app-field label="Nome">
            <input appInput formControlName="name" maxlength="100" required />
          </app-field>
          <app-field label="Vigência a partir de" hint="Primeiro dia de um mês, a partir do mês corrente.">
            <input appInput type="date" formControlName="effectiveFrom" required />
          </app-field>
        </div>
        @if (!data.plan && !data.base) {
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (opcional)" [single]="true" hint="Vazio = tabela padrão da assessoria; a do credor tem precedência." />
        }
        <h3 class="app-section-title">Mensalidade</h3>
        <app-field label="Valor fixo mensal">
          <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney formControlName="monthlyFee" required [min]="0" /></div>
        </app-field>
        <h3 class="app-section-title">Franquias e excedente</h3>
        <div class="ppd-grid">
          <app-field label="Requisições incluídas"><input appInput type="number" formControlName="includedRequests" min="0" required /></app-field>
          <app-field label="Preço por requisição excedente">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="pricePerRequest" required [min]="0" /></div>
          </app-field>
          <app-field label="MB recebidos incluídos"><input appInput type="number" formControlName="includedMbIn" min="0" required /></app-field>
          <app-field label="Preço por MB recebido excedente">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="pricePerMbIn" required [min]="0" /></div>
          </app-field>
          <app-field label="MB enviados incluídos"><input appInput type="number" formControlName="includedMbOut" min="0" required /></app-field>
          <app-field label="Preço por MB enviado excedente">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="pricePerMbOut" required [min]="0" /></div>
          </app-field>
          <app-field label="Registros incluídos"><input appInput type="number" formControlName="includedRecords" min="0" required /></app-field>
          <app-field label="Preço por registro excedente">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="pricePerRecord" required [min]="0" /></div>
          </app-field>
        </div>
        <p class="app-muted ppd-small">1 MB = 1.000.000 bytes. Requisições limitadas (429) e falhas da plataforma (5xx) não são cobradas.</p>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar rascunho</button>
      </div>
    </form>
  `,
  styles: `
    .ppd-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr));
      gap: 0 16px;
    }
    .ppd-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class PricePlanDialog {
  protected readonly data = inject<PricePlanDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PricePlanView>>(DialogRef);
  private readonly api = inject(PublicApiUsageApi);
  protected readonly creditors = inject(CreditorLookup);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly source = this.data.plan ?? this.data.base;

  protected readonly form = this.fb.group({
    code: [this.source?.code ?? 'PADRAO', [Validators.required, Validators.pattern(/^[A-Za-z0-9_-]{2,40}$/)]],
    name: [this.source?.name ?? 'Tabela padrão da API pública', [Validators.required, Validators.maxLength(100)]],
    creditor: this.fb.control<string[]>(this.source?.creditorId ? [this.source.creditorId] : []),
    effectiveFrom: [this.data.plan?.effectiveFrom ?? nextMonthStart(), Validators.required],
    monthlyFee: this.fb.control<number | null>(this.source?.monthlyFee ?? 0, Validators.required),
    includedRequests: [this.source?.includedRequests ?? 100000, [Validators.required, Validators.min(0)]],
    pricePerRequest: this.fb.control<number | null>(this.source?.pricePerRequest ?? 0, Validators.required),
    includedMbIn: [this.source?.includedMbIn ?? 1000, [Validators.required, Validators.min(0)]],
    pricePerMbIn: this.fb.control<number | null>(this.source?.pricePerMbIn ?? 0, Validators.required),
    includedMbOut: [this.source?.includedMbOut ?? 1000, [Validators.required, Validators.min(0)]],
    pricePerMbOut: this.fb.control<number | null>(this.source?.pricePerMbOut ?? 0, Validators.required),
    includedRecords: [this.source?.includedRecords ?? 100000, [Validators.required, Validators.min(0)]],
    pricePerRecord: this.fb.control<number | null>(this.source?.pricePerRecord ?? 0, Validators.required),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      this.error.set(this.form.invalid ? 'Revise os campos destacados.' : null);
      return;
    }
    const v = this.form.getRawValue();
    const request = {
      code: v.code.trim().toUpperCase(),
      creditorId: v.creditor[0] ?? null,
      name: v.name.trim(),
      effectiveFrom: v.effectiveFrom,
      monthlyFee: v.monthlyFee ?? 0,
      includedRequests: Number(v.includedRequests),
      pricePerRequest: v.pricePerRequest ?? 0,
      includedMbIn: Number(v.includedMbIn),
      pricePerMbIn: v.pricePerMbIn ?? 0,
      includedMbOut: Number(v.includedMbOut),
      pricePerMbOut: v.pricePerMbOut ?? 0,
      includedRecords: Number(v.includedRecords),
      pricePerRecord: v.pricePerRecord ?? 0,
    };
    this.saving.set(true);
    const call = this.data.plan ? this.api.updatePlan(this.data.plan.id, request) : this.api.createPlan(request);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (plan) => this.ref.close(plan),
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }
}
