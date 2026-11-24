import { Component, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { ApiError, toApiError, userMessage } from '../../core/http/problem';
import { Amount } from '../../shared/components/amount/amount';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { Icon } from '../../shared/components/icon/icon';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { DIALOG_DATA, DialogRef } from '../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../shared/dialog/dialog.directives';
import { DialogService } from '../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { groupedHash } from '../../shared/util/hash';
import { AppValidators } from '../../shared/validation/validators';
import { CreditorSession } from '../data/creditor-session';
import { CreditorStatementsApi } from '../data/creditor-statements.api';
import { CreditorPermission, CreditorStatementDetail, CreditorStatementFile } from '../data/creditor.models';
import { directionText } from './creditor-statements';

const FORMAT_LABELS: Record<CreditorStatementFile['format'], string> = { CSV: 'Planilha CSV', XLSX: 'Planilha Excel', PDF: 'PDF' };

/** Mensagem curta para a resposta ao demonstrativo (sem códigos internos). */
function responseMessage(error: ApiError): string {
  if (error.code === 'statement.files_changed') {
    return 'Os arquivos deste demonstrativo mudaram desde que você abriu a página. Atualizamos a lista abaixo: confira os novos códigos de integridade e responda de novo.';
  }
  if (error.code === 'creditor_step_up.invalid' || error.code === 'statement.mfa_required') {
    return error.detail ?? 'Código do autenticador inválido. Digite o código atual do aplicativo.';
  }
  if (error.status === 404) {
    return 'Este demonstrativo não está mais disponível. Volte à prestação de contas.';
  }
  if (error.status === 409) {
    return 'O demonstrativo mudou enquanto você respondia (por exemplo, outra pessoa do seu time já respondeu). Atualizamos a página abaixo: confira e, se ainda for o caso, responda de novo.';
  }
  if (error.status === 422) {
    return error.detail ?? 'Não foi possível registrar a resposta. Confira os dados e tente de novo.';
  }
  return userMessage(error);
}

interface ResponseDialogData {
  detail: CreditorStatementDetail;
}

/** O diálogo devolve a ficha atualizada ou, quando o demonstrativo mudou (409/404), o pedido de recarregar com o aviso. */
type ResponseResult = CreditorStatementDetail | { reload: true; message: string };

/** Erros em que a ficha aberta ficou velha: o diálogo fecha e a ficha recarrega com o aviso. */
function staleError(error: ApiError): boolean {
  return error.status === 409 || error.status === 404;
}

/**
 * Aceite: a pessoa confirma os valores e os arquivos (com o código de integridade de cada um) e digita o código do
 * autenticador. O termo guarda quem, quando, de onde e o código de integridade dos arquivos.
 */
@Component({
  selector: 'app-creditor-acknowledge-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Aceitar demonstrativo nº {{ d.statement.sequenceNumber }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Ao aceitar, você confirma que conferiu os valores e os arquivos abaixo. O aceite fica registrado com o seu nome, a
          data e o código de integridade de cada arquivo, e não pode ser desfeito: se depois encontrar uma diferença, a
          assessoria corrige por ajuste no próximo demonstrativo.
        </p>
        <dl class="app-definition-list">
          <dt>{{ direction }}</dt>
          <dd><app-amount [value]="net" /></dd>
          @for (f of d.files; track f.id) {
            <dt>{{ formats[f.format] }}</dt>
            <dd><span class="ca-hash">{{ hash(f.sha256) }}</span></dd>
          }
        </dl>
        <app-field class="mb-3" label="Observação (opcional)">
          <textarea appInput formControlName="note" rows="2" maxlength="2000"></textarea>
        </app-field>
        <app-field label="Código do autenticador" error="Digite os 6 dígitos." hint="O código de 6 dígitos do aplicativo autenticador.">
          <input appInput ngbAutofocus formControlName="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required
                 (input)="onlyDigits()" />
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="busy()">Aceitar demonstrativo</button>
      </div>
    </form>
  `,
  styles: `
    .ca-hash {
      font-family: var(--bs-font-monospace);
      font-size: var(--app-text-xs);
      word-break: break-all;
    }
  `,
})
export class CreditorAcknowledgeDialog {
  protected readonly d = inject<ResponseDialogData>(DIALOG_DATA).detail;
  private readonly ref = inject<DialogRef<ResponseResult>>(DialogRef);
  private readonly api = inject(CreditorStatementsApi);
  protected readonly formats = FORMAT_LABELS;
  protected readonly hash = groupedHash;
  protected readonly direction = directionText(this.d.statement.direction);
  protected readonly net = Math.abs(Number(this.d.statement.netTransfer));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    note: ['', [Validators.maxLength(2000)]],
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  protected onlyDigits(): void {
    const c = this.form.controls.code;
    const digits = c.value.replace(/\D/g, '').slice(0, 6);
    if (digits !== c.value) {
      c.setValue(digits);
    }
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.busy()) {
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.api
      .acknowledge(this.d.statement.id, v.code, v.note.trim() || null, this.d.files.map((f) => f.sha256))
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (detail) => this.ref.close(detail),
        error: (err: unknown) => {
          const e = toApiError(err);
          if (staleError(e)) {
            this.ref.close({ reload: true, message: responseMessage(e) });
            return;
          }
          this.error.set(responseMessage(e));
          this.form.controls.code.reset('');
        },
      });
  }
}

/** Contestação: motivo obrigatório. Os valores não mudam — a assessoria corrige por ajuste no período seguinte. */
@Component({
  selector: 'app-creditor-contest-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Contestar demonstrativo nº {{ d.statement.sequenceNumber }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Conte o que não confere (ex.: pagamento que faltou, valor diferente). A assessoria analisa e corrige por ajuste no
          próximo demonstrativo; depois, você pode aceitar este.
        </p>
        <app-field label="O que não confere" error="Descreva o motivo da contestação.">
          <textarea appInput ngbAutofocus formControlName="reason" rows="4" maxlength="2000" required></textarea>
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-danger" type="submit" [disabled]="busy()">Contestar demonstrativo</button>
      </div>
    </form>
  `,
})
export class CreditorContestDialog {
  protected readonly d = inject<ResponseDialogData>(DIALOG_DATA).detail;
  private readonly ref = inject<DialogRef<ResponseResult>>(DialogRef);
  private readonly api = inject(CreditorStatementsApi);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    reason: ['', [AppValidators.notBlank, Validators.maxLength(2000)]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .contest(this.d.statement.id, this.form.getRawValue().reason.trim())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (detail) => this.ref.close(detail),
        error: (err: unknown) => {
          const e = toApiError(err);
          if (staleError(e)) {
            this.ref.close({ reload: true, message: responseMessage(e) });
            return;
          }
          this.error.set(responseMessage(e));
        },
      });
  }
}

