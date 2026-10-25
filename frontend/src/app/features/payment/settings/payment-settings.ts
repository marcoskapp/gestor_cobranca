import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { PaymentSettingsApi } from '../data/payment.api';
import { PaymentSettingsView } from '../data/payment.models';
import { parseMoney } from '../data/payment.rules';

/**
 * Parâmetros de pagamentos da assessoria: limite para segunda aprovação de pagamentos
 * informados, validade do Pix e tolerância de datas na conciliação. Alteração exige
 * `ORG_SETTINGS_MANAGE` e acesso a todos os credores; os demais perfis só consultam.
 */
@Component({
  selector: 'app-payment-settings',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, AppDatePipe, EmptyState, PageHeader, Skeleton],
  template: `
    <app-page-header title="Parâmetros de pagamentos" subtitle="Regras de risco e de validade aplicadas a toda a assessoria." />
    @if (resource.isLoading() && !settings()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar os parâmetros." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (settings(); as s) {
      <form class="app-card ps-card" [formGroup]="form" (ngSubmit)="save()" novalidate data-tour="payment-settings">
        <p class="app-muted ps-small">{{ s.customized ? 'Personalizados' + (s.updatedAt ? ' em ' + (s.updatedAt | appDate) : '') : 'Valem os padrões da plataforma.' }}{{ canEdit() ? '' : ' Somente leitura para o seu perfil.' }}</p>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <div class="app-form-grid">
          <app-field label="Limite para segunda aprovação" hint="Pagamentos informados acima deste valor esperam outra pessoa." error="Valor inválido.">
            <div class="input-group">
              <span class="input-group-text">R$</span>
              <input appInput formControlName="threshold" inputmode="decimal" />
            </div>
          </app-field>
          <app-field label="Validade do Pix imediato (horas)" hint="De 1 hora a 30 dias (720 h)." error="Entre 1 e 720 horas.">
            <input appInput type="number" formControlName="pixHours" min="1" max="720" step="1" />
          </app-field>
          <app-field label="Validade após o vencimento (dias)" hint="Pix com vencimento: até quando aceita pagamento atrasado." error="Entre 0 e 60 dias.">
            <input appInput type="number" formControlName="dueDays" min="0" max="60" step="1" />
          </app-field>
          <app-field label="Tolerância de datas na conciliação (dias)" hint="Diferença aceita entre o extrato e o pagamento." error="Entre 0 e 15 dias.">
            <input appInput type="number" formControlName="toleranceDays" min="0" max="15" step="1" />
          </app-field>
        </div>
        @if (canEdit()) {
          <div class="app-form-actions">
            <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">Salvar parâmetros</button>
          </div>
        }
      </form>
    }
  `,
  styles: `
    .ps-card {
      max-width: 880px;
    }
    .ps-small {
      margin: 0 0 12px;
      font-size: var(--app-text-sm);
    }
  `,
})
export class PaymentSettings {
  private readonly api = inject(PaymentSettingsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly canEdit = computed(() => this.auth.hasPermission(Permission.ORG_SETTINGS_MANAGE) && (this.auth.user()?.allCreditors ?? false));
  protected readonly resource = rxResource({ stream: () => this.api.get() });
  protected readonly settings = valueOr<PaymentSettingsView | undefined>(this.resource, undefined);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    threshold: ['', [(c: AbstractControl) => (parseMoney(c.value as string) === null ? { money: true } : null)]],
    pixHours: [24, [Validators.required, Validators.min(1), Validators.max(720)]],
    dueDays: [30, [Validators.required, Validators.min(0), Validators.max(60)]],
    toleranceDays: [3, [Validators.required, Validators.min(0), Validators.max(15)]],
  });

  constructor() {
    effect(() => {
      const s = this.settings();
      if (s) {
        this.form.reset({
          threshold: Number(s.externalReviewThreshold).toFixed(2).replace('.', ','),
          pixHours: Math.round(s.pixExpirationSeconds / 3600),
          dueDays: s.pixDueValidityDays,
          toleranceDays: s.reconciliationDateToleranceDays,
        });
      }
      if (this.canEdit()) {
        this.form.enable({ emitEvent: false });
      } else {
        this.form.disable({ emitEvent: false });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const threshold = parseMoney(v.threshold)!;
    this.confirm
      .confirm({
        title: 'Salvar parâmetros de pagamentos',
        message: 'Valem para toda a assessoria. O novo limite vale para pagamentos informados a partir de agora.',
        confirmLabel: 'Salvar',
        details: [
          { label: 'Segunda aprovação acima de', value: formatAmount(threshold) },
          { label: 'Validade do Pix imediato', value: `${v.pixHours} h` },
          { label: 'Validade após o vencimento', value: `${v.dueDays} dia(s)` },
          { label: 'Tolerância na conciliação', value: `${v.toleranceDays} dia(s)` },
        ],
      })
      .subscribe(() => {
        this.saving.set(true);
        this.error.set(null);
        this.api
          .update({ externalReviewThreshold: threshold, pixExpirationSeconds: v.pixHours * 3600, pixDueValidityDays: v.dueDays, reconciliationDateToleranceDays: v.toleranceDays })
          .pipe(finalize(() => this.saving.set(false)))
          .subscribe({
            next: (saved) => {
              this.notifications.success('Parâmetros salvos.');
              this.resource.set(saved);
            },
            error: (err: unknown) => this.error.set(userMessage(toApiError(err))),
          });
      });
  }
}
