import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { JsonView } from '../../../shared/components/detail-dialog/json-view';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ACCESS_EVENT_LABELS, AccessLogEntry } from '../data/governance.models';

@Component({
  selector: 'app-access-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, JsonView, AppDatePipe],
  template: `
    <h2 mat-dialog-title>{{ label }}</h2>
    <mat-dialog-content>
      <dl class="app-definition-list">
        <dt>Data/hora</dt>
        <dd>{{ e.occurredAt | appDate }}</dd>
        <dt>Resultado</dt>
        <dd>{{ e.success ? 'Sucesso' : 'Falha' }}</dd>
        <dt>Ator</dt>
        <dd>{{ e.actorName ?? e.principal ?? e.actorType ?? '—' }}</dd>
        <dt>Identificação</dt>
        <dd>{{ e.principal ?? '—' }}</dd>
        <dt>IP</dt>
        <dd>{{ e.clientIp ?? '—' }}</dd>
        <dt>Navegador</dt>
        <dd>{{ e.userAgent ?? '—' }}</dd>
        <dt>Rota</dt>
        <dd class="app-mono">{{ e.route ?? '—' }}</dd>
        <dt>Sessão</dt>
        <dd class="app-mono">{{ e.sessionId ?? '—' }}</dd>
        <dt>Código da requisição</dt>
        <dd class="app-mono">{{ e.requestId ?? '—' }}</dd>
      </dl>
      <h3 class="app-card__title">Detalhes</h3>
      <app-json-view [value]="e.details" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
})
export class AccessDetailDialog {
  protected readonly e = inject<AccessLogEntry>(MAT_DIALOG_DATA);
  protected readonly label = ACCESS_EVENT_LABELS[this.e.eventType] ?? this.e.eventType;
}
