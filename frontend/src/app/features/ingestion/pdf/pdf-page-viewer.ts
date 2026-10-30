import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { PdfImportsApi } from '../data/pdf-import.api';
import { RegionView } from '../data/pdf-import.models';
import { regionBox } from '../data/pdf-import.rules';

/** Legenda das regiões lidas no PDF. */
export const REGION_LEGEND: readonly { kind: string; label: string }[] = [
  { kind: 'DEBTOR', label: 'Devedor (cabeçalho)' },
  { kind: 'HEADER', label: 'Cabeçalho da tabela' },
  { kind: 'TOTAL', label: 'Totais do documento' },
  { kind: 'DOCUMENT_FIELD', label: 'Campo do documento' },
  { kind: 'UNRECOGNIZED', label: 'Linha não reconhecida' },
];

/**
 * Página do PDF (imagem gerada no servidor, com o token no cabeçalho e convertida em `data:` URL) com as regiões lidas
 * em destaque. A região selecionada (clique num título) leva à sua página e fica realçada.
 */
@Component({
  selector: 'app-pdf-page-viewer',
  imports: [Icon],
  template: `
    <div class="pv-toolbar">
      <button class="btn btn-sm btn-outline-secondary" type="button" (click)="go(page() - 1)" [disabled]="page() <= 1" aria-label="Página anterior">
        <app-icon name="chevron-left" />
      </button>
      <span class="pv-page" aria-live="polite">Página {{ page() }} de {{ pages() }}</span>
      <button class="btn btn-sm btn-outline-secondary" type="button" (click)="go(page() + 1)" [disabled]="page() >= pages()" aria-label="Próxima página">
        <app-icon name="chevron-right" />
      </button>
      <div class="form-check form-switch pv-switch">
        <input class="form-check-input" type="checkbox" role="switch" id="pv-regions" [checked]="showRegions()" (change)="showRegions.set(!showRegions())" />
        <label class="form-check-label" for="pv-regions">Destacar o que foi lido</label>
      </div>
    </div>
    @if (showRegions()) {
      <ul class="pv-legend" aria-label="Legenda">
        @for (l of legend; track l.kind) {
          <li><span class="pv-swatch" [attr.data-kind]="l.kind" aria-hidden="true"></span>{{ l.label }}</li>
        }
        <li><span class="pv-swatch" data-kind="SELECTED" aria-hidden="true"></span>Título selecionado</li>
      </ul>
    }
    <div class="pv-frame" data-tour="pdf-viewer">
      @if (image(); as src) {
        <div class="pv-page-box">
          <img [src]="src" [alt]="'Página ' + page() + ' do PDF'" class="pv-img" />
          @if (showRegions()) {
            @for (r of pageRegions(); track $index) {
              <span class="pv-region" [attr.data-kind]="r.kind" [style.left.%]="box(r).left" [style.top.%]="box(r).top" [style.width.%]="box(r).width" [style.height.%]="box(r).height"></span>
            }
          }
          @for (r of pageHighlight(); track $index) {
            <span class="pv-region pv-region--selected" data-kind="SELECTED" [style.left.%]="box(r).left" [style.top.%]="box(r).top" [style.width.%]="box(r).width" [style.height.%]="box(r).height"></span>
          }
        </div>
      } @else if (failed()) {
        <p class="app-muted pv-empty">Não foi possível carregar a página. A imagem exige permissão de importação.</p>
      } @else {
        <p class="app-muted pv-empty">Carregando a página…</p>
      }
    </div>
  `,
  styles: `
    .pv-toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }
    .pv-page {
      font-size: var(--app-text-sm);
      min-width: 110px;
      text-align: center;
    }
    .pv-switch {
      margin: 0 0 0 auto;
      font-size: var(--app-text-sm);
    }
    .pv-legend {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      margin: 0 0 8px;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .pv-legend li {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .pv-swatch {
      display: inline-block;
      width: 12px;
      height: 12px;
      border-radius: 3px;
      border: 2px solid var(--pv-color);
    }
    .pv-frame {
      max-height: 78vh;
      overflow: auto;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-lg);
      background: var(--bs-tertiary-bg);
    }
    .pv-page-box {
      position: relative;
      width: 100%;
      min-width: 560px;
    }
    .pv-img {
      display: block;
      width: 100%;
      height: auto;
    }
    .pv-region {
      position: absolute;
      border: 1.5px solid var(--pv-color);
      background: color-mix(in srgb, var(--pv-color) 12%, transparent);
      border-radius: 2px;
      pointer-events: none;
    }
    .pv-region--selected {
      border-width: 2px;
      background: color-mix(in srgb, var(--pv-color) 25%, transparent);
    }
    [data-kind='DEBTOR'] {
      --pv-color: var(--bs-info);
    }
    [data-kind='HEADER'] {
      --pv-color: var(--bs-primary);
    }
    [data-kind='TOTAL'] {
      --pv-color: var(--bs-success);
    }
    [data-kind='DOCUMENT_FIELD'] {
      --pv-color: var(--bs-secondary);
    }
    [data-kind='UNRECOGNIZED'] {
      --pv-color: var(--bs-danger);
    }
    [data-kind='SELECTED'] {
      --pv-color: var(--bs-warning);
    }
    .pv-empty {
      padding: 24px;
      margin: 0;
    }
  `,
})
export class PdfPageViewer {
  readonly importId = input.required<string>();
  readonly pages = input.required<number>();
  readonly pageSizes = input<readonly { page: number; width: number; height: number }[]>([]);
  readonly regions = input<readonly RegionView[]>([]);
  readonly highlight = input<readonly RegionView[]>([]);
  readonly page = model(1);
  private readonly api = inject(PdfImportsApi);
  protected readonly legend = REGION_LEGEND;
  protected readonly showRegions = signal(true);
  protected readonly failed = signal(false);
  private readonly images = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly image = computed(() => this.images().get(this.page()) ?? null);
  protected readonly pageRegions = computed(() => this.regions().filter((r) => r.page === this.page()));
  protected readonly pageHighlight = computed(() => this.highlight().filter((r) => r.page === this.page()));

  constructor() {
    effect(() => {
      const first = this.highlight()[0];
      if (first && first.page !== this.page()) {
        this.page.set(first.page);
      }
    });
    effect(() => this.load(this.page()));
  }

  protected go(page: number): void {
    if (page >= 1 && page <= this.pages()) {
      this.page.set(page);
    }
  }

  protected box(r: RegionView) {
    const size = this.pageSizes().find((p) => p.page === r.page) ?? { width: 595.32, height: 841.92 };
    return regionBox(r, size.width, size.height);
  }

  private load(page: number): void {
    if (this.images().has(page)) {
      return;
    }
    this.failed.set(false);
    this.api.pageImage(this.importId(), page).subscribe({
      next: (url) => this.images.update((m) => new Map(m).set(page, url)),
      error: () => this.failed.set(true),
    });
  }
}
