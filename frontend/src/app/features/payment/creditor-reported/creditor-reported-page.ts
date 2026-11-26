import { Component } from '@angular/core';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { urlTab } from '../../../shared/data/url-state';
import { CreditorImportsTab } from './creditor-imports-tab';
import { CreditorPaymentsTab } from './creditor-payments-tab';
import { ReversalHoldsTab } from './reversal-holds-tab';

/**
 * Baixas informadas pelo credor (H07, decisão 1466): o que o credor recebeu diretamente e avisou à assessoria — pela
 * integração, pelo portal ou por um operador — e o que a carga do credor zerou ou suspendeu. Só consulta (sem
 * exportação); `PAYMENT_VIEW` e escopo por credor no servidor. Aba na URL (`?aba=carga`, `?aba=estornos`). A terceira aba
 * (H07 parte 3) mostra os estornos de pagamento recebido pelo credor que pararam a cobrança até a conferência.
 */
@Component({
  selector: 'app-creditor-reported-page',
  imports: [NgbNavModule, PageHeader, CreditorPaymentsTab, CreditorImportsTab, ReversalHoldsTab],
  template: `
    <app-page-header title="Baixas informadas pelo credor"
                     subtitle="Pagamentos feitos diretamente ao credor, títulos que a carga do credor zerou ou suspendeu e estornos em conferência." />
    <ul ngbNav #tabs="ngbNav" [activeId]="tab.value()" (activeIdChange)="tab.set($event)" class="nav-underline app-tabs" data-tour="creditor-reported-tabs">
      <li ngbNavItem="pagamentos">
        <button ngbNavLink type="button">Pagamentos informados pelo credor</button>
        <ng-template ngbNavContent><app-creditor-payments-tab /></ng-template>
      </li>
      <li ngbNavItem="carga">
        <button ngbNavLink type="button">Pela carga do credor</button>
        <ng-template ngbNavContent><app-creditor-imports-tab /></ng-template>
      </li>
      <li ngbNavItem="estornos">
        <button ngbNavLink type="button" data-tour="reversal-holds-tab">Estornos em conferência</button>
        <ng-template ngbNavContent><app-reversal-holds-tab /></ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="tabs"></div>
  `,
})
export class CreditorReportedPage {
  protected readonly tab = urlTab(['pagamentos', 'carga', 'estornos'] as const, 'pagamentos');
}
