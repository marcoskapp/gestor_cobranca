import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Amount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalPix } from '../components/portal-pix';
import { PortalApi, portalMessage } from '../data/portal.api';
import { AGREEMENT_LABELS, INSTALLMENT_LABELS } from '../data/portal.labels';
import { InstallmentItem, PixView } from '../data/portal.models';
import { Icon } from '../../shared/components/icon/icon';

/** Acordo e parcelas: 2ª via do Pix por parcela em aberto e o termo aceito (PDF). */
@Component({
  selector: 'app-portal-agreement',
  imports: [Icon, RouterLink, Amount, AppDatePipe, PortalPix],
  template: `
    <a class="btn btn-link portal-btn-link" [routerLink]="['/portal', slug(), 'acordos']"><app-icon name="arrow-left" /> Meus acordos</a>
    @if (agreement.error()) {
      <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ errorText() }}</span></p>
    } @else if (agreement.value(); as a) {
      <h1 class="portal-title">{{ labels[a.status] ?? a.status }}</h1>
      <section class="portal-card">
        <ul class="portal-list" aria-label="Resumo do acordo">
          <li><span>Total do acordo</span><app-amount [value]="a.totalAmount" /></li>
          <li><span>Desconto concedido</span><app-amount [value]="a.discountAmount" /></li>
          <li><span>Pago</span><app-amount [value]="a.paidAmount" /></li>
          <li><strong>Falta pagar</strong><strong><app-amount [value]="a.remainingAmount" /></strong></li>
        </ul>
        <p class="portal-label">Fechado em {{ a.formalizedAt | appDate }}.</p>
        @if (a.termAvailable) {
          <div class="portal-actions">
            <button class="btn btn-outline-primary portal-btn-outline" type="button" (click)="downloadTerm(a.id)">
              <app-icon name="file-earmark-text" /> Baixar termo do acordo (PDF)
            </button>
          </div>
        }
      </section>
      <h2 class="portal-card-title">Parcelas</h2>
      @for (i of a.installments; track i.id) {
        <section class="portal-card" [attr.aria-label]="'Parcela ' + i.number">
          <div class="installment">
            <span><strong>{{ a.installments.length === 1 ? 'Parcela única' : i.number + 'ª parcela' }}</strong><br />
              <span class="portal-label">Vence em {{ i.dueDate | appDate: 'date' }} · {{ statusLabels[i.status] }}</span></span>
            <app-amount [value]="i.amount" />
          </div>
          @if (open(i) && pixFor() === i.id && pix(); as p) {
            <app-portal-pix [pix]="p" (refresh)="loadPix(a.id, i)" />
          } @else if (open(i) && ['FORMALIZED', 'IN_PAYMENT'].includes(a.status)) {
            <div class="portal-actions">
              <button class="btn btn-primary portal-btn" type="button" (click)="loadPix(a.id, i)" [disabled]="loading() === i.id">
                <app-icon name="qr-code" /> {{ loading() === i.id ? 'Gerando…' : 'Pagar com Pix' }}
              </button>
            </div>
          }
        </section>
      }
      @if (error()) {
        <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
      }
    } @else {
      <p class="py-4 text-center text-body-secondary" role="status" aria-live="polite">Carregando…</p>
    }
  `,
  styles: `
    .installment { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
    .portal-card-title { margin: 8px 0; font-size: 18px; font-weight: 600; }
  `,
})
export class PortalAgreement {
  readonly slug = input.required<string>();
  readonly agreementId = input.required<string>();

  private readonly api = inject(PortalApi);
  protected readonly labels = AGREEMENT_LABELS;
  protected readonly statusLabels = INSTALLMENT_LABELS;
  protected readonly agreement = rxResource({ params: () => this.agreementId(), stream: ({ params }) => this.api.agreement(params) });
  protected readonly errorText = computed(() => portalMessage(this.agreement.error(), 'Acordo não encontrado.'));
  protected readonly pix = signal<PixView | null>(null);
  protected readonly pixFor = signal<string | null>(null);
  protected readonly loading = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected open(i: InstallmentItem): boolean {
    return i.status === 'OPEN' || i.status === 'OVERDUE' || i.status === 'PARTIALLY_PAID';
  }

  protected loadPix(agreementId: string, i: InstallmentItem): void {
    this.loading.set(i.id);
    this.error.set(null);
    this.api.pix(agreementId, i.id).subscribe({
      next: (p) => {
        this.loading.set(null);
        this.pixFor.set(i.id);
        this.pix.set(p);
      },
      error: (e: unknown) => {
        this.loading.set(null);
        this.error.set(portalMessage(e, 'Não foi possível gerar o Pix agora.'));
      },
    });
  }

  protected downloadTerm(id: string): void {
    this.api.downloadTerm(id).subscribe({ error: (e: unknown) => this.error.set(portalMessage(e)) });
  }
}
