import { Component, inject } from '@angular/core';
import { JsonView } from '../../../shared/components/detail-dialog/json-view';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AUDIT_ENTITY_LABELS, AuditLogEntry } from '../data/governance.models';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

@Component({
  selector: 'app-audit-detail-dialog',
  imports: [...DIALOG_DIRECTIVES, JsonView, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registro de auditoria nº {{ e.seq }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <dl class="app-definition-list">
        <dt>Data/hora</dt>
        <dd>{{ e.occurredAt | appDate }}</dd>
        <dt>Ação</dt>
        <dd class="app-mono">{{ e.action }}</dd>
        <dt>Entidade</dt>
        <dd>{{ entity }} <span class="app-mono app-muted">{{ e.entityId ?? '' }}</span></dd>
        <dt>Autor</dt>
        <dd>{{ e.actorName ?? e.actorType ?? '—' }}</dd>
        <dt>Motivo</dt>
        <dd>{{ e.reason ?? '—' }}</dd>
        <dt>IP</dt>
        <dd>{{ e.clientIp ?? '—' }}</dd>
        <dt>Código da requisição</dt>
        <dd class="app-mono">{{ e.requestId ?? '—' }}</dd>
        <dt>Selado</dt>
        <dd>{{ e.sealed ? 'Sim (encadeamento de hash concluído)' : 'Pendente de selagem' }}</dd>
      </dl>
      <div class="audit-diff">
        <section>
          <h3 class="app-section-title mt-3">Antes</h3>
          <app-json-view [value]="e.before" />
        </section>
        <section>
          <h3 class="app-section-title mt-3">Depois</h3>
          <app-json-view [value]="e.after" />
        </section>
      </div>
      @if (e.metadata) {
        <h3 class="app-section-title mt-3">Metadados</h3>
        <app-json-view [value]="e.metadata" />
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-primary" type="button" ngbAutofocus appDialogClose>Fechar</button>
    </div>
  `,
  styles: `
    .audit-diff {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 12px;
      margin: 16px 0;
    }
  `,
})
export class AuditDetailDialog {
  protected readonly e = inject<AuditLogEntry>(DIALOG_DATA);
  protected readonly entity = AUDIT_ENTITY_LABELS[this.e.entityType] ?? this.e.entityType;
}
