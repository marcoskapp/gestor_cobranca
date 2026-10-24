import { Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { toDataURL } from 'qrcode';
import { Amount } from '../../shared/components/amount/amount';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PixView } from '../data/portal.models';

/**
 * Pagamento por Pix: QR Code (gerado no navegador a partir do "copia e cola") e botão de copiar. O
 * QR tem texto alternativo e o código fica sempre disponível como texto (leitor de tela, celular sem
 * outro aparelho para ler o QR). Enquanto o PSP registra a cobrança, pede atualização.
 */
@Component({
  selector: 'app-portal-pix',
  imports: [MatButtonModule, MatIconModule, Amount, AppDatePipe],
  template: `
    @if (pix().status === 'ACTIVE' && pix().copyPaste) {
      <div class="pix">
        <p class="portal-label">Valor do Pix</p>
        <p class="portal-amount"><app-amount [value]="pix().amount" /></p>
        @if (qr()) {
          <img class="pix__qr" [src]="qr()" width="220" height="220"
               alt="QR Code do Pix. Se não puder ler, use o código copia e cola abaixo." />
        }
        <p class="portal-label">Ou use o Pix copia e cola:</p>
        <p class="pix__code" id="pix-code">{{ pix().copyPaste }}</p>
        <div class="portal-actions">
          <button mat-flat-button type="button" (click)="copy()" aria-describedby="pix-code">
            <mat-icon aria-hidden="true">{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
            {{ copied() ? 'Código copiado' : 'Copiar código Pix' }}
          </button>
        </div>
        <p class="portal-label" aria-live="polite">{{ copied() ? 'Código copiado. Cole no aplicativo do seu banco.' : '' }}</p>
        @if (pix().expiresAt) {
          <p class="portal-label">Este Pix vale até {{ pix().expiresAt | appDate }}.</p>
        }
      </div>
    } @else if (pix().status === 'PENDING') {
      <p class="portal-note" role="status"><mat-icon aria-hidden="true">hourglass_top</mat-icon>
        <span>Estamos gerando o seu Pix. Isso leva poucos segundos.</span></p>
      <div class="portal-actions"><button mat-stroked-button type="button" (click)="refresh.emit()">Atualizar</button></div>
    } @else if (pix().status === 'PAID') {
      <p class="portal-note portal-success" role="status"><mat-icon aria-hidden="true">check_circle</mat-icon>
        <span>Pagamento recebido. Obrigado!</span></p>
    } @else {
      <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon>
        <span>Este Pix não está mais disponível. Gere um novo.</span></p>
      <div class="portal-actions"><button mat-flat-button type="button" (click)="refresh.emit()">Gerar novo Pix</button></div>
    }
  `,
  styles: `
    .pix { text-align: center; }
    .pix__qr { display: block; margin: 12px auto; background: #fff; padding: 8px; border-radius: 8px; }
    .pix__code { padding: 12px; border: 1px dashed var(--app-border); border-radius: 8px; word-break: break-all; font-family: monospace; font-size: 13px; text-align: left; }
  `,
})
export class PortalPix {
  readonly pix = input.required<PixView>();
  readonly refresh = output<void>();

  protected readonly qr = signal<string | null>(null);
  protected readonly copied = signal(false);
  private readonly code = computed(() => this.pix().copyPaste);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const code = this.code();
      this.qr.set(null);
      if (code) {
        toDataURL(code, { errorCorrectionLevel: 'M', margin: 1, width: 220 })
          .then((url) => this.qr.set(url))
          .catch(() => this.qr.set(null));
      }
    });
    inject(DestroyRef).onDestroy(() => this.timer && clearTimeout(this.timer));
  }

  protected copy(): void {
    const code = this.pix().copyPaste;
    if (!code) {
      return;
    }
    navigator.clipboard?.writeText(code).then(() => {
      this.copied.set(true);
      this.timer = setTimeout(() => this.copied.set(false), 4000);
    }).catch(() => this.copied.set(false));
  }
}
