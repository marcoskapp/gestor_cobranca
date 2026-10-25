import { Clipboard } from '@angular/cdk/clipboard';
import { Component, effect, inject, input, signal } from '@angular/core';
import { toDataURL } from 'qrcode';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../icon/icon';

/**
 * QR code do cadastro de MFA (TOTP), gerado localmente a partir do `otpauthUri` (nenhum serviço
 * externo recebe o segredo), e o segredo em texto para digitação manual.
 */
@Component({
  selector: 'app-totp-setup',
  imports: [Icon],
  templateUrl: './totp-setup.html',
  styleUrl: './totp-setup.scss',
})
export class TotpSetup {
  readonly otpauthUri = input.required<string>();
  readonly secret = input.required<string>();

  private readonly clipboard = inject(Clipboard);
  private readonly notifications = inject(NotificationService);
  protected readonly qrDataUrl = signal<string | null>(null);
  protected readonly qrFailed = signal(false);

  constructor() {
    effect((onCleanup) => {
      const uri = this.otpauthUri();
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      this.qrDataUrl.set(null);
      this.qrFailed.set(false);
      toDataURL(uri, { errorCorrectionLevel: 'M', margin: 2, width: 200 })
        .then((url) => {
          if (!cancelled) {
            this.qrDataUrl.set(url);
          }
        })
        .catch(() => {
          if (!cancelled) {
            this.qrFailed.set(true);
          }
        });
    });
  }

  /** Segredo em grupos de 4 caracteres para facilitar a leitura. */
  protected groupedSecret(): string {
    return this.secret().replace(/(.{4})/g, '$1 ').trim();
  }

  protected copy(): void {
    if (this.clipboard.copy(this.secret())) {
      this.notifications.info('Chave copiada.');
    }
  }
}
