import { Component, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
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
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

/**
 * Passo 1 da conferência da importação: carteira, layout e arquivo. Nada é gravado na carteira até o passo 6; o
 * arquivo é lido em prévia e os próximos passos conferem o formato dos valores, as linhas, os totais e os alertas.
 */
@Component({
  selector: 'app-review-start',
  imports: [...FORM_FIELD, Icon, ReactiveFormsModule, RouterLink, EntityPicker, PageHeader],
  template: `
    <app-page-header title="Nova importação" subtitle="Envie o arquivo do credor. Antes de gravar, você confere formato, totais e alertas.">
      <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" /> Importações</a>
    </app-page-header>

    <ol class="rs-steps" aria-label="Etapas da conferência">
      @for (s of steps; track s.step) {
        <li [class.rs-steps--current]="s.step === 1"><span class="rs-steps__n" aria-hidden="true">{{ s.step }}</span>{{ s.label }}</li>
      }
    </ol>

    <form class="app-card rs-form" [formGroup]="form" (ngSubmit)="send()" novalidate data-tour="review-upload">
      <h2 class="app-card__title">Arquivo e layout</h2>
      <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Os títulos entram nesta carteira (e no seu credor)." />
      <app-field class="app-full-width" label="Layout">
        <select appInput formControlName="layoutId">
          <option value="">Layout padrão (formato pela extensão)</option>
          @for (l of layouts(); track l.id) {
            <option [ngValue]="l.id">{{ l.name }} · {{ l.code }} v{{ l.version }} ({{ l.format }})</option>
          }
        </select>
      </app-field>
      <div class="rs-file">
        <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
        <button class="btn btn-outline-primary" type="button" (click)="input.click()">
          <app-icon name="file-earmark-arrow-up" />
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
        <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      <div class="rs-actions">
        <a class="btn btn-link" routerLink="/importacao">Cancelar</a>
        <button class="btn btn-primary" type="submit" [disabled]="sending() || !file() || !portfolioId()">
          <app-icon name="arrow-right" />
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
      color: var(--bs-secondary-color);
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
      border: 1px solid var(--bs-border-color);
      font-size: var(--app-text-xs);
    }
    .rs-steps--current {
      color: var(--bs-body-color);
      font-weight: 600;
    }
    .rs-steps--current .rs-steps__n {
      background: var(--bs-primary);
      border-color: var(--bs-primary-text-emphasis);
      color: var(--bs-white);
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
