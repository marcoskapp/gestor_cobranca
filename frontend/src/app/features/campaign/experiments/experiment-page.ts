import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CampaignTabs } from '../campaigns/campaign-tabs';
import { CampaignsApi, ExperimentsApi } from '../data/campaign.api';
import { CONTROL_ARM, METRIC_HINTS, METRIC_LABELS } from '../data/campaign.labels';
import { ExperimentInput, ExperimentMetric, ExperimentView } from '../data/campaign.models';
import { PublishedTemplates } from '../data/published-templates';
import { allocation, cloneExperiment, count, experimentProblems, newExperiment, toServerExperiment } from '../data/campaign.rules';
import { ExperimentResultsView } from './experiment-results';
import { VariantEditor } from './variant-editor';

const METRICS = Object.keys(METRIC_LABELS) as ExperimentMetric[];

function toInput(e: ExperimentView): ExperimentInput {
  return cloneExperiment({
    name: e.name,
    hypothesis: e.hypothesis,
    primaryMetric: e.primaryMetric,
    controlShare: e.controlShare,
    variants: e.variants,
    observationDays: e.observationDays,
    minSamplePerArm: e.minSamplePerArm,
  });
}

/**
 * Testes A/B da campanha: configuração das variantes e do grupo de controle (rascunho), início com
 * variantes congeladas, resultados por braço com IC de 95% e encerramento com motivo.
 */
@Component({
  selector: 'app-experiment-page',
  imports: [FormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, Icon, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe, CampaignTabs, ExperimentResultsView, VariantEditor],
  templateUrl: './experiment-page.html',
  styleUrl: './experiment-page.scss',
})
export class ExperimentPage {
  readonly id = input.required<string>();

