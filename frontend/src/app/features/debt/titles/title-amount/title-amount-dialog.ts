import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount } from '../../../../shared/components/amount/amount';
import { Icon } from '../../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppValidators } from '../../../../shared/validation/validators';
import { TitleAmountApi } from '../../data/debt.api';
import { AMOUNT_BLOCKER_LABELS, AMOUNT_REASON_LABELS, LEDGER_COMPONENT_LABELS } from '../../data/debt.labels';
import { AmountAdjustmentContext, AmountAdjustmentReason, AmountAdjustmentView, AmountComponent } from '../../data/debt.models';
import { adjustmentPreview } from './title-amount.rules';

export interface TitleAmountDialogData {
  titleId: string;
  titleLabel: string;
  /** Atalho da contestação procedente: motivo e vínculo já preenchidos. */
  disputeId?: string | null;
  note?: string | null;
}

const NOTE_MAX = 2000;
const EVIDENCE_ACCEPT = 'application/pdf,image/png,image/jpeg';
const EVIDENCE_MAX = 20 * 1024 * 1024;

/**
 * Correção manual do valor do título: novo saldo por componente (máscara monetária), motivo da lista com descrição,
 * evidência e vínculo com a contestação. Mostra antes de enviar se a correção será aplicada já (redução até o limite)
 * ou irá para aprovação de outra pessoa (soma das reduções acima do limite ou algum componente aumentando), com evidência obrigatória.
 */
