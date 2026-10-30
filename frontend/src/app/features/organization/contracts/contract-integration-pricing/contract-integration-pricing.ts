import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount } from '../../../../shared/components/amount/amount';
import { Icon } from '../../../../shared/components/icon/icon';
import { valueOr } from '../../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { ContractsApi } from '../../data/organization.api';
import { ContractView, IntegrationPricingRequest, IntegrationPricingView } from '../../data/organization.models';

/** Mensagens dos códigos de erro das condições de cobrança (o restante cai na mensagem do servidor). */
export const INTEGRATION_PRICING_ERRORS: Record<string, string> = {
  'contract.not_active': 'Só contratos vigentes recebem novas condições.',
  'contract_pricing.closing_day_invalid': 'O dia de fechamento deve estar entre 1 e 31.',
  'contract_pricing.scale_invalid': 'Use até 2 casas na mensalidade e até 6 nos preços unitários.',
  'contract_pricing.value_invalid': 'Valores devem ser zero ou positivos.',
};

/**
 * Condições de cobrança das integrações (API pública) no contrato do credor: dia de fechamento do ciclo de consumo,
 * mensalidade, franquias e preço do excedente. Cada gravação é uma nova versão, que vale a partir do próximo ciclo
 * (o ciclo em curso mantém as condições anteriores); o histórico fica abaixo.
 */
