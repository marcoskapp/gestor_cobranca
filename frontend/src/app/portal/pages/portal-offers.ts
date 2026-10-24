import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { tap } from 'rxjs';
import { Amount, formatAmount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalPix } from '../components/portal-pix';
import { PortalApi, portalMessage } from '../data/portal.api';
import { AcceptanceResult, OfferSummary, OffersView, PixView } from '../data/portal.models';
import { PortalFlow } from '../data/portal-flow';

type Step = 'choose' | 'review' | 'done';

/**
 * Condições para pagar: ofertas pré-autorizadas (à vista e parceladas) → revisão com o resumo do termo
 * → aceite ("Li e aceito") → acordo fechado com o Pix da 1ª parcela/à vista. O servidor revalida tudo.
 */
@Component({
  selector: 'app-portal-offers',
  imports: [FormsModule, RouterLink, MatButtonModule, MatCheckboxModule, MatIconModule, Amount, AppDatePipe, PortalPix],
  template: `
    <a mat-button [routerLink]="['/portal', slug(), 'dividas']"><mat-icon aria-hidden="true">arrow_back</mat-icon>Minhas dívidas</a>

    @if (step() === 'done' && result(); as r) {
      <section class="portal-card portal-center" aria-labelledby="ok-titulo">
        <mat-icon class="portal-big-icon" aria-hidden="true">verified</mat-icon>
        <h1 class="portal-title" id="ok-titulo" tabindex="-1">Acordo fechado!</h1>
        <p>Pague a {{ r.agreement.installments.length > 1 ? '1ª parcela' : 'parcela única' }} até
          {{ r.agreement.installments[0].dueDate | appDate: 'date' }} para confirmar o acordo.</p>
      </section>
      <section class="portal-card">
        @if (pix(); as p) {
          <app-portal-pix [pix]="p" (refresh)="refreshPix(r)" />
        } @else {
          <p class="portal-note" role="status"><mat-icon aria-hidden="true">info</mat-icon>
            <span>O Pix ainda não está pronto. Gere o código em "Meus acordos".</span></p>
        }
        <div class="portal-actions">
          <a mat-stroked-button [routerLink]="['/portal', slug(), 'acordos', r.agreement.id]">Ver meu acordo</a>
        </div>
      </section>
    } @else if (offers.isLoading() && !offers.value()) {
      <p class="portal-loading" role="status" aria-live="polite">Calculando as condições…</p>
    } @else if (offers.error()) {
      <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ errorText() }}</span></p>
      <div class="portal-actions"><a mat-stroked-button [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a></div>
    } @else if (offers.value(); as o) {
      @if (step() === 'choose') {
        <h1 class="portal-title">Escolha como pagar</h1>
        <p class="portal-subtitle">Valor atualizado hoje: <strong><app-amount [value]="o.updatedAmount" /></strong></p>
        @if (o.offers.length === 0) {
          <p class="portal-note" role="note"><mat-icon aria-hidden="true">info</mat-icon>
            <span>Não há condições disponíveis pelo portal para esta dívida. Um atendente pode ajudar.</span></p>
          <div class="portal-actions"><a mat-flat-button [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a></div>
        } @else {
          <fieldset class="portal-options">
            <legend class="cdk-visually-hidden">Condições disponíveis</legend>
            @for (offer of o.offers; track offer.installments) {
              <label class="portal-option" [class.portal-option--selected]="selected() === offer">
                <input type="radio" name="oferta" class="cdk-visually-hidden" [checked]="selected() === offer" (change)="selected.set(offer)" />
                <span class="offer">
                  <span class="offer__title">{{ title(offer) }}</span>
                  <span class="offer__total">Total <app-amount [value]="offer.total" /></span>
                  @if (toNumber(offer.discount) > 0) {
                    <span class="portal-badge">Desconto de <app-amount [value]="offer.discount" /> ({{ offer.discountPercent }}%)</span>
                  }
                  <span class="portal-label">{{ offer.installments === 1 ? 'Pagamento' : '1ª parcela' }} em {{ offer.firstDueDate | appDate: 'date' }}</span>
                </span>
              </label>
            }
          </fieldset>
          <p class="portal-label">Prefere outro número de parcelas (até {{ o.maxInstallments }})? Escolha acima; as que aparecem são as disponíveis.</p>
          <div class="portal-actions">
            <a mat-button [routerLink]="['/portal', slug(), 'atendimento']">Nenhuma cabe no meu bolso</a>
            <button mat-flat-button type="button" [disabled]="!selected()" (click)="review()">Continuar</button>
          </div>
        }
      } @else if (selected(); as offer) {
        <h1 class="portal-title" tabindex="-1">Confira antes de fechar</h1>
        <section class="portal-card">
          <ul class="portal-list" aria-label="Resumo do acordo">
            <li><span>Valor atualizado</span><app-amount [value]="o.updatedAmount" /></li>
            <li><span>Desconto</span><app-amount [value]="offer.discount" /></li>
            @if (toNumber(offer.financingInterest) > 0) {
              <li><span>Juros do parcelamento</span><app-amount [value]="offer.financingInterest" /></li>
            }
            <li><strong>Total a pagar</strong><strong><app-amount [value]="offer.total" /></strong></li>
          </ul>
          <h2 class="portal-card-title">{{ offer.installments === 1 ? 'Pagamento' : 'Parcelas' }}</h2>
          <ul class="portal-list">
            @for (s of offer.schedule; track s.number) {
              <li><span>{{ offer.installments === 1 ? 'Parcela única' : s.number + 'ª parcela' }} · {{ s.dueDate | appDate: 'date' }}</span>
                <app-amount [value]="s.amount" /></li>
            }
          </ul>
          <p class="portal-note" role="note"><mat-icon aria-hidden="true">info</mat-icon><span>
            @if (o.breakOverdueInstallments) {
              Se {{ o.breakOverdueInstallments }} {{ o.breakOverdueInstallments === 1 ? 'parcela ficar' : 'parcelas ficarem' }} sem pagamento
              por mais de {{ o.breakToleranceDays }} dias, o acordo é desfeito e o desconto deixa de valer.
            } Ao confirmar, geramos o termo do acordo, que fica disponível para baixar.
          </span></p>
          <mat-checkbox [(ngModel)]="agreed">Li as condições e aceito fechar este acordo</mat-checkbox>
          @if (error()) {
            <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button mat-button type="button" (click)="step.set('choose')">Voltar</button>
            <button mat-flat-button type="button" [disabled]="!agreed || busy()" (click)="accept(o, offer)">
              {{ busy() ? 'Fechando o acordo…' : 'Fechar acordo de ' + total(offer) }}
            </button>
          </div>
        </section>
      }
    }
  `,
  styles: `
    .portal-options { margin: 0; padding: 0; border: 0; }
    .offer { display: flex; flex-direction: column; gap: 4px; }
    .offer__title { font-size: 18px; font-weight: 600; }
    .offer__total { color: var(--mat-sys-on-surface-variant); }
    .portal-option:focus-within { outline: var(--app-focus-ring); outline-offset: 2px; }
    .portal-card-title { margin: 16px 0 4px; font-size: 16px; font-weight: 600; }
  `,
})
export class PortalOffers {
  readonly slug = input.required<string>();
  readonly portfolioId = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly flow = inject(PortalFlow);
  private readonly router = inject(Router);

