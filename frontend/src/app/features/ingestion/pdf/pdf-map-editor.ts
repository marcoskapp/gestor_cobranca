import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, effect, input, output, signal } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CanonicalFieldOption, ControlTotal, ExtractionMap, LabelField, NumberStyle, TotalScope, ValuePosition, ValueTransform } from '../data/pdf-import.models';
import {
  INFO_PREFIX,
  PAID_WHEN_FILLED,
  cloneMap,
  editorColumns,
  emptyMap,
  parseLabels,
  targetLabel,
  withAnchor,
  withColumnTargets,
  withEndMarkers,
  withHeaderLine,
  withIgnoredTexts,
  withLinesPerRecord,
} from '../data/pdf-import.rules';

const MONETARY = ['valor_principal', 'saldo', 'valor_contrato', 'avaliacao_valor'];

/** Nome técnico de informação adicional a partir do rótulo da coluna (`Jur Mora` → `info:jur_mora`). */
export function infoTarget(header: string): string {
  const slug = header
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
  return INFO_PREFIX + (slug || 'campo');
}

/**
 * Editor do mapa de extração: destino de cada coluna (ou ignorar), linhas por título, coluna que inicia o título,
 * início (rótulos do cabeçalho) e fim da tabela, devedor, totais de controle e linhas ignoradas; JSON para casos
 * avançados. "Aplicar e reprocessar" envia o mapa e o servidor reaplica de forma determinística.
 */
