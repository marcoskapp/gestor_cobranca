import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ImportReviewsApi } from '../data/ingestion.api';
import { ColumnFormatView, FormatOption, FormatOptionView, ReviewView } from '../data/ingestion.models';
import { COLUMN_LABELS, FORMAT_STATUS_LABELS, OPTION_HINTS, OPTION_LABELS, PROBLEM_LABELS, REVIEW_ERROR_LABELS, STYLE_LABELS, pendingColumns } from '../data/review.rules';

/**
 * Passo 2: como os valores com vírgula e ponto foram lidos, coluna por coluna, com exemplos reais do arquivo
 * convertidos lado a lado. Colunas ambíguas ou misturadas exigem a escolha; as demais, a confirmação.
 */
@Component({
  selector: 'app-format-step',
  imports: [DecimalPipe, MatButtonModule, MatIconModule, MatRadioModule, Amount, Skeleton, AppDatePipe],
  template: `
    @if (!review().format.available) {
      <p class="app-muted" role="status">Lendo o arquivo para identificar o formato dos valores…</p>
      <app-skeleton variant="lines" [rows]="4" />
    } @else {
      @let f = review().format;
      <p class="fs-intro">
        Confira como os valores do arquivo foram lidos. O sistema nunca adivinha: quando um valor como <span class="app-mono">1.000</span> pode ser mil ou um
        real, você escolhe vendo o efeito na soma.
      </p>
      <ul class="fs-facts" aria-label="Resumo do arquivo">
        <li><strong class="app-numeric">{{ f.rows | number }}</strong> linhas de dados</li>
        @if (f.typedValues) {
          <li><strong class="app-numeric">{{ f.typedValues | number }}</strong> valores numéricos da planilha (sem ambiguidade)</li>
        }
        @if (f.textValues) {
          <li><strong class="app-numeric">{{ f.textValues | number }}</strong> valores em texto{{ review().batch.source === 'XLSX' ? ' ("números armazenados como texto")' : '' }}, conferidos abaixo</li>
        }
      </ul>
      @if (f.awaitingDecision) {
        <p class="app-alert app-alert--warning" role="alert">
          <mat-icon aria-hidden="true">rule</mat-icon>
          <span>A validação das linhas está parada: escolha como ler as colunas marcadas para continuar.</span>
        </p>
      } @else if (f.confirmed && !editing()) {
        <p class="app-alert app-alert--success" role="status">
          <mat-icon aria-hidden="true">task_alt</mat-icon>
          <span>Formato confirmado{{ f.confirmedAt ? ' em ' + (f.confirmedAt | appDate) : '' }}.</span>
        </p>
      }
      @if (!f.columns.length) {
        <p class="app-muted">O arquivo não tem colunas de valor monetário preenchidas.</p>
      }
      @for (c of f.columns; track c.column) {
        <section class="app-card fs-column" [attr.aria-labelledby]="'fs-' + c.column" [attr.data-tour]="$first ? 'review-format' : null">
          <div class="fs-head">
            <h3 class="fs-title" [id]="'fs-' + c.column">{{ label(c.column) }} <span class="app-muted app-mono">{{ c.column }}</span></h3>
            <span class="app-status" [class]="'app-status app-status--' + status(c).tone">
              <mat-icon aria-hidden="true">{{ c.requiresDecision ? 'help' : 'check' }}</mat-icon>{{ status(c).label }}
            </span>
          </div>
          <p class="app-muted fs-counts">
            @for (s of styles(c); track s.style) {
              <span>{{ s.count | number }} {{ s.label }}</span>
            }
          </p>
          @if (c.suggestion && c.requiresDecision) {
            <p class="fs-suggestion"><mat-icon aria-hidden="true">lightbulb</mat-icon>As outras colunas do arquivo usam {{ optionLabel(c.suggestion) }}.</p>
          }
          @let editable = canEdit() && (c.requiresDecision || c.options.length > 1);
          <mat-radio-group class="fs-options" [attr.aria-label]="'Leitura de ' + label(c.column)" [value]="chosen()[c.column]" (change)="choose(c.column, $event.value)" [disabled]="!editable">
            @for (o of c.options; track o.option) {
              <div class="fs-option" [class.fs-option--selected]="chosen()[c.column] === o.option">
                <mat-radio-button [value]="o.option">
                  <strong>{{ optionLabel(o.option) }}</strong>
                  @if (c.recommended === o.option) {
                    <span class="app-status app-status--info fs-badge"><mat-icon aria-hidden="true">auto_awesome</mat-icon>indicada pelos dados</span>
                  }
                </mat-radio-button>
                <p class="app-muted fs-hint">{{ hint(o.option) }}</p>
                <p class="fs-total">
                  Soma da coluna: <strong><app-amount [value]="o.total" /></strong>
                  @if (o.invalidCount) {
                    <span class="fs-invalid"><mat-icon aria-hidden="true">warning</mat-icon>{{ o.invalidCount | number }} valor(es) não lidos nesta leitura</span>
                  }
                </p>
                <table class="fs-examples">
                  <caption class="cdk-visually-hidden">Exemplos do arquivo lidos como {{ optionLabel(o.option) }}</caption>
                  <thead>
                    <tr><th scope="col">Linha</th><th scope="col">No arquivo</th><th scope="col">Lido como</th></tr>
                  </thead>
                  <tbody>
                    @for (e of o.examples; track e.rowNumber + e.raw) {
                      <tr [class.fs-ambiguous]="e.style === 'AMBIGUOUS_DOT' || e.style === 'AMBIGUOUS_COMMA'">
                        <td class="app-numeric">{{ e.rowNumber }}</td>
                        <td class="app-mono">{{ e.raw }}</td>
                        <td>
                          @if (e.value !== null) {
                            <app-amount [value]="e.value" />
                          } @else {
                            <span class="fs-invalid">{{ problem(e.problem) }}</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </mat-radio-group>
        </section>
      }
      @if (error()) {
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }
      @if (canEdit()) {
        @if (willRevalidate()) {
          <p class="app-alert app-alert--info" role="note">
            <mat-icon aria-hidden="true">refresh</mat-icon>
            <span>A leitura mudou depois da validação: as linhas serão validadas de novo e os totais e alertas, refeitos.</span>
          </p>
        }
        <div class="fs-actions">
          @if (f.confirmed && !editing()) {
            <button mat-stroked-button type="button" (click)="editing.set(true)"><mat-icon aria-hidden="true">edit</mat-icon>Alterar a leitura</button>
          } @else {
            <button mat-flat-button type="button" (click)="confirm()" [disabled]="saving() || missing().length > 0" data-tour="review-format-confirm">
              <mat-icon aria-hidden="true">done</mat-icon>
              {{ f.awaitingDecision ? 'Confirmar e validar as linhas' : 'Confirmar formato' }}
            </button>
          }
        </div>
        @if (missing().length) {
          <p class="app-muted fs-small" role="status">Escolha a leitura de: {{ missingLabels() }}.</p>
        }
      }
    }
  `,
  styles: `
    .fs-intro {
      margin-top: 0;
      max-width: 72ch;
    }
    .fs-facts {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 20px;
      margin: 0 0 12px;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
    }
    .fs-column {
      margin-bottom: var(--app-gap);
    }
    .fs-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .fs-title {
      margin: 0;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .fs-counts {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      margin: 4px 0 8px;
      font-size: var(--app-text-sm);
    }
    .fs-suggestion {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: var(--app-text-sm);
    }
    .fs-options {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr));
      gap: 12px;
    }
    .fs-option {
      padding: 8px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
    }
    .fs-option--selected {
      border-color: var(--mat-sys-primary);
      box-shadow: inset 0 0 0 1px var(--mat-sys-primary);
    }
    .fs-badge {
      margin-left: 6px;
    }
    .fs-hint {
      margin: 0 0 4px 40px;
      font-size: var(--app-text-sm);
    }
    .fs-total {
      margin: 4px 0;
      font-size: var(--app-text-sm);
    }
    .fs-invalid {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      margin-left: 8px;
      color: var(--app-danger-fg);
      font-size: var(--app-text-sm);
    }
    .fs-invalid .mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
    .fs-examples {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
    }
    .fs-examples th,
    .fs-examples td {
      padding: 3px 6px;
      text-align: left;
      border-bottom: 1px solid var(--app-border);
    }
    .fs-examples th {
      font-weight: 600;
      color: var(--mat-sys-on-surface-variant);
    }
    .fs-ambiguous td:nth-child(2) {
      font-weight: 700;
    }
    .fs-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
    }
    .fs-small {
      text-align: right;
      font-size: var(--app-text-sm);
    }
  `,
})
export class FormatStep {
  readonly review = input.required<ReviewView>();
  readonly canExecute = input(false);
  readonly changed = output<ReviewView>();

