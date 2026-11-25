import { Component, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
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
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { CONTACT_NOVELTY_ANCHOR, ContactNoveltySection } from '../../debt/settings/contact-novelty-section';

/**
 * Configurações da assessoria, em seções conforme a permissão (seção sem permissão fica oculta):
 * - identificação, dados gerais e janela de contato permitida (regras de comunicação): `ORG_SETTINGS_MANAGE`;
 * - contatos recebidos (janela do selo "Novo" do contato que chega numa nova carga): `DEBTOR_MANAGE`, a mesma que o
 *   servidor exige; âncora `#contatos-recebidos` (para onde redireciona o antigo `/devedores/contatos-recebidos`).
 * A rota aceita qualquer das duas; o servidor confere cada uma.
 */
@Component({
  selector: 'app-tenant-settings',
  imports: [Icon, EmptyState, Skeleton, ...FORM_FIELD,
    ReactiveFormsModule,
    PageHeader,
    StatusChip,
    DocumentPipe,
    ContactNoveltySection,
  ],
  templateUrl: './tenant-settings.html',
})
export class TenantSettings {
  private readonly api = inject(TenantSettingsApi);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);
  private readonly injector = inject(Injector);

  protected readonly limits = Limits;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.ORG_SETTINGS_MANAGE));
  protected readonly canManageContactNovelty = computed(() => this.auth.hasPermission(Permission.DEBTOR_MANAGE));
  /** O subtítulo descreve só as seções que a pessoa vê. */
  protected readonly subtitle = computed(() => {
    const parts = [
      ...(this.canManage() ? ['dados cadastrais', 'janela de contato com devedores'] : []),
      ...(this.canManageContactNovelty() ? ['prazo do selo “Novo” dos contatos recebidos'] : []),
    ];
    const joined = parts.length > 1 ? `${parts.slice(0, -1).join(', ')} e ${parts.at(-1)}` : (parts[0] ?? '');
    return joined ? `${joined.charAt(0).toUpperCase()}${joined.slice(1)}.` : '';
  });
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  // sem ORG_SETTINGS_MANAGE a seção não aparece: nem busca os dados da assessoria
  protected readonly tenant = rxResource({ params: () => (this.canManage() ? true : undefined), stream: () => this.api.current() });
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private scrolledTo: string | null = null;
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
    });

    // link para uma seção (`#contatos-recebidos`): rola até ela quando o que vem antes já carregou, para não deslocar
    effect(() => {
      const anchor = this.fragment();
      const above = this.canManage() && this.tenant.isLoading();
      if (anchor !== CONTACT_NOVELTY_ANCHOR) {
        this.scrolledTo = null;
      } else if (this.canManageContactNovelty() && !above && this.scrolledTo !== anchor) {
        this.scrolledTo = anchor; // uma vez por link (recarregar os dados depois não puxa a página de volta)
        untracked(() => this.scrollTo(anchor));
      }
    });
  }

  private scrollTo(anchor: string): void {
    afterNextRender(
      () => {
        const heading = this.host.nativeElement.querySelector<HTMLElement>(`#${anchor}`);
        heading?.scrollIntoView?.({ block: 'start' });
        heading?.focus({ preventScroll: true });
      },
      { injector: this.injector },
    );
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
