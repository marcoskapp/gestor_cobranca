import { Component, ElementRef, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { PortalApi, portalMessage, retryAfterSeconds } from '../data/portal.api';
import { CHANNEL_LABELS, KNOWLEDGE_LABELS } from '../data/portal.labels';
import { ChallengeView, PortalChannel } from '../data/portal.models';
import { PortalFlow, maskDocument, validDocument } from '../data/portal-flow';
import { PortalSession } from '../data/portal-session';
import { PortalSite } from '../layout/portal-site';
import { Icon } from '../../shared/components/icon/icon';
import { FORM_FIELD } from '../../shared/forms/field';

/**
 * Entrada do portal: CPF/CNPJ → código no contato cadastrado (e o dado de conhecimento, se o credor
 * exigir). A mensagem depois do pedido é a mesma exista ou não o documento.
 */
@Component({
  selector: 'app-portal-entry',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Icon],
  template: `
    @if (session.endedReason() === 'expired') {
      <p class="alert alert-info d-flex align-items-start gap-2" role="status"><app-icon name="clock" />
        <span>Sua sessão terminou por segurança. Entre de novo para continuar.</span></p>
    } @else if (session.endedReason() === 'logout') {
      <p class="alert alert-success d-flex align-items-start gap-2" role="status"><app-icon name="check-circle" />
        <span>Você saiu do portal.</span></p>
    }

    <section class="portal-card">
      @if (!challenge()) {
        <h1 class="portal-title" #heading tabindex="-1">Consulte e negocie suas dívidas</h1>
        <p class="portal-subtitle">Veja o valor atualizado, escolha como pagar e feche o acordo aqui mesmo.</p>
        <form (submit)="$event.preventDefault(); requestCode()" novalidate>
          <app-field class="portal-field" label="CPF ou CNPJ" hint="Só os números já bastam" error="Confira o CPF ou CNPJ">
            <input appInput class="form-control-lg" [formControl]="document" inputmode="numeric" autocomplete="off" maxlength="18" (input)="onDocumentInput()" />
          </app-field>
          @if (channels().length > 1) {
            <fieldset class="portal-fieldset">
              <legend>Onde você quer receber o código?</legend>
              @for (c of channels(); track c) {
                <div class="form-check portal-radio">
                  <input class="form-check-input portal-check" type="radio" name="portal-channel" [id]="'portal-channel-' + c" [value]="c" [formControl]="channel" />
                  <label class="form-check-label" [for]="'portal-channel-' + c">{{ channelLabels[c] }}</label>
                </div>
              }
            </fieldset>
          } @else {
            <p class="portal-label">Vamos enviar um código para o seu {{ channelLabels[channels()[0]] }} cadastrado.</p>
          }
          @if (error()) {
            <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button class="btn btn-primary portal-btn" type="submit" [disabled]="busy()">Receber código</button>
          </div>
        </form>
      } @else {
        <h1 class="portal-title" #heading tabindex="-1">Digite o código</h1>
        <p class="portal-subtitle" role="status">
          Se o documento estiver cadastrado, você vai receber um código de {{ challenge()!.codeLength }} números
          pelo {{ channelLabels[challenge()!.channel] }}. Ele vale por alguns minutos.
        </p>
        <form (submit)="$event.preventDefault(); verify()" novalidate>
          <app-field class="portal-field" label="Código recebido">
            <input appInput class="form-control-lg" [formControl]="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" />
          </app-field>
          @if (knowledge(); as k) {
            <app-field class="portal-field" [label]="k.label" [hint]="k.hint">
              <input appInput class="form-control-lg" [formControl]="answer" autocomplete="off" maxlength="20" />
            </app-field>
          }
          @if (error()) {
            <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button class="btn btn-link portal-btn-link" type="button" (click)="restart()">Usar outro documento</button>
            <button class="btn btn-outline-primary portal-btn-outline" type="button" (click)="requestCode()" [disabled]="busy()">Reenviar código</button>
            <button class="btn btn-primary portal-btn" type="submit" [disabled]="busy()">Entrar</button>
          </div>
        </form>
      }
    </section>
  `,
  styles: `
    .portal-fieldset { margin: 8px 0 0; padding: 0; border: 0; }
    .portal-fieldset legend { margin-bottom: 4px; font-weight: 500; }
    .portal-radio { display: flex; align-items: center; gap: 8px; min-height: 44px; margin: 0; }
  `,
})
export class PortalEntry implements OnInit {
  readonly slug = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly flow = inject(PortalFlow);
  private readonly router = inject(Router);
  private readonly site = inject(PortalSite).site;
  protected readonly session = inject(PortalSession);
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly document = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, (c) => (validDocument(c.value ?? '') ? null : { document: true })],
  });
  protected readonly channel = new FormControl<PortalChannel>('WHATSAPP', { nonNullable: true });
  protected readonly code = new FormControl('', { nonNullable: true });
  protected readonly answer = new FormControl('', { nonNullable: true });
  protected readonly challenge = signal<ChallengeView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly channels = computed<PortalChannel[]>(() => this.site()?.otpChannels ?? ['WHATSAPP']);
  protected readonly knowledge = computed(() => {
    const type = this.challenge()?.knowledgeChallenge;
    return type && type !== 'NONE' ? KNOWLEDGE_LABELS[type] : null;
  });

  ngOnInit(): void {
    if (this.session.activeFor(this.slug())) {
      void this.router.navigate(['/portal', this.slug(), 'dividas']);
      return;
    }
    this.channel.setValue(this.channels()[0]);
  }

  protected onDocumentInput(): void {
    this.document.setValue(maskDocument(this.document.value), { emitEvent: false });
  }

  protected requestCode(): void {
    this.document.markAsTouched();
    if (this.document.invalid || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.requestCode(this.slug(), this.document.value, this.channel.value).subscribe({
      next: (challenge) => {
        this.busy.set(false);
        this.code.reset();
        this.challenge.set(challenge);
        this.focusHeading();
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(lockMessage(e) ?? portalMessage(e));
      },
    });
  }

  protected verify(): void {
    const challenge = this.challenge();
    if (!challenge || this.busy()) {
      return;
    }
    if (!/^\d{6}$/.test(this.code.value)) {
      this.error.set('Digite os 6 números do código.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.verify(this.slug(), challenge.challengeId, this.code.value, this.knowledge() ? this.answer.value : null)
      .subscribe({
        next: (grant) => this.flow.enter(this.slug(), grant),
        error: (e: unknown) => {
          this.busy.set(false);
          this.error.set(lockMessage(e) ?? portalMessage(e));
        },
      });
  }

  protected restart(): void {
    this.challenge.set(null);
    this.error.set(null);
    this.focusHeading();
  }

  private focusHeading(): void {
    setTimeout(() => this.heading()?.nativeElement.focus());
  }
}

/** Bloqueio progressivo: quanto tempo esperar, em linguagem simples. */
export function lockMessage(error: unknown): string | null {
  const seconds = retryAfterSeconds(error);
  if (seconds === null) {
    return null;
  }
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  return `Muitas tentativas seguidas. Por segurança, tente de novo em ${minutes} minuto${minutes > 1 ? 's' : ''}.`;
}
