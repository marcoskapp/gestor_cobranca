import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { tap } from 'rxjs';
import { Amount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalApi, portalMessage } from '../data/portal.api';
import { AGREEMENT_LABELS } from '../data/portal.labels';
import { PortalFlow } from '../data/portal-flow';
import { Icon } from '../../shared/components/icon/icon';

@Component({
  selector: 'app-portal-agreements',
  imports: [Icon, RouterLink, Amount, AppDatePipe],
  template: `
    <h1 class="portal-title">Meus acordos</h1>
    @if (list.isLoading() && !list.value()) {
      <p class="portal-loading" role="status" aria-live="polite">Carregando…</p>
    } @else if (list.error()) {
      <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ errorText() }}</span></p>
    } @else if ((list.value() ?? []).length === 0) {
      <section class="portal-card portal-center">
        <p>Você ainda não tem acordos.</p>
        <div class="portal-actions"><a class="btn btn-primary portal-btn" [routerLink]="['/portal', slug(), 'dividas']">Ver minhas dívidas</a></div>
      </section>
    } @else {
      @for (a of list.value()!; track a.id) {
        <a class="portal-card portal-agreement" [routerLink]="['/portal', slug(), 'acordos', a.id]">
          <span class="portal-agreement__head">
            <strong>{{ labels[a.status] ?? a.status }}</strong>
            <app-icon name="chevron-right" />
          </span>
          <span>Total <app-amount [value]="a.totalAmount" /> em {{ a.installments }} {{ a.installments === 1 ? 'pagamento' : 'parcelas' }}</span>
          <span class="portal-label">Pago até agora: <app-amount [value]="a.paidAmount" /></span>
          @if (a.nextDueDate) {
            <span class="portal-label">Próximo vencimento: {{ a.nextDueDate | appDate: 'date' }} — <app-amount [value]="a.nextAmount" /></span>
          }
        </a>
      }
    }
  `,
  styles: `
    .portal-agreement { display: flex; flex-direction: column; gap: 4px; color: inherit; text-decoration: none; }
    .portal-agreement__head { display: flex; justify-content: space-between; align-items: center; }
  `,
})
export class PortalAgreements {
  readonly slug = input.required<string>();
  private readonly api = inject(PortalApi);
  private readonly flow = inject(PortalFlow);
  protected readonly labels = AGREEMENT_LABELS;
  protected readonly list = rxResource({
    stream: () => this.api.agreements().pipe(tap({ error: (e: unknown) => this.flow.handlePrivacy(this.slug(), e) })),
  });
  protected readonly errorText = computed(() => portalMessage(this.list.error(), 'Não foi possível carregar seus acordos.'));
}
