import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { toHourMinute } from '../../../shared/models/dates';
import { DocumentPipe } from '../../../shared/pipes/document.pipe';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { TenantSettingsApi } from '../data/organization.api';
import { FORM_FIELD } from '../../../shared/forms/field';
import { Icon } from '../../../shared/components/icon/icon';

/** Configurações gerais da assessoria e janela de contato permitida (regras de comunicação). */
@Component({
  selector: 'app-tenant-settings',
  imports: [Icon, ...FORM_FIELD,
    ReactiveFormsModule,
    PageHeader,
    StatusChip,
    DocumentPipe,
  ],
  templateUrl: './tenant-settings.html',
})
export class TenantSettings {
  private readonly api = inject(TenantSettingsApi);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);

  protected readonly limits = Limits;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.ORG_SETTINGS_MANAGE));
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly tenant = rxResource({ stream: () => this.api.current() });
  protected readonly current = computed(() => (this.tenant.hasValue() ? this.tenant.value() : undefined));

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      legalName: ['', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]],
      tradeName: ['', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]],
      contactWindowStart: ['08:00', [AppValidators.notBlank]],
      contactWindowEnd: ['20:00', [AppValidators.notBlank]],
      contactOnSaturday: [false],
      contactOnSunday: [false],
    },
    {
      validators: (group) => {
        const start = group.get('contactWindowStart')?.value as string;
        const end = group.get('contactWindowEnd')?.value as string;
        return start && end && end <= start ? { window: true } : null;
      },
    },
  );

  constructor() {
    // preenche o formulário quando os dados chegam (inclusive após salvar)
    effect(() => {
      const t = this.current();
      if (!t) {
        return;
      }
      this.form.reset({
        legalName: t.legalName,
        tradeName: t.tradeName,
        contactWindowStart: toHourMinute(t.contactWindowStart),
        contactWindowEnd: toHourMinute(t.contactWindowEnd),
        contactOnSaturday: t.contactOnSaturday,
        contactOnSunday: t.contactOnSunday,
      });
      if (this.canManage()) {
        this.form.enable();
      } else {
        this.form.disable();
      }
    });
  }

  protected message(control: 'legalName' | 'tradeName'): string {
    return errorMessage(this.form.controls[control].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .update({
        legalName: v.legalName.trim(),
        tradeName: v.tradeName.trim(),
        contactWindowStart: `${v.contactWindowStart}:00`,
        contactWindowEnd: `${v.contactWindowEnd}:00`,
        contactOnSaturday: v.contactOnSaturday,
        contactOnSunday: v.contactOnSunday,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.notifications.success('Configurações salvas.');
          this.tenant.set(updated);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