  private readonly api = inject(ImportReviewsApi);
  private readonly notifications = inject(NotificationService);

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Escolha por coluna: começa pela aplicada ou pela indicada pelos dados. */
  protected readonly chosen = linkedSignal<ReviewView, Record<string, FormatOption>>({
    source: () => this.review(),
    computation: (r) => Object.fromEntries(r.format.columns.flatMap((c) => {
      const value = c.decision ?? (c.requiresDecision ? null : c.recommended);
      return value ? [[c.column, value]] : [];
    })),
  });
  protected readonly canEdit = computed(() => this.canExecute() && this.review().status === 'OPEN');
  protected readonly missing = computed(() => pendingColumns(this.review().format.columns, this.chosen()));
  protected readonly missingLabels = computed(() => this.missing().map((c) => this.label(c)).join(', '));
  /** A escolha difere da aplicada numa prévia que já validou linhas. */
  protected readonly willRevalidate = computed(() => {
    const r = this.review();
    if (r.batch.status === 'AWAITING_FORMAT' || r.batch.status === 'RECEIVED') return false;
    return r.format.columns.some((c) => c.decision !== null && this.chosen()[c.column] !== c.decision);
  });

  protected label(column: string): string {
    return COLUMN_LABELS[column] ?? column;
  }

  protected optionLabel(option: FormatOption): string {
    return OPTION_LABELS[option];
  }

