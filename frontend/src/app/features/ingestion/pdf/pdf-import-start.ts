import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { sizeText } from '../data/ingestion.labels';
import { PdfImportsApi } from '../data/pdf-import.api';
import { PDF_ACCEPT, UPLOAD_ERROR_LABELS, pdfFileProblem } from '../data/pdf-import.rules';

/**
 * Início da Importação PDF inteligente: carteira e PDF. O sistema lê o PDF, encontra os dados e monta o "mapa" de
 * extração; nada é gravado na carteira até você validar o mapa e concluir a conferência da importação.
 */
@Component({
  selector: 'app-pdf-import-start',
  imports: [ReactiveFormsModule, RouterLink, EntityPicker, Icon, PageHeader],
  template: `
    <app-page-header title="Importação PDF inteligente" subtitle="Para quando o credor só consegue enviar PDF: o sistema encontra os títulos e você valida o mapa antes de importar.">
      <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" /> Importações</a>
    </app-page-header>

    <ol class="ps-how" aria-label="Como funciona" data-tour="pdf-how">
      <li><strong>1. Envio</strong> — escolha a carteira e o PDF do credor.</li>
      <li><strong>2. Mapa</strong> — o sistema descobre onde estão devedor, títulos e totais (com IA ou com o mapa já salvo do credor).</li>
      <li><strong>3. Validação</strong> — você confere o mapa e os títulos lado a lado com o PDF e os totais do documento.</li>
      <li><strong>4. Conferência</strong> — o lote segue para a conferência de sempre (formato, linhas, grandes totais e alertas).</li>
    </ol>

    <form class="app-card ps-form" [formGroup]="form" (ngSubmit)="send()" novalidate data-tour="pdf-upload">
      <h2 class="app-card__title">Carteira e PDF</h2>
      <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Os títulos entram nesta carteira (e no seu credor)." />
      <div class="ps-file">
        <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
        <button class="btn btn-outline-primary" type="button" (click)="input.click()">
          <app-icon name="file-earmark-pdf" />
          Escolher PDF
        </button>
        @if (file(); as f) {
          <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
        } @else {
          <span class="app-muted">PDF de até 20 MB, sem senha</span>
        }
      </div>
      <p class="app-muted ps-small">
        PDFs gerados pelo sistema do credor (com texto) são lidos com exatidão: todo valor importado sai do texto do PDF. PDFs digitalizados (só imagem) são lidos visualmente
        pela IA e pedem conferência título a título.
      </p>
      @if (error()) {
        <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      <div class="ps-actions">
        <a class="btn btn-link" routerLink="/importacao">Cancelar</a>
        <button class="btn btn-primary" type="submit" [disabled]="sending() || !file() || !portfolioId()">
          <app-icon name="magic" />
          Enviar e ler o PDF
        </button>
      </div>
    </form>
  `,
  styles: `
    .ps-how {
      display: grid;
      gap: 4px;
      max-width: 720px;
      margin: 0 0 var(--app-gap);
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .ps-how strong {
      color: var(--bs-body-color);
      font-weight: 500;
    }
    .ps-form {
      max-width: 720px;
    }
    .ps-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
    }
    .ps-small {
      font-size: var(--app-text-sm);
    }
    .ps-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 12px;
    }
  `,
})
export class PdfImportStart {
  private readonly api = inject(PdfImportsApi);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  protected readonly portfolios = inject(PortfolioLookup).source();
  protected readonly accept = PDF_ACCEPT;
  protected readonly size = sizeText;
  protected readonly file = signal<File | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly sending = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly initialPortfolio = inject(ActivatedRoute).snapshot.queryParamMap.get('carteira');
  protected readonly form = this.fb.group({ portfolio: this.fb.control<string[]>(this.initialPortfolio ? [this.initialPortfolio] : []) });
  protected readonly portfolioId = toSignal(this.form.controls.portfolio.valueChanges.pipe(startWith(this.form.controls.portfolio.value), map((v): string | null => v[0] ?? null)), {
    initialValue: this.initialPortfolio,
  });

  protected pick(list: FileList | null): void {
    const f = list?.[0] ?? null;
    this.error.set(null);
    if (f) {
      const problem = pdfFileProblem(f);
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
      .upload(f, portfolioId)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (view) => {
          this.notifications.success('PDF recebido: procurando os dados no documento.');
          void this.router.navigate(['/importacao/pdf', view.id]);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          const existing = err instanceof HttpErrorResponse ? (err.error as { details?: { pdfImportId?: string } } | null)?.details?.pdfImportId : undefined;
          if (e.code === 'pdf_import.duplicate_file' && existing) {
            this.notifications.info('Este PDF já foi enviado: abrindo a importação existente.');
            void this.router.navigate(['/importacao/pdf', existing]);
            return;
          }
          this.error.set((e.code && UPLOAD_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
