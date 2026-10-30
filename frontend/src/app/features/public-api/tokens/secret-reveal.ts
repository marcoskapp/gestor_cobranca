import { Clipboard } from '@angular/cdk/clipboard';
import { Component, inject, input, signal } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';

/**
 * Exibição única do segredo do token: campo somente leitura, cópia para a área de transferência e aviso de que o valor
 * não será mostrado de novo. O valor fica apenas em memória deste componente (nunca em armazenamento do navegador).
 */
@Component({
  selector: 'app-secret-reveal',
  imports: [Icon],
  template: `
    <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
      <app-icon name="shield-exclamation" />
      <span><strong>Copie agora.</strong> Por segurança este token não será exibido de novo: a assessoria guarda só uma impressão irreversível dele. Entregue-o ao credor por canal seguro e peça que fique num cofre de segredos.</span>
    </div>
    <label class="form-label" for="sr-value">Token de integração</label>
    <div class="input-group" data-tour="token-secret">
      <input id="sr-value" class="form-control app-mono" type="text" [value]="value()" readonly spellcheck="false" autocomplete="off" (focus)="select($event)" />
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="copy()">
        <app-icon [name]="copied() ? 'clipboard-check' : 'copy'" />
        {{ copied() ? 'Copiado' : 'Copiar' }}
      </button>
    </div>
    <div class="form-text">Use no cabeçalho <code>Authorization: Bearer &lt;token&gt;</code> das chamadas à API pública.</div>
  `,
})
export class SecretReveal {
  readonly value = input.required<string>();
  private readonly clipboard = inject(Clipboard);
  protected readonly copied = signal(false);

  protected copy(): void {
    this.copied.set(this.clipboard.copy(this.value()));
  }

  protected select(event: FocusEvent): void {
    (event.target as HTMLInputElement).select();
  }
}
