import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PortalApi, portalMessage } from '../data/portal.api';
import { CHANNEL_LABELS, REASON_LABELS } from '../data/portal.labels';
import { PortalChannel, ServiceReason, ServiceRequestView } from '../data/portal.models';
import { PortalSite } from '../layout/portal-site';
import { Icon } from '../../shared/components/icon/icon';
import { FORM_FIELD } from '../../shared/forms/field';

/** Falar com atendente: o pedido vira conversa na fila humana; o atendente responde pelo canal escolhido. */
@Component({
  selector: 'app-portal-help',
  imports: [ReactiveFormsModule, ...FORM_FIELD, AppDatePipe, Icon],
  template: `
    <h1 class="portal-title">Falar com um atendente</h1>
    @if (sent(); as s) {
      <section class="portal-card text-center" role="status">
        <app-icon class="portal-big-icon" name="chat-square-dots" />
        <h2 class="portal-title">Pedido recebido</h2>
        <p>Protocolo <strong>{{ s.protocol }}</strong>. Um atendente vai falar com você pelo {{ channelLabels[s.preferredChannel] }}.</p>
        <div class="portal-actions"><button class="btn btn-outline-primary portal-btn-outline" type="button" (click)="sent.set(null)">Fazer outro pedido</button></div>
      </section>
    } @else {
      <section class="portal-card">
        <form (submit)="$event.preventDefault(); send()" novalidate>
          <fieldset class="portal-fieldset">
            <legend>Como podemos ajudar?</legend>
            @for (r of reasons; track r) {
              <div class="form-check portal-radio">
                <input class="form-check-input portal-check" type="radio" name="portal-reason" [id]="'portal-reason-' + r" [value]="r" [formControl]="reason" />
                <label class="form-check-label" [for]="'portal-reason-' + r">
                  <span>{{ reasonLabels[r].label }}</span><br /><span class="portal-label">{{ reasonLabels[r].hint }}</span>
                </label>
              </div>
            }
          </fieldset>
          <app-field class="portal-field" [label]="'Mensagem ' + (reason.value === 'OTHER' ? '' : '(opcional)')" [hint]="'Não informe senhas nem dados de cartão. ' + note.value.length + '/500'">
            <textarea appInput class="form-control-lg" [formControl]="note" rows="3" maxlength="500"></textarea>
          </app-field>
          <fieldset class="portal-fieldset">
            <legend>Por onde o atendente deve responder?</legend>
            @for (c of channels(); track c) {
              <div class="form-check portal-radio">
                <input class="form-check-input portal-check" type="radio" name="portal-reply" [id]="'portal-reply-' + c" [value]="c" [formControl]="channel" />
                <label class="form-check-label" [for]="'portal-reply-' + c">{{ channelLabels[c] }}</label>
              </div>
            }
          </fieldset>
          @if (error()) {
            <p class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button class="btn btn-primary portal-btn" type="submit" [disabled]="busy()">Enviar pedido</button>
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
    .portal-fieldset legend { margin-bottom: 4px; font-size: 1rem; font-weight: 600; }
    .portal-radio { display: flex; align-items: center; gap: 8px; min-height: 44px; margin: 0 0 4px; }
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
