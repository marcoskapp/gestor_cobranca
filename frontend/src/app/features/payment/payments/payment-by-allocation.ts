import { Component, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { PaymentsApi } from '../data/payment.api';

/**
 * Link do razão do título para o pagamento: a referência do lançamento é a alocação (o `paymentId` dos eventos), então
 * esta rota pede ao servidor o pagamento dono dela e troca a URL (`replaceUrl`) pela ficha do pagamento — o "voltar"
 * leva de novo ao título, não a esta rota. Fora do escopo de credores ou inexistente: mesma mensagem (erro silencioso
 * na chamada: a mensagem é da tela). A consulta é um `rxResource`, cancelada ao sair ou ao trocar de alocação.
 */
@Component({
  selector: 'app-payment-by-allocation',
  imports: [EmptyState, RouterLink, Skeleton],
  template: `
    @if (resource.error()) {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível abrir o pagamento deste lançamento."
                       message="Ele pode estar fora do seu escopo de credores. Tente de novo ou procure na lista de pagamentos.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/pagamentos">Ir para Pagamentos</a>
      </app-empty-state>
    } @else {
      <app-skeleton variant="card" [rows]="3" />
      <p class="visually-hidden" role="status">Abrindo o pagamento…</p>
    }
  `,
})
export class PaymentByAllocation {
  readonly allocationId = input.required<string>();
  private readonly api = inject(PaymentsApi);
  private readonly router = inject(Router);
  protected readonly resource = rxResource({ params: () => this.allocationId(), stream: ({ params }) => this.api.byAllocation(params) });

  constructor() {
    effect(() => {
      // `value()` com erro lança: só lê quando há valor
      if (this.resource.hasValue()) {
        void this.router.navigate(['/pagamentos', this.resource.value().id], { replaceUrl: true });
      }
    });
  }
}
