import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { tap } from 'rxjs';
import { Amount, formatAmount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalPix } from '../components/portal-pix';
import { PortalApi, portalMessage } from '../data/portal.api';
import { AcceptanceResult, AgreementDetail, OfferSummary, OffersView, PixView, PortalChannel, RepresentativeStatus, ServiceRequestView } from '../data/portal.models';
import { CHANNEL_LABELS } from '../data/portal.labels';
import { PortalSite } from '../layout/portal-site';
import { Observable, catchError, of, switchMap } from 'rxjs';
import { PortalFlow } from '../data/portal-flow';
import { Icon } from '../../shared/components/icon/icon';

type Step = 'choose' | 'review' | 'done';

/**
 * Condições para pagar: ofertas pré-autorizadas (à vista e parceladas) → revisão com o resumo do termo
 * → aceite ("Li e aceito") → acordo fechado com o Pix da 1ª parcela/à vista. O servidor revalida tudo.
 */
@Component({
  selector: 'app-portal-offers',
  imports: [Icon, FormsModule, RouterLink, Amount, AppDatePipe, PortalPix],
  template: `
    <a class="btn btn-link portal-btn-link" [routerLink]="['/portal', slug(), 'dividas']"><app-icon name="arrow-left" /> Minhas dívidas</a>

    @if (step() === 'done' && handoff(); as h) {
      <section class="portal-card text-center" aria-labelledby="handoff-titulo">
        <app-icon class="portal-big-icon" name="headset" />
        <h1 class="portal-title" id="handoff-titulo" tabindex="-1">Recebemos sua escolha</h1>
        <p>Um atendente vai concluir o acordo com a empresa pelo {{ channelLabels[h.preferredChannel] }}. Protocolo <strong>{{ h.protocol }}</strong>.</p>
        <div class="portal-actions">
          <a class="btn btn-outline-primary portal-btn-outline" [routerLink]="['/portal', slug(), 'atendimento']">Ver meus pedidos</a>
        </div>
      </section>
    } @else if (step() === 'done' && result()?.agreement; as agreement) {
      <section class="portal-card text-center" aria-labelledby="ok-titulo">
        <app-icon class="portal-big-icon" name="patch-check" />
        <h1 class="portal-title" id="ok-titulo" tabindex="-1">Acordo fechado!</h1>
        <p>Pague a {{ agreement.installments.length > 1 ? '1ª parcela' : 'parcela única' }} até
          {{ agreement.installments[0].dueDate | appDate: 'date' }} para confirmar o acordo.</p>
      </section>
      <section class="portal-card">
        @if (pix(); as p) {
          <app-portal-pix [pix]="p" (refresh)="refreshPix(agreement)" />
        } @else {
          <p class="alert alert-info d-flex align-items-start gap-2" role="status"><app-icon name="info-circle" />
            <span>O Pix ainda não está pronto. Gere o código em "Meus acordos".</span></p>
        }
        <div class="portal-actions">
          <a class="btn btn-outline-primary portal-btn-outline" [routerLink]="['/portal', slug(), 'acordos', agreement.id]">Ver meu acordo</a>
        </div>
      </section>
    } @else if (offers.isLoading() && !offers.value()) {
      <p class="py-4 text-center text-body-secondary" role="status" aria-live="polite">Calculando as condições…</p>
    } @else if (offers.error()) {
      <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ errorText() }}</span></p>
      <div class="portal-actions"><a class="btn btn-outline-primary portal-btn-outline" [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a></div>
    } @else if (offers.value(); as o) {
      @if (step() === 'choose') {
        <h1 class="portal-title">Escolha como pagar</h1>
        <p class="mb-3 text-body-secondary">Valor atualizado hoje: <strong><app-amount [value]="o.updatedAmount" /></strong></p>
        @if (o.offers.length === 0) {
          <p class="alert alert-info d-flex align-items-start gap-2" role="note"><app-icon name="info-circle" />
            <span>Não há condições disponíveis pelo portal para esta dívida. Um atendente pode ajudar.</span></p>
          <div class="portal-actions"><a class="btn btn-primary portal-btn" [routerLink]="['/portal', slug(), 'atendimento']">Falar com atendente</a></div>
        } @else {
          <fieldset class="portal-options">
            <legend class="visually-hidden">Condições disponíveis</legend>
            @for (offer of o.offers; track offer.installments) {
              <label class="portal-option" [class.portal-option--selected]="selected() === offer">
                <input type="radio" name="oferta" class="visually-hidden" [checked]="selected() === offer" (change)="selected.set(offer)" />
                <span class="offer">
                  <span class="offer__title">{{ title(offer) }}</span>
                  <span class="offer__total">Total <app-amount [value]="offer.total" /></span>
                  @if (toNumber(offer.discount) > 0) {
                    <span class="badge rounded-pill bg-success-subtle text-success-emphasis align-self-start">Desconto de <app-amount [value]="offer.discount" /> ({{ percent(offer.discountPercent) }})</span>
                  }
                  <span class="portal-label">{{ offer.installments === 1 ? 'Pagamento' : '1ª parcela' }} em {{ offer.firstDueDate | appDate: 'date' }}</span>
                </span>
              </label>
            }
          </fieldset>
          <p class="portal-label">Prefere outro número de parcelas (até {{ o.maxInstallments }})? Escolha acima; as que aparecem são as disponíveis.</p>
          <div class="portal-actions">
            <a class="btn btn-link portal-btn-link" [routerLink]="['/portal', slug(), 'atendimento']">Nenhuma condição cabe no meu orçamento</a>
            <button class="btn btn-primary portal-btn" type="button" [disabled]="!selected()" (click)="review()">Continuar</button>
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
          <p class="alert alert-info d-flex align-items-start gap-2" role="note"><app-icon name="info-circle" /><span>
            @if (o.breakOverdueInstallments) {
              Se {{ o.breakOverdueInstallments }} {{ o.breakOverdueInstallments === 1 ? 'parcela ficar' : 'parcelas ficarem' }} sem pagamento
              por mais de {{ o.breakToleranceDays }} dias, o acordo é desfeito e o desconto deixa de valer.
            } Ao confirmar, geramos o termo do acordo, que fica disponível para baixar.
          </span></p>
          @if (company()?.required) {
            <!-- empresa (B4): quem está negociando; a tela é a mesma quando o CPF confere e quando não confere -->
            <fieldset class="portal-fieldset" aria-describedby="rep-ajuda">
              <legend>Quem está negociando pela empresa</legend>
              <p class="portal-label" id="rep-ajuda">
                @if (company()?.humanCompletion) {
                  Um atendente vai concluir o acordo com você.
                } @else {
                  Informe seus dados. Se precisarmos confirmar algo, um atendente conclui o acordo com você.
                }
              </p>
              @if (!company()?.declared) {
                <label class="form-label" for="rep-nome">Seu nome</label>
                <input class="form-control" id="rep-nome" autocomplete="name" maxlength="200" [(ngModel)]="repName" />
                <label class="form-label mt-2" for="rep-cpf">Seu CPF</label>
                <input class="form-control" id="rep-cpf" inputmode="numeric" autocomplete="off" maxlength="14" [(ngModel)]="repCpf" />
              } @else {
                <p class="mb-2">Negociando: <strong>{{ company()?.declaredName }}</strong></p>
              }
              <label class="form-label mt-2" for="rep-canal">Se precisarmos falar com você</label>
              <select class="form-select" id="rep-canal" [(ngModel)]="repChannel">
                @for (c of channels(); track c) {
                  <option [ngValue]="c">{{ channelLabels[c] }}</option>
                }
              </select>
            </fieldset>
          }
          <div class="form-check portal-radio">
            <input class="form-check-input portal-check" type="checkbox" id="portal-agree" [(ngModel)]="agreed" />
            <label class="form-check-label" for="portal-agree">Li as condições e aceito fechar este acordo</label>
          </div>
          @if (error()) {
            <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button class="btn btn-link portal-btn-link" type="button" (click)="step.set('choose')">Voltar</button>
            <button class="btn btn-primary portal-btn" type="button" [disabled]="!agreed || busy() || !representativeReady()" (click)="accept(o, offer)">
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
    .offer__total { color: var(--bs-secondary-color); }
    .portal-option:focus-within { outline: var(--app-focus-ring); outline-offset: 2px; }
    .portal-card-title { margin: 16px 0 4px; font-size: 16px; font-weight: 600; }
    .portal-radio { display: flex; align-items: center; gap: 8px; min-height: 44px; }
    .portal-fieldset { margin: 16px 0; padding: 0; border: 0; }
    .portal-fieldset legend { margin-bottom: 4px; font-size: 1rem; font-weight: 600; }
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
  protected readonly channelLabels = CHANNEL_LABELS;
  /** Marca do portal (moldura); canais do código = canais por onde o atendente pode responder. */
  private readonly site = inject(PortalSite, { optional: true });
  protected readonly channels = computed<PortalChannel[]>(() => this.site?.site()?.otpChannels ?? ['WHATSAPP']);
  /** Empresa: quem negocia (pessoa física → `required = false`; sem resposta, segue como pessoa física). */
  private readonly representative = rxResource({
    stream: () => this.api.representative().pipe(catchError(() => of<RepresentativeStatus | null>(null))),
  });
  protected readonly company = computed(() => this.representative.value() ?? null);
  /** Pedido de atendimento criado no lugar do acordo (empresa sem representante confirmado). */
  protected readonly handoff = signal<ServiceRequestView | null>(null);
  /** CPF e nome digitados: só em memória, enviados no aceite. */
  protected repName = '';
  protected repCpf = '';
  protected repChannel: PortalChannel | null = null;

  protected representativeReady(): boolean {
    const c = this.company();
    if (!c?.required) {
      return true;
    }
    return (c.declared || (this.repName.trim().length > 0 && this.repCpf.replace(/\D/g, '').length === 11)) && !!this.channelOrDefault();
  }

  private channelOrDefault(): PortalChannel | null {
    return this.repChannel ?? this.channels()[0] ?? null;
  }

  protected percent(v: number | string): string {
    return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(Number(v)) + '%';
  }

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
    const company = this.company();
    // empresa: primeiro quem está negociando (mesma resposta quando o CPF confere e quando não confere)
    const declared: Observable<unknown> =
      company?.required && !company.declared
        ? this.api.declareRepresentative({ cpf: this.repCpf.replace(/\D/g, ''), name: this.repName.trim() })
        : of(null);
    declared
      .pipe(
        switchMap(() =>
          this.api.accept({
            simulationId: o.simulationId,
            level: offer.level,
            installments: offer.installments,
            termsAccepted: true,
            ...(company?.required ? { preferredChannel: this.channelOrDefault() } : {}),
          }),
        ),
      )
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.repCpf = '';
          this.result.set(r);
          this.handoff.set(r.serviceRequest ?? null);
          this.pix.set(r.pix);
          this.step.set('done');
          if (r.agreement && r.pix?.status === 'PENDING') {
            const agreement = r.agreement;
            setTimeout(() => this.refreshPix(agreement), 2500);
          }
        },
        error: (e: unknown) => {
          this.busy.set(false);
          this.error.set(portalMessage(e, 'Não foi possível fechar o acordo agora.'));
        },
      });
  }

  protected refreshPix(agreement: AgreementDetail): void {
    const first = agreement.installments.find((i) => i.status !== 'PAID' && i.status !== 'CANCELLED');
    if (first) {
      this.api.pix(agreement.id, first.id).subscribe({ next: (p) => this.pix.set(p), error: () => undefined });
    }
  }

  protected goAgreements(): void {
    void this.router.navigate(['/portal', this.slug(), 'acordos']);
  }
}
