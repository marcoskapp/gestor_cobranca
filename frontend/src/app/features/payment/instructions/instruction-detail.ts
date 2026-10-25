import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { InstructionsApi } from '../data/payment.api';
import { CHANNEL_OPTIONS, INSTRUCTION_TYPE_LABELS } from '../data/payment.labels';
import { InstructionView } from '../data/payment.models';
import { InstructionPanel } from './instruction-panel';

/** Ficha da instrução: QR Code e copia e cola enquanto aberta, destino, identificadores no PSP. */
@Component({
  selector: 'app-instruction-detail',
  imports: [RouterLink, AppDatePipe, EmptyState, PageHeader, Skeleton, InstructionPanel],
  template: `
    @if (resource.isLoading() && !instruction()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a instrução." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (instruction(); as i) {
      <app-page-header [title]="types[i.type] + ' · ' + debtors.name(i.debtorId)" [subtitle]="'Gerada em ' + (i.createdAt | appDate) + ' · ' + creditors.name(i.creditorId)" />
      <div class="id-grid">
        <section class="app-card" aria-labelledby="id-pix">
          <h2 class="app-card__title" id="id-pix">Pagamento pelo devedor</h2>
          <app-instruction-panel [instruction]="i" (changed)="resource.set($event)" />
        </section>
        <section class="app-card" aria-labelledby="id-facts">
          <h2 class="app-card__title" id="id-facts">Detalhes</h2>
          <dl class="app-definition-list">
            <dt>Destino</dt>
            <dd>
              @if (i.agreementId) {
                @if (canViewAgreements()) {
                  <a [routerLink]="['/negociacao/acordos', i.agreementId]">{{ i.installmentNumber ? i.installmentNumber + 'ª parcela do acordo' : 'Parcela do acordo' }}</a>
                } @else {
                  {{ i.installmentNumber ? i.installmentNumber + 'ª parcela do acordo' : 'Parcela do acordo' }}
                }
              } @else if (i.titleId) {
                @if (canViewTitles()) {
                  <a [routerLink]="['/titulos', i.titleId]">Título avulso</a>
                } @else {
                  Título avulso
                }
              }
            </dd>
            @if (i.caseId && canViewCases()) {
              <dt>Caso</dt>
              <dd><a [routerLink]="['/cobranca/casos', i.caseId]">Abrir caso</a></dd>
            }
            <dt>Canal de envio</dt>
            <dd>{{ channel(i.channel) }}</dd>
            <dt>txid</dt>
            <dd class="app-mono">{{ i.txid ?? '—' }}</dd>
            @if (i.paymentId) {
              <dt>Pagamento</dt>
              <dd><a [routerLink]="['/pagamentos', i.paymentId]">Abrir pagamento</a></dd>
            }
          </dl>
        </section>
      </div>
    }
  `,
  styles: `
    .id-grid {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(260px, 340px);
      gap: var(--app-gap);
      align-items: start;
      /* espaçamento pelo gap da grade */
      .app-card + .app-card {
        margin-top: 0;
      }
    }
    @media (max-width: 959px) {
      .id-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class InstructionDetail {
  readonly id = input.required<string>();

  private readonly api = inject(InstructionsApi);
  private readonly auth = inject(AuthService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly types = INSTRUCTION_TYPE_LABELS;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly instruction = valueOr<InstructionView | undefined>(this.resource, undefined);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.instruction()?.creditorId]);
  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.instruction()?.debtorId], { batch: 100 });
  protected readonly canViewAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly canViewTitles = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));
  protected readonly canViewCases = computed(() => this.auth.hasPermission(Permission.CASE_VIEW));

  constructor() {
    effect(() => {
      const i = this.instruction();
      this.breadcrumbs.setCurrentLabel(i ? this.debtors.name(i.debtorId) || null : null);
    });
  }

  protected channel(code: string | null): string {
    return code ? (CHANNEL_OPTIONS.find((c) => c.value === code)?.label ?? code) : 'Não informado';
  }
}
