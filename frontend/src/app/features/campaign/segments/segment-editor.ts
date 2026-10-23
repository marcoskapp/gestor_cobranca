import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, debounceTime, finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { sameValue } from '../../../shared/data/same-value';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { SegmentsApi } from '../data/campaign.api';
import { SegmentCriteria, SegmentView } from '../data/campaign.models';
import { codeProblem, count, criteriaProblems, criteriaSummary, emptyCriteria, normalizeCriteria, suggestCode } from '../data/campaign.rules';
import { CriteriaEditor } from './criteria-editor';

/**
 * Construtor de segmento: filtros combináveis com contagem prévia (casos, devedores e saldo) a
 * cada alteração válida, salvar, duplicar e ativar/desativar. Segmento sem credor vale para toda
 * a assessoria (exige acesso a todos os credores).
 */
@Component({
  selector: 'app-segment-editor',
  imports: [FormsModule, ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule, MatTooltipModule, Amount, EmptyState, EntityPicker, PageHeader, Skeleton, StatusChip, AppDatePipe, CriteriaEditor],
  templateUrl: './segment-editor.html',
  styleUrl: './segment-editor.scss',
})
export class SegmentEditor {
  /** Segmento aberto (ausente em `/novo`). */
  readonly id = input<string>();
  /** `?copiar=<id>`: novo segmento a partir de outro. */
  readonly copiar = input<string>();