  protected hint(option: FormatOption): string {
    return OPTION_HINTS[option];
  }

  protected status(c: ColumnFormatView) {
    return FORMAT_STATUS_LABELS[c.status];
  }

  protected problem(code: string | null): string {
    return (code && PROBLEM_LABELS[code]) || 'não lido';
  }

  protected styles(c: ColumnFormatView): { style: string; count: number; label: string }[] {
    return Object.entries(c.styleCounts)
      .filter(([, n]) => (n ?? 0) > 0)
      .map(([style, n]) => ({ style, count: n ?? 0, label: STYLE_LABELS[style as keyof typeof STYLE_LABELS] ?? style }));
  }

  protected choose(column: string, option: FormatOption): void {
    this.chosen.update((current) => ({ ...current, [column]: option }));
    this.error.set(null);
  }

  /** Total de uma opção (para testes e leitores de tela). */
  protected optionOf(c: ColumnFormatView, option: FormatOption): FormatOptionView | undefined {
    return c.options.find((o) => o.option === option);
  }

  protected confirm(): void {
    const r = this.review();
    if (this.saving() || this.missing().length) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .confirmFormat(r.id, r.batch.id, this.chosen())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.editing.set(false);
          this.notifications.success(saved.batch.id !== r.batch.id ? 'Leitura alterada: as linhas serão validadas de novo.' : 'Formato confirmado.');
          this.changed.emit(saved);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && REVIEW_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
