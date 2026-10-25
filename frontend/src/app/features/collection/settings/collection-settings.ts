import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CollectionSettingsApi } from '../data/collection.api';
import { SettingsRequest } from '../data/collection.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

/** Limites de `CollectionSettingsController.SettingsRequest`. */
export const SETTINGS_LIMITS = {
  promiseToleranceDays: { min: 0, max: 30 },
  promiseMaxDays: { min: 1, max: 180 },
  callbackToleranceMinutes: { min: 0, max: 1440 },
  disputeResponseDays: { min: 1, max: 90 },
} as const;

const range = (key: keyof typeof SETTINGS_LIMITS) => [Validators.required, Validators.min(SETTINGS_LIMITS[key].min), Validators.max(SETTINGS_LIMITS[key].max)];

/** Parâmetros da operação (tolerâncias e prazos). Valem para registros criados depois da alteração. */
@Component({
  selector: 'app-collection-settings',
  imports: [ReactiveFormsModule, EmptyState, PageHeader, Skeleton, AppDatePipe, Icon, ...FORM_FIELD],
  template: `
    <app-page-header title="Parâmetros da operação" subtitle="Tolerâncias de promessas e retornos e prazo de resposta das contestações." />
    @if (settings.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (settings.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar os parâmetros.">
        <button class="btn btn-outline-primary" type="button" (click)="settings.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else {
      <form class="app-card" [formGroup]="form" (ngSubmit)="save()" novalidate data-tour="settings-form">
        <div class="alert alert-info d-flex gap-2 align-items-start" role="note">
          <app-icon name="info-circle" />
          <span>
            Compromissos já assumidos não mudam: cada promessa, retorno e contestação guarda os prazos do momento em que foi registrado.
            @if (current(); as s) {
              {{ s.customized ? 'Última alteração em ' + (s.updatedAt | appDate) + '.' : 'Valendo os padrões da plataforma.' }}
            }
          </span>
        </div>
        <div class="app-form-grid gap-3">
          <app-field label="Tolerância da promessa (dias)" hint="Dias após a data prometida em que o pagamento ainda cumpre a promessa (0 a 30).">
            <input appInput type="number" formControlName="promiseToleranceDays" min="0" max="30" required />
          </app-field>
          <app-field label="Prazo máximo da promessa (dias)" hint="Até quantos dias à frente o operador pode marcar a data prometida (1 a 180).">
            <input appInput type="number" formControlName="promiseMaxDays" min="1" max="180" required />
          </app-field>
          <app-field label="Tolerância do retorno (minutos)" hint="Depois do horário combinado, o retorno passa a contar como atrasado (0 a 1.440).">
            <input appInput type="number" formControlName="callbackToleranceMinutes" min="0" max="1440" required />
          </app-field>
          <app-field label="Prazo de resposta da contestação (dias)" hint="Prazo padrão quando a contestação é aberta sem prazo informado (1 a 90).">
            <input appInput type="number" formControlName="disputeResponseDays" min="1" max="90" required />
          </app-field>
        </div>
        <div class="app-form-actions">
          <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">Salvar parâmetros</button>
        </div>
      </form>
    }
  `,
})
export class CollectionSettingsPage {
  private readonly api = inject(CollectionSettingsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly settings = rxResource({ stream: () => this.api.get() });
  protected readonly current = valueOr(this.settings, undefined);
  protected readonly saving = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    promiseToleranceDays: this.fb.control(2, range('promiseToleranceDays')),
    promiseMaxDays: this.fb.control(30, range('promiseMaxDays')),
    callbackToleranceMinutes: this.fb.control(30, range('callbackToleranceMinutes')),
    disputeResponseDays: this.fb.control(10, range('disputeResponseDays')),
  });

  constructor() {
    effect(() => {
      const s = this.current();
      if (s) {
        this.form.reset({
          promiseToleranceDays: s.promiseToleranceDays,
          promiseMaxDays: s.promiseMaxDays,
          callbackToleranceMinutes: s.callbackToleranceMinutes,
          disputeResponseDays: s.disputeResponseDays,
        });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: SettingsRequest = {
      promiseToleranceDays: Number(v.promiseToleranceDays),
      promiseMaxDays: Number(v.promiseMaxDays),
      callbackToleranceMinutes: Number(v.callbackToleranceMinutes),
      disputeResponseDays: Number(v.disputeResponseDays),
    };
    this.confirm
      .confirm({
        title: 'Alterar parâmetros da operação',
        message: 'Os novos valores valem para promessas, retornos e contestações registrados a partir de agora.',
        confirmLabel: 'Salvar',
        details: [
          { label: 'Tolerância da promessa', value: `${request.promiseToleranceDays} dia(s)` },
          { label: 'Prazo máximo da promessa', value: `${request.promiseMaxDays} dia(s)` },
          { label: 'Tolerância do retorno', value: `${request.callbackToleranceMinutes} min` },
          { label: 'Resposta da contestação', value: `${request.disputeResponseDays} dia(s)` },
        ],
      })
      .subscribe(() => {
        this.saving.set(true);
        this.api
          .update(request)
          .pipe(finalize(() => this.saving.set(false)))
          .subscribe((saved) => {
            this.settings.set(saved);
            this.notifications.success('Parâmetros salvos.');
          });
      });
  }
}
