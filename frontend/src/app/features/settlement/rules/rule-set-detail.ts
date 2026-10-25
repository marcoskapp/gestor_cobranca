import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { ContractsApi } from '../../organization/data/organization.api';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { ContractView } from '../../organization/data/organization.models';
import { parseMoney } from '../../payment/data/payment.rules';
import { FormDialogService } from '../../legal/components/form-dialog';
import { CalculationMemory } from '../components/calculation-memory';
import { CommissionRulesApi } from '../data/settlement.api';
import { HONORARIUM_OWNERSHIP_LABELS, PAYMENT_TYPE_LABELS, RECEIVED_BY_LABELS, SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { CalculationView, CommissionRules, HonorariumOwnership, PaymentType, RateRule, ReceivedBy } from '../data/settlement.models';
import { cloneRules, criteriaText, periodText, rulesProblems, specificity } from '../data/settlement.rules';
import { RateRuleDialog, RateRuleDialogData } from './rate-rule-dialog';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../shared/dialog/dialog.service';

/**
 * Versão das regras de comissão: leitura (publicada/descartada) ou edição do rascunho com
 * validação contínua, simulação de um recebimento com a memória de cálculo, publicação com início
 * de vigência por outra pessoa (quem editou por último não publica) e linha do tempo das versões.
 */
@Component({
  selector: 'app-rule-set-detail',
  imports: [
    FormsModule,
    RouterLink,
    NgbTooltip,
    ...FORM_FIELD,
    Icon,
    Amount,
    AppDatePipe,
    CalculationMemory,
    EmptyState,
    PageHeader,
    Skeleton,
    StatusChip,
  ],
  templateUrl: './rule-set-detail.html',
  styleUrl: '../settlement.scss',
})
export class RuleSetDetail {
  readonly id = input.required<string>();

  private readonly api = inject(CommissionRulesApi);
  private readonly contractsApi = inject(ContractsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly forms = inject(FormDialogService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly paymentTypes = Object.entries(PAYMENT_TYPE_LABELS) as [PaymentType, string][];
  protected readonly receivers = Object.entries(RECEIVED_BY_LABELS) as [ReceivedBy, string][];
  protected readonly ownerships = Object.entries(HONORARIUM_OWNERSHIP_LABELS) as [HonorariumOwnership, string][];
  protected readonly paymentTypeLabels = PAYMENT_TYPE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_MANAGE));
  private readonly userId = computed(() => this.auth.user()?.id ?? null);

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.resource, undefined);
  private readonly versionList = rxResource({
    params: () => this.current()?.contractId,
    stream: ({ params }) => this.api.list(null, params),
  });
  protected readonly versions = computed(() => (this.versionList.hasValue() ? [...this.versionList.value()!].sort((a, b) => b.ruleVersion - a.ruleVersion) : []));
  private readonly contractList = rxResource({
    params: () => this.current()?.creditorId,
    stream: ({ params }) => this.contractsApi.listByCreditor(params).pipe(catchError(() => of<ContractView[]>([]))),
  });
  protected readonly contract = computed(() => (this.contractList.hasValue() ? this.contractList.value()!.find((c) => c.id === this.current()?.contractId) : undefined));

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.draft()?.rateRules.map((r) => r.portfolioId) ?? []);
  protected readonly users = nameMap(inject(UserNames), () => [this.current()?.rulesEditedBy, this.current()?.publishedBy], { batch: USER_NAMES_BATCH });

  /** Regras em edição (cópia; o servidor só muda ao salvar). */
  protected readonly draft = signal<CommissionRules | null>(null);
  protected readonly notes = signal('');
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly editable = computed(() => this.current()?.status === 'DRAFT' && this.canManage());
  protected readonly problems = computed(() => {
    this.revision();
    const d = this.draft();
    return d ? rulesProblems(d) : [];
  });
  protected readonly hasDraft = computed(() => this.versions().some((v) => v.status === 'DRAFT'));
  /** Quem editou por último não publica (segregação; o servidor recusa inclusive o administrador). */
  protected readonly ownEdit = computed(() => {
    const p = this.current();
    return !!p && p.status === 'DRAFT' && !!p.rulesEditedBy && p.rulesEditedBy === this.userId();
  });
  protected readonly sortedRules = computed(() => {
    this.revision();
    return [...(this.draft()?.rateRules ?? [])].sort((a, b) => b.priority - a.priority || specificity(b) - specificity(a) || a.code.localeCompare(b.code));
  });

  // ------------------------------------------------------------------ simulação
  protected readonly sim = {
    amount: signal('1.100,00'),
    honorarium: signal('100,00'),
    portfolioId: signal(''),
    daysPastDue: signal<number | null>(120),
    paymentType: signal<PaymentType>('INSTALLMENTS'),
    receivedBy: signal<ReceivedBy>('AGENCY'),
    renegotiation: signal(false),
    first: signal(true),
  };
  protected readonly simulation = signal<CalculationView | null>(null);
  protected readonly simulating = signal(false);
  protected readonly simulationError = signal<string | null>(null);
  protected readonly simPortfolios = computed(() => [...new Set((this.draft()?.rateRules ?? []).map((r) => r.portfolioId).filter((x): x is string => !!x))]);

  constructor() {
    effect(() => {
      const p = this.current();
      this.breadcrumbs.setCurrentLabel(p ? `v${p.ruleVersion}` : null);
      if (p) {
        this.draft.set(cloneRules(p.rules));
        this.notes.set(p.notes ?? '');
        this.dirty.set(false);
        this.simulation.set(null);
        this.revision.update((n) => n + 1);
      }
    });
  }

  protected edited(): void {
    this.dirty.set(true);
    this.simulation.set(null);
    this.revision.update((n) => n + 1);
  }

  protected setNumber(field: 'defaultRate', value: string): void {
    const d = this.draft();
    if (d) {
      d[field] = value === '' ? (NaN as unknown as number) : Number(String(value).replace(',', '.'));
      this.edited();
    }
  }

  protected setFixedFee(value: string): void {
    const d = this.draft();
    if (d) {
      d.fixedFeePerAgreement = value.trim() === '' ? null : (parseMoney(value) ?? -1);
      this.edited();
    }
  }

  protected setOwnership(value: HonorariumOwnership): void {
    const d = this.draft();
    if (d) {
      d.honorarium = { ownership: value, agencySharePercent: value === 'AGENCY' ? (d.honorarium.agencySharePercent ?? 100) : null };
      this.edited();
    }
  }

  protected setShare(value: string): void {
    const d = this.draft();
    if (d) {
      d.honorarium = { ...d.honorarium, agencySharePercent: value === '' ? null : Number(String(value).replace(',', '.')) };
      this.edited();
    }
  }

  protected setReconciliation(value: boolean): void {
    const d = this.draft();
    if (d) {
      d.reconciliationRequired = value;
      this.edited();
    }
  }

  protected rateText(value: number | null | undefined): string {
    return value === null || value === undefined || Number.isNaN(value) ? '' : String(value).replace('.', ',');
  }

  protected feeText(value: number | null): string {
    return value === null ? '' : formatAmount(value).replace('R$', '').trim();
  }

  protected criteria(rule: RateRule): string {
    return criteriaText(rule, (id) => this.portfolios.name(id));
  }

  protected editRule(rule: RateRule | null): void {
    const d = this.draft();
    const p = this.current();
    if (!d || !p) {
      return;
    }
    const used = d.rateRules.filter((r) => r !== rule).map((r) => r.code);
    this.dialog
      .open<RateRuleDialog, RateRuleDialogData, RateRule>(RateRuleDialog, { data: { creditorId: p.creditorId, rule, usedCodes: used }, size: 'lg' })
      .afterClosed()
      .subscribe((result) => {
        if (!result) {
          return;
        }
        const index = rule ? d.rateRules.indexOf(rule) : -1;
        if (index >= 0) {
          d.rateRules[index] = result;
        } else {
          d.rateRules.push(result);
        }
        this.edited();
      });
  }

  protected removeRule(rule: RateRule): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    this.confirm
      .confirm({ title: 'Remover regra', message: 'Os recebimentos que casavam com ela passam ao percentual padrão ou a outra regra.', confirmLabel: 'Remover', destructive: true, details: [{ label: 'Regra', value: `${rule.code} · ${this.criteria(rule)}` }] })
      .subscribe(() => {
        d.rateRules.splice(d.rateRules.indexOf(rule), 1);
        this.edited();
      });
  }

  protected discardChanges(): void {
    const p = this.current();
    if (p) {
      this.draft.set(cloneRules(p.rules));
      this.notes.set(p.notes ?? '');
      this.dirty.set(false);
      this.revision.update((n) => n + 1);
    }
  }

  protected save(): void {
    const p = this.current();
    const d = this.draft();
    if (!p || !d || this.saving()) {
      return;
    }
    if (this.problems().length) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    this.api
      .update(p.id, { contractId: p.contractId, rules: d, notes: this.notes().trim() || null })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.resource.set(saved);
          this.notifications.success('Rascunho salvo. A publicação precisa ser feita por outra pessoa.');
        },
        error: (err: unknown) => this.fail(err),
      });
  }

  protected publish(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    if (this.dirty()) {
      this.notifications.error('Salve o rascunho antes de publicar.');
      return;
    }
    const today = zonedNow().date;
    const active = this.versions().find((v) => v.status === 'PUBLISHED' && (!v.effectiveTo || v.effectiveTo >= today));
    const c = this.contract();
    const min = c?.startDate ?? '2000-01-01';
    this.forms
      .open<string>({
        title: `Publicar versão ${p.ruleVersion}`,
        message: 'A versão publicada não muda mais. Recebimentos com data de pagamento a partir do início da vigência são apurados por ela; os que aguardavam regra são apurados agora.',
        submitLabel: 'Publicar',
        details: [
          { label: 'Credor', value: this.creditors.name(p.creditorId) },
          { label: 'Contrato', value: c ? `${c.number} (fechamento dia ${c.settlementDay})` : p.contractId.slice(0, 8) },
          { label: 'Percentual padrão', value: `${this.rateText(p.rules.defaultRate)}%` },
          { label: 'Regras específicas', value: String(p.rules.rateRules.length) },
          { label: 'Editado por', value: this.users.name(p.rulesEditedBy) },
          { label: 'Versão vigente', value: active ? `v${active.ruleVersion} (termina na véspera)` : 'nenhuma' },
        ],
        warning: 'A vigência não pode alcançar recebimentos já apurados nem períodos já prestados.',
        fields: [
          {
            key: 'effectiveFrom',
            label: 'Vigente a partir de',
            type: 'date',
            required: true,
            value: today,
            wide: true,
            validate: (v) => (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && v < min ? `Use uma data a partir de ${min.split('-').reverse().join('/')}.` : null),
          },
        ],
        submit: (v) => of(v['effectiveFrom'] as string),
      })
      .subscribe((date) => {
        if (!date) {
          return;
        }
        this.actionError.set(null);
        this.api.publish(p.id, date).subscribe({
          next: (saved) => {
            this.resource.set(saved);
            this.versionList.reload();
            this.notifications.success(`Versão ${saved.ruleVersion} publicada a partir de ${date.split('-').reverse().join('/')}.`);
          },
          error: (err: unknown) => this.fail(err),
        });
      });
  }

  protected discardDraft(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({ title: `Descartar rascunho v${p.ruleVersion}`, message: 'O rascunho fica registrado como descartado e não pode mais ser publicado.', confirmLabel: 'Descartar', destructive: true })
      .subscribe(() =>
        this.api.discard(p.id).subscribe((saved) => {
          this.resource.set(saved);
          this.versionList.reload();
          this.notifications.success('Rascunho descartado.');
        }),
      );
  }

  protected newVersion(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    this.api.create({ contractId: p.contractId, rules: cloneRules(p.rules), notes: null }).subscribe({
      next: (created) => {
        this.notifications.success(`Rascunho da versão ${created.ruleVersion} criado a partir da versão ${p.ruleVersion}.`);
        void this.router.navigate(['/prestacao-de-contas/regras', created.id]);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  protected simulate(): void {
    const d = this.draft();
    const p = this.current();
    const amount = parseMoney(this.sim.amount());
    if (!d || !p || this.simulating()) {
      return;
    }
    if (amount === null || amount <= 0) {
      this.simulationError.set('Informe o valor recebido.');
      return;
    }
    if (this.problems().length) {
      this.simulationError.set('Corrija os problemas das regras antes de simular.');
      return;
    }
    this.simulating.set(true);
    this.simulationError.set(null);
    this.api
      .simulate({
        ruleSetId: this.dirty() || p.status === 'DRAFT' ? null : p.id,
        rules: this.dirty() || p.status === 'DRAFT' ? d : null,
        amount,
        honorarium: parseMoney(this.sim.honorarium()) ?? 0,
        portfolioId: this.sim.portfolioId() || null,
        daysPastDue: this.sim.daysPastDue(),
        paymentType: this.sim.paymentType(),
        receivedBy: this.sim.receivedBy(),
        renegotiation: this.sim.renegotiation(),
        firstReceiptOfAgreement: this.sim.first(),
      })
      .pipe(finalize(() => this.simulating.set(false)))
      .subscribe({
        next: (calc) => this.simulation.set(calc),
        error: (err: unknown) => this.simulationError.set(userMessage(toApiError(err))),
      });
  }

  protected period(from: string | null, to: string | null): string {
    if (!from) {
      return 'não publicada';
    }
    return to ? periodText(from, to) : `a partir de ${from.split('-').reverse().join('/')}`;
  }

  protected userName(id: string | null): string {
    if (!id) {
      return '—';
    }
    return id === this.userId() ? 'você' : this.users.name(id);
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.actionError.set((e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e));
  }
}
