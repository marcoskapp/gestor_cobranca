import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PlatformBillingApi } from '../data/platform-billing.api';
import { PolicyTermsRequest, PolicyVersionView, PolicyView, TierView } from '../data/platform-billing.models';
import { PLATFORM_BILLING_ERRORS, tierProblem } from './platform-billing.labels';

/** Dados do diálogo: sem política, cria (vigência + versão 1); com política, registra nova versão a partir da última. */
export interface PlatformPolicyDialogData {
  tenantId: string;
  tenantName: string;
  policy: PolicyView;
}

/** Monta as condições do formulário (faixas só no modo escalonado; a última faixa sempre sem limite). */
export function buildTerms(v: {
  closingDay: number;
  monthlyFee: number | null;
  includedRequests: number;
  tiered: boolean;
  pricePerRequest: number | null;
  tiers: { upTo: number | null; unitPrice: number | null }[];
  includedMbIn: number;
  pricePerMbIn: number | null;
  includedMbOut: number;
  pricePerMbOut: number | null;
  includedRecords: number;
  pricePerRecord: number | null;
}): PolicyTermsRequest {
  const tiers: TierView[] = v.tiered
    ? v.tiers.map((t, i) => ({ upTo: i === v.tiers.length - 1 || t.upTo === null ? null : Number(t.upTo), unitPrice: t.unitPrice ?? 0 }))
    : [];
  return {
    closingDay: Number(v.closingDay),
    monthlyFee: v.monthlyFee ?? 0,
    includedRequests: Number(v.includedRequests),
    pricePerRequest: v.tiered ? null : (v.pricePerRequest ?? 0),
    requestTiers: tiers,
    includedMbIn: Number(v.includedMbIn),
    pricePerMbIn: v.pricePerMbIn ?? 0,
    includedMbOut: Number(v.includedMbOut),
    pricePerMbOut: v.pricePerMbOut ?? 0,
    includedRecords: Number(v.includedRecords),
    pricePerRecord: v.pricePerRecord ?? 0,
  };
}

/**
 * Política de cobrança da plataforma para uma assessoria: dia de fechamento, mensalidade, franquias e preço do
 * excedente por chamada (único ou por faixas de volume), por MB recebido, por MB enviado e por registro. A primeira
 * versão vale já no ciclo em andamento; as seguintes, a partir do próximo ciclo. Independente das condições que a
 * assessoria cobra dos credores.
 */
