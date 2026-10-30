import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { IntegrationTokensApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS } from '../data/public-api.labels';
import { IssuedToken, TokenView } from '../data/public-api.models';
import { SecretReveal } from './secret-reveal';

export interface RotateTokenDialogData {
  token: TokenView;
}

/** Rotação: novo segredo (exibido uma vez); o anterior continua aceito durante a carência escolhida. */
@Component({
  selector: 'app-rotate-token-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, SecretReveal],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Rotacionar token · {{ data.token.name }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" (click)="close()"></button>
    </div>
    @if (issued(); as result) {
      <div class="modal-body">
        @if (result.details.previousValidUntil) {
          <p>O segredo anterior continua aceito até o fim da carência. Troque a configuração do credor antes disso.</p>
        } @else {
          <p>O segredo anterior deixou de funcionar agora.</p>
        }
        <app-secret-reveal [value]="result.token" />
      </div>
      <div class="modal-footer">
        <button class="btn btn-primary" type="button" (click)="close()">Já copiei o token</button>
      </div>
    } @else {
      <form [formGroup]="form" (ngSubmit)="rotate()" novalidate>
        <div class="modal-body">
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <p>Gera um novo segredo para o mesmo token (<span class="app-mono">{{ data.token.prefix }}</span>). Escopos, limite e IPs não mudam.</p>
          <app-field label="Carência do segredo anterior" hint="Tempo para o credor trocar a configuração sem parar a integração. Zero em caso de vazamento.">
            <select appInput formControlName="grace">
              <option [ngValue]="0">Nenhuma (invalida agora — use em caso de vazamento)</option>
              <option [ngValue]="15">15 minutos</option>
              <option [ngValue]="60">1 hora</option>
              <option [ngValue]="240">4 horas</option>
              <option [ngValue]="1440">24 horas</option>
            </select>
          </app-field>
        </div>
        <div class="modal-footer">
          <button class="btn btn-link" type="button" (click)="close()">Cancelar</button>
          <button class="btn btn-primary" type="submit" [disabled]="saving()">Rotacionar</button>
        </div>
      </form>
    }
  `,
})
export class RotateTokenDialog {
  protected readonly data = inject<RotateTokenDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TokenView | null>>(DialogRef);
  private readonly api = inject(IntegrationTokensApi);
  protected readonly form = inject(FormBuilder).nonNullable.group({ grace: [60, [Validators.min(0), Validators.max(1440)]] });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly issued = signal<IssuedToken | null>(null);

  protected rotate(): void {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .rotate(this.data.token.id, this.form.getRawValue().grace)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (result) => this.issued.set(result),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  protected close(): void {
    const result = this.issued();
    this.issued.set(null);
    this.ref.close(result ? result.details : null);
  }
}