@Component({
  selector: 'app-contract-integration-pricing',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, AppDatePipe, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Integrações do contrato {{ contract.number }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <p class="app-muted cip-small">
          Preço da API pública para este credor. Alterações valem a partir do próximo ciclo de consumo: o ciclo em curso
          mantém as condições anteriores. Cobram-se só chamadas bem-sucedidas (2xx/3xx); dados em MB inteiros
          (1 MB = 1.048.576 bytes), arredondados para cima por token no ciclo.
        </p>
        @if (canManage()) {
          <h3 class="app-section-title">Ciclo de consumo</h3>
          <app-field label="Dia de fechamento" hint="1 a 31. O ciclo fecha no fim do dia e o próximo começa às 00:00 do dia seguinte; meses sem o dia fecham no último dia.">
            <input appInput type="number" formControlName="usageClosingDay" min="1" max="31" required />
          </app-field>
          <h3 class="app-section-title">Mensalidade</h3>
          <app-field label="Valor fixo por ciclo">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney formControlName="monthlyFee" required [min]="0" /></div>
          </app-field>
          <h3 class="app-section-title">Franquias e excedente</h3>
          <div class="cip-grid">
            <app-field label="Chamadas incluídas"><input appInput type="number" formControlName="includedRequests" min="0" required /></app-field>
            <app-field label="Preço por chamada excedente">
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
          <app-field label="Motivo da alteração" hint="Opcional; fica na trilha de auditoria.">
            <input appInput formControlName="reason" maxlength="500" />
          </app-field>
        }
        <h3 class="app-section-title">Versões</h3>
        @if (history.isLoading()) {
          <p class="app-muted">Carregando…</p>
        } @else if (!versions().length) {
          <p class="app-muted">Sem condições registradas: o consumo é registrado com valor zero e o ciclo fecha no último dia do mês.</p>
        } @else {
          <div class="table-responsive">
            <table class="table table-sm mb-0">
              <caption class="visually-hidden">Versões das condições de cobrança das integrações</caption>
              <thead><tr><th scope="col">Versão</th><th scope="col">Registrada em</th><th scope="col" class="text-end">Fechamento</th><th scope="col" class="text-end">Mensalidade</th><th scope="col">Franquias (cham. · MB rec./env. · reg.)</th></tr></thead>
              <tbody>
                @for (v of versions(); track v.id) {
                  <tr>
                    <td>v{{ v.version }}@if (v.reason) {<div class="app-muted cip-small">{{ v.reason }}</div>}</td>
                    <td>{{ v.registeredAt | appDate }}</td>
                    <td class="text-end">dia {{ v.usageClosingDay }}</td>
                    <td class="text-end"><app-amount [value]="v.monthlyFee" /></td>
                    <td class="cip-small">{{ n(v.includedRequests) }} · {{ n(v.includedMbIn) }}/{{ n(v.includedMbOut) }} · {{ n(v.includedRecords) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>{{ canManage() ? 'Cancelar' : 'Fechar' }}</button>
        @if (canManage()) {
          <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar nova versão</button>
        }
      </div>
    </form>
  `,
  styles: `
    .cip-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr));
      gap: 0 16px;
    }
    .cip-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class ContractIntegrationPricing {
  protected readonly contract = inject<ContractView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<IntegrationPricingView>>(DialogRef);
  private readonly api = inject(ContractsApi);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly canManage = computed(
    () => this.auth.hasPermission(Permission.CONTRACT_MANAGE) && this.contract.status === 'ACTIVE',
  );
  protected readonly history = rxResource({
    params: () => this.contract.id,
    stream: ({ params }) => this.api.integrationPricing(params),
  });
  protected readonly versions = valueOr(this.history, []);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    usageClosingDay: [31, [Validators.required, Validators.min(1), Validators.max(31)]],
    monthlyFee: this.fb.control<number | null>(0, Validators.required),
    includedRequests: [100000, [Validators.required, Validators.min(0)]],
    pricePerRequest: this.fb.control<number | null>(0, Validators.required),
    includedMbIn: [1000, [Validators.required, Validators.min(0)]],
    pricePerMbIn: this.fb.control<number | null>(0, Validators.required),
    includedMbOut: [1000, [Validators.required, Validators.min(0)]],
    pricePerMbOut: this.fb.control<number | null>(0, Validators.required),
    includedRecords: [100000, [Validators.required, Validators.min(0)]],
    pricePerRecord: this.fb.control<number | null>(0, Validators.required),
    reason: ['', [Validators.maxLength(500)]],
  });

  constructor() {
    // a nova versão parte da mais recente, quando houver (sem sobrescrever o que já foi digitado)
    effect(() => {
      const latest = this.versions()[0];
      if (latest && this.form.pristine) {
        this.form.patchValue({
          usageClosingDay: latest.usageClosingDay,
          monthlyFee: latest.monthlyFee,
          includedRequests: latest.includedRequests,
          pricePerRequest: latest.pricePerRequest,
          includedMbIn: latest.includedMbIn,
          pricePerMbIn: latest.pricePerMbIn,
          includedMbOut: latest.includedMbOut,
          pricePerMbOut: latest.pricePerMbOut,
          includedRecords: latest.includedRecords,
          pricePerRecord: latest.pricePerRecord,
        });
      }
    });
  }

  protected n(value: number): string {
    return new Intl.NumberFormat('pt-BR').format(value);
  }

  protected save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      this.error.set(this.form.invalid ? 'Revise os campos destacados.' : null);
      return;
    }
    const v = this.form.getRawValue();
    const request: IntegrationPricingRequest = {
      usageClosingDay: Number(v.usageClosingDay),
      monthlyFee: v.monthlyFee ?? 0,
      includedRequests: Number(v.includedRequests),
      pricePerRequest: v.pricePerRequest ?? 0,
      includedMbIn: Number(v.includedMbIn),
      pricePerMbIn: v.pricePerMbIn ?? 0,
      includedMbOut: Number(v.includedMbOut),
      pricePerMbOut: v.pricePerMbOut ?? 0,
      includedRecords: Number(v.includedRecords),
      pricePerRecord: v.pricePerRecord ?? 0,
      reason: v.reason.trim() || null,
    };
    this.saving.set(true);
    this.error.set(null);
    this.api
      .defineIntegrationPricing(this.contract.id, request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(`Versão ${saved.version} registrada. Vale a partir do próximo ciclo de consumo.`);
          this.ref.close(saved);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && INTEGRATION_PRICING_ERRORS[e.code]) || userMessage(e));
        },
      });
  }
}