  private readonly campaigns = inject(CampaignsApi);
  private readonly api = inject(ExperimentsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly publishedTemplates = inject(PublishedTemplates);

  protected readonly metrics = METRICS;
  protected readonly metricLabels = METRIC_LABELS;
  protected readonly metricHints = METRIC_HINTS;
  protected readonly control = CONTROL_ARM;
  protected readonly count = count;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.EXPERIMENT_MANAGE));

  protected readonly campaign = rxResource({ params: () => this.id(), stream: ({ params }) => this.campaigns.get(params) });
  protected readonly current = valueOr(this.campaign, undefined);
  private readonly cadenceRes = rxResource({
    params: () => this.current()?.publishedCadenceId ?? undefined,
    stream: ({ params }) => this.campaigns.cadence(params),
  });
  protected readonly publishedSteps = computed(() => (this.cadenceRes.hasValue() ? this.cadenceRes.value()!.definition.steps : null));
  private readonly templateRes = rxResource({
    params: () => this.current()?.creditorId,
    stream: ({ params }) => this.publishedTemplates.forCreditor(params),
  });
  protected readonly templates = computed(() => (this.templateRes.hasValue() ? (this.templateRes.value() ?? null) : null));

  protected readonly list = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.list(params) });
  protected readonly experiments = computed(() => (this.list.hasValue() ? [...this.list.value()!].sort((a, b) => b.createdAt.localeCompare(a.createdAt)) : []));
  protected readonly openExperiment = computed(() => this.experiments().find((e) => e.status !== 'STOPPED') ?? null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed<ExperimentView | null>(() => {
    const id = this.selectedId();
    return this.experiments().find((e) => e.id === id) ?? this.openExperiment() ?? this.experiments()[0] ?? null;
  });

  /** Rascunho em edição (novo ou o selecionado em DRAFT). */
  protected readonly draft = signal<ExperimentInput | null>(null);
  protected readonly creating = signal(false);
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);

  protected readonly editable = computed(() => this.canManage() && this.current()?.status !== 'FINISHED' && (this.creating() || this.selected()?.status === 'DRAFT'));
  protected readonly problems = computed(() => {
    this.revision();
    const d = this.draft();
    return d ? experimentProblems(d, this.publishedSteps()) : [];
  });
  protected readonly shares = computed(() => {
    this.revision();
    const d = this.draft();
    return d ? allocation(d.controlShare, d.variants) : [];
  });
  protected readonly canCreate = computed(() => this.canManage() && !this.openExperiment() && !this.creating() && this.current()?.status !== 'FINISHED');

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.name ?? null));
    effect(() => {
      const e = this.selected();
      if (this.creating()) {
        return;
      }
      this.draft.set(e ? toInput(e) : null);
      this.dirty.set(false);
      this.revision.update((n) => n + 1);
    });
  }

  protected edited(): void {
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected percent(value: number): string {
    return `${value.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%`;
  }

  protected create(): void {
    this.creating.set(true);
    const input = newExperiment();
    input.name = `Teste A/B · ${this.current()?.name ?? ''}`.slice(0, 120);
    this.draft.set(input);
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected cancelCreate(): void {
    this.creating.set(false);
    this.selectedId.set(this.selected()?.id ?? null);
    const e = this.selected();
    this.draft.set(e ? toInput(e) : null);
    this.dirty.set(false);
  }

  protected select(id: string): void {
    this.creating.set(false);
    this.selectedId.set(id);
  }

  protected addVariant(): void {
    const d = this.draft();
    if (!d || d.variants.length >= 10) {
      return;
    }
    const used = new Set(d.variants.map((v) => v.code));
    const code = 'ABCDEFGHIJKLMNOP'.split('').find((c) => !used.has(c)) ?? `V${d.variants.length + 1}`;
    d.variants.push({ code, name: `Variante ${code}`, weight: 1, overrides: [] });
    this.edited();
  }

  protected removeVariant(index: number): void {
    this.draft()?.variants.splice(index, 1);
    this.edited();
  }

  protected save(): void {
    const d = this.draft();
    const c = this.current();
    if (!d || !c || this.saving()) {
      return;
    }
    if (this.problems().length) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    const body = toServerExperiment(d);
    const existing = this.creating() ? null : this.selected();
    this.saving.set(true);
    this.errors.set([]);
    (existing ? this.api.update(existing.id, body) : this.api.create(c.id, body)).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.creating.set(false);
        this.selectedId.set(saved.id);
        this.list.reload();
        this.notifications.success(existing ? 'Teste A/B salvo.' : 'Teste A/B criado como rascunho.');
      },
      error: (err: unknown) => this.errors.set([userMessage(toApiError(err))]),
    });
  }

  protected start(): void {
    const e = this.selected();
    const c = this.current();
    if (!e || !c || this.dirty()) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Iniciar teste A/B',
        message: 'Os próximos devedores inscritos são sorteados entre os braços e ficam sempre no mesmo braço. Depois de iniciado, o teste não pode ser alterado.',
        confirmLabel: 'Iniciar teste',
        confirmationText: 'INICIAR',
        details: [
          { label: 'Teste', value: e.name },
          { label: 'Repartição', value: allocation(e.controlShare, e.variants).map((a) => `${a.label}: ${this.percent(a.percent)}`).join(' · ') },
          { label: 'Métrica principal', value: METRIC_LABELS[e.primaryMetric] },
          { label: 'Horizonte e amostra', value: `${e.observationDays} dias · mínimo de ${count(e.minSamplePerArm)} devedores por braço` },
          { label: 'Régua', value: c.publishedCadenceVersion ? `versão ${c.publishedCadenceVersion} (variantes conferidas contra ela)` : 'sem versão publicada' },
        ],
        warning: e.controlShare > 0 ? `O grupo de controle (${this.percent(e.controlShare)}) não recebe nenhuma ação da campanha durante o horizonte.` : 'Sem grupo de controle: a comparação é entre as variantes.',
      })
      .pipe(switchMap(() => this.api.start(e.id)))
      .subscribe({
        next: () => {
          this.list.reload();
          this.notifications.success('Teste A/B iniciado.');
        },
        error: (err: unknown) => this.errors.set([userMessage(toApiError(err))]),
      });
  }

  protected stop(): void {
    const e = this.selected();
    if (!e) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Encerrar teste A/B',
        message: 'Novos devedores deixam de ser sorteados e todos passam a seguir a régua publicada. Os resultados continuam disponíveis.',
        confirmLabel: 'Encerrar teste',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo e decisão (ex.: variante B adotada)',
        reasonMaxLength: 300,
        confirmationText: 'ENCERRAR',
        details: [
          { label: 'Teste', value: e.name },
          { label: 'Em execução desde', value: e.startedAt ? new Date(e.startedAt).toLocaleDateString('pt-BR') : '—' },
        ],
        warning: 'Esta ação não pode ser desfeita: para testar de novo, crie outro teste.',
      })
      .pipe(switchMap((reason) => this.api.stop(e.id, reason)))
      .subscribe(() => {
        this.list.reload();
        this.notifications.success('Teste A/B encerrado.');
      });
  }
}
