import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
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
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, AppDatePipe],
  template: `
    <h2 mat-dialog-title>Gravação da chamada</h2>
    <mat-dialog-content>
      <dl class="app-definition-list rd-dl">
        <dt>Devedor</dt>
        <dd>{{ data.debtorName }} · {{ data.call.numberMasked ?? '—' }}</dd>
        <dt>Chamada</dt>
        <dd>{{ (data.call.answeredAt ?? data.call.queuedAt) | appDate }} · conversa {{ talk }}</dd>
        <dt>Aviso ao devedor</dt>
        <dd>{{ data.call.recording?.announced ? 'Aviso de gravação tocado no início' : 'Sem registro do aviso' }}</dd>
      </dl>
      <p class="app-alert app-alert--warning" role="note" data-tour="recording-notice">
        <mat-icon aria-hidden="true">policy</mat-icon>
        <span>Gravação é dado pessoal. <strong>Seu acesso fica registrado</strong> no log de acesso com a finalidade declarada; use só para a finalidade escolhida.</span>
      </p>
      @if (!audioUrl()) {
        <mat-form-field class="app-full-width">
          <mat-label>Finalidade do acesso</mat-label>
          <mat-select [ngModel]="purpose()" (ngModelChange)="purpose.set($event)" required>
            @for (p of purposes; track p[0]) {
              <mat-option [value]="p[0]">{{ p[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      } @else {
        <p class="rd-purpose">Finalidade declarada: <strong>{{ purposeLabel() }}</strong></p>
        <audio class="rd-audio" controls autoplay [src]="audioUrl()" aria-label="Gravação da chamada"></audio>
        @if (sha(); as h) {
          <p class="app-muted rd-small">
            SHA-256 {{ grouped(h.actual) }} ·
            @if (h.verified) {
              <span class="app-status app-status--success"><mat-icon class="app-status__icon" aria-hidden="true">verified</mat-icon>íntegro</span>
            } @else {
              <span class="app-status app-status--danger"><mat-icon class="app-status__icon" aria-hidden="true">error_outline</mat-icon>não confere com o servidor</span>
            }
          </p>
        }
      }
      @if (error()) {
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
      @if (!audioUrl()) {
        <button mat-flat-button type="button" (click)="listen()" [disabled]="!purpose() || loading()">
          <mat-icon aria-hidden="true">play_arrow</mat-icon>
          Ouvir
        </button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    .rd-dl {
      margin-bottom: 12px;
    }
    .rd-audio {
      width: 100%;
    }
    .rd-purpose {
      margin: 0 0 8px;
    }
    .rd-small {
      font-size: var(--app-text-xs);
      word-break: break-all;
    }
  `,
})
export class RecordingDialog {
  protected readonly data = inject<RecordingDialogData>(MAT_DIALOG_DATA);
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