@Component({
  selector: 'app-pdf-map-editor',
  imports: [...FORM_FIELD, Icon, NgTemplateOutlet],
  template: `
    <div class="me" data-tour="pdf-map-editor">
      <div class="me-grid">
        <app-field label="Linhas por título">
          <input appInput type="number" min="1" max="5" [value]="table().linesPerRecord" (change)="setLines(val($event))" />
        </app-field>
        <app-field label="Título começa na coluna" hint="Coluna da 1ª linha sempre preenchida (de preferência o vencimento).">
          <select appInput [value]="table().anchorColumn ?? ''" (change)="setAnchor(val($event))">
            <option value="">— nenhuma (1 linha por título) —</option>
            @for (h of lineOne(); track h) {
              <option [value]="h" [selected]="h === table().anchorColumn">{{ h }}</option>
            }
          </select>
        </app-field>
        <app-field label="Separador decimal">
          <select appInput [value]="draft().numberFormat" (change)="setNumber(val($event))">
            <option value="COMMA" [selected]="draft().numberFormat === 'COMMA'">Vírgula (1.234,56)</option>
            <option value="DOT" [selected]="draft().numberFormat === 'DOT'">Ponto (1,234.56)</option>
          </select>
        </app-field>
      </div>

      <div class="me-grid">
        @for (line of headerIndexes(); track line) {
          <app-field [label]="line === 0 ? 'Início da tabela: rótulos da 1ª linha do cabeçalho' : 'Rótulos da linha ' + (line + 1) + ' do cabeçalho'" hint="Um rótulo por linha, exatamente como no PDF.">
            <textarea appInput rows="4" [value]="headerText(line)" (change)="setHeader(line, val($event))"></textarea>
          </app-field>
        }
        <app-field label="Fim da tabela: textos que a encerram" hint="Um por linha (ex.: Totais:).">
          <textarea appInput rows="4" [value]="table().endMarkers.join('\\n')" (change)="setEnd(val($event))"></textarea>
        </app-field>
        <app-field label="Ignorar linhas que contêm" hint="Rodapés e numeração de página (ao menos 3 caracteres).">
          <textarea appInput rows="4" [value]="draft().ignoreLinesContaining.join('\\n')" (change)="setIgnored(val($event))"></textarea>
        </app-field>
      </div>

      <h3 class="me-title">Colunas da tabela</h3>
      <div class="table-responsive">
        <table class="table table-sm align-middle me-table">
          <caption class="visually-hidden">Destino de cada coluna do PDF</caption>
          <thead>
            <tr>
              <th scope="col">Coluna no PDF</th>
              <th scope="col">Linha</th>
              <th scope="col">Vai para</th>
            </tr>
          </thead>
          <tbody>
            @for (c of columns(); track c.header) {
              <tr>
                <th scope="row" class="fw-normal">{{ c.header }}</th>
                <td>{{ c.line }}</td>
                <td>
                  <div class="me-targets">
                    @for (t of c.targets; track t) {
                      <span class="badge rounded-pill text-bg-light me-chip">
                        {{ label(t) }}
                        <button type="button" class="btn-close btn-close-sm" [attr.aria-label]="'Remover ' + t + ' de ' + c.header" (click)="removeTarget(c.header, c.line, c.targets, t)"></button>
                      </span>
                    } @empty {
                      <span class="app-muted">Ignorada</span>
                    }
                    <select class="form-select form-select-sm me-add" [attr.aria-label]="'Adicionar destino para ' + c.header" (change)="addTarget(c.header, c.line, c.targets, take($event))">
                      <option value="">+ destino…</option>
                      @for (f of fields(); track f.field) {
                        <option [value]="f.field">{{ label(f.field) }}{{ f.required ? ' *' : '' }}</option>
                      }
                      <option [value]="info(c.header)">Informação adicional ({{ info(c.header) }})</option>
                      <option [value]="paid">{{ label(paid) }}</option>
                    </select>
                    @if (c.targets.length) {
                      <button class="btn btn-sm btn-link" type="button" (click)="setTargets(c.header, c.line, [])">Ignorar</button>
                    }
                  </div>
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="3" class="app-muted">Informe os rótulos do cabeçalho para listar as colunas.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      <h3 class="me-title">Devedor</h3>
      <div class="me-grid">
        <app-field label="Onde está o devedor">
          <select appInput [value]="draft().debtor.source" (change)="setDebtorSource(val($event))">
            <option value="HEADER" [selected]="draft().debtor.source === 'HEADER'">Cabeçalho antes de cada tabela (Rótulo: valor)</option>
            <option value="COLUMNS" [selected]="draft().debtor.source === 'COLUMNS'">Colunas da própria tabela</option>
          </select>
        </app-field>
        @if (draft().debtor.source === 'HEADER') {
          <app-field label="Rótulo que abre o bloco do devedor">
            <input appInput [value]="draft().debtor.blockStartLabel ?? ''" (change)="setBlockStart(val($event))" />
          </app-field>
        }
      </div>
      @if (draft().debtor.source === 'HEADER') {
        <ng-container *ngTemplateOutlet="labelFields; context: { $implicit: draft().debtor.fields, kind: 'debtor' }" />
      }
      <h3 class="me-title">Campos do documento <span class="app-muted me-sub">(valem para todos os títulos, ex.: data-base do saldo)</span></h3>
      <ng-container *ngTemplateOutlet="labelFields; context: { $implicit: draft().documentFields, kind: 'document' }" />

      <ng-template #labelFields let-items let-kind="kind">
        <div class="me-rows">
          @for (f of items; track $index; let i = $index) {
            <div class="me-row">
              <input class="form-control form-control-sm" [value]="f.label" aria-label="Rótulo" (change)="patchField(kind, i, { label: val($event) })" />
              <select class="form-select form-select-sm" aria-label="Campo" (change)="patchField(kind, i, { target: val($event) })">
                @for (o of fieldOptions(f.target); track o) {
                  <option [value]="o" [selected]="o === f.target">{{ label(o) }}</option>
                }
              </select>
              <select class="form-select form-select-sm" aria-label="Posição do valor" (change)="patchField(kind, i, { position: asPosition(val($event)) })">
                <option value="RIGHT" [selected]="f.position === 'RIGHT'">à direita</option>
                <option value="BELOW" [selected]="f.position === 'BELOW'">abaixo</option>
              </select>
              <select class="form-select form-select-sm" aria-label="Ajuste" (change)="patchField(kind, i, { transform: asTransform(val($event)) })">
                <option value="NONE" [selected]="f.transform === 'NONE'">como está</option>
                <option value="DROP_LEADING_CODE" [selected]="f.transform === 'DROP_LEADING_CODE'">sem o código inicial</option>
                <option value="LEADING_CODE" [selected]="f.transform === 'LEADING_CODE'">só o código inicial</option>
              </select>
              <button class="btn btn-sm btn-outline-secondary border-0" type="button" aria-label="Remover campo" (click)="removeField(kind, i)"><app-icon name="x-lg" /></button>
            </div>
          }
          <button class="btn btn-sm btn-link align-self-start" type="button" (click)="addField(kind)"><app-icon name="plus-lg" /> Campo</button>
        </div>
      </ng-template>

      <h3 class="me-title">Totais de controle do documento</h3>
      <div class="me-rows">
        @for (c of draft().controlTotals; track $index; let i = $index) {
          <div class="me-row">
            <input class="form-control form-control-sm" [value]="c.label" aria-label="Rótulo do total" (change)="patchControl(i, { label: val($event) })" />
            <select class="form-select form-select-sm" aria-label="Coluna do total" (change)="patchControl(i, { column: val($event) || null })">
              <option value="" [selected]="!c.column">valor à direita do rótulo</option>
              @for (h of lineOne(); track h) {
                <option [value]="h" [selected]="h === c.column">coluna {{ h }}</option>
              }
            </select>
            <select class="form-select form-select-sm" aria-label="O que soma" (change)="patchControl(i, { target: val($event) })">
              @for (t of controlTargets; track t) {
                <option [value]="t" [selected]="t === c.target">{{ t === 'quantidade' ? 'quantidade de títulos' : label(t) }}</option>
              }
            </select>
            <select class="form-select form-select-sm" aria-label="Abrangência" (change)="patchControl(i, { scope: val($event) === 'DOCUMENT' ? 'DOCUMENT' : 'BLOCK' })">
              <option value="BLOCK" [selected]="c.scope === 'BLOCK'">por devedor</option>
              <option value="DOCUMENT" [selected]="c.scope === 'DOCUMENT'">documento inteiro</option>
            </select>
            <button class="btn btn-sm btn-outline-secondary border-0" type="button" aria-label="Remover total" (click)="removeControl(i)"><app-icon name="x-lg" /></button>
          </div>
        }
        <button class="btn btn-sm btn-link align-self-start" type="button" (click)="addControl()"><app-icon name="plus-lg" /> Total</button>
      </div>

      <details class="me-json">
        <summary>Mapa em JSON (avançado)</summary>
        <textarea class="form-control font-monospace" rows="12" [value]="json()" (change)="setJson(val($event))" aria-label="Mapa em JSON"></textarea>
        @if (jsonError()) {
          <div class="text-danger small" role="alert">{{ jsonError() }}</div>
        }
      </details>

      <div class="me-actions">
        <button class="btn btn-link" type="button" (click)="reset()" [disabled]="!dirty() || busy()">Desfazer alterações</button>
        <button class="btn btn-primary" type="button" (click)="apply.emit(draft())" [disabled]="!dirty() || busy()">
          <app-icon name="arrow-repeat" />
          Aplicar e reprocessar
        </button>
      </div>
    </div>
  `,
  styles: `
    .me {
      display: grid;
      gap: 8px;
    }
    .me-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 0 12px;
    }
    .me-title {
      margin: 8px 0 4px;
      font-size: var(--app-text-sm);
      font-weight: 600;
    }
    .me-sub {
      font-weight: 400;
    }
    .me-table {
      font-size: var(--app-text-sm);
    }
    .me-targets {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
    }
    .me-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-weight: 400;
      border: 1px solid var(--bs-border-color);
    }
    .btn-close-sm {
      width: 0.5em;
      height: 0.5em;
    }
    .me-add {
      width: auto;
      max-width: 220px;
    }
    .me-rows {
      display: grid;
      gap: 6px;
    }
    .me-row {
      display: grid;
      grid-template-columns: minmax(120px, 1.2fr) minmax(140px, 1.4fr) minmax(110px, 1fr) minmax(110px, 1fr) auto;
      gap: 6px;
      align-items: center;
    }
    .me-json textarea {
      margin-top: 6px;
      font-size: var(--app-text-xs);
    }
    .me-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      padding-top: 8px;
      border-top: 1px solid var(--bs-border-color);
    }
    @media (max-width: 720px) {
      .me-row {
        grid-template-columns: 1fr 1fr;
      }
    }
  `,
})
export class PdfMapEditor {
  readonly map = input<ExtractionMap | null>(null);
  readonly fields = input<readonly CanonicalFieldOption[]>([]);
  readonly busy = input(false);
  readonly apply = output<ExtractionMap>();
  protected readonly paid = PAID_WHEN_FILLED;
  protected readonly controlTargets = [...MONETARY, 'quantidade'];
  protected readonly draft = signal<ExtractionMap>(emptyMap());
  protected readonly jsonError = signal<string | null>(null);
  private original = '';

