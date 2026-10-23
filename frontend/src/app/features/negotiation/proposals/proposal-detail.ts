import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DocumentDisplay } from '../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { UserNames } from '../../collection/data/collection.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { DebtorsApi } from '../../debt/data/debt.api';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ProposalsApi } from '../data/agreement.api';
import { ACCEPTANCE_CHANNEL_LABELS } from '../data/agreement.labels';
import { AcceptanceChannel, ProposalView } from '../data/agreement.models';
import { actorText, proposalActions, proposalTimeline, shortHash, validityLapsed, violationText } from '../data/agreement.rules';
import { SimulationApi } from '../data/negotiation.api';
import { CONCESSION_REASON_LABELS, ENGINE_COMPONENT_LABELS, RULE_LABELS, percent, planText, ratesText } from '../data/negotiation.labels';
import { ConcessionReason } from '../data/negotiation.models';
import { AcceptDialog, AcceptDialogData } from './accept-dialog';

const date = (iso: string | null | undefined) => (iso ? iso.split('-').reverse().join('/') : '—');

/**
 * Detalhe da proposta: condições e cronograma, memória de cálculo, nível da escada, justificativa,
 * alçada e histórico. Ações conforme a situação e o perfil: aprovar/reprovar (aprovador), registrar
 * o aceite do devedor, formalizar o acordo, assumir a sugestão transferida pelo assistente e baixar o
 * termo. Toda ação é revalidada no servidor pelo guard.
 */