  private readonly api = inject(SegmentsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.SEGMENT_MANAGE));
  /** A prévia de filtros não salvos exige gestão de segmento ou de campanha. */
  private readonly canPreview = computed(() => this.auth.hasAnyPermission([Permission.SEGMENT_MANAGE, Permission.CAMPAIGN_MANAGE]));
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  protected readonly source = rxResource({
    params: () => this.id() ?? this.copiar(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly loaded = valueOr(this.source, undefined);
  /** Segmento salvo em edição (nulo na criação, inclusive ao duplicar). */
  protected readonly segment = signal<SegmentView | null>(null);
  protected readonly isNew = computed(() => !this.id());
  protected readonly editable = computed(() => this.canManage() && (this.isNew() || !!this.segment()));

  protected readonly creditor = new FormControl<string[]>([], { nonNullable: true });
  private readonly creditorValue = toSignal(this.creditor.valueChanges, { initialValue: [] as string[] });
  protected readonly creditorId = computed(() => (this.isNew() ? (this.creditorValue()[0] ?? null) : (this.segment()?.creditorId ?? null)));
  protected readonly code = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly criteria = signal<SegmentCriteria>(emptyCriteria());
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  private codeTouched = false;

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.segment()?.creditorId]);
  protected readonly portfolioNames = nameMap(inject(PortfolioNames), () => {
    this.revision();
    return this.criteria().portfolioIds ?? [];
  });

  protected readonly problems = computed(() => {
    this.revision();
    const list = criteriaProblems(this.criteria());
    if (this.isNew()) {
      const code = codeProblem(this.code());
      if (code) list.unshift(code);
      if (!this.creditorId() && !this.allCreditors()) list.unshift('Escolha o credor: segmentos da assessoria exigem acesso a todos os credores.');
    }
    if (!this.name().trim() || this.name().length > 120) list.unshift('Informe o nome do segmento (até 120 caracteres).');
    if (this.description().length > 500) list.push('Descrição com até 500 caracteres.');
    return list;
  });
  protected readonly summary = computed(() => {
    this.revision();
    return criteriaSummary(this.criteria(), (id) => this.portfolioNames.name(id));
  });

  /** Filtros válidos e estáveis por 500 ms disparam a contagem prévia. */
  private readonly previewKey = computed(
    () => {
      this.revision();
      const criteria = normalizeCriteria(this.criteria());
      return criteriaProblems(criteria).length ? null : { creditorId: this.creditorId(), criteria };
    },
    { equal: sameValue },
  );
  private readonly debouncedKey = toSignal(toObservable(this.previewKey).pipe(debounceTime(500)), { initialValue: null });
  protected readonly preview = rxResource({
    params: () => {
      const key = this.debouncedKey();
      if (!key) return undefined;
      if (this.canPreview()) return { kind: 'preview' as const, ...key };
      const saved = this.segment();
      // sem gestão: só a contagem do segmento salvo (filtros não editáveis)
      return saved ? { kind: 'count' as const, id: saved.id, creditorId: null, criteria: key.criteria } : undefined;
    },
    stream: ({ params }) =>
      params.kind === 'preview' ? this.api.preview({ creditorId: params.creditorId, portfolioId: null, criteria: params.criteria }) : params.id ? this.api.count(params.id, null, null) : EMPTY,
  });
  protected readonly audience = valueOr(this.preview, undefined);
  protected readonly previewError = computed(() => {
    if (this.preview.status() !== 'error') return null;
    const error = toApiError(this.preview.error());
    return error.status === 403 || error.status === 404
      ? 'Sem acesso para contar este público. Segmentos da assessoria exigem acesso a todos os credores.'
      : userMessage(error);
  });
  protected readonly count = count;

  constructor() {
    effect(() => {
      const s = this.loaded();
      if (!s) {
        return;
      }
      const copy = this.isNew();
      this.segment.set(copy ? null : s);
      this.breadcrumbs.setCurrentLabel(copy ? null : s.name);
      this.code.set(copy ? `${s.code}_COPIA`.slice(0, 60) : s.code);
      this.name.set(copy ? `Cópia de ${s.name}`.slice(0, 120) : s.name);
      this.description.set(s.description ?? '');
      this.criteria.set(normalizeCriteria(s.criteria));
      if (copy) {
        this.creditor.setValue(s.creditorId ? [s.creditorId] : []);
        this.dirty.set(true);
      } else {
        this.dirty.set(false);
      }
      this.revision.update((n) => n + 1);
    });
    this.creditor.valueChanges.subscribe(() => this.edited());
  }

  protected edited(): void {
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected setName(value: string): void {
    this.name.set(value);
    if (this.isNew() && !this.codeTouched) {
      this.code.set(suggestCode(value));
    }
    this.edited();
  }

  protected setCode(value: string): void {
    this.codeTouched = true;
    this.code.set(value.toUpperCase().replace(/\s+/g, '_'));
    this.edited();
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    if (this.problems().length) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    const criteria = normalizeCriteria(this.criteria());
    const description = this.description().trim() || null;
    const saved = this.segment();
    const request = saved
      ? this.api.update(saved.id, { name: this.name().trim(), description, criteria })
      : this.api.create({ creditorId: this.creditorId(), code: this.code(), name: this.name().trim(), description, criteria });
    this.saving.set(true);
    this.errors.set([]);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (result) => {
        this.dirty.set(false);
        if (saved) {
          this.source.set(result);
          this.notifications.success('Segmento salvo.');
        } else {
          this.notifications.success('Segmento criado.');
          void this.router.navigate(['/campanhas/segmentos', result.id]);
        }
      },
      error: (err: unknown) => this.errors.set([userMessage(toApiError(err))]),
    });
  }

  protected discard(): void {
    const s = this.loaded();
    if (s && !this.isNew()) {
      this.source.set({ ...s });
    }
  }

  protected duplicate(): void {
    const s = this.segment();
    if (s) {
      void this.router.navigate(['/campanhas/segmentos/novo'], { queryParams: { copiar: s.id } });
    }
  }

  protected toggleActive(): void {
    const s = this.segment();
    if (!s) {
      return;
    }
    const activate = !s.active;
    const audience = this.audience();
    (activate
      ? this.confirm.confirm({
          title: 'Ativar segmento',
          message: 'Campanhas do credor passam a poder usar este segmento.',
          confirmLabel: 'Ativar',
          details: [{ label: 'Segmento', value: `${s.name} (${s.code})` }],
        })
      : this.confirm.confirm({
          title: 'Desativar segmento',
          message: 'O segmento deixa de ser oferecido para novas campanhas e campanhas que o usam não podem ser ativadas ou reativadas.',
          confirmLabel: 'Desativar',
          destructive: true,
          details: [
            { label: 'Segmento', value: `${s.name} (${s.code})` },
            { label: 'Público atual', value: audience ? `${count(audience.debtors)} devedores em ${count(audience.cases)} casos` : 'não calculado' },
          ],
        })
    ).subscribe(() =>
      this.api.setActive(s.id, activate).subscribe((result) => {
        this.source.set(result);
        this.notifications.success(activate ? 'Segmento ativado.' : 'Segmento desativado.');
      }),
    );
  }
}
