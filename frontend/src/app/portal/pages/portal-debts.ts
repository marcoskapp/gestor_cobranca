import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { tap } from 'rxjs';
import { Amount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalApi, portalMessage } from '../data/portal.api';
import { UNAVAILABLE_LABELS } from '../data/portal.labels';
import { PortalFlow } from '../data/portal-flow';

/** Minhas dívidas: carteiras habilitadas do credor, valor atualizado hoje e o caminho para negociar. */
@Component({
  selector: 'app-portal-debts',
  imports: [RouterLink, MatButtonModule, MatIconModule, Amount, AppDatePipe],
  template: `
    <h1 class="portal-title">Minhas dívidas</h1>
    <p class="portal-subtitle">Com {{ debts.value()?.creditorName ?? '…' }}</p>

    @if (debts.isLoading() && !debts.value()) {
      <p class="portal-loading" role="status" aria-live="polite">Buscando suas dívidas…</p>
    } @else if (debts.error()) {
      <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ errorText() }}</span></p>
      <div class="portal-actions"><button mat-stroked-button type="button" (click)="debts.reload()">Tentar de novo</button></div>
    } @else if ((debts.value()?.portfolios ?? []).length === 0) {
      <section class="portal-card portal-center">
        <mat-icon class="portal-big-icon" aria-hidden="true">task_alt</mat-icon>
        <h2 class="portal-title">Nenhuma dívida em aberto</h2>
        <p>Não encontramos dívidas em aberto neste portal. Se tiver dúvidas, fale com um atendente.</p>
        <div class="portal-actions"><a mat-stroked-button [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a></div>
      </section>
    } @else {
      @for (p of debts.value()!.portfolios; track p.portfolioId) {
        <section class="portal-card" [attr.aria-labelledby]="'divida-' + p.portfolioId">
          <h2 class="portal-card-title" [id]="'divida-' + p.portfolioId">{{ p.portfolioName ?? 'Dívida' }}</h2>
          @if (p.updatedAmount !== null) {
            <p class="portal-label">Valor atualizado hoje</p>
            <p class="portal-amount"><app-amount [value]="p.updatedAmount" /></p>
            <p class="portal-label">Inclui juros, multa e encargos até hoje. As condições podem ter desconto.</p>
          } @else {
            <p class="portal-label">Valor em aberto</p>
            <p class="portal-amount"><app-amount [value]="p.openBalance" /></p>
          }
          <details class="portal-details">
            <summary>Ver {{ p.titles.length }} {{ p.titles.length === 1 ? 'parcela em aberto' : 'parcelas em aberto' }}</summary>
            <ul class="portal-list">
              @for (t of p.titles; track t.titleId) {
                <li>
                  <span>Venceu em {{ t.dueDate | appDate: 'date' }}@if (t.reference) {<br /><small>{{ t.reference }}</small>}</span>
                  <app-amount [value]="t.openBalance" />
                </li>
              }
            </ul>
          </details>
          @if (p.negotiable) {
            <div class="portal-actions">
              <a mat-flat-button [routerLink]="['/portal', slug(), 'dividas', p.portfolioId]">Ver condições para pagar</a>
            </div>
          } @else {
            <p class="portal-note" role="note"><mat-icon aria-hidden="true">info</mat-icon><span>{{ unavailable[p.unavailableReason!] }}</span></p>
            <div class="portal-actions">
              @if (p.activeAgreementId) {
                <a mat-flat-button [routerLink]="['/portal', slug(), 'acordos', p.activeAgreementId]">Ver meu acordo</a>
              } @else {
                <a mat-stroked-button [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a>
              }
            </div>
          }
        </section>
      }
    }
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
  protected readonly errorText = computed(() => portalMessage(this.debts.error(), 'Não foi possível carregar suas dívidas.'));
}
