import { LowerCasePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, finalize, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { TitlesApi } from '../../debt/data/debt.api';
import { titleLabel } from '../../debt/data/debt.labels';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { AgreementsApi, ProposalsApi } from '../data/agreement.api';
import { SubmitMode, submitMode } from '../data/agreement.rules';
import { AuthorityApi, SimulationApi } from '../data/negotiation.api';
import {
  AUTHORITY_SOURCE_LABELS,
  COMPONENT_LABELS,
  CONCESSION_REASON_LABELS,
  ENGINE_COMPONENT_LABELS,
  EXCLUDED_REASON_LABELS,
  RULE_LABELS,
  VIOLATION_KIND_LABELS,
  percent,
  planText,
  ratesText,
} from '../data/negotiation.labels';
import { ConcessionReason, DEBT_COMPONENTS, OfferView, PlanRequest, SimulationView, ValidationView } from '../data/negotiation.models';
import { justificationProblem, offeredLevels, savingShare, sideBySide } from '../data/negotiation.rules';

/**
 * Simulador de negociação: composição da dívida atualizada, ofertas à vista × parcelado lado a
 * lado até o nível da escada permitido pela alçada, memória de cálculo sob demanda e validação de
 * condições pelo `NegotiationGuard` (dentro da alçada, requer aprovação ou rejeitado). Subir de
 * nível exige justificativa registrada. As condições validadas viram proposta: "Propor" dentro da
 * alçada ou "Pedir aprovação" acima dela (o botão muda, nunca some).
 *
 * Query params: `credor`, `devedor`, `caso` (aberto a partir da estação de cobrança), `renegociar`
 * (id do acordo substituído) e `transferencia` (proposta do assistente transferida para humano).
 */
@Component({
  selector: 'app-simulator',
  imports: [
    Icon,
    NgbInputDatepicker,
    ...FORM_FIELD,
    ReactiveFormsModule,
    RouterLink,
    Amount,
    EmptyState,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
    LowerCasePipe,
  ],
  templateUrl: './simulator.html',
  styleUrl: './simulator.scss',
})
export class Simulator {
  readonly credor = input<string>();
  readonly devedor = input<string>();
  readonly caso = input<string>();
  /** Acordo a renegociar (`replacedAgreementId`). */
  readonly renegociar = input<string>();
  /** Sugestão do assistente que o humano está renegociando (`handoffProposalId`). */
  readonly transferencia = input<string>();

  private readonly api = inject(SimulationApi);
  private readonly proposalsApi = inject(ProposalsApi);
  private readonly agreementsApi = inject(AgreementsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly authorityApi = inject(AuthorityApi);
  private readonly titlesApi = inject(TitlesApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly debtorSource = inject(DebtorLookup);

  protected readonly components = DEBT_COMPONENTS;
  protected readonly componentLabels = COMPONENT_LABELS;
  protected readonly engineLabels = ENGINE_COMPONENT_LABELS;
  protected readonly ruleLabels = RULE_LABELS;
  protected readonly violationKinds = VIOLATION_KIND_LABELS;
  protected readonly sourceLabels = AUTHORITY_SOURCE_LABELS;
  protected readonly excludedLabels = EXCLUDED_REASON_LABELS;
  protected readonly reasons = Object.entries(CONCESSION_REASON_LABELS).map(([value, label]) => ({ value: value as ConcessionReason, label }));
  protected readonly percent = percent;
  protected readonly planText = planText;
  protected readonly ratesText = ratesText;
  protected readonly titleLabel = titleLabel;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly context = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    debtor: this.fb.control<string[]>([]),
    // datas ISO yyyy-MM-dd (datepicker do ng-bootstrap)
    referenceDate: this.fb.control<string | null>(null),
    channel: [''],
    installments: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(360)]),
    downPayment: this.fb.control<number | null>(null, [Validators.min(0.01)]),
    firstDueDate: this.fb.control<string | null>(null),
  });
  protected readonly escalation = this.fb.group({
    level: this.fb.control<number>(0),
    reason: this.fb.control<ConcessionReason | ''>(''),
    note: ['', [Validators.maxLength(500)]],
  });
  /** Condições ajustadas para validação (`/validations`). */
  protected readonly custom = this.fb.group({
    principal: this.fb.control(0),
    correction: this.fb.control(0),
    interest: this.fb.control(0),
    fine: this.fb.control(0),
    fees: this.fb.control(0),
    honorarium: this.fb.control(0),
    installments: this.fb.control(1, [Validators.required, Validators.min(1), Validators.max(360)]),
    downPayment: this.fb.control<number | null>(null),
    firstDueDate: this.fb.control<string | null>(null),
  });

  private readonly contextValue = toSignal(this.context.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly creditorId = computed(() => {
    this.contextValue();
    return this.context.controls.creditor.value[0] ?? null;
  });
  protected readonly debtorId = computed(() => {
    this.contextValue();
    return this.context.controls.debtor.value[0] ?? null;
  });

  /** Alçada do usuário para o credor, visível antes de simular. */
  private readonly authority = rxResource({
    params: () => this.creditorId() ?? undefined,
    stream: ({ params }) => this.authorityApi.mine(params).pipe(catchError(() => of(undefined))),
  });
  protected readonly mine = valueOr(this.authority, undefined);

  private readonly titleList = rxResource({
    params: () => this.debtorId() ?? undefined,
    stream: ({ params }) => this.titlesApi.listByDebtor(params),
  });
  private readonly titleRows = valueOr(this.titleList, []);
  protected readonly titles = computed(() =>
    this.titleRows().filter((t) => t.creditorId === this.creditorId() && t.status === 'OPEN' && t.openBalance > 0),
  );
  protected readonly selectedTitles = signal<ReadonlySet<string>>(new Set());

  protected readonly simulation = signal<SimulationView | null>(null);
  protected readonly running = signal(false);
  protected readonly viewLevel = signal(0);
  protected readonly showMemory = signal(false);
  protected readonly validation = signal<ValidationView | null>(null);
  protected readonly validating = signal(false);
  protected readonly escalationError = signal<string | null>(null);
  protected readonly proposing = signal(false);
  protected readonly canPropose = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_PROPOSE));
  /** "Propor" (dentro da alçada) ou "Pedir aprovação" (acima); nada quando rejeitado. */
  protected readonly mode = computed<SubmitMode | null>(() => submitMode(this.validation()?.outcome));

  /** Acordo substituído, exibido como contexto da renegociação. */
  private readonly replaced = rxResource({
    params: () => this.renegociar() || undefined,
    stream: ({ params }) => this.agreementsApi.get(params).pipe(catchError(() => of(undefined))),
  });
  protected readonly replacedAgreement = valueOr(this.replaced, undefined);

  protected readonly result = computed(() => this.simulation()?.result ?? null);
  protected readonly levels = computed(() => (this.result() ? offeredLevels(this.result()!) : []));
  protected readonly pair = computed(() => (this.result() ? sideBySide(this.result()!, this.viewLevel()) : null));
  /** Níveis acima do concedido, até o limite da alçada (subir exige justificativa). */
  protected readonly higherLevels = computed(() => {
    const r = this.result();
    if (!r) {
      return [];
    }
    return Array.from({ length: Math.max(0, r.limits.maxLevel - r.grantedLevel) }, (_, i) => r.grantedLevel + i + 1);
  });

  constructor() {
    // contexto vindo da estação de cobrança
    effect(() => {
      const credor = this.credor();
      const devedor = this.devedor();
      untracked(() => {
        if (credor) {
          this.context.controls.creditor.setValue([credor]);
        }
        if (devedor) {
          this.context.controls.debtor.setValue([devedor]);
        }
      });
    });
    // mudou credor/devedor: resultado anterior não vale mais
    effect(() => {
      this.creditorId();
      this.debtorId();
      untracked(() => {
        this.simulation.set(null);
        this.validation.set(null);
        this.selectedTitles.set(new Set());
        this.escalation.reset({ level: 0, reason: '', note: '' });
      });
    });
    // condições alteradas depois da validação: é preciso validar de novo antes de propor
    this.custom.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.validation.set(null));
  }

  protected toggleTitle(id: string, on: boolean): void {
    this.selectedTitles.update((s) => {
      const next = new Set(s);
      if (on) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  protected simulate(level = 0): void {
    const creditorId = this.creditorId();
    const debtorId = this.debtorId();
    if (!creditorId || !debtorId || this.running() || this.context.invalid) {
      this.context.markAllAsTouched();
      return;
    }
    const e = this.escalation.getRawValue();
    const problem = justificationProblem(level, e.reason || null, e.note);
    this.escalationError.set(problem);
    if (problem) {
      return;
    }
    const v = this.context.getRawValue();
    const plans: PlanRequest[] = v.installments
      ? [
          { installments: 1, downPayment: null, firstDueDate: v.firstDueDate },
          { installments: Number(v.installments), downPayment: v.downPayment ? Number(v.downPayment) : null, firstDueDate: v.firstDueDate },
        ]
      : [];
    this.running.set(true);
    this.validation.set(null);
    this.api
      .simulate({
        creditorId,
        debtorId,
        titleIds: this.selectedTitles().size ? [...this.selectedTitles()] : null,
        referenceDate: v.referenceDate,
        requestedLevel: level,
        justification: level > 0 ? { reason: e.reason as ConcessionReason, note: e.note.trim() || null } : null,
        plans: plans.length ? plans : null,
        channel: v.channel || null,
      })
      .pipe(finalize(() => this.running.set(false)))
      .subscribe((sim) => {
        this.simulation.set(sim);
        this.viewLevel.set(sim.result.grantedLevel);
        this.escalation.controls.level.setValue(Math.min(sim.result.grantedLevel + 1, sim.result.limits.maxLevel));
        const best = sideBySide(sim.result, sim.result.grantedLevel);
        const offer = best.cash ?? best.installment;
        if (offer) {
          this.useOffer(offer);
        }
      });
  }

  protected escalate(): void {
    this.simulate(Number(this.escalation.controls.level.value));
  }

  /** Copia a oferta para o ajuste de condições. */
  protected useOffer(offer: OfferView): void {
    this.custom.reset({
      ...offer.discountRates,
      installments: offer.installments,
      downPayment: offer.downPayment,
      firstDueDate: offer.schedule[0]?.dueDate ?? null,
    });
    this.validation.set(null);
  }

  protected validate(): void {
    const r = this.result();
    if (!r || this.custom.invalid || this.validating()) {
      this.custom.markAllAsTouched();
      return;
    }
    const c = this.custom.getRawValue();
    const e = this.escalation.getRawValue();
    const level = this.viewLevel();
    this.validating.set(true);
    this.api
      .validate({
        creditorId: r.creditorId,
        debtorId: r.debtorId,
        titleIds: this.selectedTitles().size ? [...this.selectedTitles()] : null,
        referenceDate: r.referenceDate,
        policyId: r.policy.policyId,
        level,
        justification: level > 0 && r.justification ? r.justification : level > 0 && e.reason ? { reason: e.reason, note: e.note.trim() || null } : null,
        discounts: {
          principal: Number(c.principal) || 0,
          correction: Number(c.correction) || 0,
          interest: Number(c.interest) || 0,
          fine: Number(c.fine) || 0,
          fees: Number(c.fees) || 0,
          honorarium: Number(c.honorarium) || 0,
        },
        plan: { installments: Number(c.installments), downPayment: c.downPayment ? Number(c.downPayment) : null, firstDueDate: c.firstDueDate },
        channel: r.channel,
      })
      .pipe(finalize(() => this.validating.set(false)))
      .subscribe((v) => this.validation.set(v));
  }

  /**
   * Cria a proposta com as condições validadas. Dentro da alçada ela fica pronta para o aceite do
   * devedor; acima, entra na fila de aprovação de quem tem alçada suficiente.
   */
  protected propose(): void {
    const sim = this.simulation();
    const v = this.validation();
    const mode = this.mode();
    if (!sim || !v?.offer || !mode || this.proposing() || this.custom.invalid) {
      return;
    }
    const c = this.custom.getRawValue();
    const o = v.offer;
    const approval = mode === 'request-approval';
    this.confirm
      .confirm({
        title: approval ? 'Pedir aprovação da proposta' : 'Propor condições ao devedor',
        message: approval
          ? 'As condições passam da sua alçada: a proposta vai para a fila de aprovação de quem pode concedê-las. Avise o devedor de que a resposta depende de aprovação.'
          : 'A proposta fica registrada e pronta para o aceite do devedor, que gera o termo do acordo.',
        confirmLabel: approval ? 'Pedir aprovação' : 'Propor',
        details: [
          { label: 'Total', value: formatAmount(o.total) },
          { label: 'Forma de pagamento', value: planText(o) },
          { label: '1º vencimento', value: o.schedule[0] ? o.schedule[0].dueDate.split('-').reverse().join('/') : '—' },
          { label: 'Nível da escada', value: `${this.viewLevel()} · ${o.levelName}` },
          ...(this.renegociar() ? [{ label: 'Renegocia', value: 'Acordo em vigor ou quebrado (será substituído na formalização)' }] : []),
        ],
      })
      .subscribe(() => {
        this.proposing.set(true);
        this.proposalsApi
          .create({
            simulationId: sim.id,
            level: this.viewLevel(),
            discounts: {
              principal: Number(c.principal) || 0,
              correction: Number(c.correction) || 0,
              interest: Number(c.interest) || 0,
              fine: Number(c.fine) || 0,
              fees: Number(c.fees) || 0,
              honorarium: Number(c.honorarium) || 0,
            },
            plan: { installments: Number(c.installments), downPayment: c.downPayment ? Number(c.downPayment) : null, firstDueDate: c.firstDueDate },
            replacedAgreementId: this.renegociar() || null,
            handoffProposalId: this.transferencia() || null,
            copilot: null,
            channel: sim.result.channel,
          })
          .pipe(finalize(() => this.proposing.set(false)))
          .subscribe((p) => {
            this.notifications.success(p.status === 'PENDING_APPROVAL' ? 'Proposta enviada para aprovação.' : 'Proposta registrada.');
            void this.router.navigate(['/negociacao/propostas', p.id]);
          });
      });
  }

  protected reasonText(code: string): string {
    return EXCLUDED_REASON_LABELS[code] ?? code;
  }

  protected saving(offer: OfferView): number {
    return savingShare(offer, this.result()?.composition.totalAmount ?? 0);
  }

  protected installmentValue(offer: OfferView): number | null {
    const regular = offer.schedule.filter((p) => !offer.downPayment || p.number > 1);
    return regular[0]?.amount ?? null;
  }
}