@Component({
  selector: 'app-platform-policy-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>{{ creating ? 'Política da plataforma' : 'Nova versão da política' }} — {{ data.tenantName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <p class="app-muted ppd-small">
          {{ creating ? 'O primeiro cadastro vale já no ciclo em andamento (o ciclo inteiro).' : 'A nova versão vale a partir do próximo ciclo; o ciclo em curso mantém a versão atual.' }}
          A base é o consumo somado de todos os tokens da assessoria, de todos os credores; só chamadas bem-sucedidas.
          Dados em MB inteiros por token (1 MB = 1.048.576 bytes). Independente das condições cobradas dos credores.
        </p>
        @if (creating) {
          <h3 class="app-section-title">Vigência</h3>
          <div class="ppd-grid">
            <app-field label="Início" hint="Os ciclos começam aqui. Ciclos já encerrados antes do cadastro não são cobrados.">
              <input appInput type="date" formControlName="startDate" required />
            </app-field>
            <app-field label="Fim" hint="Opcional. O último ciclo termina no fim da vigência, proporcional.">
              <input appInput type="date" formControlName="endDate" />
            </app-field>
          </div>
        }
        <h3 class="app-section-title">Ciclo e mensalidade</h3>
        <div class="ppd-grid">
          <app-field label="Dia de fechamento" hint="1 a 31; meses sem o dia fecham no último dia. O próximo ciclo começa às 00:00 do dia seguinte.">
            <input appInput type="number" formControlName="closingDay" min="1" max="31" required />
          </app-field>
          <app-field label="Mensalidade por ciclo">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney formControlName="monthlyFee" required [min]="0" /></div>
          </app-field>
        </div>

        <h3 class="app-section-title">Chamadas</h3>
        <div class="ppd-grid">
          <app-field label="Chamadas incluídas na franquia"><input appInput type="number" formControlName="includedRequests" min="0" required /></app-field>
          <fieldset class="ppd-mode">
            <legend class="form-label">Preço do excedente</legend>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="ppd-flat" [value]="false" formControlName="tiered" />
              <label class="form-check-label" for="ppd-flat">Preço único</label>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="ppd-tiered" [value]="true" formControlName="tiered" />
              <label class="form-check-label" for="ppd-tiered">Faixas de volume</label>
            </div>
          </fieldset>
        </div>
        @if (!form.controls.tiered.value) {
          <app-field label="Preço por chamada excedente">
            <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="pricePerRequest" [min]="0" /></div>
          </app-field>
        } @else {
          <p class="app-muted ppd-small" id="ppd-tiers-help">
            As faixas contam as chamadas do ciclo desde a primeira; as da franquia não são cobradas e cada chamada excedente
            paga o preço da faixa em que cai. Deixe "até" vazio na última faixa.
          </p>
          <div formArrayName="tiers" class="ppd-tiers" aria-describedby="ppd-tiers-help">
            @for (t of tiers.controls; track t; let i = $index; let last = $last) {
              <div class="ppd-tier" [formGroupName]="i">
                <app-field [label]="'Faixa ' + (i + 1) + ': até a chamada'">
                  <input appInput type="number" formControlName="upTo" min="1" [attr.placeholder]="last ? 'sem limite' : null" />
                </app-field>
                <app-field [label]="'Faixa ' + (i + 1) + ': preço por chamada'">
                  <div class="input-group"><span class="input-group-text" aria-hidden="true">R$</span><input appInput appMoney [decimals]="6" formControlName="unitPrice" [min]="0" /></div>
                </app-field>
                <button class="btn btn-outline-secondary ppd-remove" type="button" (click)="removeTier(i)" [disabled]="tiers.length <= 1" [attr.aria-label]="'Remover a faixa ' + (i + 1)"><app-icon name="trash3" /></button>
              </div>
            }
          </div>
          <button class="btn btn-link px-0" type="button" (click)="addTier()" [disabled]="tiers.length >= 10"><app-icon name="plus-lg" /> Acrescentar faixa</button>
        }

        <h3 class="app-section-title">Dados e registros</h3>
        <div class="ppd-grid">
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
        <app-field label="Motivo" hint="Opcional; fica na trilha de auditoria da plataforma e da assessoria.">
          <input appInput formControlName="reason" maxlength="500" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ creating ? 'Criar política' : 'Registrar nova versão' }}</button>
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
    .ppd-mode {
      margin-bottom: 1rem;
    }
    .ppd-tier {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
      gap: 0 12px;
      align-items: end;
    }
    .ppd-remove {
      margin-bottom: 1rem;
    }
  `,
})
export class PlatformPolicyDialog {
  protected readonly data = inject<PlatformPolicyDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PolicyView>>(DialogRef);
  private readonly api = inject(PlatformBillingApi);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly creating = !this.data.policy.policyId;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly latest: PolicyVersionView | undefined = this.data.policy.versions[0];

  protected readonly form = this.fb.group({
    startDate: [new Date().toISOString().slice(0, 8) + '01'],
    endDate: [''],
    closingDay: [this.latest?.closingDay ?? 31, [Validators.required, Validators.min(1), Validators.max(31)]],
    monthlyFee: this.fb.control<number | null>(this.latest?.monthlyFee ?? 0, Validators.required),
    includedRequests: [this.latest?.includedRequests ?? 100000, [Validators.required, Validators.min(0)]],
    tiered: [!!this.latest?.requestTiers.length],
    pricePerRequest: this.fb.control<number | null>(this.latest?.pricePerRequest ?? 0),
    tiers: this.fb.array(this.initialTiers()),
    includedMbIn: [this.latest?.includedMbIn ?? 1000, [Validators.required, Validators.min(0)]],
    pricePerMbIn: this.fb.control<number | null>(this.latest?.pricePerMbIn ?? 0, Validators.required),
    includedMbOut: [this.latest?.includedMbOut ?? 1000, [Validators.required, Validators.min(0)]],
    pricePerMbOut: this.fb.control<number | null>(this.latest?.pricePerMbOut ?? 0, Validators.required),
    includedRecords: [this.latest?.includedRecords ?? 100000, [Validators.required, Validators.min(0)]],
    pricePerRecord: this.fb.control<number | null>(this.latest?.pricePerRecord ?? 0, Validators.required),
    reason: ['', [Validators.maxLength(500)]],
  });

  protected get tiers(): FormArray {
    return this.form.controls.tiers;
  }

  private initialTiers() {
    const existing = this.latest?.requestTiers ?? [];
    const source = existing.length ? existing : [{ upTo: 1_000_000, unitPrice: 0 }, { upTo: null, unitPrice: 0 }];
    return source.map((t) => this.tier(t.upTo, t.unitPrice));
  }

  private tier(upTo: number | null, unitPrice: number | null) {
    return this.fb.group({ upTo: this.fb.control<number | null>(upTo), unitPrice: this.fb.control<number | null>(unitPrice) });
  }

  protected addTier(): void {
    // a nova faixa entra antes da última (que continua sem limite)
    this.tiers.insert(Math.max(0, this.tiers.length - 1), this.tier(null, 0));
  }

  protected removeTier(index: number): void {
    this.tiers.removeAt(index);
  }

  protected save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      this.error.set(this.form.invalid ? 'Revise os campos destacados.' : null);
      return;
    }
    const v = this.form.getRawValue();
    const terms = buildTerms({ ...v, tiers: v.tiers as { upTo: number | null; unitPrice: number | null }[] });
    const problem = v.tiered ? tierProblem(terms.requestTiers) : null;
    if (problem) {
      this.error.set(problem);
      return;
    }
    if (this.creating && !v.startDate) {
      this.error.set('Informe o início da vigência.');
      return;
    }
    const reason = v.reason.trim() || null;
    const request$: Observable<PolicyView> = this.creating
      ? this.api.createPolicy(this.data.tenantId, { startDate: v.startDate, endDate: v.endDate || null, terms, reason })
      : this.api.addVersion(this.data.tenantId, terms, reason);
    this.saving.set(true);
    this.error.set(null);
    request$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(this.creating
          ? 'Política criada. A versão 1 já vale no ciclo em andamento.'
          : `Versão ${saved.versions[0]?.version} registrada. Vale a partir do próximo ciclo.`);
        this.ref.close(saved);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && PLATFORM_BILLING_ERRORS[e.code]) || userMessage(e));
      },
    });
  }
}
