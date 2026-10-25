import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { map, of, startWith } from 'rxjs';
import { toApiError } from '../../core/http/problem';
import { NotificationService } from '../../core/notification/notification.service';
import { ConfirmService } from '../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { EntityPicker } from '../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { valueOr } from '../../shared/data/resource-value';
import { PageRequest } from '../../shared/models/page';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { applyServerErrors } from '../../shared/validation/server-errors';
import { CreditorLookup, PortfolioLookup } from '../organization/data/organization.lookups';
import { KnowledgeChallenge, PortalAdminApi, PortalConfigurationRequest, PortalSessionSummary, contrastWithWhite } from './data/portal-admin.api';
import { Icon } from '../../shared/components/icon/icon';
import { FORM_FIELD } from '../../shared/forms/field';

const DEFAULT_NOTICE =
  'Este portal é operado pela assessoria de cobrança contratada pelo credor. Usamos seu CPF/CNPJ, seus contatos ' +
  'cadastrados e os dados da dívida somente para identificar você, mostrar a dívida e registrar o acordo, com base no ' +
  'exercício regular de direitos e na proteção do crédito (LGPD, art. 7º, VI e X). Os acessos são registrados para sua ' +
  'segurança. Para exercer seus direitos de titular, fale com o encarregado pelo canal de atendimento.';

/** Configuração do portal de um credor, com prévia da marca, funil e sessões. */
@Component({
  selector: 'app-portal-config-edit',
  imports: [...FORM_FIELD, Icon,
    ReactiveFormsModule, RouterLink, DataTable, CellDef, EntityPicker, PageHeader, StatusChip, AppDatePipe,
  ],
  templateUrl: './portal-config-edit.html',
  styleUrl: './portal-config-edit.scss',
})
export class PortalConfigEdit {
  /** `novo` ou o id da configuração. */
  readonly id = input.required<string>();

  private readonly api = inject(PortalAdminApi);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  protected readonly creditorSource = inject(CreditorLookup);
  private readonly portfolioLookup = inject(PortfolioLookup);

  protected readonly isNew = computed(() => this.id() === 'novo');
  protected readonly config = rxResource({
    params: () => (this.isNew() ? null : this.id()),
    stream: ({ params }) => (params ? this.api.get(params) : of(undefined)),
  });
  protected readonly current = valueOr(this.config, undefined);
  protected readonly privacyHint = computed(() => {
    const version = this.current()?.privacyNoticeVersion;
    const base = 'Alterar o texto cria nova versão; os devedores confirmam de novo.';
    return version !== undefined ? `${base} Versão atual: ${version}.` : base;
  });
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly logoPreview = signal<string | null>(null);
  protected readonly sessionPage = signal<PageRequest>({ page: 0, size: 10 });