/**
 * Ficha do demonstrativo no portal do credor: valores do período, arquivos (com conferência do código de integridade
 * no download) e a resposta — aceitar (com o código do autenticador) ou contestar (com motivo).
 */
@Component({
  selector: 'app-creditor-statement-detail',
  imports: [RouterLink, Amount, AppDatePipe, EmptyState, Icon, Skeleton, StatusChip],
  template: `
    <a class="btn btn-link px-0" [routerLink]="['/credor', slug(), 'prestacao-de-contas']">
      <app-icon name="arrow-left" /> Prestação de contas
    </a>
    @if (detail.isLoading() && !current()) {
      <app-skeleton variant="card" />
    } @else if (detail.error() && !current()) {
      <section class="app-card">
        @if (notFound()) {
          <app-empty-state tone="error" icon="file-earmark-text" title="Demonstrativo não encontrado."
                           message="Confira o link recebido ou abra o demonstrativo pela prestação de contas.">
            <a class="btn btn-outline-secondary" [routerLink]="['/credor', slug(), 'prestacao-de-contas']">Voltar à prestação de contas</a>
          </app-empty-state>
        } @else {
          <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível abrir o demonstrativo."
                           message="Verifique sua conexão e tente de novo. Se continuar, avise a assessoria.">
            <button class="btn btn-outline-secondary" type="button" (click)="detail.reload()">Tentar novamente</button>
          </app-empty-state>
        }
      </section>
    }
    @if (current(); as d) {
      <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
        <h1 #pageTitle class="h4 mb-0" tabindex="-1">Demonstrativo nº {{ d.statement.sequenceNumber }}</h1>
        <app-status-chip domain="creditorStatement" [status]="d.statement.status" />
      </div>
      <p class="app-muted">
        Período de {{ d.statement.periodStart | appDate: 'date' }} a {{ d.statement.periodEnd | appDate: 'date' }} · enviado em
        {{ d.statement.sentAt | appDate }}
      </p>
      <div aria-live="polite">
        @if (notice(); as n) {
          <div class="alert d-flex align-items-start gap-2" [class.alert-success]="n.ok" [class.alert-warning]="!n.ok">
            <app-icon [name]="n.ok ? 'check-circle' : 'exclamation-triangle'" /><span>{{ n.text }}</span>
          </div>
        }
      </div>

      @switch (d.statement.status) {
        @case ('SENT') {
          <div class="alert alert-warning d-flex flex-wrap align-items-center gap-2" role="note" data-tour="creditor-statement-respond">
            <span class="flex-grow-1">Confira os valores e os arquivos e responda: aceite ou conteste.</span>
            @if (canRespond()) {
              <button class="btn btn-primary" type="button" (click)="acknowledge(d)">Aceitar</button>
              <button class="btn btn-outline-danger" type="button" (click)="contest(d)">Contestar</button>
            } @else {
              <span class="app-muted">Quem responde pelo credor é o perfil Financeiro.</span>
            }
          </div>
        }
        @case ('CONTESTED') {
          <div class="alert alert-info d-flex flex-wrap align-items-center gap-2" role="note">
            <span class="flex-grow-1">
              Contestado em {{ d.statement.respondedAt | appDate }}{{ d.respondedInPortal ? ' pelo seu time, no portal' : ' (registrado pela assessoria, a seu pedido)' }}{{ note(d.responseNote) }}.
              A assessoria corrige por ajuste no próximo demonstrativo; se já estiver de acordo, aceite.
            </span>
            @if (canRespond()) {
              <button class="btn btn-outline-primary" type="button" (click)="acknowledge(d)">Aceitar</button>
            }
          </div>
        }
        @case ('ACKNOWLEDGED') {
          <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
            <app-icon name="check-circle" />
            <span>
              Aceito em {{ d.statement.respondedAt | appDate }}{{ d.respondedInPortal ? ' pelo seu time, no portal' : ' (registrado pela assessoria, a seu pedido)' }}{{ note(d.responseNote) }}.
            </span>
          </div>
        }
      }

      <section class="app-card" aria-labelledby="csd-valores">
        <h2 class="app-card__title" id="csd-valores">Valores do período</h2>
        <dl class="app-definition-list">
          <dt>Recebido pela assessoria</dt>
          <dd><app-amount [value]="d.totals.receivedByAgency" /></dd>
          <dt>Recebido diretamente por você</dt>
          <dd><app-amount [value]="d.totals.receivedByCreditor" /></dd>
          <dt>Estornos</dt>
          <dd>− <app-amount [value]="abs(d.totals.reversed)" /></dd>
          <dt>Recuperado</dt>
          <dd><strong><app-amount [value]="d.totals.recovered" /></strong></dd>
          <dt>Honorários retidos pela assessoria</dt>
          <dd>− <app-amount [value]="d.totals.agencyHonorarium" /></dd>
          <dt>Comissão da assessoria</dt>
          <dd>− <app-amount [value]="d.totals.commission" /></dd>
          <dt>Tarifas fixas</dt>
          <dd>− <app-amount [value]="d.totals.fixedFees" /></dd>
          <dt>Despesas reembolsáveis</dt>
          <dd>− <app-amount [value]="d.totals.expenses" /></dd>
          <dt>Ajustes</dt>
          <dd><app-amount [value]="d.totals.adjustments" [signed]="true" /></dd>
          <dt>{{ direction(d.statement.direction) }}</dt>
          <dd><strong><app-amount [value]="abs(d.totals.netTransfer)" /></strong></dd>
          @if (d.statement.direction !== 'NONE') {
            <dt>Já pago</dt>
            <dd><app-amount [value]="d.transferred" /></dd>
            <dt>Falta pagar</dt>
            <dd><app-amount [value]="d.outstanding" /></dd>
          }
        </dl>
        <p class="app-muted mb-0">
          O líquido é o que a assessoria recebeu, menos estornos, honorários retidos, comissão, tarifas e despesas, mais ou
          menos os ajustes. Sobre o que você recebeu diretamente, você deve à assessoria os honorários, a comissão e as tarifas.
        </p>
      </section>

      <section class="app-card" aria-labelledby="csd-arquivos">
        <h2 class="app-card__title" id="csd-arquivos">Arquivos</h2>
        @for (f of d.files; track f.id) {
          <div class="csd-file">
            <div class="csd-file__name">
              <strong>{{ formats[f.format] }}</strong> · {{ f.fileName }}
              <div class="app-muted csd-small">Código de integridade: <span class="csd-hash">{{ hash(f.sha256) }}</span></div>
              @if (checked()[f.id] !== undefined) {
                <app-status-chip domain="creditorFileCheck" [status]="checked()[f.id] ? 'OK' : 'MISMATCH'" />
              }
            </div>
            <button class="btn btn-outline-secondary" type="button" (click)="download(d, f)" [disabled]="downloading() === f.id"
                    [attr.aria-label]="'Baixar ' + formats[f.format] + ' ' + f.fileName">
              <app-icon name="download" /> Baixar
            </button>
          </div>
        } @empty {
          <p class="app-muted mb-0">A assessoria não anexou arquivos a este demonstrativo.</p>
        }
        <div aria-live="polite">
          @if (downloadMessage(); as m) {
            <div class="alert mt-3 mb-0" [class.alert-success]="m.ok" [class.alert-danger]="!m.ok">{{ m.text }}</div>
          }
        </div>
      </section>
    }
  `,
  styles: `
    .csd-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 8px 0;
      border-bottom: var(--bs-border-width) solid var(--app-border);
    }
    .csd-file:last-of-type {
      border-bottom: 0;
    }
    .csd-file__name {
      min-width: 0;
    }
    .csd-small {
      font-size: var(--app-text-xs);
    }
    .csd-hash {
      font-family: var(--bs-font-monospace);
      word-break: break-all;
    }
  `,
})
export class CreditorStatementDetailPage {
  private readonly api = inject(CreditorStatementsApi);
  private readonly dialog = inject(DialogService);
  private readonly session = inject(CreditorSession);
  private readonly route = inject(ActivatedRoute);
  protected readonly slug = toSignal(this.route.parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });
  private readonly id = toSignal(this.route.paramMap.pipe(map((p) => p.get('id') ?? '')), { initialValue: '' });
  protected readonly detail = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  private readonly updated = signal<CreditorStatementDetail | null>(null);
  protected readonly current = computed(() => {
    const u = this.updated();
    return u && u.statement.id === this.id() ? u : (this.detail.value() ?? null);
  });
  protected readonly notFound = computed(() => toApiError(this.detail.error()).status === 404);
  protected readonly canRespond = computed(() => this.session.me()?.permissions.includes(CreditorPermission.STATEMENT_RESPOND) ?? false);
  protected readonly notice = signal<{ ok: boolean; text: string } | null>(null);
  /** Resultado da conferência do código de integridade no download, por arquivo. */
  protected readonly checked = signal<Record<string, boolean>>({});
  private readonly injector = inject(Injector);
  private readonly pageTitle = viewChild<ElementRef<HTMLElement>>('pageTitle');
  protected readonly downloading = signal<string | null>(null);
  protected readonly downloadMessage = signal<{ ok: boolean; text: string } | null>(null);
  protected readonly formats = FORMAT_LABELS;
  protected readonly hash = groupedHash;
  protected readonly direction = directionText;
  protected readonly abs = (value: number) => Math.abs(Number(value));
  /** Observação depois de ": ", sem ponto duplicado no fim. */
  protected readonly note = (text: string | null) => (text ? ': ' + text.trim().replace(/[.\s]+$/, '') : '');

  protected acknowledge(d: CreditorStatementDetail): void {
    this.dialog
      .open<CreditorAcknowledgeDialog, ResponseDialogData, ResponseResult>(CreditorAcknowledgeDialog, { data: { detail: d } })
      .afterClosed()
      .subscribe((saved) => this.applied(saved, 'Demonstrativo aceito. O aceite ficou registrado.'));
  }

  protected contest(d: CreditorStatementDetail): void {
    this.dialog
      .open<CreditorContestDialog, ResponseDialogData, ResponseResult>(CreditorContestDialog, { data: { detail: d } })
      .afterClosed()
      .subscribe((saved) => this.applied(saved, 'Contestação enviada à assessoria.'));
  }

  protected download(d: CreditorStatementDetail, f: CreditorStatementFile): void {
    this.downloading.set(f.id);
    this.downloadMessage.set(null);
    this.api
      .download(d.statement.id, f)
      .pipe(finalize(() => this.downloading.set(null)))
      .subscribe({
        next: ({ verified }) => {
          this.checked.update((c) => ({ ...c, [f.id]: verified }));
          this.downloadMessage.set(
            verified
              ? { ok: true, text: `${f.fileName} baixado. O código de integridade confere.` }
              : { ok: false, text: `O arquivo recebido não confere com o código de integridade e não foi salvo. Tente de novo; se continuar, avise a assessoria.` },
          );
        },
        error: (err: unknown) => this.downloadMessage.set({ ok: false, text: responseMessage(toApiError(err)) }),
      });
  }

  private applied(result: ResponseResult | undefined, message: string): void {
    if (!result) {
      return;
    }
    if ('reload' in result) {
      // a ficha aberta ficou velha (arquivos novos, outra pessoa respondeu): traz o estado atual do servidor
      this.updated.set(null);
      this.detail.reload();
      this.notice.set({ ok: false, text: result.message });
    } else {
      this.updated.set(result);
      this.notice.set({ ok: true, text: message });
    }
    // os botões de resposta somem com a mudança de situação: o foco vai para o título da ficha
    afterNextRender(() => this.pageTitle()?.nativeElement.focus(), { injector: this.injector });
  }
}
