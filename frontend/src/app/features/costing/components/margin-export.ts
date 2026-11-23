import { Component, computed, inject, input, signal } from '@angular/core';
import { NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { MarginApi } from '../data/costing.api';
import { MarginBasis, MarginFilter } from '../data/costing.models';

/**
 * "Exportar" da margem (planilha ou CSV com o recorte e a base da tela; o servidor registra a exportação no log de
 * acesso). Só aparece com `REPORT_EXPORT`. Usado no cabeçalho do painel e do relatório completo.
 */
@Component({
  selector: 'app-margin-export',
  imports: [Icon, NgbDropdownModule],
  // sem caixa própria: sem a permissão, nada ocupa espaço entre as ações do cabeçalho
  styles: `
    :host {
      display: contents;
    }
  `,
  template: `
    @if (canExport()) {
      <div ngbDropdown class="d-inline-flex" placement="bottom-end">
        <button class="btn btn-outline-secondary" type="button" ngbDropdownToggle [disabled]="exporting()" data-tour="margin-export">
          <app-icon name="download" />
          Exportar
          <app-icon name="chevron-down" />
        </button>
        <div ngbDropdownMenu class="shadow app-menu" aria-label="Formato da exportação">
          <button ngbDropdownItem type="button" (click)="export('xlsx')">Planilha (XLSX)</button>
          <button ngbDropdownItem type="button" (click)="export('csv')">Texto separado por ponto e vírgula (CSV)</button>
        </div>
      </div>
    }
  `,
})
export class MarginExport {
  private readonly api = inject(MarginApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);

  readonly filter = input.required<MarginFilter>();
  readonly basis = input.required<MarginBasis>();

  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly exporting = signal(false);

  protected export(format: 'xlsx' | 'csv'): void {
    this.exporting.set(true);
    this.api
      .export(this.filter(), format, this.basis())
      .pipe(finalize(() => this.exporting.set(false)))
      .subscribe((name) => this.notifications.success(`Relatório exportado: ${name}. A exportação fica registrada no log de acesso.`));
  }
}