  protected readonly creditorControl = new FormControl<string[]>([], { nonNullable: true });
  protected readonly portfolioControl = new FormControl<string[]>([], { nonNullable: true });
  protected readonly form = inject(FormBuilder).nonNullable.group({
    slug: ['', [Validators.required, Validators.pattern(/^[a-z0-9]+(-[a-z0-9]+)*$/), Validators.minLength(3), Validators.maxLength(60)]],
    enabled: [false],
    displayName: ['', [Validators.required, Validators.maxLength(80)]],
    primaryColor: ['#1D4E89', [Validators.required, Validators.pattern(/^#[0-9a-fA-F]{6}$/)]],
    accentColor: ['#0B6E4F', [Validators.required, Validators.pattern(/^#[0-9a-fA-F]{6}$/)]],
    whatsapp: [true],
    sms: [false],
    email: [false],
    knowledgeChallenge: ['NONE' as KnowledgeChallenge],
    linkValidityHours: [48, [Validators.required, Validators.min(1), Validators.max(168)]],
    privacyNoticeText: [DEFAULT_NOTICE, [Validators.required, Validators.minLength(50), Validators.maxLength(8000)]],
    supportText: ['', [Validators.maxLength(300)]],
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue()), map(() => this.form.getRawValue())), {
    requireSync: true,
  });
  protected readonly creditorId = toSignal(
    this.creditorControl.valueChanges.pipe(startWith([] as string[]), map((ids) => ids[0] ?? null)),
    { initialValue: null },
  );
  protected readonly portfolioSource = this.portfolioLookup.source(() => this.creditorId() ?? this.current()?.creditorId ?? null);
  protected readonly primaryContrast = computed(() => contrastWithWhite(this.formValue().primaryColor));
  protected readonly accentContrast = computed(() => contrastWithWhite(this.formValue().accentColor));

  protected readonly funnel = rxResource({
    params: () => this.current()?.id ?? null,
    stream: ({ params }) => (params ? this.api.funnel(params, null, null) : of(undefined)),
  });
  protected readonly sessions = rxResource({
    params: () => (this.current()?.id ? { id: this.current()!.id, page: this.sessionPage() } : null),
    stream: ({ params }) => (params ? this.api.sessions(params.id, params.page) : of(undefined)),
  });
  protected readonly sessionRows = computed(() => this.sessions.value()?.content ?? []);
  protected readonly sessionTablePage = computed(() => {
    const p = this.sessions.value();
    return p ? { index: p.page, size: p.size, total: p.totalElements } : null;
  });
  protected readonly sessionColumns: TableColumn[] = [
    { key: 'createdAt', header: 'Início' },
    { key: 'method', header: 'Acesso' },
    { key: 'lastSeenAt', header: 'Último uso' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => {
      const c = this.current();
      if (!c) {
        return;
      }
      this.creditorControl.setValue([c.creditorId]);
      this.creditorControl.disable();
      this.portfolioControl.setValue(c.portfolioIds);
      this.form.reset({
        slug: c.slug, enabled: c.enabled, displayName: c.displayName, primaryColor: c.primaryColor, accentColor: c.accentColor,
        whatsapp: c.otpChannels.includes('WHATSAPP'), sms: c.otpChannels.includes('SMS'), email: c.otpChannels.includes('EMAIL'),
        knowledgeChallenge: c.knowledgeChallenge, linkValidityHours: c.linkValidityHours,
        privacyNoticeText: c.privacyNoticeText, supportText: c.supportText ?? '',
      });
      this.loadLogo(c.id, c.hasLogo);
    });
    inject(DestroyRef).onDestroy(() => this.revokePreview());
  }

  protected save(): void {
    this.form.markAllAsTouched();
    const v = this.form.getRawValue();
    const channels = [v.whatsapp && 'WHATSAPP', v.sms && 'SMS', v.email && 'EMAIL'].filter(Boolean) as string[];
    const creditorId = this.current()?.creditorId ?? this.creditorId();
    const problems: string[] = [];
    if (!creditorId) {
      problems.push('Escolha o credor.');
    }
    if (channels.length === 0) {
      problems.push('Escolha ao menos um canal para o código de acesso.');
    }
    if ((this.primaryContrast() ?? 0) < 4.5) {
      problems.push('A cor primária precisa de contraste de pelo menos 4,5:1 com texto branco.');
    }
    if ((this.accentContrast() ?? 0) < 3) {
      problems.push('A cor de destaque precisa de contraste de pelo menos 3:1 com o fundo branco.');
    }
    this.errors.set(problems);
    if (this.form.invalid || problems.length) {
      return;
    }
    const request: PortalConfigurationRequest = {
      creditorId, slug: v.slug, enabled: v.enabled, displayName: v.displayName, primaryColor: v.primaryColor.toUpperCase(),
      accentColor: v.accentColor.toUpperCase(), portfolioIds: this.portfolioControl.value, otpChannels: channels,
      knowledgeChallenge: v.knowledgeChallenge, linkValidityHours: v.linkValidityHours, privacyNoticeText: v.privacyNoticeText,
      supportText: v.supportText || null,
    };
    const c = this.current();
    this.saving.set(true);
    (c ? this.api.update(c.id, request) : this.api.create(request)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.notifications.success(c ? 'Portal atualizado.' : 'Portal configurado.');
        if (c) {
          this.config.reload();
        } else {
          void this.router.navigate(['/autonegociacao', saved.id]);
        }
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.errors.set(applyServerErrors(this.form, toApiError(e)));
      },
    });
  }

  protected onLogo(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const c = this.current();
    if (!file || !c) {
      return;
    }
    this.api.uploadLogo(c.id, file).subscribe({
      next: () => {
        this.notifications.success('Logotipo atualizado.');
        this.config.reload();
      },
    });
  }

  protected removeLogo(): void {
    const c = this.current();
    if (c) {
      this.api.removeLogo(c.id).subscribe({ next: () => this.config.reload() });
    }
  }

  protected revoke(s: PortalSessionSummary): void {
    this.confirm
      .confirm({ title: 'Encerrar a sessão do devedor?', message: 'O devedor precisará entrar de novo no portal.',
        confirmLabel: 'Encerrar sessão', destructive: true, reason: 'required', reasonMaxLength: 300 })
      .subscribe((reason) => this.api.revokeSession(s.id, reason).subscribe(() => this.sessions.reload()));
  }

  private loadLogo(id: string, hasLogo: boolean): void {
    this.revokePreview();
    if (!hasLogo) {
      return;
    }
    this.api.logo(id).subscribe({ next: (blob) => this.logoPreview.set(URL.createObjectURL(blob)), error: () => undefined });
  }

  private revokePreview(): void {
    const url = this.logoPreview();
    if (url) {
      URL.revokeObjectURL(url);
      this.logoPreview.set(null);
    }
  }
}