  protected readonly table = computed(() => this.draft().table ?? emptyMap().table!);
  protected readonly columns = computed(() => editorColumns(this.draft()));
  protected readonly lineOne = computed(() => this.table().headerLines[0] ?? []);
  protected readonly headerIndexes = computed(() => Array.from({ length: this.table().linesPerRecord }, (_, i) => i));
  protected readonly json = computed(() => JSON.stringify(this.draft(), null, 2));
  protected readonly dirty = computed(() => JSON.stringify(this.draft()) !== this.original);

  constructor() {
    effect(() => {
      const source = this.map() ? cloneMap(this.map()!) : emptyMap();
      this.original = JSON.stringify(source);
      this.draft.set(source);
      this.jsonError.set(null);
    });
  }

  /** Valor do campo que disparou o evento. */
  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement).value;
  }

  /** Valor escolhido num seletor de "adicionar", que volta ao item vazio. */
  protected take(event: Event): string {
    const select = event.target as HTMLSelectElement;
    const value = select.value;
    select.value = '';
    return value;
  }

  protected label(target: string): string {
    return targetLabel(target);
  }

  protected asPosition(value: string): ValuePosition {
    return value === 'BELOW' ? 'BELOW' : 'RIGHT';
  }

  protected asTransform(value: string): ValueTransform {
    return value === 'DROP_LEADING_CODE' || value === 'LEADING_CODE' ? value : 'NONE';
  }

  protected info(header: string): string {
    return infoTarget(header);
  }

  protected fieldOptions(current: string): string[] {
    const all = this.fields().map((f) => f.field);
    return all.includes(current) || !current ? all : [current, ...all];
  }

  protected headerText(line: number): string {
    return (this.table().headerLines[line] ?? []).join('\n');
  }

  protected reset(): void {
    this.draft.set(JSON.parse(this.original) as ExtractionMap);
    this.jsonError.set(null);
  }

  protected setLines(value: string): void {
    this.draft.set(withLinesPerRecord(this.draft(), Number(value) || 1));
  }

  protected setAnchor(value: string): void {
    this.draft.set(withAnchor(this.draft(), value || null));
  }

  protected setNumber(value: string): void {
    this.draft.update((m) => ({ ...cloneMap(m), numberFormat: value as NumberStyle }));
  }

  protected setHeader(line: number, text: string): void {
    this.draft.set(withHeaderLine(this.draft(), line, parseLabels(text)));
  }

  protected setEnd(text: string): void {
    this.draft.set(withEndMarkers(this.draft(), parseLabels(text)));
  }

  protected setIgnored(text: string): void {
    this.draft.set(withIgnoredTexts(this.draft(), parseLabels(text)));
  }

  protected setTargets(header: string, line: number, targets: string[]): void {
    this.draft.set(withColumnTargets(this.draft(), header, line, targets));
  }

  protected addTarget(header: string, line: number, targets: string[], target: string): void {
    if (target && !targets.includes(target)) {
      this.setTargets(header, line, [...targets, target]);
    }
  }

  protected removeTarget(header: string, line: number, targets: string[], target: string): void {
    this.setTargets(header, line, targets.filter((t) => t !== target));
  }

  protected setDebtorSource(source: string): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      next.debtor.source = source === 'HEADER' ? 'HEADER' : 'COLUMNS';
      return next;
    });
  }

  protected setBlockStart(label: string): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      next.debtor.blockStartLabel = label.trim() || null;
      return next;
    });
  }

  private fieldList(m: ExtractionMap, kind: 'debtor' | 'document'): LabelField[] {
    return kind === 'debtor' ? m.debtor.fields : m.documentFields;
  }

  protected patchField(kind: 'debtor' | 'document', index: number, patch: Partial<{ label: string; target: string; position: ValuePosition; transform: ValueTransform }>): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      const list = this.fieldList(next, kind);
      list[index] = { ...list[index], ...patch };
      return next;
    });
  }

  protected addField(kind: 'debtor' | 'document'): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      this.fieldList(next, kind).push({ label: '', target: kind === 'debtor' ? 'nome' : 'data_saldo', position: 'RIGHT', transform: 'NONE' });
      return next;
    });
  }

  protected removeField(kind: 'debtor' | 'document', index: number): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      this.fieldList(next, kind).splice(index, 1);
      return next;
    });
  }

  protected patchControl(index: number, patch: Partial<{ label: string; column: string | null; target: string; scope: TotalScope }>): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      next.controlTotals[index] = { ...next.controlTotals[index], ...patch } as ControlTotal;
      return next;
    });
  }

  protected addControl(): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      next.controlTotals.push({ label: 'Totais:', column: null, target: 'valor_principal', scope: 'BLOCK' });
      return next;
    });
  }

  protected removeControl(index: number): void {
    this.draft.update((m) => {
      const next = cloneMap(m);
      next.controlTotals.splice(index, 1);
      return next;
    });
  }

  protected setJson(text: string): void {
    try {
      const parsed = JSON.parse(text) as ExtractionMap;
      if (!parsed || typeof parsed !== 'object' || !('schemaVersion' in parsed)) {
        throw new Error('sem schemaVersion');
      }
      this.draft.set(parsed);
      this.jsonError.set(null);
    } catch {
      this.jsonError.set('JSON inválido: corrija antes de aplicar.');
    }
  }
}
