import { Component, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map, startWith, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { valueOr } from '../../../shared/data/resource-value';
import { PortfoliosApi } from '../../organization/data/organization.api';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { ImportLayoutsApi, ImportReviewsApi } from '../data/ingestion.api';
import { ACCEPT, UPLOAD_ERROR_LABELS, fileProblem, sizeText } from '../data/ingestion.labels';
import { REVIEW_STEPS } from '../data/review.rules';

/**
 * Passo 1 da conferência da importação: carteira, layout e arquivo. Nada é gravado na carteira até o passo 6; o
 * arquivo é lido em prévia e os próximos passos conferem o formato dos valores, as linhas, os totais e os alertas.
 */
@Component({
  selector: 'app-review-start',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, EntityPicker, PageHeader],
  template: `
    <app-page-header title="Nova importação" subtitle="Envie o arquivo do credor. Antes de gravar, você confere formato, totais e alertas.">
      <a mat-button routerLink="/importacao"><mat-icon aria-hidden="true">arrow_back</mat-icon>Importações</a>
    </app-page-header>

    <ol class="rs-steps" aria-label="Etapas da conferência">
      @for (s of steps; track s.step) {
        <li [class.rs-steps--current]="s.step === 1"><span class="rs-steps__n" aria-hidden="true">{{ s.step }}</span>{{ s.label }}</li>
      }
    </ol>

    <form class="app-card rs-form" [formGroup]="form" (ngSubmit)="send()" novalidate data-tour="review-upload">
      <h2 class="app-card__title">Arquivo e layout</h2>
      <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Os títulos entram nesta carteira (e no seu credor)." />
      <mat-form-field class="app-full-width">
        <mat-label>Layout</mat-label>
        <mat-select formControlName="layoutId">
          <mat-option value="">Layout padrão (formato pela extensão)</mat-option>
          @for (l of layouts(); track l.id) {
            <mat-option [value]="l.id">{{ l.name }} · {{ l.code }} v{{ l.version }} ({{ l.format }})</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <div class="rs-file">
        <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
        <button mat-stroked-button type="button" (click)="input.click()">
          <mat-icon aria-hidden="true">upload_file</mat-icon>
          Escolher arquivo
        </button>
        @if (file(); as f) {
          <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
        } @else {
          <span class="app-muted">CSV ou XLSX, até 50 MB</span>
        }
      </div>
      <p class="app-muted rs-small">
        Valores com vírgula ou ponto são analisados coluna por coluna: se houver dúvida (por exemplo, <span class="app-mono">1.000</span> pode ser mil ou um real),
        você escolhe a leitura vendo exemplos do próprio arquivo.
      </p>
      @if (error()) {
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }
      <div class="rs-actions">
        <a mat-button routerLink="/importacao">Cancelar</a>
        <button mat-flat-button type="submit" [disabled]="sending() || !file() || !portfolioId()">
          <mat-icon aria-hidden="true">arrow_forward</mat-icon>
          Enviar e conferir
        </button>
      </div>
    </form>
  `,
  styles: `
    .rs-steps {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
      margin: 0 0 var(--app-gap);
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
      color: var(--mat-sys-on-surface-variant);
    }
    .rs-steps li {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .rs-steps__n {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      border: 1px solid var(--app-border);
      font-size: var(--app-text-xs);
    }
    .rs-steps--current {
      color: var(--mat-sys-on-surface);
      font-weight: 600;
    }
    .rs-steps--current .rs-steps__n {
      background: var(--mat-sys-primary);
      border-color: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
    }
    .rs-form {
      max-width: 720px;
    }
    .rs-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
    }
    .rs-small {
      font-size: var(--app-text-sm);
    }
    .rs-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 12px;
    }
  `,
})
export class ReviewStart {
  private readonly api = inject(ImportReviewsApi);
  private readonly layoutsApi = inject(ImportLayoutsApi);
  private readonly portfoliosApi = inject(PortfoliosApi);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  protected readonly portfolios = inject(PortfolioLookup).source();
  protected readonly accept = ACCEPT;
  protected readonly size = sizeText;
  protected readonly steps = REVIEW_STEPS;

  protected readonly file = signal<File | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly sending = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly initialPortfolio = inject(ActivatedRoute).snapshot.queryParamMap.get('carteira');
  protected readonly form = this.fb.group({
    portfolio: this.fb.control<string[]>(this.initialPortfolio ? [this.initialPortfolio] : []),
    layoutId: [''],
  });
  protected readonly portfolioId = toSignal(this.form.controls.portfolio.valueChanges.pipe(startWith(this.form.controls.portfolio.value), map((v): string | null => v[0] ?? null)), {
    initialValue: this.initialPortfolio,
  });

  /** Layouts ativos do credor da carteira, gerais ou da própria carteira. */
  private readonly layoutList = rxResource({
    params: () => this.portfolioId() ?? undefined,
    stream: ({ params }) =>
      this.portfoliosApi.get(params).pipe(
        switchMap((p) => this.layoutsApi.list(p.creditorId, false)),
        map((list) => list.filter((l) => l.active && (!l.portfolioId || l.portfolioId === params))),
      ),
  });
  protected readonly layouts = valueOr(this.layoutList, []);

  protected pick(list: FileList | null): void {
    const f = list?.[0] ?? null;
    this.error.set(null);
    if (f) {
      const problem = fileProblem(f);
      this.error.set(problem);
      this.file.set(problem ? null : f);
    }
  }

  protected send(): void {
    const f = this.file();
    const portfolioId = this.portfolioId();
    if (!f || !portfolioId || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .start(f, portfolioId, this.form.getRawValue().layoutId || null)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (review) => {
          this.notifications.success('Arquivo recebido: lendo os valores.');
          void this.router.navigate(['/importacao/conferencias', review.id]);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && UPLOAD_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
