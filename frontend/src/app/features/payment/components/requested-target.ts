import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { TitlesApi } from '../../debt/data/debt.api';
import { AgreementsApi } from '../../negotiation/data/agreement.api';
import { RequestedTargetView } from '../data/payment.models';
import { requestedTargetText } from '../data/payment.rules';

/**
 * Para onde vai o valor de um pagamento informado ainda não aprovado (destino solicitado no
 * registro). Resolve o número das parcelas e a identificação dos títulos quando o perfil permite;
 * sem acesso, mostra a quantidade de itens.
 */
@Component({
  selector: 'app-requested-target',
  imports: [Icon, RouterLink],
  template: `
    <span class="rt" [class.rt--inline]="inline()">
      @if (!inline()) {
        <app-icon class="rt__icon" [name]="target().kind === 'AGREEMENT' ? 'person-hearts' : 'file-earmark-text'" />
      }
      @if (target().agreementId && canViewAgreements() && !inline()) {
        <a [routerLink]="['/negociacao/acordos', target().agreementId]">{{ text() }}</a>
      } @else {
        {{ text() }}
      }
    </span>
  `,
  styles: `
    .rt {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .rt--inline {
      display: inline;
    }
    .rt__icon {
      font-size: 1rem;
      color: var(--bs-secondary-color);
    }
  `,
})
export class RequestedTarget {
  readonly target = input.required<RequestedTargetView>();
  /** Texto corrido (tabelas): sem ícone nem link. */
  readonly inline = input(false);

  private readonly auth = inject(AuthService);
  private readonly agreementsApi = inject(AgreementsApi);
  private readonly titlesApi = inject(TitlesApi);
  protected readonly canViewAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  private readonly canViewTitles = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));

  private readonly agreement = rxResource({
    params: () => (this.target().kind === 'AGREEMENT' && this.target().agreementId && this.canViewAgreements() ? this.target().agreementId! : undefined),
    stream: ({ params }) => this.agreementsApi.get(params).pipe(catchError(() => of(null))),
  });
  private readonly titles = rxResource({
    params: () => (this.target().kind === 'TITLES' && this.target().itemIds.length && this.canViewTitles() ? this.target().itemIds : undefined),
    stream: ({ params }) =>
      forkJoin(
        params.map((id) =>
          this.titlesApi.get(id).pipe(
            map((t) => [id, `${t.externalId}${t.installmentNumber ? '/' + t.installmentNumber : ''}`] as const),
            catchError(() => of(null)),
          ),
        ),
      ),
  });
  private readonly agreementValue = valueOr(this.agreement, null);
  private readonly titleValues = valueOr(this.titles, []);

  protected readonly text = computed(() => {
    const a = this.agreementValue();
    const formalized = a?.formalizedAt ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' }).format(new Date(a.formalizedAt)) : null;
    return requestedTargetText(this.target(), {
      agreementLabel: formalized,
      installmentNumbers: new Map((a?.installments ?? []).map((i) => [i.id, i.number])),
      titleLabels: new Map(this.titleValues().filter((x): x is readonly [string, string] => !!x)),
    });
  });
}
