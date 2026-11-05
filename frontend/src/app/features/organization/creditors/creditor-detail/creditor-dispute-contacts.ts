import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../../shared/components/icon/icon';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppValidators } from '../../../../shared/validation/validators';
import { CreditorDisputeContactsApi } from '../../data/organization.api';
import { PortfolioLookup, PortfolioNames } from '../../data/organization.lookups';
import { DisputeContactView } from '../../data/organization.models';

/**
 * Contatos do credor para os pedidos de contestação (canal formal). O contato principal recebe o pedido; os de cópia
 * vão em cópia. Contatos de uma carteira substituem os do credor nas contestações daquela carteira quando ela tem um
 * principal ativo.
 */
@Component({
  selector: 'app-creditor-dispute-contacts',
  imports: [ReactiveFormsModule, EntityPicker, Icon, ...FORM_FIELD],
  // o próprio elemento é o cartão: mantém o espaçamento entre cartões irmãos da ficha (.app-card + .app-card)
  host: { class: 'app-card d-block', role: 'region', 'aria-labelledby': 'contatos-contestacao', 'data-tour': 'creditor-dispute-contacts' },
  template: `
      <h2 class="app-card__title" id="contatos-contestacao">Contatos para contestações</h2>
      <p class="app-muted">Recebem os pedidos de análise das contestações, os lembretes e a cobrança do retorno vencido. Respostas vão ao analista responsável.</p>
      <ul class="list-unstyled cc-list">
        @for (c of rows(); track c.id) {
          <li [class.app-muted]="!c.active">
            <span>
              <strong>{{ c.name }}</strong> · {{ c.email }}{{ c.phone ? ' · ' + c.phone : '' }}
            </span>
            <span class="badge rounded-pill" [class]="c.role === 'PRIMARY' ? 'bg-primary-subtle text-primary-emphasis' : 'bg-secondary-subtle text-secondary-emphasis'">
              {{ c.role === 'PRIMARY' ? 'Principal' : 'Cópia' }}
            </span>
            <span class="app-muted">{{ c.portfolioId ? 'carteira ' + portfolios.name(c.portfolioId) : 'todas as carteiras' }}{{ c.active ? '' : ' · inativo' }}</span>
            @if (canManage() && c.active) {
              <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(c)" [attr.aria-label]="'Editar contato ' + c.name"><app-icon name="pencil" /></button>
              <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="deactivate(c)" [attr.aria-label]="'Inativar contato ' + c.name"><app-icon name="x-circle" /></button>
            }
          </li>
        } @empty {
          <li class="app-muted">Nenhum contato. Sem contato principal, a assessoria não consegue enviar pedidos de contestação a este credor.</li>
        }
      </ul>
      @if (canManage()) {
        <form class="row g-3 align-items-start" [formGroup]="form" (ngSubmit)="save()" novalidate>
          <app-field class="col-md-3" label="Nome ou área" error="Informe o nome.">
            <input appInput formControlName="name" maxlength="150" required />
          </app-field>
          <app-field class="col-md-3" label="E-mail" error="Informe um e-mail válido.">
            <input appInput type="email" formControlName="email" maxlength="200" required />
          </app-field>
          <app-field class="col-md-2" label="Telefone (opcional)">
            <input appInput formControlName="phone" maxlength="30" />
          </app-field>
          <app-field class="col-md-2" label="Papel">
            <select appInput formControlName="role">
              <option value="PRIMARY">Principal</option>
              <option value="COPY">Cópia</option>
            </select>
          </app-field>
          <div class="col-md-2">
            <app-entity-picker [control]="portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
          </div>
          <div class="col-12 d-flex gap-2 justify-content-end">
            @if (editing()) {
              <button class="btn btn-link" type="button" (click)="cancel()">Cancelar</button>
            }
            <button class="btn btn-outline-primary" type="submit" [disabled]="busy()">{{ editing() ? 'Salvar contato' : 'Incluir contato' }}</button>
          </div>
        </form>
      }
  `,
  styles: `
    .cc-list {
      display: grid;
      gap: 4px;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 8px;
      }
    }
  `,
})
export class CreditorDisputeContacts {
  readonly creditorId = input.required<string>();

  private readonly api = inject(CreditorDisputeContactsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.creditorId());

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CREDITOR_MANAGE));
  protected readonly contacts = rxResource({ params: () => this.creditorId(), stream: ({ params }) => this.api.list(params) });
  protected readonly rows = valueOr(this.contacts, []);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((c) => c.portfolioId));
  protected readonly editing = signal<DisputeContactView | null>(null);
  protected readonly busy = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    name: ['', [AppValidators.notBlank, Validators.maxLength(150)]],
    email: ['', [AppValidators.notBlank, Validators.email, Validators.maxLength(200)]],
    phone: ['', [Validators.maxLength(30)]],
    role: this.fb.control<'PRIMARY' | 'COPY'>('PRIMARY'),
  });
  protected readonly portfolio = new FormControl<string[]>([], { nonNullable: true });

  protected edit(c: DisputeContactView): void {
    this.editing.set(c);
    this.form.reset({ name: c.name, email: c.email, phone: c.phone ?? '', role: c.role });
    this.portfolio.setValue(c.portfolioId ? [c.portfolioId] : []);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.form.reset({ name: '', email: '', phone: '', role: 'PRIMARY' });
    this.portfolio.setValue([]);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request = { portfolioId: this.portfolio.value[0] ?? null, name: v.name.trim(), email: v.email.trim(), phone: v.phone.trim() || null, role: v.role };
    const current = this.editing();
    this.busy.set(true);
    (current ? this.api.update(this.creditorId(), current.id, request) : this.api.create(this.creditorId(), request))
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success(current ? 'Contato atualizado.' : 'Contato incluído.');
        this.cancel();
        this.contacts.reload();
      });
  }

  protected deactivate(c: DisputeContactView): void {
    this.confirm
      .confirm({
        title: 'Inativar contato de contestação',
        message: 'O contato deixa de receber os pedidos, lembretes e cobranças de retorno. Pedidos já enviados não mudam.',
        confirmLabel: 'Inativar',
        details: [{ label: 'Contato', value: `${c.name} (${c.email})` }],
      })
      .subscribe(() =>
        this.api.deactivate(this.creditorId(), c.id).subscribe(() => {
          this.notifications.success('Contato inativado.');
          this.contacts.reload();
        }),
      );
  }
}