@Component({
  selector: 'app-title-amount-dialog',
  imports: [ReactiveFormsModule, Amount, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Corrigir valor · {{ data.titleLabel }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    @if (context.error()) {
      <div class="modal-body">
        <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>Não foi possível carregar os saldos do título.</span></div>
      </div>
    } @else if (!ctx()) {
      <div class="modal-body"><div class="spinner-border spinner-border-sm" role="status" aria-label="Carregando os saldos"></div></div>
    } @else {
      @let c = ctx()!;
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save(c)" novalidate>
        <div class="modal-body">
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          @for (b of c.blockers; track b) {
            <div class="alert alert-warning d-flex gap-2" role="note"><app-icon name="exclamation-triangle" /><span>{{ blockerLabels[b] }}</span></div>
          }
          <p class="app-muted">
            Informe o novo saldo dos componentes a corrigir. A correção fica registrada no título com o motivo, quem pediu e quem aprovou; não altera
            pagamentos já recebidos.
          </p>
          <table class="table table-sm align-middle ta-table">
            <caption class="visually-hidden">Saldos por componente</caption>
            <thead>
              <tr>
                <th scope="col">Componente</th>
                <th scope="col" class="text-end">Saldo atual</th>
                <th scope="col">Novo saldo</th>
              </tr>
            </thead>
            <tbody>
              @for (row of c.components; track row.component) {
                <tr>
                  <th scope="row">{{ componentLabels[row.component] }}</th>
                  <td class="text-end"><app-amount [value]="row.balance" /></td>
                  <td>
                    <div class="input-group input-group-sm">
                      <span class="input-group-text" aria-hidden="true">R$</span>
                      <input appInput appMoney [formControl]="valueOf(row.component)" [attr.aria-label]="'Novo saldo de ' + componentLabels[row.component]" />
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @let p = preview();
          <div class="alert d-flex gap-2" [class.alert-info]="p.mode === 'WITHIN_LIMIT'" [class.alert-warning]="p.mode === 'DUAL_CONTROL'" role="status" aria-live="polite">
            <app-icon [name]="p.mode === 'DUAL_CONTROL' ? 'person-check' : 'info-circle'" />
            <span>
              @if (!p.changed) {
                Altere ao menos um componente.
              } @else {
                Saldo de <app-amount [value]="c.openBalance" /> para <app-amount [value]="p.newOpenBalance" /> ({{ p.delta > 0 ? 'aumento' : 'redução' }} de
                <app-amount [value]="p.absDelta" />).
                {{
                  p.mode === 'WITHIN_LIMIT'
                    ? 'Redução até o limite da assessoria, sem nenhum componente aumentar: aplicada ao salvar.'
                    : 'Reduções acima do limite ou algum componente aumentando: vai para aprovação de outra pessoa e exige evidência ou contestação.'
                }}
              }
            </span>
          </div>
          <div class="row g-3">
            <app-field class="col-md-6" label="Motivo" error="Escolha o motivo.">
              <select appInput formControlName="reason" required>
                <option value="" disabled>Selecione</option>
                @for (r of reasons; track r.value) {
                  <option [ngValue]="r.value">{{ r.label }}</option>
                }
              </select>
            </app-field>
            <div class="col-md-6 app-control-offset">
              <input #file type="file" [accept]="accept" (change)="pick(file.files); file.value = ''" hidden />
              <button type="button" class="btn btn-outline-primary" (click)="file.click()">
                <app-icon name="paperclip" />
                {{ evidence() ? evidence()!.name : 'Anexar evidência (PDF ou imagem)' }}
              </button>
            </div>
          </div>
          <app-field class="mt-3" label="Descrição da correção" [hintEnd]="form.controls.note.value.length + ' / ' + noteMax" error="Descreva o que foi corrigido e por quê.">
            <textarea appInput formControlName="note" rows="3" [maxlength]="noteMax" required></textarea>
          </app-field>
          @if (data.disputeId) {
            <p class="app-muted mt-2 mb-0">
              <app-icon name="hammer" />
              Vinculada à contestação {{ data.disputeId.slice(0, 8).toUpperCase() }}: a pendência de ajuste da contestação é concluída quando a correção for aplicada.
            </p>
          }
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
          <button type="submit" class="btn btn-primary" [disabled]="busy() || c.blockers.length > 0 || !p.changed">
            {{ p.mode === 'WITHIN_LIMIT' ? 'Aplicar correção' : 'Pedir aprovação' }}
          </button>
        </div>
      </form>
    }
  `,
  styles: `
    .ta-table input {
      max-width: 160px;
    }
  `,
})
export class TitleAmountDialog {
  protected readonly data = inject<TitleAmountDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AmountAdjustmentView>>(DialogRef);
  private readonly api = inject(TitleAmountApi);
  private readonly notifications = inject(NotificationService);
  protected readonly componentLabels = LEDGER_COMPONENT_LABELS;
  protected readonly blockerLabels = AMOUNT_BLOCKER_LABELS;
  protected readonly reasons = Object.entries(AMOUNT_REASON_LABELS).map(([value, label]) => ({ value: value as AmountAdjustmentReason, label }));
  protected readonly noteMax = NOTE_MAX;
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly evidence = signal<File | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    reason: this.fb.control<AmountAdjustmentReason | ''>(this.data.disputeId ? 'DISPUTE_UPHELD' : '', [AppValidators.notBlank]),
    note: [this.data.note ?? '', [AppValidators.notBlank, Validators.maxLength(NOTE_MAX)]],
  });
  private readonly values = new Map<AmountComponent, FormControl<number | null>>();
  private readonly changes = signal(0);

  protected readonly context = rxResource<AmountAdjustmentContext, string>({
    params: () => this.data.titleId,
    stream: ({ params }) => this.api.context(params),
  });
  protected readonly ctx = computed(() => {
    const c = this.context.hasValue() ? this.context.value() : undefined;
    if (c && !this.values.size) {
      for (const row of c.components) {
        const control = new FormControl<number | null>(row.balance, { validators: [Validators.min(0)] });
        control.valueChanges.subscribe(() => this.changes.update((n) => n + 1));
        this.values.set(row.component, control);
      }
    }
    return c;
  });
  protected readonly preview = computed(() => {
    this.changes();
    const c = this.ctx();
    return adjustmentPreview(c?.components ?? [], this.targets(), c?.openBalance ?? 0, c?.approvalThreshold ?? 0);
  });

  protected valueOf(component: AmountComponent): FormControl<number | null> {
    return this.values.get(component)!;
  }

  protected pick(list: FileList | null): void {
    const file = list?.[0];
    if (!file) {
      return;
    }
    if (!EVIDENCE_ACCEPT.split(',').includes(file.type) || file.size > EVIDENCE_MAX) {
      this.notifications.error('A evidência deve ser PDF, PNG ou JPEG de até 20 MB.');
      return;
    }
    this.evidence.set(file);
  }

  private targets(): Partial<Record<AmountComponent, number>> {
    const out: Partial<Record<AmountComponent, number>> = {};
    this.values.forEach((control, component) => {
      if (control.value !== null) {
        out[component] = control.value;
      }
    });
    return out;
  }

  protected save(c: AmountAdjustmentContext): void {
    const p = this.preview();
    if (this.form.invalid || !p.changed) {
      this.form.markAllAsTouched();
      return;
    }
    if (p.mode === 'DUAL_CONTROL' && !this.evidence() && !this.data.disputeId) {
      this.error.set('Acima do limite ou com aumento de algum componente, anexe a evidência ou abra a correção pela contestação.');
      return;
    }
    const changed: Partial<Record<AmountComponent, number>> = {};
    for (const row of c.components) {
      const v = this.values.get(row.component)?.value;
      if (v !== null && v !== undefined && v !== row.balance) {
        changed[row.component] = v;
      }
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .request(this.data.titleId, { components: changed, reason: v.reason as AmountAdjustmentReason, note: v.note.trim(), disputeId: this.data.disputeId ?? null }, this.evidence())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (a) => {
          this.notifications.success(a.status === 'APPLIED' ? 'Correção aplicada ao título.' : 'Correção enviada para aprovação de outra pessoa.');
          this.ref.close(a);
        },
        error: (e) => this.error.set(userMessage(toApiError(e))),
      });
  }
}

