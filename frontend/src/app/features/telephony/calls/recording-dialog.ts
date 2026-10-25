import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { groupedHash, sha256Hex } from '../../settlement/data/settlement.rules';
import { CallsApi } from '../data/telephony.api';
import { RECORDING_PURPOSE_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { CallView, RecordingPurpose } from '../data/telephony.models';
import { durationText } from '../data/telephony.rules';

export interface RecordingDialogData {
  call: CallView;
  debtorName: string;
}

/**
 * Player da gravação: a finalidade é declarada antes de ouvir e o acesso fica registrado no log de
 * acesso (quem, quando, chamada, credor e finalidade). O áudio fica só em memória (sem cache, sem
 * download) e o SHA-256 recebido é conferido com o do servidor.
 */
@Component({
  selector: 'app-recording-dialog',
  imports: [FormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Gravação da chamada</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <dl class="app-definition-list mb-3">
        <dt>Devedor</dt>
        <dd>{{ data.debtorName }} · {{ data.call.numberMasked ?? '—' }}</dd>
        <dt>Chamada</dt>
        <dd>{{ (data.call.answeredAt ?? data.call.queuedAt) | appDate }} · conversa {{ talk }}</dd>
        <dt>Aviso ao devedor</dt>
        <dd>{{ data.call.recording?.announced ? 'Aviso de gravação tocado no início' : 'Sem registro do aviso' }}</dd>
      </dl>
      <p class="alert alert-warning d-flex gap-2" role="note" data-tour="recording-notice">
        <app-icon name="shield-check" />
        <span>Gravação é dado pessoal. <strong>Seu acesso fica registrado</strong> no log de acesso com a finalidade declarada; use só para a finalidade escolhida.</span>
      </p>
      @if (!audioUrl()) {
        <app-field label="Finalidade do acesso">
          <select appInput [ngModel]="purpose()" (ngModelChange)="purpose.set($event)" required>
            <option [ngValue]="null" disabled>Selecione</option>
            @for (p of purposes; track p[0]) {
              <option [ngValue]="p[0]">{{ p[1] }}</option>
            }
          </select>
        </app-field>
      } @else {
        <p class="mb-2">Finalidade declarada: <strong>{{ purposeLabel() }}</strong></p>
        <audio class="w-100" controls autoplay [src]="audioUrl()" aria-label="Gravação da chamada"></audio>
        @if (sha(); as h) {
          <p class="app-muted small text-break">
            SHA-256 {{ grouped(h.actual) }} ·
            @if (h.verified) {
              <span class="badge rounded-pill border bg-success-subtle text-success-emphasis border-success-subtle fw-normal"><app-icon name="patch-check" />íntegro</span>
            } @else {
              <span class="badge rounded-pill border bg-danger-subtle text-danger-emphasis border-danger-subtle fw-normal"><app-icon name="exclamation-circle" />não confere com o servidor</span>
            }
          </p>
        }
      }
      @if (error()) {
        <p class="alert alert-danger d-flex gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Fechar</button>
      @if (!audioUrl()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="listen()" [disabled]="!purpose() || loading()">
          <app-icon name="play" />
          Ouvir
        </button>
      }
    </div>
  `,
})
export class RecordingDialog {
  protected readonly data = inject<RecordingDialogData>(DIALOG_DATA);
  private readonly api = inject(CallsApi);
  private readonly document = inject(DOCUMENT);
  protected readonly purposes = Object.entries(RECORDING_PURPOSE_LABELS) as [RecordingPurpose, string][];
  protected readonly talk = durationText(this.data.call.talkSeconds ?? this.data.call.recording?.durationSeconds);
  protected readonly grouped = groupedHash;
  protected readonly purpose = signal<RecordingPurpose | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly audioUrl = signal<string | null>(null);
  protected readonly sha = signal<{ actual: string; verified: boolean } | null>(null);

  constructor() {
    // o áudio é liberado da memória ao fechar
    inject(DestroyRef).onDestroy(() => {
      const url = this.audioUrl();
      if (url) {
        this.document.defaultView?.URL.revokeObjectURL(url);
      }
    });
  }

  protected purposeLabel(): string {
    const p = this.purpose();
    return p ? RECORDING_PURPOSE_LABELS[p] : '';
  }

  protected listen(): void {
    const purpose = this.purpose();
    const view = this.document.defaultView;
    if (!purpose || this.loading() || !view?.URL?.createObjectURL) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api
      .recording(this.data.call.id, purpose)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ blob, sha256 }) => {
          this.audioUrl.set(view.URL.createObjectURL(blob));
          void blob.arrayBuffer().then((buffer) =>
            sha256Hex(buffer).then((actual) => this.sha.set({ actual, verified: !sha256 || sha256.toLowerCase() === actual })),
          );
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
