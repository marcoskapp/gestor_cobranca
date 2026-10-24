import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, finalize, of } from 'rxjs';
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
import { AuthorityApi } from '../../negotiation/data/negotiation.api';
import { ProfileView } from '../../negotiation/data/negotiation.models';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AssistantPoliciesApi } from '../data/assistant.api';
import { ASSISTANT_ERROR_LABELS, DAY_LABELS, DEFAULT_PROFILES, HANDOFF_REASON_LABELS, MANDATORY_TRIGGERS, MODE_HINTS, MODE_LABELS } from '../data/assistant.labels';
import { AssistantMode, DayOfWeek, PixInConversation, PolicySettings, PolicyView } from '../data/assistant.models';
import { cloneSettings, scopeOf, settingsProblems } from '../data/assistant.rules';
import { DisablePortfolioData, DisablePortfolioDialog } from './policy-dialogs';

const DAYS: DayOfWeek[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/**
 * Versão da política do assistente: rascunho editável com validação contínua (modo, canais,
 * horários, alçada, prompt, conversa, identidade, temas, gatilhos, mensagens, limites e avaliação),
 * publicação (imutável; aposenta a anterior do mesmo escopo), nova versão, aposentadoria e
 * desligamento imediato da carteira.
 */
@Component({
  selector: 'app-assistant-policy-detail',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    AppDatePipe,
    EmptyState,
    PageHeader,
    Skeleton,
    StatusChip,
  ],
  templateUrl: './assistant-policy-detail.html',
  styleUrl: '../assistant.scss',
})
export class AssistantPolicyDetail {
  readonly id = input.required<string>();

