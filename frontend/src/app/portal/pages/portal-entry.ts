import { Component, ElementRef, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { Router } from '@angular/router';
import { PortalApi, portalMessage, retryAfterSeconds } from '../data/portal.api';
import { CHANNEL_LABELS, KNOWLEDGE_LABELS } from '../data/portal.labels';
import { ChallengeView, PortalChannel } from '../data/portal.models';
import { PortalFlow, maskDocument, validDocument } from '../data/portal-flow';
import { PortalSession } from '../data/portal-session';
import { PortalSite } from '../layout/portal-site';

/**
 * Entrada do portal: CPF/CNPJ → código no contato cadastrado (e o dado de conhecimento, se o credor
 * exigir). A mensagem depois do pedido é a mesma exista ou não o documento.
 */
@Component({
  selector: 'app-portal-entry',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule],
  template: `
    @if (session.endedReason() === 'expired') {
      <p class="portal-note" role="status"><mat-icon aria-hidden="true">schedule</mat-icon>
        <span>Sua sessão terminou por segurança. Entre de novo para continuar.</span></p>
    } @else if (session.endedReason() === 'logout') {
      <p class="portal-note portal-success" role="status"><mat-icon aria-hidden="true">check_circle</mat-icon>
        <span>Você saiu do portal.</span></p>
    }

    <section class="portal-card">
      @if (!challenge()) {
        <h1 class="portal-title" #heading tabindex="-1">Consulte e negocie suas dívidas</h1>
        <p class="portal-subtitle">Veja o valor atualizado, escolha como pagar e feche o acordo aqui mesmo.</p>
        <form (submit)="$event.preventDefault(); requestCode()" novalidate>
          <mat-form-field class="portal-field" appearance="outline">
            <mat-label>CPF ou CNPJ</mat-label>
            <input matInput [formControl]="document" inputmode="numeric" autocomplete="off" maxlength="18"
                   (input)="onDocumentInput()" aria-describedby="doc-hint" />
            <mat-hint id="doc-hint">Só os números já bastam</mat-hint>
            @if (document.touched && document.invalid) {
              <mat-error>Confira o CPF ou CNPJ</mat-error>
            }
          </mat-form-field>
          @if (channels().length > 1) {
            <fieldset class="portal-fieldset">
              <legend>Onde você quer receber o código?</legend>
              <mat-radio-group [formControl]="channel" aria-label="Canal para receber o código">
                @for (c of channels(); track c) {
                  <mat-radio-button [value]="c">{{ channelLabels[c] }}</mat-radio-button>
                }
              </mat-radio-group>
            </fieldset>
          } @else {
            <p class="portal-label">Vamos enviar um código para o seu {{ channelLabels[channels()[0]] }} cadastrado.</p>
          }
          @if (error()) {
            <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button mat-flat-button type="submit" [disabled]="busy()">Receber código</button>
          </div>
        </form>
      } @else {
        <h1 class="portal-title" #heading tabindex="-1">Digite o código</h1>
        <p class="portal-subtitle" role="status">
          Se o documento estiver cadastrado, você vai receber um código de {{ challenge()!.codeLength }} números
          pelo {{ channelLabels[challenge()!.channel] }}. Ele vale por alguns minutos.
        </p>
        <form (submit)="$event.preventDefault(); verify()" novalidate>
          <mat-form-field class="portal-field" appearance="outline">
            <mat-label>Código recebido</mat-label>
            <input matInput [formControl]="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" />
          </mat-form-field>
          @if (knowledge(); as k) {
            <mat-form-field class="portal-field" appearance="outline">
              <mat-label>{{ k.label }}</mat-label>
              <input matInput [formControl]="answer" autocomplete="off" maxlength="20" aria-describedby="k-hint" />
              <mat-hint id="k-hint">{{ k.hint }}</mat-hint>
            </mat-form-field>
          }
          @if (error()) {
            <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button mat-button type="button" (click)="restart()">Usar outro documento</button>
            <button mat-stroked-button type="button" (click)="requestCode()" [disabled]="busy()">Reenviar código</button>
            <button mat-flat-button type="submit" [disabled]="busy()">Entrar</button>
          </div>
        </form>
      }
    </section>
  `,
  styles: `
    .portal-fieldset { margin: 8px 0 0; padding: 0; border: 0; }
    .portal-fieldset legend { margin-bottom: 4px; font-weight: 500; }
    .portal-fieldset mat-radio-button { display: block; min-height: 44px; }
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