@Component({
  selector: 'app-proposal-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, Amount, DocumentDisplay, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe],
  templateUrl: './proposal-detail.html',
  styleUrl: '../detail-page.scss',
})
export class ProposalDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ProposalsApi);
  private readonly simulations = inject(SimulationApi);
  private readonly debtorsApi = inject(DebtorsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly percent = percent;
  protected readonly ratesText = ratesText;
  protected readonly engineLabels = ENGINE_COMPONENT_LABELS;
  protected readonly ruleLabels = RULE_LABELS;
  protected readonly shortHash = shortHash;
  protected readonly violationText = violationText;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly proposal = valueOr(this.resource, undefined);
  protected readonly busy = signal(false);

  private readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  private readonly debtor = rxResource({
    params: () => (this.canViewDebtor() ? this.proposal()?.debtorId : undefined),
    stream: ({ params }) => this.debtorsApi.get(params).pipe(catchError(() => of(undefined))),
  });
  protected readonly debtorView = valueOr(this.debtor, undefined);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.proposal()?.creditorId]);
  private readonly users = nameMap(inject(UserNames), () => {
    const p = this.proposal();
    return p ? [p.actor.type === 'USER' ? p.actor.id : null, p.decision?.decidedBy, p.acceptance?.actorType === 'USER' ? p.acceptance.actorId : null] : [];
  });

  /** Memória completa da simulação de origem (sob demanda, exige `NEGOTIATION_SIMULATE`). */
  protected readonly canSeeSimulation = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  protected readonly showMemory = signal(false);
  private readonly simulation = rxResource({
    params: () => (this.showMemory() && this.canSeeSimulation() ? this.proposal()?.simulationId : undefined),
    stream: ({ params }) => this.simulations.get(params).pipe(catchError(() => of(undefined))),
  });
  protected readonly composition = computed(() => (this.simulation.hasValue() ? (this.simulation.value()?.result.composition ?? null) : null));

  private readonly today = zonedNow().date;
  protected readonly actions = computed(() => {
    const p = this.proposal();
    return p
      ? proposalActions(
          p,
          {
            userId: this.auth.user()?.id ?? null,
            canPropose: this.auth.hasPermission(Permission.NEGOTIATION_PROPOSE),
            canApprove: this.auth.hasPermission(Permission.NEGOTIATION_APPROVE),
            canFormalize: this.auth.hasPermission(Permission.AGREEMENT_FORMALIZE),
          },
          this.today,
        )
      : null;
  });
  protected readonly lapsed = computed(() => !!this.proposal() && validityLapsed(this.proposal()!, this.today));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  protected readonly canViewAgreement = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly timeline = computed(() => {
    const p = this.proposal();
    return p ? proposalTimeline(p, (type, id) => this.actorName(type, id)) : [];
  });
  protected readonly debtorName = computed(() => this.debtorView()?.name ?? null);

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.debtorName()));
  }

  protected actorName(type: string, id: string | null): string {
    return actorText(type, id, this.auth.user()?.id ?? null, (uid) => this.users.name(uid));
  }

  protected plan(p: ProposalView): string {
    return planText({ installments: p.installments, downPayment: p.downPayment });
  }

  protected reasonText(code: string | null): string {
    return code ? (CONCESSION_REASON_LABELS[code as ConcessionReason] ?? code) : 'Sem justificativa (nível 0)';
  }

  protected channelText(code: string | null): string {
    return code ? (ACCEPTANCE_CHANNEL_LABELS[code as AcceptanceChannel] ?? code) : 'Não informado';
  }

  /** Resumo do impacto para as confirmações (devedor mascarado, valor, parcelas, vencimentos). */
  private impact(p: ProposalView): { label: string; value: string }[] {
    const d = this.debtorView();
    const schedule = p.offer.schedule;
    return [
      { label: 'Devedor', value: d ? `${d.name} · ${d.maskedDocument}` : 'Devedor da proposta' },
      { label: 'Credor', value: this.creditors.name(p.creditorId) },
      { label: 'Total', value: formatAmount(p.totalAmount) },
      { label: 'Forma de pagamento', value: this.plan(p) },
      { label: 'Vencimentos', value: schedule.length ? `${date(schedule[0].dueDate)} a ${date(schedule[schedule.length - 1].dueDate)}` : '—' },
    ];
  }

  protected approve(): void {
    const p = this.proposal();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Aprovar proposta',
        message: 'As condições são revalidadas com a sua alçada. Aprovada, a proposta fica pronta para o aceite do devedor.',
        confirmLabel: 'Aprovar',
        reason: 'optional',
        reasonLabel: 'Observação (opcional)',
        details: this.impact(p),
      })
      .subscribe((note) => this.run(this.api.approve(p.id, note || null), 'Proposta aprovada.'));
  }

  protected reject(): void {
    const p = this.proposal();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Reprovar proposta',
        message: 'O negociador vê o motivo e pode simular outras condições.',
        confirmLabel: 'Reprovar',
        destructive: true,
        reason: 'required',
        details: this.impact(p),
      })
      .subscribe((reason) => this.run(this.api.reject(p.id, reason), 'Proposta reprovada.'));
  }

  protected accept(): void {
    const p = this.proposal();
    if (!p) {
      return;
    }
    this.dialog
      .open<AcceptDialog, AcceptDialogData, ProposalView>(AcceptDialog, { data: { proposal: p, details: this.impact(p) }, width: '520px' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Aceite registrado. O termo do acordo foi gerado.');
          this.resource.reload();
        }
      });
  }

  protected formalize(): void {
    const p = this.proposal();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Formalizar acordo',
        message: 'O acordo passa a valer com o cronograma abaixo e os títulos ficam vinculados a ele.',
        confirmLabel: 'Formalizar',
        details: this.impact(p),
        warning: p.replacedAgreementId
          ? 'O acordo anterior será marcado como renegociado. A formalização não pode ser desfeita: só o cancelamento com motivo.'
          : 'A formalização não pode ser desfeita: só o cancelamento do acordo, com motivo.',
        confirmationText: 'FORMALIZAR',
      })
      .subscribe(() => {
        this.busy.set(true);
        this.api
          .formalize(p.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe((a) => {
            this.notifications.success('Acordo formalizado.');
            if (this.canViewAgreement()) {
              void this.router.navigate(['/negociacao/acordos', a.id]);
            } else {
              this.resource.reload();
            }
          });
      });
  }

  protected adopt(): void {
    const p = this.proposal();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Assumir sugestão do assistente',
        message: 'Uma nova proposta é criada em seu nome com as mesmas condições, revalidadas com a sua alçada. Se passar dela, vai para aprovação.',
        confirmLabel: 'Assumir sugestão',
        details: this.impact(p),
      })
      .subscribe(() => {
        this.busy.set(true);
        this.api
          .adopt(p.id, p.channel)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe((created) => {
            this.notifications.success(created.status === 'PENDING_APPROVAL' ? 'Sugestão assumida e enviada para aprovação.' : 'Sugestão assumida.');
            void this.router.navigate(['/negociacao/propostas', created.id]);
          });
      });
  }

  /** Renegociar a sugestão: volta ao simulador com o vínculo da transferência. */
  protected negotiateOther(): void {
    const p = this.proposal();
    if (p) {
      void this.router.navigate(['/negociacao/simulador'], {
        queryParams: { credor: p.creditorId, devedor: p.debtorId, caso: p.caseId ?? undefined, transferencia: p.id, renegociar: p.replacedAgreementId ?? undefined },
      });
    }
  }

  protected downloadTerm(): void {
    const p = this.proposal();
    if (p) {
      this.api.downloadTerm(p.id).subscribe((name) => this.notifications.success(`Termo baixado: ${name}`));
    }
  }

  private run(call: ReturnType<ProposalsApi['approve']>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe(() => {
      this.notifications.success(message);
      this.resource.reload();
    });
  }
}