  private readonly api = inject(AssistantPoliciesApi);
  private readonly authorityApi = inject(AuthorityApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly modes = Object.entries(MODE_LABELS) as [AssistantMode, string][];
  protected readonly modeHints = MODE_HINTS;
  protected readonly modeLabels = MODE_LABELS;
  protected readonly days = DAYS;
  protected readonly dayLabels = DAY_LABELS;
  protected readonly reasons = HANDOFF_REASON_LABELS;
  protected readonly reasonCodes = Object.keys(HANDOFF_REASON_LABELS);
  protected readonly mandatory = MANDATORY_TRIGGERS;
  protected readonly defaultProfiles = DEFAULT_PROFILES;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.ASSISTANT_MANAGE));
  protected readonly canAssignAuthority = computed(() => this.auth.hasPermission(Permission.AUTHORITY_MANAGE));

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly policy = valueOr(this.resource, undefined);
  private readonly versionList = rxResource({ params: () => this.policy()?.id, stream: ({ params }) => this.api.versions(params) });
  protected readonly versions = computed(() => (this.versionList.hasValue() ? [...this.versionList.value()!].sort((a, b) => b.policyVersion - a.policyVersion) : []));
  private readonly promptList = rxResource({ stream: () => this.api.prompts().pipe(catchError(() => of([]))) });
  protected readonly prompts = valueOr(this.promptList, []);
  private readonly profileList = rxResource({ stream: () => this.authorityApi.profiles().pipe(catchError(() => of<ProfileView[]>([]))) });
  protected readonly profiles = valueOr(this.profileList, []);

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.policy()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.policy()?.portfolioId]);
  protected readonly users = nameMap(inject(UserNames), () => [this.policy()?.publishedBy], { batch: USER_NAMES_BATCH });

  protected readonly draft = signal<PolicySettings | null>(null);
  protected readonly name = signal('');
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly editable = computed(() => this.policy()?.status === 'DRAFT' && this.canManage());
  protected readonly problems = computed(() => {
    this.revision();
    const d = this.draft();
    return d ? settingsProblems(d) : [];
  });
  protected readonly hasDraft = computed(() => this.versions().some((v) => v.status === 'DRAFT'));
  protected readonly scope = computed(() => {
    const p = this.policy();
    if (!p) {
      return '';
    }
    const s = scopeOf(p);
    return s === 'TENANT' ? 'Toda a assessoria' : s === 'CREDITOR' ? this.creditors.name(p.creditorId) : `${this.creditors.name(p.creditorId)} › ${this.portfolios.name(p.portfolioId)}`;
  });
  protected readonly selectedProfile = computed(() => {
    this.revision();
    return this.profiles().find((p) => p.id === this.draft()?.authorityProfileId) ?? null;
  });

  constructor() {
    effect(() => {
      const p = this.policy();
      this.breadcrumbs.setCurrentLabel(p ? `${p.name} · v${p.policyVersion}` : null);
      if (p) {
        this.reset(p);
      }
    });
  }

  protected edited(): void {
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected set<K extends keyof PolicySettings>(key: K, value: PolicySettings[K]): void {
    const d = this.draft();
    if (d) {
      d[key] = value;
      this.edited();
    }
  }

  protected setNumber(key: 'maxTurns' | 'maxMisunderstandings' | 'maxIdentityAttempts', value: string | number | null): void {
    this.set(key, value === null || value === '' ? null : Number(value));
  }

  protected setFraction(key: 'minConfidence' | 'reviewSampleRate', percent: string | number | null): void {
    this.set(key, percent === null || percent === '' ? null : Number(String(percent).replace(',', '.')) / 100);
  }

  protected percentOf(value: number | null | undefined): string {
    return value === null || value === undefined ? '' : String(Math.round(value * 1000) / 10).replace('.', ',');
  }

  protected setLimit(key: 'maxOutputTokens' | 'maxTokensPerTurn' | 'maxToolCallsPerTurn' | 'timeoutSeconds' | 'maxCostPerConversation', value: string | number | null): void {
    const d = this.draft();
    if (d) {
      d.limits = { maxOutputTokens: null, maxTokensPerTurn: null, maxToolCallsPerTurn: null, timeoutSeconds: null, maxCostPerConversation: null, ...(d.limits ?? {}) };
      d.limits[key] = value === null || value === '' ? null : Number(String(value).replace(',', '.'));
      this.edited();
    }
  }

  protected setPixEnabled(enabled: boolean): void {
    const d = this.draft();
    if (d) {
      d.pixInConversation = { ...emptyPix(), ...(d.pixInConversation ?? {}), enabled };
      this.edited();
    }
  }

  protected setPix(key: 'maxAmount' | 'maxPerConversationPerDay' | 'expirationMinutes' | 'riskLookbackDays', value: string | number | null): void {
    const d = this.draft();
    if (d) {
      d.pixInConversation = { ...emptyPix(), ...(d.pixInConversation ?? {}) };
      d.pixInConversation[key] = value === null || value === '' ? null : decimalBr(String(value));
      this.edited();
    }
  }

  protected topicsText(list: string[] | null): string {
    return (list ?? []).join(', ');
  }

  protected setTopics(key: 'allowedTopics' | 'forbiddenTopics', text: string): void {
    this.set(
      key,
      text
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
    );
  }

  protected setPrompt(value: string): void {
    const [code, version] = value.split('@');
    const d = this.draft();
    if (d) {
      d.promptCode = code;
      d.promptVersion = Number(version);
      this.edited();
    }
  }

  // horários
  protected addWindow(): void {
    const d = this.draft();
    if (d) {
      d.serviceHours = [...(d.serviceHours ?? []), { days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'], start: '08:00:00', end: '20:00:00' }];
      this.edited();
    }
  }

  protected removeWindow(i: number): void {
    const d = this.draft();
    if (d?.serviceHours) {
      d.serviceHours.splice(i, 1);
      this.edited();
    }
  }

  protected setWindowDays(i: number, days: DayOfWeek[]): void {
    const w = this.draft()?.serviceHours?.[i];
    if (w) {
      w.days = DAYS.filter((d) => days.includes(d));
      this.edited();
    }
  }

  protected setWindowTime(i: number, field: 'start' | 'end', value: string): void {
    const w = this.draft()?.serviceHours?.[i];
    if (w) {
      w[field] = value && value.length === 5 ? `${value}:00` : value;
      this.edited();
    }
  }

  // gatilhos
  protected addTrigger(): void {
    const d = this.draft();
    if (d) {
      d.extraTriggers = [...(d.extraTriggers ?? []), { keyword: '', reasonCode: 'POLICY_TRIGGER' }];
      this.edited();
    }
  }

  protected removeTrigger(i: number): void {
    const d = this.draft();
    if (d?.extraTriggers) {
      d.extraTriggers.splice(i, 1);
      this.edited();
    }
  }

  protected setTrigger(i: number, field: 'keyword' | 'reasonCode', value: string): void {
    const t = this.draft()?.extraTriggers?.[i];
    if (t) {
      t[field] = value;
      this.edited();
    }
  }

  protected profileFor(reason: string): string {
    return this.draft()?.requiredProfiles?.[reason] ?? '';
  }

  protected setProfile(reason: string, value: string): void {
    const d = this.draft();
    if (d) {
      const map = { ...(d.requiredProfiles ?? {}) };
      if (value.trim()) {
        map[reason] = value.trim().toUpperCase();
      } else {
        delete map[reason];
      }
      d.requiredProfiles = map;
      this.edited();
    }
  }

  // ações
  protected discardChanges(): void {
    const p = this.policy();
    if (p) {
      this.reset(p);
    }
  }

  protected save(): void {
    const p = this.policy();
    const d = this.draft();
    if (!p || !d || this.saving()) {
      return;
    }
    if (this.problems().length || !this.name().trim()) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    this.saving.set(true);
    this.run(this.api.update(p.id, this.name().trim(), d).pipe(finalize(() => this.saving.set(false))), 'Rascunho salvo.');
  }

  protected publish(): void {
    const p = this.policy();
    if (!p) {
      return;
    }
    const current = this.versions().find((v) => v.status === 'PUBLISHED');
    const profile = this.selectedProfile();
    this.confirm
      .confirm({
        title: `Publicar ${p.name} v${p.policyVersion}`,
        message: 'A versão publicada não muda mais e vale a partir da próxima mensagem, inclusive nas conversas em andamento. A versão publicada anterior deste escopo é aposentada.',
        confirmLabel: 'Publicar',
        details: [
          { label: 'Escopo', value: this.scope() },
          { label: 'Modo', value: MODE_LABELS[p.settings.mode] },
          { label: 'Canais', value: (p.settings.channels ?? ['WHATSAPP']).join(', ') },
          { label: 'Alçada', value: profile ? `${profile.name} (${profile.code})` : p.settings.authorityProfileId ? 'perfil escolhido' : 'padrão ASSISTENTE_INICIAL' },
          { label: 'Prompt', value: `${p.promptCode} v${p.promptVersion}` },
          { label: 'Versão atual', value: current ? `v${current.policyVersion} · ${MODE_LABELS[current.mode]} (será aposentada)` : 'nenhuma' },
        ],
        warning: p.settings.authorityProfileId && !this.canAssignAuthority() ? 'Vincular um perfil de alçada exige também a permissão de gestão de alçadas: o servidor recusará se você não a tiver.' : undefined,
      })
      .subscribe(() => this.run(this.api.publish(p.id), `Versão ${p.policyVersion} publicada.`, true));
  }

  protected newVersion(): void {
    const p = this.policy();
    if (!p) {
      return;
    }
    this.api.newVersion(p.id).subscribe({
      next: (created) => {
        this.notifications.success(`Rascunho v${created.policyVersion} criado a partir da v${p.policyVersion}.`);
        void this.router.navigate(['/assistente/politicas', created.id]);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  protected retire(): void {
    const p = this.policy();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Aposentar política',
        message: 'Sem outra versão publicada mais ampla, o assistente deixa de atuar neste escopo e as conversas vão para humanos.',
        confirmLabel: 'Aposentar',
        destructive: true,
        reason: 'required',
        reasonMaxLength: 300,
        details: [
          { label: 'Escopo', value: this.scope() },
          { label: 'Versão', value: `v${p.policyVersion} · ${MODE_LABELS[p.mode]}` },
        ],
      })
      .subscribe((reason) => this.run(this.api.retire(p.id, reason), 'Política aposentada.', true));
  }

  protected disablePortfolio(): void {
    const p = this.policy();
    this.dialog
      .open<DisablePortfolioDialog, DisablePortfolioData, PolicyView>(DisablePortfolioDialog, { data: { creditorId: p?.creditorId ?? null, portfolioId: p?.portfolioId ?? null }, width: '600px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((off) => {
        if (off) {
          this.notifications.success('Assistente desligado na carteira.');
          void this.router.navigate(['/assistente/politicas', off.id]);
        }
      });
  }

  private reset(p: PolicyView): void {
    this.draft.set(cloneSettings(p.settings));
    this.name.set(p.name);
    this.dirty.set(false);
    this.revision.update((n) => n + 1);
  }

  private run(call: Observable<PolicyView>, message: string, reloadVersions = false): void {
    this.actionError.set(null);
    call.subscribe({
      next: (saved) => {
        this.resource.set(saved);
        if (reloadVersions) {
          this.versionList.reload();
        }
        this.notifications.success(message);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.actionError.set((e.code && ASSISTANT_ERROR_LABELS[e.code]) || userMessage(e));
  }
}

function emptyPix(): PixInConversation {
  return { enabled: false, maxAmount: null, maxPerConversationPerDay: null, expirationMinutes: null, riskLookbackDays: null };
}

/** "5.000,00" ou "5000.5" → número. */
function decimalBr(text: string): number {
  const t = text.trim();
  return Number(t.includes(',') ? t.replace(/\./g, '').replace(',', '.') : t);
}
