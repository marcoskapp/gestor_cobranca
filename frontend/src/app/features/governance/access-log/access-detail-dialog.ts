import { Component, inject } from '@angular/core';
import { JsonView } from '../../../shared/components/detail-dialog/json-view';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ACCESS_EVENT_LABELS, AccessLogEntry } from '../data/governance.models';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

@Component({
  selector: 'app-access-detail-dialog',
  imports: [...DIALOG_DIRECTIVES, JsonView, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ label }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
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
      <h3 class="app-section-title mt-3">Detalhes</h3>
      <app-json-view [value]="e.details" />
    </div>
    <div class="modal-footer">
      <button class="btn btn-primary" type="button" ngbAutofocus appDialogClose>Fechar</button>
    </div>
  `,
})
export class AccessDetailDialog {
  protected readonly e = inject<AccessLogEntry>(DIALOG_DATA);
  protected readonly label = ACCESS_EVENT_LABELS[this.e.eventType] ?? this.e.eventType;
}
