import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { tap } from 'rxjs';
import { Amount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalApi, portalMessage } from '../data/portal.api';
import { UNAVAILABLE_LABELS } from '../data/portal.labels';
import { PortalFlow } from '../data/portal-flow';
import { PortfolioDebt, TitleSummary } from '../data/portal.models';
import { Icon } from '../../shared/components/icon/icon';

/** Minhas dívidas: carteiras habilitadas do credor, valor atualizado hoje e o caminho para negociar. */
@Component({
  selector: 'app-portal-debts',
  imports: [Icon, RouterLink, Amount, AppDatePipe, NgTemplateOutlet],
  template: `
    <h1 class="portal-title">Minhas dívidas</h1>
    <p class="mb-3 text-body-secondary">Com {{ debts.value()?.creditorName ?? '…' }}</p>

    @if (debts.isLoading() && !debts.value()) {
      <p class="py-4 text-center text-body-secondary" role="status" aria-live="polite">Buscando suas dívidas…</p>
    } @else if (debts.error()) {
      <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ errorText() }}</span></p>
      <div class="portal-actions"><button class="btn btn-outline-primary portal-btn-outline" type="button" (click)="debts.reload()">Tentar de novo</button></div>
    } @else if ((debts.value()?.portfolios ?? []).length === 0) {
      <section class="portal-card text-center">
        <app-icon class="portal-big-icon" name="check2-circle" />
        <h2 class="portal-title">Nada para negociar agora</h2>
        <p>Não há dívidas em aberto para negociar neste portal. Se você tem um acordo, veja as parcelas e pague pelo Pix em "Meus acordos".</p>
        <div class="portal-actions">
          <a class="btn btn-primary portal-btn" [routerLink]="['/portal', slug(), 'acordos']">Ver meus acordos</a>
          <a class="btn btn-outline-primary portal-btn-outline" [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a>
        </div>
      </section>
    } @else {
      @for (c of cards(); track c.p.portfolioId) {
        @let p = c.p;
        <section class="portal-card" [attr.aria-labelledby]="'divida-' + p.portfolioId">
          <h2 class="portal-card-title" [id]="'divida-' + p.portfolioId">{{ p.portfolioName ?? 'Dívida' }}</h2>
          @if (p.updatedAmount !== null) {
            <p class="portal-label">Valor atualizado hoje</p>
            <p class="portal-amount"><app-amount [value]="p.updatedAmount" /></p>
            <p class="portal-label">Inclui juros, multa e encargos até hoje. As condições podem ter desconto.</p>
            @if (c.outside.length) {
              <p class="portal-label mt-2">{{ c.outside.length === 1
                ? 'Não inclui 1 parcela, que está na lista abaixo. Para ela, fale com um atendente.'
                : 'Não inclui ' + c.outside.length + ' parcelas, que estão na lista abaixo. Para elas, fale com um atendente.' }}</p>
            }
          } @else {
            <p class="portal-label">Valor em aberto</p>
            <p class="portal-amount"><app-amount [value]="p.openBalance" /></p>
            @if (!p.activeAgreementId) {
              <p class="portal-label">Sem juros e encargos de hoje. Um atendente informa o valor para pagar.</p>
            }
          }
          <details class="portal-details">
            <summary>Ver {{ p.titles.length }} {{ p.titles.length === 1 ? 'parcela em aberto' : 'parcelas em aberto' }}</summary>
            @if (c.outside.length) {
              @if (c.inside.length) {
                <h3 class="portal-label fw-semibold mt-2 mb-0" [id]="'dentro-' + p.portfolioId">Nas condições do portal</h3>
                <ul class="portal-list" [attr.aria-labelledby]="'dentro-' + p.portfolioId">
                  @for (t of c.inside; track t.titleId) {
                    <ng-container *ngTemplateOutlet="parcela; context: { $implicit: t }" />
                  }
                </ul>
              }
              <h3 class="portal-label fw-semibold mt-2 mb-0" [id]="'fora-' + p.portfolioId">Fora das condições do portal: fale com um atendente</h3>
              <ul class="portal-list" [attr.aria-labelledby]="'fora-' + p.portfolioId">
                @for (t of c.outside; track t.titleId) {
                  <ng-container *ngTemplateOutlet="parcela; context: { $implicit: t }" />
                }
              </ul>
            } @else {
              <ul class="portal-list">
                @for (t of p.titles; track t.titleId) {
                  <ng-container *ngTemplateOutlet="parcela; context: { $implicit: t }" />
                }
              </ul>
            }
          </details>
          @if (p.negotiable) {
            <div class="portal-actions">
              <a class="btn btn-primary portal-btn" [routerLink]="['/portal', slug(), 'dividas', p.portfolioId]">Ver condições para pagar</a>
              @if (c.outside.length) {
                <a class="btn btn-link portal-btn-link" [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente sobre {{ c.outside.length === 1 ? 'a parcela fora' : 'as parcelas fora' }}</a>
              }
            </div>
          } @else {
            <p class="alert alert-info d-flex align-items-start gap-2" role="note"><app-icon name="info-circle" /><span>{{ unavailable[p.unavailableReason!] }}</span></p>
            <div class="portal-actions">
              @if (p.activeAgreementId) {
                <a class="btn btn-primary portal-btn" [routerLink]="['/portal', slug(), 'acordos', p.activeAgreementId]">Ver meu acordo</a>
              } @else {
                <a class="btn btn-outline-primary portal-btn-outline" [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a>
              }
            </div>
          }
        </section>
      }
    }

    <ng-template #parcela let-t>
      <li>
        <span>Venceu em {{ t.dueDate | appDate: 'date' }}@if (t.reference) {<br /><small>{{ t.reference }}</small>}</span>
        <app-amount [value]="t.openBalance" />
      </li>
    </ng-template>
  `,
  styles: `
    .portal-card-title { margin: 0 0 8px; font-size: 18px; font-weight: 600; }
    .portal-details { margin-top: 12px; }
    .portal-details summary { min-height: 44px; display: flex; align-items: center; cursor: pointer; color: var(--portal-ink); font-weight: 500; }
  `,
})
export class PortalDebts {
  readonly slug = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly flow = inject(PortalFlow);
  protected readonly unavailable = UNAVAILABLE_LABELS;
  protected readonly debts = rxResource({
    stream: () => this.api.debts().pipe(tap({ error: (e: unknown) => this.flow.handlePrivacy(this.slug(), e) })),
  });
  /** Com condição, separa uma vez por resposta as parcelas que entram no valor das que ficam fora (sem o motivo). */
  protected readonly cards = computed(() =>
    (this.debts.value()?.portfolios ?? []).map((p: PortfolioDebt) => {
      const split = p.updatedAmount !== null;
      const inside: TitleSummary[] = split ? p.titles.filter((t) => t.inOffer) : p.titles;
      const outside: TitleSummary[] = split ? p.titles.filter((t) => !t.inOffer) : [];
      return { p, inside, outside };
    }),
  );

  protected readonly errorText = computed(() => portalMessage(this.debts.error(), 'Não foi possível carregar suas dívidas.'));
}
