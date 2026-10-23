import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { finalize, map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { nameMap } from '../../../shared/data/name-map';
import { ContactPolicyApi } from '../data/compliance.api';
import { CHANNEL_LABELS, RULE_SOURCE_LABELS, WEEK_DAYS, WINDOW_SOURCE_LABELS, weekDaysText } from '../data/compliance.labels';
import { EffectiveChannelRule, PolicyView, WeekDay } from '../data/compliance.models';
import { RuleDraft, draftFrom, draftProblems, limitsText, toRuleInputs, windowText } from './policy-draft';

/** Início de vigência a partir da data e hora escolhidas; nulo = agora. */
export function effectiveFromInstant(date: Date | null, time: string): string | null {
  if (!date) {
    return null;
  }
  const [h, m] = (time || '00:00').split(':').map(Number);
  return new Date(date.getFullYear(), date.getMonth(), date.getDate(), h || 0, m || 0).toISOString();
}

/**
 * Política de contato versionada (RB02): regras vigentes por canal, rascunho da próxima versão
 * e histórico de versões publicadas. Publicar cria uma versão imutável; não há edição de versão.
 */
@Component({
  selector: 'app-contact-policy',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatDatepickerModule,
    MatExpansionModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    DataTable,
    CellDef,
    EmptyState,
    EntityPicker,
    PageHeader,
    Skeleton,
    AppDatePipe,
  ],
  templateUrl: './contact-policy.html',
  styleUrl: '../compliance.scss',
})
export class ContactPolicyPage {
  private readonly api = inject(ContactPolicyApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly ruleSources = RULE_SOURCE_LABELS;
  protected readonly windowSources = WINDOW_SOURCE_LABELS;
  protected readonly weekDays = WEEK_DAYS;
  protected readonly weekDaysText = weekDaysText;
  protected readonly windowText = windowText;
  protected readonly limitsText = limitsText;

  /** Escopo: política padrão da assessoria ou de um credor (prevalece canal a canal). */
  protected readonly scopeKind = signal<'TENANT' | 'CREDITOR'>('TENANT');
  protected readonly creditorControl = new FormControl<string[]>([], { nonNullable: true });
  private readonly creditorValue = toSignal(
    this.creditorControl.valueChanges.pipe(
      startWith([] as string[]),
      map((ids) => ids[0] ?? null),
    ),
    { initialValue: null },
  );
  /** `undefined` = credor ainda não escolhido. */
  protected readonly creditorId = computed(() => (this.scopeKind() === 'TENANT' ? null : (this.creditorValue() ?? undefined)));
  protected readonly creditors = nameMap(this.creditorSource, () => [this.creditorValue()]);
  protected readonly scopeLabel = computed(() => {
    const id = this.creditorId();
    return id ? `Credor ${this.creditors.name(id)}` : 'Padrão da assessoria';
  });

  protected readonly effective = rxResource({
    params: () => (this.creditorId() === undefined ? undefined : { creditorId: this.creditorId() ?? null }),
    stream: ({ params }) => this.api.effective(params.creditorId),
  });
  protected readonly effectiveView = valueOr(this.effective, undefined);
  protected readonly effectiveRules = computed(() => this.effectiveView()?.channels ?? []);
  protected readonly history = rxResource({
    params: () => (this.creditorId() === undefined ? undefined : { creditorId: this.creditorId() ?? null }),
    stream: ({ params }) => this.api.history(params.creditorId),
  });
  protected readonly versions = valueOr(this.history, []);
  protected readonly latest = computed<PolicyView | null>(() => this.versions()[0] ?? null);

  /** Rascunho da próxima versão; recomeça da versão mais recente ao trocar de escopo ou publicar. */
  protected readonly draft = linkedSignal<PolicyView | null, RuleDraft[]>({
    source: this.latest,
    computation: (latest) => draftFrom(latest),
  });
  protected readonly baseline = computed(() => JSON.stringify(draftFrom(this.latest())));
  protected readonly dirty = computed(() => JSON.stringify(this.draft()) !== this.baseline());
  protected readonly problems = computed(() => draftProblems(this.draft()));
  protected readonly effectiveDate = signal<Date | null>(null);
  protected readonly effectiveTime = signal('08:00');
  protected readonly notes = signal('');
  protected readonly publishing = signal(false);
  protected readonly today = new Date();

  protected readonly effectiveColumns: TableColumn[] = [
    { key: 'channel', header: 'Canal' },
    { key: 'source', header: 'Origem da regra' },
    { key: 'window', header: 'Janela' },
    { key: 'days', header: 'Dias' },
    { key: 'limits', header: 'Limites por devedor' },
    { key: 'holidays', header: 'Feriados' },
  ];
  protected readonly channelKey = (r: EffectiveChannelRule) => r.channel;

  protected setScope(kind: 'TENANT' | 'CREDITOR'): void {
    this.scopeKind.set(kind);
  }

  protected patch(index: number, change: Partial<RuleDraft>): void {
    this.draft.update((rules) => rules.map((r, i) => (i === index ? { ...r, ...change } : r)));
  }

  protected toggleDay(index: number, day: WeekDay, on: boolean): void {
    const rule = this.draft()[index];
    const days = on ? [...new Set([...rule.allowedDays, day])] : rule.allowedDays.filter((d) => d !== day);
    this.patch(index, { allowedDays: WEEK_DAYS.map((d) => d.value).filter((d) => days.includes(d)) });
  }

  protected number(value: unknown): number | null {
    const n = Number(value);
    return value === '' || value === null || !Number.isFinite(n) || n <= 0 ? null : Math.trunc(n);
  }

  /** Recomeça o rascunho a partir de uma versão publicada (restaurar regra anterior). */
  protected useAsBase(policy: PolicyView): void {
    this.draft.set(draftFrom(policy));
    this.notifications.success(`Rascunho preenchido com a versão ${policy.version}. Revise e publique para valer.`);
  }

  protected discard(): void {
    this.draft.set(draftFrom(this.latest()));
    this.effectiveDate.set(null);
    this.notes.set('');
  }

  protected publish(): void {
    const creditorId = this.creditorId();
    if (creditorId === undefined || this.problems().length || this.publishing()) {
      return;
    }
    const effectiveFrom = effectiveFromInstant(this.effectiveDate(), this.effectiveTime());
    if (effectiveFrom && new Date(effectiveFrom) < new Date()) {
      this.notifications.error('O início da vigência não pode estar no passado.');
      return;
    }
    const rules = toRuleInputs(this.draft());
    const next = (this.latest()?.version ?? 0) + 1;
    this.confirm
      .confirm({
        title: `Publicar versão ${next}`,
        message: 'A nova versão passa a valer para todos os contatos (operadores, campanhas e réguas) a partir do início da vigência.',
        confirmLabel: 'Publicar',
        details: [
          { label: 'Política', value: this.scopeLabel() },
          { label: 'Vigência', value: effectiveFrom ? new Date(effectiveFrom).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'Imediata' },
          { label: 'Canais', value: rules.map((r) => CHANNEL_LABELS[r.channel]).join(', ') },
        ],
        warning: 'Versões publicadas não podem ser alteradas; para corrigir, publique outra versão.',
      })
      .subscribe(() => {
        this.publishing.set(true);
        this.api
          .publish({ creditorId, effectiveFrom, notes: this.notes().trim() || null, rules })
          .pipe(finalize(() => this.publishing.set(false)))
          .subscribe((published) => {
            this.notifications.success(`Versão ${published.version} publicada.`);
            this.effectiveDate.set(null);
            this.notes.set('');
            this.history.reload();
            this.effective.reload();
          });
      });
  }
}
