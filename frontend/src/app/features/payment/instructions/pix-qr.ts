import { Component, effect, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * QR Code do Pix gerado no navegador a partir do "copia e cola" (BR Code), sem serviço externo.
 * Usa a biblioteca `qrcode` (MIT), já adotada no cadastro de MFA, carregada sob demanda (fica num
 * bloco próprio, fora do pacote inicial). Correção de erro "M", recomendada para BR Code.
 */
@Component({
  selector: 'app-pix-qr',
  imports: [MatIconModule],
  template: `
    <div class="pq" [style.width.px]="size()" [style.height.px]="size()">
      @if (dataUrl(); as url) {
        <img [src]="url" [width]="size()" [height]="size()" [attr.alt]="alt()" />
      } @else if (failed()) {
        <p class="pq__fail" role="status"><mat-icon aria-hidden="true">qr_code_2</mat-icon>Não foi possível desenhar o QR Code. Use o copia e cola.</p>
      } @else {
        <div class="pq__wait" role="status" aria-label="Gerando QR Code"></div>
      }
    </div>
  `,
  styles: `
    .pq {
      display: grid;
      place-items: center;
      max-width: 100%;
      border-radius: var(--app-radius-md);
      // fundo branco fixo também no tema escuro: leitores de QR precisam de contraste claro
      background: #fff;
      padding: 8px;
      box-sizing: content-box;
    }
    img {
      display: block;
      max-width: 100%;
      height: auto;
      image-rendering: pixelated;
    }
    .pq__wait {
      width: 70%;
      height: 70%;
      border-radius: var(--app-radius-sm);
      background: repeating-linear-gradient(45deg, #eceff3 0 8px, #f6f7f9 8px 16px);
    }
    .pq__fail {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      margin: 0;
      padding: 12px;
      text-align: center;
      font-size: var(--app-text-sm);
      color: #3b4452;
    }
  `,
})
export class PixQr {
  /** Conteúdo do QR Code (BR Code "copia e cola"). */
  readonly value = input.required<string>();
  readonly size = input(224);
  readonly alt = input('QR Code do Pix');

  protected readonly dataUrl = signal<string | null>(null);
  protected readonly failed = signal(false);

  constructor() {
    effect((onCleanup) => {
      const value = this.value();
      const width = this.size();
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      this.dataUrl.set(null);
      this.failed.set(false);
      import('qrcode')
        .then((qr) => qr.toDataURL(value, { errorCorrectionLevel: 'M', margin: 1, width: width * 2 }))
        .then((url) => !cancelled && this.dataUrl.set(url))
        .catch(() => !cancelled && this.failed.set(true));
    });
  }
}
