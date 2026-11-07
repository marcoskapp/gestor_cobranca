import { Component, computed, inject, input, output } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { zonedNow } from '../../collection/data/collection.rules';
import { AgreementsApi, ProposalsApi } from '../data/agreement.api';
import { AgreementView, InstallmentView, ProposalView } from '../data/agreement.models';
import { proposalActions, scheduleSummary } from '../data/agreement.rules';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { installmentPix } from './installment-pix';
import { planText } from '../data/negotiation.labels';

/**
 * Cartão da negociação em curso na ficha do caso (estação de cobrança): o acordo recém-formalizado
 * (Pix da próxima parcela, termo e acordo), a proposta aguardando aprovação de quem tem alçada ou a
 * proposta pronta para o aceite ("Fechar acordo"). Só aparece quando há algo a fazer; o histórico
 * completo fica na aba Negociação.
 */
@Component({
  selector: 'app-case-deal-card',
  imports: [RouterLink, Icon, Amount, AppDatePipe],
  template: `
    @if (agreement(); as a) {
      @let s = summary(a);
      <section class="deal deal--success" aria-labelledby="deal-title" data-tour="case-deal">
        <app-icon class="deal__icon" name="check-circle" />
        <div class="deal__body">
          <h3 class="deal__title" id="deal-title">Acordo formalizado</h3>
          <p class="deal__text">
            <app-amount [value]="a.totalAmount" /> em {{ plan(a) }}
            @if (s.next) {
              · próxima {{ s.next.number }}ª parcela de <app-amount [value]="s.next.amount" /> em {{ s.next.dueDate | appDate: 'date' }}
            }
          </p>
          <div class="deal__actions">
            @if (canPix() && s.next) {
              <button type="button" class="btn btn-primary btn-sm d-inline-flex align-items-center gap-1" (click)="pix(a, s.next)">
                <app-icon name="qr-code" />
                Gerar Pix da {{ s.next.number }}ª parcela
              </button>
            }
            <button type="button" class="btn btn-link btn-sm" (click)="term(a)">Baixar termo</button>
            @if (canViewAgreement()) {
              <a class="btn btn-link btn-sm" [routerLink]="['/negociacao/acordos', a.id]">Abrir acordo</a>
            }
          </div>
        </div>
      </section>
    } @else if (pending(); as p) {
      <section class="deal deal--info" aria-labelledby="deal-title" data-tour="case-deal">
        <app-icon class="deal__icon" name="hourglass-split" />
        <div class="deal__body">
          <h3 class="deal__title" id="deal-title">Proposta aguardando aprovação</h3>
          <p class="deal__text">
            <app-amount [value]="p.totalAmount" /> em {{ proposalPlan(p) }}. Quem tem alçada suficiente decide; avise o devedor de que a resposta depende de aprovação.
          </p>
          <div class="deal__actions">
            <a class="btn btn-link btn-sm" [routerLink]="['/negociacao/propostas', p.id]">Abrir proposta</a>
          </div>
        </div>
      </section>
    } @else if (ready(); as p) {
      <section class="deal deal--info" aria-labelledby="deal-title" data-tour="case-deal">
        <app-icon class="deal__icon" name="clipboard-check" />
        <div class="deal__body">
          <h3 class="deal__title" id="deal-title">{{ p.status === 'ACCEPTED' ? 'Aceite registrado: falta formalizar' : 'Proposta pronta para o aceite' }}</h3>
          <p class="deal__text">
            <app-amount [value]="p.totalAmount" /> em {{ proposalPlan(p) }}
            @if (p.validUntil) {
              · válida até {{ p.validUntil | appDate: 'date' }}
            }
          </p>
          <div class="deal__actions">
            <button type="button" class="btn btn-primary btn-sm d-inline-flex align-items-center gap-1" (click)="closeRequested.emit(p)">
              <app-icon name="check2-circle" />
              Fechar acordo
            </button>
            <a class="btn btn-link btn-sm" [routerLink]="['/negociacao/propostas', p.id]">Abrir proposta</a>
          </div>
        </div>
      </section>
    }
  `,
  styles: `
    .deal {
      display: flex;
      gap: 10px;
      margin: 8px 0 0;
      padding: 10px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-left-width: 4px;
      border-radius: var(--bs-border-radius);
      font-size: var(--app-text-sm);
    }
    .deal--success {
      border-left-color: var(--bs-success-text-emphasis);
      background: var(--bs-success-bg-subtle);
    }
    .deal--info {
      border-left-color: var(--bs-info-text-emphasis);
      background: var(--bs-info-bg-subtle);
    }
    .deal__icon {
      flex: none;
      font-size: 1.125rem;
    }
    .deal__body {
      flex: 1;
      min-width: 0;
    }
    .deal__title {
      margin: 0;
      font-size: var(--app-text-sm);
      font-weight: 600;
    }
    .deal__text {
      margin: 2px 0 6px;
    }
    .deal__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
    }
  `,
})
export class CaseDealCard {
  readonly caseId = input.required<string>();
  readonly creditorId = input.required<string>();
  readonly debtorId = input.required<string>();
  /** Acordo formalizado agora na estação (mostra o cartão de conclusão). */
  readonly agreementId = input<string | null>(null);
  /** Muda quando a estação cria ou avança uma proposta (recarrega). */
  readonly revision = input(0);
  /** "Fechar acordo" numa proposta pronta para o aceite ou para a formalização. */
  readonly closeRequested = output<ProposalView>();
  /** Pix gerado ou parcela paga: a ficha recarrega. */
  readonly changed = output<void>();

