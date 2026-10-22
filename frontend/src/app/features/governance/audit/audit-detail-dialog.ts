import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { JsonView } from '../../../shared/components/detail-dialog/json-view';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AUDIT_ENTITY_LABELS, AuditLogEntry } from '../data/governance.models';

@Component({
  selector: 'app-audit-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, JsonView, AppDatePipe],
  template: `
    <h2 mat-dialog-title>Registro de auditoria nº {{ e.seq }}</h2>
    <mat-dialog-content>
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
          <h3 class="app-card__title">Antes</h3>
          <app-json-view [value]="e.before" />
        </section>
        <section>
          <h3 class="app-card__title">Depois</h3>
          <app-json-view [value]="e.after" />
        </section>
      </div>
      @if (e.metadata) {
        <h3 class="app-card__title">Metadados</h3>
        <app-json-view [value]="e.metadata" />
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
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
  protected readonly e = inject<AuditLogEntry>(MAT_DIALOG_DATA);
  protected readonly entity = AUDIT_ENTITY_LABELS[this.e.entityType] ?? this.e.entityType;
}
