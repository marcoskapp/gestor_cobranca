import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { IntegrationTokensApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS, parseIpList } from '../data/public-api.labels';
import { IssuedToken, TokenView } from '../data/public-api.models';
import { SecretReveal } from './secret-reveal';

export interface TokenDialogData {
  /** Edição de nome, descrição, limite e IPs; ausente = novo token. */
  token?: TokenView;
}

/** Permissões que cada escopo concede ao token (espelho de `TokenScope`): só se concede o que o próprio perfil tem. */
export const SCOPE_PERMISSIONS: Record<string, readonly string[]> = {
  'portfolios:read': ['CREDITOR_VIEW', 'PORTFOLIO_VIEW'],
  'portfolios:write': ['CREDITOR_VIEW', 'PORTFOLIO_VIEW', 'PORTFOLIO_MANAGE'],
  'imports:read': ['IMPORT_VIEW'],
  'imports:write': ['IMPORT_VIEW', 'IMPORT_EXECUTE'],
  'titles:read': ['DEBTOR_VIEW'],
  'titles:write': ['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE'],
  'debtors:read': ['DEBTOR_VIEW'],
  'debtors:write': ['DEBTOR_VIEW', 'DEBTOR_MANAGE'],
  'collection:read': ['CASE_VIEW'],
  'agreements:read': ['AGREEMENT_VIEW'],
  'payments:read': ['PAYMENT_VIEW'],
  'payments:write': ['PAYMENT_VIEW', 'PAYMENT_REGISTER_EXTERNAL'],
  'disputes:read': ['CASE_VIEW'],
  'disputes:write': ['CASE_VIEW'],
};

/**
 * Criação (credor, nome, escopos, limite, IPs, expiração) e edição de token. Na criação, o segredo aparece uma única
 * vez no próprio diálogo, com cópia; ao fechar, ele é descartado.
 */