  private readonly auth = inject(AuthService);
  private readonly proposalsApi = inject(ProposalsApi);
  private readonly agreementsApi = inject(AgreementsApi);
  private readonly notifications = inject(NotificationService);

  protected readonly canPix = computed(() => this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE));
  protected readonly canViewAgreement = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  private readonly canReadProposals = computed(() =>
    this.auth.hasAnyPermission([Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE]),
  );

  private readonly agreementResource = rxResource({
    params: () => (this.agreementId() && this.canViewAgreement() ? { id: this.agreementId()!, revision: this.revision() } : undefined),
    stream: ({ params }) => this.agreementsApi.get(params.id).pipe(catchError(() => of(undefined))),
  });
  private readonly loadedAgreement = valueOr(this.agreementResource, undefined);
  protected readonly agreement = computed<AgreementView | null>(() => {
    const a = this.loadedAgreement();
    return a && a.caseId === this.caseId() ? a : null;
  });

  private readonly proposals = rxResource({
    params: () => (this.canReadProposals() ? { creditorId: this.creditorId(), caseId: this.caseId(), revision: this.revision() } : undefined),
    stream: ({ params }) =>
      this.proposalsApi.search({ creditorId: params.creditorId, debtorId: null, caseId: params.caseId, status: null, actorType: null }, { page: 0, size: 20 }).pipe(
        map((page) => page.content),
        catchError(() => of<ProposalView[]>([])),
      ),
  });
  private readonly rows = valueOr(this.proposals, []);

  protected readonly pending = computed(() => this.rows().find((p) => p.status === 'PENDING_APPROVAL') ?? null);
  /** Proposta que o usuário pode levar ao aceite ou à formalização (as mesmas regras da página da proposta). */
  protected readonly ready = computed(() => {
    const perms = {
      userId: this.auth.user()?.id ?? null,
      canPropose: this.auth.hasPermission(Permission.NEGOTIATION_PROPOSE),
      canApprove: this.auth.hasPermission(Permission.NEGOTIATION_APPROVE),
      canFormalize: this.auth.hasPermission(Permission.AGREEMENT_FORMALIZE),
    };
    const today = zonedNow().date;
    return this.rows().find((p) => {
      const a = proposalActions(p, perms, today);
      return a.accept || a.formalize;
    }) ?? null;
  });

  private readonly launcher = inject(PaymentLauncher);

  protected summary(a: AgreementView) {
    return scheduleSummary(a);
  }

  protected plan(a: AgreementView): string {
    return planText({ installments: a.installments.length, downPayment: a.downPayment }).toLowerCase();
  }

  protected proposalPlan(p: ProposalView): string {
    return planText({ installments: p.installments, downPayment: p.downPayment }).toLowerCase();
  }

  protected pix(a: AgreementView, next: InstallmentView): void {
    installmentPix(this.launcher, a, next, this.debtorId()).subscribe((created) => {
      if (created) {
        this.changed.emit();
      }
    });
  }

  protected term(a: AgreementView): void {
    this.agreementsApi.downloadTerm(a.id).subscribe((name) => this.notifications.success(`Termo baixado: ${name}`));
  }
}
