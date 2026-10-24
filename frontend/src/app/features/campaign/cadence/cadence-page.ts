import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CampaignsApi } from '../data/campaign.api';
import { PublishedTemplates } from '../data/published-templates';
import { ACTION_ICONS, CHANNEL_LABELS, REPLY_LABELS } from '../data/campaign.labels';
import { CadenceDefinition, CadenceStep, CadenceView, ReplyTransition } from '../data/campaign.models';
import {
  MAX_STEPS,
  TemplateRef,
  cadenceProblems,
  checkTemplate,
  cloneDefinition,
  conditionText,
  durationText,
  emptyDefinition,
  newStep,
  nextStepCode,
  pickTemplate,
  sendsMessage,
  stepSummary,
  toServerDefinition,
  windowText,
} from '../data/campaign.rules';
import { CampaignTabs } from '../campaigns/campaign-tabs';
import { StepEditor, StepMove } from './step-editor';

const REPLIES = Object.keys(REPLY_LABELS) as ReplyTransition[];

/**
 * Régua versionada da campanha: editor visual das etapas em sequência (espera, janela, condição,
 * ação e resposta), validação contínua (inclusive contra os templates publicados), rascunho,
 * publicação de nova versão e linha do tempo das versões.
 */
@Component({
  selector: 'app-cadence-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe, CampaignTabs, StepEditor],
  templateUrl: './cadence-page.html',
  styleUrl: './cadence-page.scss',
})
export class CadencePage {
  readonly id = input.required<string>();

  private readonly api = inject(CampaignsApi);
  private readonly publishedTemplates = inject(PublishedTemplates);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly teamLookup = inject(TeamLookup);

