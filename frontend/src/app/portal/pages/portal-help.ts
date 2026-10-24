import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalApi, portalMessage } from '../data/portal.api';
import { CHANNEL_LABELS, REASON_LABELS } from '../data/portal.labels';
import { PortalChannel, ServiceReason, ServiceRequestView } from '../data/portal.models';
import { PortalSite } from '../layout/portal-site';

/** Falar com atendente: o pedido vira conversa na fila humana; o atendente responde pelo canal escolhido. */
@Component({
  selector: 'app-portal-help',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule, AppDatePipe],
  template: `
    <h1 class="portal-title">Falar com um atendente</h1>
    @if (sent(); as s) {
      <section class="portal-card portal-center" role="status">
        <mat-icon class="portal-big-icon" aria-hidden="true">mark_chat_read</mat-icon>
        <h2 class="portal-title">Pedido recebido</h2>
        <p>Protocolo <strong>{{ s.protocol }}</strong>. Um atendente vai falar com você pelo {{ channelLabels[s.preferredChannel] }}.</p>
        <div class="portal-actions"><button mat-stroked-button type="button" (click)="sent.set(null)">Fazer outro pedido</button></div>
      </section>
    } @else {
      <section class="portal-card">
        <form (submit)="$event.preventDefault(); send()" novalidate>
          <fieldset class="portal-fieldset">
            <legend>Como podemos ajudar?</legend>
            <mat-radio-group [formControl]="reason" class="portal-radios">
              @for (r of reasons; track r) {
                <mat-radio-button [value]="r">
                  <span>{{ reasonLabels[r].label }}</span><br /><span class="portal-label">{{ reasonLabels[r].hint }}</span>
                </mat-radio-button>
              }
            </mat-radio-group>
          </fieldset>
          <mat-form-field class="portal-field" appearance="outline">
            <mat-label>Mensagem {{ reason.value === 'OTHER' ? '' : '(opcional)' }}</mat-label>
            <textarea matInput [formControl]="note" rows="3" maxlength="500" aria-describedby="note-hint"></textarea>
            <mat-hint id="note-hint">Não informe senhas nem dados de cartão. {{ note.value.length }}/500</mat-hint>
          </mat-form-field>
          <fieldset class="portal-fieldset">
            <legend>Por onde o atendente deve responder?</legend>
            <mat-radio-group [formControl]="channel" class="portal-radios">
              @for (c of channels(); track c) {
                <mat-radio-button [value]="c">{{ channelLabels[c] }}</mat-radio-button>
              }
            </mat-radio-group>
          </fieldset>
          @if (error()) {
            <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button mat-flat-button type="submit" [disabled]="busy()">Enviar pedido</button>
          </div>
        </form>
      </section>
    }
    @if ((history.value() ?? []).length > 0) {
      <h2 class="portal-help-title">Pedidos anteriores</h2>
      <section class="portal-card"><ul class="portal-list">
        @for (h of history.value()!; track h.id) {
          <li><span>{{ reasonLabels[h.reason].label }}<br /><small>Protocolo {{ h.protocol }}</small></span>
            <span class="portal-label">{{ h.createdAt | appDate }}</span></li>
        }
      </ul></section>
    }
  `,
  styles: `
    .portal-help-title { margin: 8px 0; font-size: 18px; font-weight: 600; }
    .portal-fieldset { margin: 0 0 12px; padding: 0; border: 0; }
    .portal-fieldset legend { margin-bottom: 4px; font-weight: 600; }
    .portal-radios mat-radio-button { display: block; margin-bottom: 8px; }
  `,
})
export class PortalHelp {
  readonly slug = input.required<string>();
  private readonly api = inject(PortalApi);
  private readonly site = inject(PortalSite).site;

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly reasonLabels = REASON_LABELS;
  protected readonly reasons: ServiceReason[] = ['TALK_TO_AGENT', 'CANNOT_AFFORD', 'DISPUTE', 'OTHER'];
  protected readonly channels = computed<PortalChannel[]>(() => this.site()?.otpChannels ?? ['WHATSAPP']);
  protected readonly reason = new FormControl<ServiceReason>('TALK_TO_AGENT', { nonNullable: true });
  protected readonly channel = new FormControl<PortalChannel>('WHATSAPP', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly sent = signal<ServiceRequestView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly history = rxResource({ params: () => this.sent(), stream: () => this.api.serviceRequests() });

  constructor() {
    this.channel.setValue(this.channels()[0]);
  }

  protected send(): void {
    if (this.reason.value === 'OTHER' && !this.note.value.trim()) {
      this.error.set('Conte em poucas palavras o que você precisa.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.requestService({ reason: this.reason.value, preferredChannel: this.channel.value, note: this.note.value || null })
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.note.reset();
          this.sent.set(r);
        },
        error: (e: unknown) => {
          this.busy.set(false);
          this.error.set(portalMessage(e, 'Não foi possível enviar o pedido agora.'));
        },
      });
  }
}