@Component({
  selector: 'app-token-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker, SecretReveal],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>{{ issued() ? 'Token criado' : editing ? 'Editar token' : 'Novo token de integração' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" (click)="close()"></button>
    </div>
    @if (issued(); as result) {
      <div class="modal-body">
        <p>Token <strong>{{ result.details.name }}</strong> (<span class="app-mono">{{ result.details.prefix }}</span>) criado com {{ result.details.scopes.length }} escopo(s).</p>
        <app-secret-reveal [value]="result.token" />
      </div>
      <div class="modal-footer">
        <button class="btn btn-primary" type="button" (click)="close()">Já copiei o token</button>
      </div>
    } @else {
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
        <div class="modal-body">
          @for (e of errors(); track e) {
            <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
          }
          @if (!editing) {
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="O token só enxerga os dados deste credor." />
          }
          <div class="app-form-grid">
            <app-field label="Nome" hint="Ex.: ERP do Banco A — produção">
              <input appInput formControlName="name" maxlength="100" required />
            </app-field>
            <app-field label="Limite por minuto" hint="Requisições por minuto (1 a 6.000).">
              <input appInput type="number" formControlName="rateLimit" min="1" max="6000" required />
            </app-field>
            @if (!editing) {
              <app-field label="Expira em (opcional)" hint="Sem data = não expira; revogue quando não for mais usado.">
                <input appInput type="date" formControlName="expiresOn" />
              </app-field>
            }
          </div>
          <app-field class="mb-3" label="Descrição (opcional)">
            <textarea appInput formControlName="description" rows="2" maxlength="500"></textarea>
          </app-field>
          <app-field class="mb-3" label="IPs permitidos (opcional)" hint="Um IP ou faixa CIDR por linha (ex.: 200.10.0.0/24). Vazio = qualquer origem.">
            <textarea appInput formControlName="ips" rows="2" spellcheck="false" class="app-mono"></textarea>
          </app-field>
          @if (!editing) {
            <fieldset data-tour="token-scopes">
              <legend class="form-label">Escopos</legend>
              <p class="form-text mt-0">Conceda só o necessário. Escopos com permissões que seu perfil não tem ficam bloqueados.</p>
              <div class="td-scopes">
                @for (s of scopes(); track s.code) {
                  <div class="form-check">
                    <input class="form-check-input" type="checkbox" [id]="'td-' + s.code" [checked]="selected().has(s.code)" [disabled]="!grantable(s.code)" (change)="toggle(s.code)" />
                    <label class="form-check-label" [for]="'td-' + s.code">
                      <span class="app-mono">{{ s.code }}</span> — {{ s.description }}
                      @if (!grantable(s.code)) {
                        <span class="app-muted">(fora do seu perfil)</span>
                      }
                    </label>
                  </div>
                }
              </div>
            </fieldset>
          }
        </div>
        <div class="modal-footer">
          <button class="btn btn-link" type="button" (click)="close()">Cancelar</button>
          <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ editing ? 'Salvar' : 'Criar token' }}</button>
        </div>
      </form>
    }
  `,
  styles: `
    .td-scopes {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
      gap: 4px 16px;
    }
  `,
})
export class TokenDialog {
  protected readonly data = inject<TokenDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TokenView | null>>(DialogRef);
  private readonly api = inject(IntegrationTokensApi);
  private readonly auth = inject(AuthService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly editing = !!this.data.token;
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>(this.data.token ? [this.data.token.creditorId] : []),
    name: [this.data.token?.name ?? '', [Validators.required, Validators.maxLength(100)]],
    description: [this.data.token?.description ?? ''],
    rateLimit: [this.data.token?.rateLimitPerMinute ?? 600, [Validators.required, Validators.min(1), Validators.max(6000)]],
    ips: [(this.data.token?.allowedIps ?? []).join('\n')],
    expiresOn: this.fb.control<string | null>(null),
  });
  private readonly scopesResource = rxResource({ stream: () => this.api.scopes() });
  protected readonly scopes = valueOr(this.scopesResource, []);
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly issued = signal<IssuedToken | null>(null);
  protected grantable(code: string): boolean {
    return (SCOPE_PERMISSIONS[code] ?? []).every((p) => this.auth.hasPermission(p));
  }

  protected toggle(code: string): void {
    const next = new Set(this.selected());
    if (next.has(code)) {
      next.delete(code);
    } else {
      next.add(code);
    }
    this.selected.set(next);
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.form.markAllAsTouched();
    const v = this.form.getRawValue();
    const problems: string[] = [];
    if (this.form.invalid) {
      problems.push('Revise os campos destacados.');
    }
    if (!this.editing && !v.creditor.length) {
      problems.push('Escolha o credor do token.');
    }
    if (!this.editing && !this.selected().size) {
      problems.push('Escolha ao menos um escopo.');
    }
    this.errors.set(problems);
    if (problems.length) {
      return;
    }
    this.saving.set(true);
    const ips = parseIpList(v.ips);
    if (this.editing) {
      this.api
        .update(this.data.token!.id, { name: v.name.trim(), description: v.description || null, rateLimitPerMinute: Number(v.rateLimit), allowedIps: ips })
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({ next: (t) => this.ref.close(t), error: (e: unknown) => this.fail(e) });
      return;
    }
    this.api
      .create({
        creditorId: v.creditor[0],
        name: v.name.trim(),
        description: v.description || null,
        scopes: [...this.selected()],
        rateLimitPerMinute: Number(v.rateLimit),
        allowedIps: ips,
        // fim do dia escolhido no horário de Brasília
        expiresAt: v.expiresOn ? `${v.expiresOn}T23:59:59-03:00` : null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({ next: (result) => this.issued.set(result), error: (e: unknown) => this.fail(e) });
  }

  protected close(): void {
    const result = this.issued();
    // o segredo é descartado ao fechar: só os dados públicos voltam para a lista
    this.issued.set(null);
    this.ref.close(result ? result.details : null);
  }

  private fail(error: unknown): void {
    const e = toApiError(error);
    this.errors.set([(e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e)]);
  }
}
