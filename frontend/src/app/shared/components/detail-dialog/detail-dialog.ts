import { Component, inject } from '@angular/core';
import { DIALOG_DATA } from '../../dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../dialog/dialog.directives';
import { DialogConfig, DialogService } from '../../dialog/dialog.service';
import { JsonView } from './json-view';

export interface DetailField {
  label: string;
  /** Texto exibido (interpolação, nunca HTML); vazio/nulo vira "—". */
  value: string | number | null | undefined;
}

export interface DetailSection {
  label: string;
  /** Objeto exibido como JSON formatado (ex.: antes/depois da auditoria). */
  json: unknown;
}

export interface DetailDialogData {
  title: string;
  subtitle?: string;
  fields: readonly DetailField[];
  sections?: readonly DetailSection[];
}

/**
 * Ficha somente leitura (detalhe sob demanda — revelação progressiva): lista de definições e
 * seções JSON opcionais. Para telas de auditoria, log de acesso e afins.
 *
 * ```ts
 * openDetail(this.dialog, { title: 'Evento de auditoria', fields: [...], sections: [{ label: 'Antes', json: e.before }] });
 * ```
 */
@Component({
  selector: 'app-detail-dialog',
  imports: [...DIALOG_DIRECTIVES, JsonView],
  template: `
    <div class="modal-header">
      <div>
        <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
        @if (data.subtitle) {
          <p class="mb-0 mt-1 small text-body-secondary">{{ data.subtitle }}</p>
        }
      </div>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <dl class="app-definition-list">
        @for (f of data.fields; track f.label) {
          <dt>{{ f.label }}</dt>
          <dd>{{ f.value === null || f.value === undefined || f.value === '' ? '—' : f.value }}</dd>
        }
      </dl>
      @for (s of data.sections ?? []; track s.label) {
        <h3 class="app-section-title mt-3">{{ s.label }}</h3>
        <app-json-view [value]="s.json" />
      }
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-primary" ngbAutofocus appDialogClose>Fechar</button>
    </div>
  `,
})
export class DetailDialog {
  protected readonly data = inject<DetailDialogData>(DIALOG_DATA);
}

/** Abre a ficha de detalhe (tamanho grande por padrão). */
export function openDetail(dialog: DialogService, data: DetailDialogData, config: Omit<DialogConfig<DetailDialogData>, 'data'> = {}) {
  return dialog.open<DetailDialog, DetailDialogData, void>(DetailDialog, { size: 'lg', ...config, data });
}
