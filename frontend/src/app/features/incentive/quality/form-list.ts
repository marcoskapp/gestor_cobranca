import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { QualityApi } from '../data/incentive.api';
import { QualityForm } from '../data/incentive.models';
import { parseDecimal } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { QualityTabs } from './quality-tabs';

/** Novo formulário de monitoria: critérios com código, peso e criticidade (falha crítica zera a nota). */
@Component({
  selector: 'app-incentive-quality-form-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Novo formulário de monitoria</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">Cada critério recebe nota de 0 a 10; a nota da avaliação é a média ponderada pelos pesos. Formulários não são editados depois de criados: desative e crie outro.</p>
        <div class="app-form-grid mb-2">
          <app-field class="app-span-all" label="Nome">
            <input appInput ngbAutofocus formControlName="name" maxlength="120" />
          </app-field>
          <app-field class="app-span-all" label="Descrição (opcional)">
            <textarea appInput formControlName="description" rows="2" maxlength="1000"></textarea>
          </app-field>
        </div>
        <fieldset formArrayName="criteria">
          <legend class="form-label">Critérios</legend>
          @for (c of criteria.controls; track c; let i = $index) {
            <div class="row g-2 align-items-end mb-2" [formGroupName]="i">
              <app-field class="col-sm-3" [label]="'Código ' + (i + 1)" error="A–Z, 0–9 e _ (2 a 40).">
                <input appInput class="font-monospace" formControlName="code" maxlength="40" (input)="upper(i)" />
              </app-field>
              <app-field class="col-sm-5" [label]="'Descrição ' + (i + 1)">
                <input appInput formControlName="description" maxlength="300" />
              </app-field>
              <app-field class="col-sm-2" [label]="'Peso ' + (i + 1)" error="De 0 a 100.">
                <input appInput formControlName="weight" inputmode="decimal" />
              </app-field>
              <div class="col-sm-2 d-flex align-items-center gap-2 pb-3">
                <div class="form-check mb-0">
                  <input class="form-check-input" type="checkbox" [id]="'qf-critical-' + i" formControlName="critical" />
                  <label class="form-check-label" [for]="'qf-critical-' + i">Crítico</label>
                </div>
                <button type="button" class="btn btn-sm btn-outline-danger" (click)="remove(i)" [disabled]="criteria.length === 1" [attr.aria-label]="'Remover o critério ' + (i + 1)"><app-icon name="trash3" /></button>
              </div>
            </div>
          }
        </fieldset>
        <button type="button" class="btn btn-sm btn-outline-primary" (click)="add()" [disabled]="criteria.length >= 50"><app-icon name="plus-lg" /> Critério</button>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Criar formulário</button>
      </div>
    </form>
  `,
})
export class QualityFormDialog {
  private readonly ref = inject<DialogRef<QualityForm>>(DialogRef);
  private readonly api = inject(QualityApi);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly criteria: FormArray = this.fb.array([this.criterion()]);
  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(1000)]],
    criteria: this.criteria,
  });

  private criterion() {
    return this.fb.group({
      code: ['', [Validators.required, Validators.pattern(/^[A-Z0-9_]{2,40}$/)]],
      description: ['', [Validators.required, Validators.maxLength(300)]],
      weight: ['1', [(c: { value: unknown }) => { const n = parseDecimal(c.value as string); return n !== null && n > 0 && n <= 100 ? null : { range: true }; }]],
      critical: [false],
    });
  }

  protected add(): void {
    this.criteria.push(this.criterion());
  }

  protected remove(i: number): void {
    this.criteria.removeAt(i);
  }

  protected upper(i: number): void {
    const c = this.criteria.at(i).get('code')!;
    c.setValue(String(c.value).toUpperCase().replace(/[^A-Z0-9_]/g, '_'), { emitEvent: false });
  }

  protected save(): void {
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue() as { name: string; description: string; criteria: { code: string; description: string; weight: string; critical: boolean }[] };
    this.sending.set(true);
    this.error.set(null);
    this.api
      .createForm({
        name: v.name.trim(),
        description: v.description.trim() || null,
        criteria: v.criteria.map((c) => ({ code: c.code, description: c.description.trim(), weight: parseDecimal(c.weight)!, critical: c.critical })),
      })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (f) => this.ref.close(f), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

/** Formulários de monitoria de qualidade (critérios ponderados), usados nas avaliações. */
@Component({
  selector: 'app-incentive-form-list',
  imports: [EmptyState, Icon, PageHeader, QualityTabs, Skeleton, StatusChip],
  template: `
    <app-page-header title="Formulários de monitoria" subtitle="Critérios e pesos das avaliações de qualidade de atendimento (nota de 0 a 10).">
      @if (canEvaluate()) {
        <button class="btn btn-primary" type="button" (click)="create()" data-tour="quality-form-new">
          <app-icon name="plus-lg" />
          Novo formulário
        </button>
      }
    </app-page-header>
    <app-quality-tabs />
    @if (error()) {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
    }
    @if (list.isLoading() && !forms().length) {
      <div aria-busy="true" aria-label="Carregando formulários"><app-skeleton variant="card" [rows]="4" /></div>
    } @else if (list.status() === 'error') {
      <app-empty-state icon="clipboard-check" tone="error" title="Não foi possível carregar os formulários.">
        <button class="btn btn-outline-primary" type="button" (click)="list.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!forms().length) {
      <app-empty-state icon="clipboard-check" title="Nenhum formulário de monitoria." message="Crie um formulário com os critérios de qualidade do atendimento para registrar avaliações." />
    } @else {
      @for (f of forms(); track f.id) {
        <section class="app-card" [attr.aria-labelledby]="'qf-' + f.id">
          <div class="in-head">
            <h2 class="app-card__title" [id]="'qf-' + f.id">{{ f.name }}</h2>
            <div class="d-flex align-items-center gap-2">
              <app-status-chip domain="active" [status]="f.active" />
              @if (canEvaluate() && f.active) {
                <button type="button" class="btn btn-sm btn-outline-danger" (click)="deactivate(f)" [disabled]="busy()">Desativar</button>
              }
            </div>
          </div>
          @if (f.description) {
            <p class="app-muted">{{ f.description }}</p>
          }
          <div class="in-scroll">
            <table class="in-table">
              <caption class="visually-hidden">Critérios de {{ f.name }}</caption>
              <thead>
                <tr>
                  <th scope="col">Código</th>
                  <th scope="col">Critério</th>
                  <th scope="col" class="in-end">Peso</th>
                  <th scope="col">Crítico</th>
                </tr>
              </thead>
              <tbody>
                @for (c of f.criteria; track c.code) {
                  <tr>
                    <td class="font-monospace">{{ c.code }}</td>
                    <td>{{ c.description }}</td>
                    <td class="in-end">{{ c.weight }}</td>
                    <td>{{ c.critical ? 'Sim — nota 0 zera a avaliação' : 'Não' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    }
  `,
  styleUrl: '../incentive.scss',
})
export class FormList {
  private readonly api = inject(QualityApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly canEvaluate = computed(() => this.auth.hasPermission(Permission.QUALITY_EVALUATE));
  protected readonly list = rxResource({ stream: () => this.api.forms() });
  private readonly raw = valueOr(this.list, []);
  protected readonly forms = computed(() => [...this.raw()].sort((a, b) => Number(b.active) - Number(a.active) || a.name.localeCompare(b.name)));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected create(): void {
    this.dialog
      .open<QualityFormDialog, void, QualityForm>(QualityFormDialog, { size: 'lg' })
      .afterClosed()
      .subscribe((f) => {
        if (f) {
          this.notifications.success(`Formulário ${f.name} criado.`);
          this.list.reload();
        }
      });
  }

  protected deactivate(f: QualityForm): void {
    this.confirm
      .confirm({
        title: 'Desativar formulário',
        message: 'O formulário deixa de aceitar novas avaliações. As avaliações já registradas continuam valendo.',
        confirmLabel: 'Desativar',
        destructive: true,
        details: [{ label: 'Formulário', value: f.name }],
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .deactivateForm(f.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Formulário desativado.');
              this.list.reload();
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }
}