  protected readonly offers = rxResource({
    params: () => this.portfolioId(),
    stream: ({ params }) => this.api.offers(params).pipe(tap({
      next: (o) => this.selected.set(o.offers[0] ?? null),
      error: (e: unknown) => this.flow.handlePrivacy(this.slug(), e),
    })),
  });
  protected readonly errorText = computed(() => portalMessage(this.offers.error(), 'Não foi possível calcular as condições.'));
  protected readonly selected = signal<OfferSummary | null>(null);
  protected readonly step = signal<Step>('choose');
  protected readonly result = signal<AcceptanceResult | null>(null);
  protected readonly pix = signal<PixView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected agreed = false;

  protected toNumber(v: number | string): number {
    return Number(v);
  }

  protected title(o: OfferSummary): string {
    if (o.installments === 1) {
      return `À vista: ${formatAmount(o.total)}`;
    }
    const down = Number(o.downPayment);
    const each = Number(o.installmentAmount);
    return Math.abs(down - each) < 0.01
      ? `${o.installments}x de ${formatAmount(each)}`
      : `Entrada de ${formatAmount(down)} + ${o.installments - 1}x de ${formatAmount(each)}`;
  }

  protected total(o: OfferSummary): string {
    return formatAmount(o.total);
  }

  protected review(): void {
    this.agreed = false;
    this.error.set(null);
    this.step.set('review');
  }

  protected accept(o: OffersView, offer: OfferSummary): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.accept({ simulationId: o.simulationId, level: offer.level, installments: offer.installments, termsAccepted: true })
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.result.set(r);
          this.pix.set(r.pix);
          this.step.set('done');
          if (r.pix?.status === 'PENDING') {
            setTimeout(() => this.refreshPix(r), 2500);
          }
        },
        error: (e: unknown) => {
          this.busy.set(false);
          this.error.set(portalMessage(e, 'Não foi possível fechar o acordo agora.'));
        },
      });
  }

  protected refreshPix(r: AcceptanceResult): void {
    const first = r.agreement.installments.find((i) => i.status !== 'PAID' && i.status !== 'CANCELLED');
    if (first) {
      this.api.pix(r.agreement.id, first.id).subscribe({ next: (p) => this.pix.set(p), error: () => undefined });
    }
  }

  protected goAgreements(): void {
    void this.router.navigate(['/portal', this.slug(), 'acordos']);
  }
}