  protected readonly replies = REPLIES;
  protected readonly replyLabels = REPLY_LABELS;
  protected readonly actionIcons = ACTION_ICONS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly maxSteps = MAX_STEPS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CAMPAIGN_MANAGE));

  protected readonly campaign = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.campaign, undefined);
  protected readonly cadenceList = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.cadences(params) });
  protected readonly versions = computed(() => (this.cadenceList.hasValue() ? [...this.cadenceList.value()!].sort((a, b) => b.versionNumber - a.versionNumber) : []));
  protected readonly draftVersion = computed(() => this.versions().find((v) => v.status === 'DRAFT') ?? null);
  protected readonly publishedVersion = computed(() => this.versions().find((v) => v.status === 'PUBLISHED') ?? null);

  /** Templates publicados do credor e da assessoria; nulo = sem permissão (validação só no servidor). */
  private readonly templateList = rxResource({
    params: () => this.current()?.creditorId,
    stream: ({ params }) => this.publishedTemplates.forCreditor(params),
  });
  protected readonly templates = computed<readonly TemplateRef[] | null>(() => (this.templateList.hasValue() ? (this.templateList.value() ?? null) : null));
  private readonly teamList = rxResource({ stream: () => this.teamLookup.search('').pipe(catchError(() => of([]))) });
  protected readonly teams = valueOr(this.teamList, []);
  protected readonly teamNames = computed(() => new Map(this.teams().map((t) => [t.value, t.label])));
  protected readonly publishers = nameMap(inject(UserNames), () => this.versions().map((v) => v.publishedBy), { batch: USER_NAMES_BATCH });

  /** Versão exibida (rascunho em edição por padrão). */
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed<CadenceView | null>(() => {
    const id = this.selectedId();
    return this.versions().find((v) => v.id === id) ?? this.draftVersion() ?? this.publishedVersion() ?? this.versions()[0] ?? null;
  });
  /** Definição em edição (cópia; o servidor só muda ao salvar). */
  protected readonly draft = signal<CadenceDefinition>(emptyDefinition());
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  /** Nova versão a partir da publicada, ainda não salva como rascunho. */
  protected readonly startingNew = signal(false);
  protected readonly saving = signal(false);
  protected readonly openStep = signal(0);
  protected readonly errors = signal<string[]>([]);

  protected readonly editable = computed(() => this.canManage() && this.current()?.status !== 'FINISHED' && (this.startingNew() || this.selected()?.status === 'DRAFT' || (!this.versions().length && this.cadenceList.hasValue())));

  protected readonly problems = computed(() => {
    this.revision();
    return cadenceProblems(this.draft());
  });
  /** Coerência com os templates publicados (erros impedem publicar; avisos de tipo). */
  protected readonly templateChecks = computed(() => {
    this.revision();
    const list = this.templates();
    const creditor = this.current()?.creditorId;
    if (!list || !creditor) {
      return this.draft().steps.map(() => ({ errors: [] as string[], warnings: [] as string[] }));
    }
    return this.draft().steps.map((s) => checkTemplate(s, sendsMessage(s.action) ? pickTemplate(list, s.action.templateCode, s.action.channel, creditor) : null));
  });
  protected readonly allProblems = computed(() => [...this.problems(), ...this.templateChecks().flatMap((c) => c.errors)]);
  protected readonly hasOffer = computed(() => {
    this.revision();
    return this.draft().steps.some((s) => s.action.type === 'OFFER');
  });

  constructor() {
    effect(() => {
      this.breadcrumbs.setCurrentLabel(this.current()?.name ?? null);
    });
    effect(() => {
      const v = this.selected();
      const loaded = this.cadenceList.hasValue();
      if (!loaded || this.startingNew()) {
        return;
      }
      const d = v ? cloneDefinition(v.definition) : emptyDefinition();
      if (!v && d.steps.length === 0) {
        d.steps.push(newStep(d.steps));
      }
      this.draft.set(d);
      this.dirty.set(false);
      this.openStep.set(0);
      this.revision.update((n) => n + 1);
    });
  }

  protected edited(): void {
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected stepProblems(index: number): string[] {
    const s = this.draft().steps[index];
    if (!s) {
      return [];
    }
    const prefix = new RegExp(`^Etapa ${index + 1}( \\(|:)`);
    return [...this.problems().filter((p) => prefix.test(p)), ...(this.templateChecks()[index]?.errors ?? [])];
  }

  protected stepWarnings(index: number): string[] {
    return this.templateChecks()[index]?.warnings ?? [];
  }

  protected summary(s: CadenceStep): string {
    return stepSummary(s, (id) => this.teamNames().get(id) ?? 'equipe');
  }

  protected conditions(s: CadenceStep): string {
    const items = conditionText(s);
    return items.length ? `se ${items.join(' e ')}` : '';
  }

  protected wait(s: CadenceStep, index: number): string {
    const d = durationText(s.waitMinutes);
    return index === 0 ? (d === 'imediatamente' ? 'Na inscrição' : `${d} após a inscrição`) : d === 'imediatamente' ? 'Logo depois' : `Aguarda ${d}`;
  }

  protected window(s: CadenceStep): string {
    return windowText(s.window);
  }

  protected reply(s: CadenceStep): string {
    const d = this.draft();
    const t = s.onReply ?? d.defaultOnReply ?? 'STOP';
    const label = REPLY_LABELS[t];
    const target = t === 'GOTO_STEP' ? ` ${s.onReply ? s.onReplyStepCode : d.defaultOnReplyStepCode}` : '';
    return `${s.onReply ? '' : 'padrão: '}${label.toLowerCase()}${target}`;
  }

  protected toggle(index: number): void {
    this.openStep.set(this.openStep() === index ? -1 : index);
  }

  protected addStep(): void {
    const d = this.draft();
    if (d.steps.length >= MAX_STEPS) {
      return;
    }
    d.steps.push(newStep(d.steps));
    this.openStep.set(d.steps.length - 1);
    this.edited();
  }

  protected moveStep(m: StepMove): void {
    const steps = this.draft().steps;
    const to = m.index + m.delta;
    if (to < 0 || to >= steps.length) {
      return;
    }
    [steps[m.index], steps[to]] = [steps[to], steps[m.index]];
    this.openStep.set(to);
    this.edited();
  }

  protected duplicateStep(index: number): void {
    const steps = this.draft().steps;
    if (steps.length >= MAX_STEPS) {
      return;
    }
    const copy = cloneDefinition({ ...this.draft(), steps: [steps[index]] }).steps[0];
    copy.code = nextStepCode(steps);
    steps.splice(index + 1, 0, copy);
    this.openStep.set(index + 1);
    this.edited();
  }

  protected removeStep(index: number): void {
    const d = this.draft();
    const s = d.steps[index];
    const targets = d.steps.filter((x) => x.onReply === 'GOTO_STEP' && x.onReplyStepCode === s.code).map((x) => x.code);
    this.confirm
      .confirm({
        title: 'Remover etapa',
        message: 'A etapa sai do rascunho. Nada muda nas inscrições em andamento até a publicação.',
        confirmLabel: 'Remover',
        destructive: true,
        details: [
          { label: 'Etapa', value: `${index + 1} · ${s.code}` },
          { label: 'Ação', value: this.summary(s) },
          ...(targets.length ? [{ label: 'Destino de resposta de', value: targets.join(', ') }] : []),
        ],
      })
      .subscribe(() => {
        d.steps.splice(index, 1);
        this.openStep.set(Math.min(index, d.steps.length - 1));
        this.edited();
      });
  }

  protected setDefaultReply(value: ReplyTransition): void {
    const d = this.draft();
    d.defaultOnReply = value;
    d.defaultOnReplyStepCode = value === 'GOTO_STEP' ? (d.defaultOnReplyStepCode ?? d.steps[0]?.code ?? null) : null;
    this.edited();
  }

  protected select(id: string): void {
    const go = () => {
      this.startingNew.set(false);
      this.selectedId.set(id);
    };
    if (!this.dirty()) {
      go();
      return;
    }
    this.confirm
      .confirm({ title: 'Descartar alterações', message: 'As alterações não salvas da régua serão perdidas.', confirmLabel: 'Descartar', destructive: true })
      .subscribe(go);
  }

  /** Começa a próxima versão a partir da publicada (ainda sem gravar). */
  protected newVersion(): void {
    const p = this.publishedVersion();
    if (!p) {
      return;
    }
    this.startingNew.set(true);
    this.draft.set(cloneDefinition(p.definition));
    this.dirty.set(true);
    this.openStep.set(0);
    this.revision.update((n) => n + 1);
  }

  protected discard(): void {
    this.startingNew.set(false);
    this.selectedId.set(this.selected()?.id ?? null);
    const v = this.selected();
    this.draft.set(v ? cloneDefinition(v.definition) : emptyDefinition());
    this.dirty.set(false);
    this.revision.update((n) => n + 1);
  }

  protected save(): void {
    const c = this.current();
    if (!c || this.saving()) {
      return;
    }
    if (this.problems().length) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    this.saving.set(true);
    this.errors.set([]);
    this.api
      .saveDraft(c.id, toServerDefinition(this.draft()))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.startingNew.set(false);
          this.selectedId.set(saved.id);
          this.cadenceList.reload();
          this.campaign.reload();
          this.notifications.success(`Rascunho da versão ${saved.versionNumber} salvo.`);
        },
        error: (err: unknown) => this.errors.set([userMessage(toApiError(err))]),
      });
  }

  protected publish(): void {
    const c = this.current();
    const v = this.draftVersion();
    if (!c || !v) {
      return;
    }
    if (this.dirty()) {
      this.notifications.error('Salve o rascunho antes de publicar.');
      return;
    }
    const steps = v.definition.steps;
    const channels = [...new Set(steps.filter((s) => sendsMessage(s.action) && s.action.channel).map((s) => CHANNEL_LABELS[s.action.channel!]))];
    const published = this.publishedVersion();
    this.confirm
      .confirm({
        title: `Publicar versão ${v.versionNumber}`,
        message: 'Novas inscrições passam a usar esta versão. Inscrições em andamento continuam na versão com que começaram.',
        confirmLabel: 'Publicar',
        confirmationText: 'PUBLICAR',
        details: [
          { label: 'Campanha', value: `${c.name} (${c.code})` },
          { label: 'Etapas', value: steps.map((s) => s.code).join(' → ') },
          { label: 'Canais', value: channels.length ? channels.join(', ') : 'nenhum envio de mensagem' },
          { label: 'Versão retirada', value: published ? `versão ${published.versionNumber}` : 'nenhuma (primeira publicação)' },
        ],
        warning: 'Versão publicada não pode ser alterada. O servidor confere os templates publicados e as variáveis de cada etapa.',
      })
      .pipe(switchMap(() => this.api.publishDraft(c.id)))
      .subscribe({
        next: (saved) => {
          this.selectedId.set(saved.id);
          this.cadenceList.reload();
          this.campaign.reload();
          this.notifications.success(`Versão ${saved.versionNumber} publicada.`);
        },
        error: (err: unknown) => this.errors.set([userMessage(toApiError(err))]),
      });
  }
}
