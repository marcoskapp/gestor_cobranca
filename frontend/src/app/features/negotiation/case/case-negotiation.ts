import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AgreementsApi, ProposalsApi } from '../data/agreement.api';
import { AgreementView, ProposalView } from '../data/agreement.models';
import { OPEN_PROPOSAL_STATUSES, scheduleSummary } from '../data/agreement.rules';
import { planText } from '../data/negotiation.labels';

/**
 * Aba "Negociação" da ficha do caso na estação de cobrança: propostas em andamento e acordos do caso,
 * com atalhos para simular, abrir a proposta e acompanhar o cronograma.
 */
@Component({
  selector: 'app-case-negotiation',
  imports: [RouterLink, MatButtonModule, MatIconModule, Amount, StatusChip, AppDatePipe],
  template: `
    <div class="cn-actions">
      @if (canSimulate()) {
        <a mat-stroked-button routerLink="/negociacao/simulador" [queryParams]="{ credor: creditorId(), devedor: debtorId(), caso: caseId() }">
          <mat-icon aria-hidden="true">calculate</mat-icon>
          Simular
          <kbd class="cn-kbd" aria-hidden="true">Alt S</kbd>
        </a>
      }
    </div>

    <h3 class="app-section-title">Propostas em andamento</h3>
    @if (!canReadProposals()) {
      <p class="app-muted">Seu perfil não consulta propostas.</p>
    } @else {
      <ul class="cn-list" [attr.aria-busy]="proposals.isLoading()">
        @for (p of openProposals(); track p.id) {
          <li class="cn-item">
            <app-status-chip domain="proposal" [status]="p.status" />
            <a [routerLink]="['/negociacao/propostas', p.id]"><app-amount [value]="p.totalAmount" /> · {{ plan(p) }}</a>
            <span class="app-muted">
              {{ p.actor.type === 'ASSISTANT' ? 'Assistente' : 'Operador' }} · {{ p.createdAt | appDate }}
              @if (p.validUntil) {
                · válida até {{ p.validUntil | appDate: 'date' }}
              }
            </span>
          </li>
        } @empty {
          <li class="app-muted">Nenhuma proposta em andamento para este caso.</li>
        }
      </ul>
    }

    <h3 class="app-section-title">Acordos</h3>
    @if (!canReadAgreements()) {
      <p class="app-muted">Seu perfil não consulta acordos.</p>
    } @else {
      <ul class="cn-list" [attr.aria-busy]="agreements.isLoading()">
        @for (a of agreementRows(); track a.id) {
          @let s = summary(a);
          <li class="cn-item">
            <app-status-chip domain="agreement" [status]="a.status" />
            <a [routerLink]="['/negociacao/acordos', a.id]"><app-amount [value]="a.totalAmount" /> · {{ s.paid }} de {{ s.total }} pagas</a>
            <span class="app-muted">
              formalizado em {{ a.formalizedAt | appDate: 'date' }}
              @if (s.next) {
                · próxima {{ s.next.dueDate | appDate: 'date' }} (<app-amount [value]="s.next.amount" />)
              }
            </span>
            @if (s.overdue) {
              <span class="cn-late">{{ s.overdue }} vencida(s)</span>
            }
          </li>
        } @empty {
          <li class="app-muted">Nenhum acordo neste caso.</li>
        }
      </ul>
    }
  `,
  styles: `
    .cn-actions {
      display: flex;
      justify-content: flex-end;
      margin-bottom: 8px;
    }
    .cn-kbd {
      margin-left: 8px;
      font-size: var(--app-text-xs);
      opacity: 0.7;
    }
    .cn-list {
      margin: 0 0 16px;
      padding: 0;
      list-style: none;
    }
    .cn-item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
      padding: 8px 0;
      border-bottom: 1px solid var(--app-border);
    }
    .cn-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
  `,
})
export class CaseNegotiation {
  readonly caseId = input.required<string>();
  readonly creditorId = input.required<string>();
  readonly debtorId = input.required<string>();

  private readonly auth = inject(AuthService);
  private readonly proposalsApi = inject(ProposalsApi);
  private readonly agreementsApi = inject(AgreementsApi);

  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  protected readonly canReadProposals = computed(() =>
    this.auth.hasAnyPermission([Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE]),
  );
  protected readonly canReadAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));

  /** Propostas do devedor no credor (o filtro da API não tem caso): as do caso, ainda abertas. */
  protected readonly proposals = rxResource({
    params: () => (this.canReadProposals() ? { creditorId: this.creditorId(), debtorId: this.debtorId(), caseId: this.caseId() } : undefined),
    stream: ({ params }) =>
      this.proposalsApi.search({ creditorId: params.creditorId, debtorId: params.debtorId, status: null, actorType: null }, { page: 0, size: 50 }).pipe(
        map((page) => page.content.filter((p) => p.caseId === params.caseId || p.caseId === null)),
        catchError(() => of<ProposalView[]>([])),
      ),
  });
  private readonly proposalRows = valueOr(this.proposals, []);
  protected readonly openProposals = computed(() =>
    this.proposalRows().filter((p) => OPEN_PROPOSAL_STATUSES.includes(p.status) || p.status === 'HANDED_OFF'),
  );

  protected readonly agreements = rxResource({
    params: () => (this.canReadAgreements() ? this.caseId() : undefined),
    stream: ({ params }) =>
      this.agreementsApi.search({ creditorId: null, debtorId: null, caseId: params, status: null }, { page: 0, size: 50 }).pipe(
        map((page) => page.content),
        catchError(() => of<AgreementView[]>([])),
      ),
  });
  protected readonly agreementRows = valueOr(this.agreements, []);

  protected plan(p: ProposalView): string {
    return planText({ installments: p.installments, downPayment: p.downPayment });
  }

  protected summary(a: AgreementView) {
    return scheduleSummary(a);
  }
}
