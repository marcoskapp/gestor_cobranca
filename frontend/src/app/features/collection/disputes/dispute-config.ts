import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { Observable, finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { DisputeConfigApi, DisputesApi } from '../data/collection.api';
import { DEADLINE_SOURCE_LABELS, DISPUTE_OUTCOME_LABELS, DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { DeadlineRuleView, DisputeSettingsRequest, DisputeTemplateView, DisputeType } from '../data/collection.models';

/** Limites de `DisputeConfigController` (mesmos do servidor). */
export const DISPUTE_CONFIG_LIMITS = {
  internalDays: { min: 1, max: 90 },
  creditorRequestDays: { min: 1, max: 60 },
  reminderDaysBefore: { min: 0, max: 10 },
  evidenceLinkDays: { min: 1, max: 30 },
  responseDays: { min: 1, max: 90 },
} as const;

const range = (key: keyof typeof DISPUTE_CONFIG_LIMITS) => [Validators.required, Validators.min(DISPUTE_CONFIG_LIMITS[key].min), Validators.max(DISPUTE_CONFIG_LIMITS[key].max)];

/**
 * Configuração das contestações (gestão): prazos por tipo e por credor (com o prazo interno separado), distribuição
 * (manual ou automática ao menos carregado, com o grupo de analistas), redistribuição e modelos versionados do pedido
 * ao credor e da resposta ao devedor. Valem para contestações abertas (ou textos gerados) depois da alteração.
 */
@Component({
  selector: 'app-dispute-config',
  imports: [ReactiveFormsModule, NgbNavModule, EmptyState, EntityPicker, PageHeader, Skeleton, AppDatePipe, Icon, ...FORM_FIELD],
  template: `
    <app-page-header title="Configuração das contestações" subtitle="Prazos, distribuição entre os analistas e modelos de texto. Contestações já abertas mantêm os prazos calculados na abertura." />

    <ul ngbNav #tabs="ngbNav" class="nav-tabs mb-3" data-tour="dispute-config-tabs">
      <li ngbNavItem>
        <button ngbNavLink type="button">Prazos</button>
        <ng-template ngbNavContent>
          @if (settings.status() === 'error') {
            <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a configuração.">
              <button class="btn btn-outline-primary" type="button" (click)="settings.reload()">Tentar novamente</button>
            </app-empty-state>
          } @else if (!current()) {
            <app-skeleton variant="card" [rows]="4" />
          } @else {
            <form class="app-card" [formGroup]="form" (ngSubmit)="saveSettings()" novalidate data-tour="dispute-config-settings">
              <h2 class="app-card__title">Padrões da assessoria</h2>
              <p class="app-muted">
                Prazo de resposta ao devedor padrão: {{ current()!.responseDays }} dias (em Parâmetros da operação). O prazo ao devedor nunca é pausado enquanto se
                aguarda o credor.
              </p>
              <div class="app-form-grid gap-3">
                <app-field label="Prazo interno (dias)" hint="Meta da equipe para resolver; nunca passa do prazo de resposta (1 a 90).">
                  <input appInput type="number" formControlName="internalDays" min="1" max="90" required />
                </app-field>
                <app-field label="Prazo pedido ao credor (dias)" hint="Sugestão ao enviar um pedido ao credor (1 a 60).">
                  <input appInput type="number" formControlName="creditorRequestDays" min="1" max="60" required />
                </app-field>
                <app-field label="Lembrete ao credor (dias antes)" hint="0 = sem lembrete. Vencido o prazo, o credor recebe uma cobrança do retorno (0 a 10).">
                  <input appInput type="number" formControlName="reminderDaysBefore" min="0" max="10" required />
                </app-field>
                <app-field label="Validade do link das evidências (dias)" hint="As evidências vão por link de acesso restrito, nunca anexadas ao e-mail (1 a 30).">
                  <input appInput type="number" formControlName="evidenceLinkDays" min="1" max="30" required />
                </app-field>
                <app-field label="Distribuição de novas contestações">
                  <select appInput formControlName="distributionMode">
                    <option value="MANUAL">Manual (fila sem responsável)</option>
                    <option value="LEAST_LOADED">Automática ao analista menos carregado do grupo</option>
                  </select>
                </app-field>
              </div>
              <div class="app-form-actions">
                <button class="btn btn-primary" type="submit" [disabled]="busy() || form.pristine">Salvar padrões</button>
              </div>
            </form>
          }

          <section class="app-card" aria-labelledby="dc-rules" data-tour="dispute-config-rules">
            <h2 class="app-card__title" id="dc-rules">Prazos por tipo e por credor</h2>
            <p class="app-muted">Vale a regra mais específica: credor e tipo, depois credor, depois tipo, depois o padrão. Prazo informado na abertura prevalece.</p>
            <div class="table-responsive">
              <table class="table table-sm align-middle">
                <caption class="visually-hidden">Regras de prazo</caption>
                <thead>
                  <tr>
                    <th scope="col">Credor</th>
                    <th scope="col">Tipo</th>
                    <th scope="col" class="text-end">Resposta (dias)</th>
                    <th scope="col" class="text-end">Interno (dias)</th>
                    <th scope="col"><span class="visually-hidden">Ações</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of ruleRows(); track r.id) {
                    <tr>
                      <td>{{ r.creditorId ? creditors.name(r.creditorId) : 'Todos' }}</td>
                      <td>{{ r.type ? types[r.type] : 'Todos' }}</td>
                      <td class="text-end app-numeric">{{ r.responseDays }}</td>
                      <td class="text-end app-numeric">{{ r.internalDays }}</td>
                      <td class="text-end">
                        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="removeRule(r)" [attr.aria-label]="'Remover regra ' + sourceLabels[r.source]">
                          <app-icon name="trash3" />
                        </button>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="5" class="app-muted">Nenhuma regra: vale o padrão para todas as contestações.</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <form class="row g-3 align-items-start" [formGroup]="ruleForm" (ngSubmit)="addRule()" novalidate>
              <div class="col-md-4">
                <app-entity-picker [control]="ruleCreditor" [source]="creditorSource" label="Credor (vazio = todos)" [single]="true" />
              </div>
              <app-field class="col-md-3" label="Tipo">
                <select appInput formControlName="type">
                  <option value="">Todos</option>
                  @for (t of typeOptions; track t.value) {
                    <option [ngValue]="t.value">{{ t.label }}</option>
                  }
                </select>
              </app-field>
              <app-field class="col-6 col-md-2" label="Resposta (dias)">
                <input appInput type="number" formControlName="responseDays" min="1" max="90" required />
              </app-field>
              <app-field class="col-6 col-md-2" label="Interno (dias)">
                <input appInput type="number" formControlName="internalDays" min="1" max="90" required />
              </app-field>
              <div class="col-md-1 app-control-offset">
                <button class="btn btn-outline-primary" type="submit" [disabled]="busy()">Incluir</button>
              </div>
            </form>
          </section>
        </ng-template>
      </li>

      <li ngbNavItem>
        <button ngbNavLink type="button">Distribuição</button>
        <ng-template ngbNavContent>
          <section class="app-card" aria-labelledby="dc-pool" data-tour="dispute-config-pool">
            <h2 class="app-card__title" id="dc-pool">Grupo de analistas</h2>
            <p class="app-muted">
              Na distribuição automática, a nova contestação vai ao analista do grupo com menos contestações em aberto (empate: quem recebeu há mais tempo). Membro
              de um credor só recebe as daquele credor. Sem grupo, a contestação fica sem responsável.
            </p>
            <ul class="list-unstyled dc-list">
              @for (m of poolRows(); track m.id) {
                <li>
                  <span>{{ m.userName ?? 'Usuário indisponível' }}</span>
                  <span class="app-muted">{{ m.creditorId ? creditors.name(m.creditorId) : 'todos os credores' }} · {{ m.openDisputes }} em aberto</span>
                  @if (!m.eligible) {
                    <span class="badge rounded-pill bg-warning-subtle text-warning-emphasis">não recebe: inativo, sem permissão ou fora do escopo</span>
                  }
                  <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="removeMember(m.id)" [attr.aria-label]="'Remover ' + (m.userName ?? 'membro') + ' do grupo'">
                    <app-icon name="trash3" />
                  </button>
                </li>
              } @empty {
                <li class="app-muted">Nenhum analista no grupo.</li>
              }
            </ul>
            <div class="row g-3 align-items-start">
              <div class="col-md-5"><app-entity-picker [control]="memberUser" [source]="users" label="Analista" [single]="true" /></div>
              <div class="col-md-5"><app-entity-picker [control]="memberCreditor" [source]="creditorSource" label="Credor (vazio = todos)" [single]="true" /></div>
              <div class="col-md-2 app-control-offset">
                <button class="btn btn-outline-primary" type="button" (click)="addMember()" [disabled]="busy() || !memberUser.value.length">Incluir</button>
              </div>
            </div>
          </section>

          <section class="app-card" aria-labelledby="dc-redistribute" data-tour="dispute-config-redistribute">
            <h2 class="app-card__title" id="dc-redistribute">Redistribuir</h2>
            <p class="app-muted">Move todas as contestações em aberto de um analista (férias, desligamento). Sem destino, voltam à fila ou seguem a distribuição automática.</p>
            <div class="row g-3 align-items-start">
              <div class="col-md-4"><app-entity-picker [control]="fromUser" [source]="users" label="De" [single]="true" /></div>
              <div class="col-md-4"><app-entity-picker [control]="toUser" [source]="users" label="Para (opcional)" [single]="true" /></div>
              <app-field class="col-md-4" label="Motivo">
                <input appInput [formControl]="redistributeReason" maxlength="500" />
              </app-field>
            </div>
            <div class="app-form-actions">
              <button class="btn btn-outline-primary" type="button" (click)="redistribute()" [disabled]="busy() || !fromUser.value.length">Redistribuir</button>
            </div>
          </section>
        </ng-template>
      </li>

      <li ngbNavItem>
        <button ngbNavLink type="button">Modelos</button>
        <ng-template ngbNavContent>
          <div class="dc-templates" data-tour="dispute-config-templates">
            <nav class="app-card" aria-label="Modelos">
              <h2 class="app-card__title">Pedido ao credor (por tipo)</h2>
              <div class="list-group list-group-flush mb-3">
                @for (t of templatesOf('CREDITOR_REQUEST'); track t.key) {
                  <button type="button" class="list-group-item list-group-item-action" [class.active]="isSelected(t)" (click)="select(t)">
                    {{ keyLabel(t) }}{{ t.customized ? ' · v' + t.version : ' · padrão' }}
                  </button>
                }
              </div>
              <h2 class="app-card__title">Resposta ao devedor (por resultado)</h2>
              <div class="list-group list-group-flush">
                @for (t of templatesOf('DEBTOR_RESPONSE'); track t.key) {
                  <button type="button" class="list-group-item list-group-item-action" [class.active]="isSelected(t)" (click)="select(t)">
                    {{ keyLabel(t) }}{{ t.customized ? ' · v' + t.version : ' · padrão' }}
                  </button>
                }
              </div>
            </nav>
            @if (selected(); as t) {
              <form class="app-card" [formGroup]="templateForm" (ngSubmit)="saveTemplate(t)" novalidate>
                <h2 class="app-card__title">{{ (t.kind === 'CREDITOR_REQUEST' ? 'Pedido ao credor · ' : 'Resposta ao devedor · ') + keyLabel(t) }}</h2>
                <p class="app-muted">
                  Variáveis: @for (v of t.variables; track v) {<code class="me-2">{{ '{{' + v + '}}' }}</code>}
                  @if (t.kind === 'CREDITOR_REQUEST') {
                    <br />O pedido ao credor não leva o nome nem o documento do devedor: o credor localiza pelo protocolo e pelos títulos.
                  }
                </p>
                <app-field class="mb-3" label="Assunto">
                  <input appInput formControlName="subject" maxlength="200" required />
                </app-field>
                <app-field class="mb-3" label="Texto" [hintEnd]="templateForm.controls.body.value.length + ' / 8000'">
                  <textarea appInput formControlName="body" rows="14" maxlength="8000" required></textarea>
                </app-field>
                <div class="app-form-actions">
                  @if (t.customized) {
                    <button class="btn btn-link" type="button" (click)="restore(t)" [disabled]="busy()">Voltar ao padrão do sistema</button>
                  }
                  <button class="btn btn-primary" type="submit" [disabled]="busy() || templateForm.pristine">Salvar nova versão</button>
                </div>
                <h3 class="app-section-title">Versões</h3>
                <ol class="dc-list list-unstyled">
                  @for (h of historyRows(); track h.version) {
                    <li>
                      <span>v{{ h.version }}</span>
                      <span class="app-muted">{{ h.createdAt | appDate }}</span>
                      <button class="btn btn-sm btn-link" type="button" (click)="load(h)">Carregar no editor</button>
                    </li>
                  } @empty {
                    <li class="app-muted">Sem versões próprias: vale o texto padrão do sistema.</li>
                  }
                </ol>
              </form>
            } @else {
              <app-empty-state icon="file-earmark-text" title="Escolha um modelo" message="Cada nova gravação cria uma versão; os pedidos e respostas guardam a versão usada." />
            }
          </div>
        </ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="tabs"></div>
  `,
  styles: `
    .dc-list {
      display: grid;
      gap: 4px;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 12px;
      }
    }
    .dc-templates {
      display: grid;
      grid-template-columns: minmax(220px, 1fr) minmax(0, 2.5fr);
      gap: var(--app-gap);
      align-items: start;
      @media (max-width: 991.98px) {
        grid-template-columns: minmax(0, 1fr);
      }
      > .app-card {
        margin-top: 0;
      }
    }
  `,
})
export class DisputeConfigPage {
  private readonly api = inject(DisputeConfigApi);
  private readonly disputes = inject(DisputesApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly users = inject(UserLookup);

  protected readonly types = DISPUTE_TYPE_LABELS;
  protected readonly sourceLabels = DEADLINE_SOURCE_LABELS;
  protected readonly typeOptions = Object.entries(DISPUTE_TYPE_LABELS).map(([value, label]) => ({ value: value as DisputeType, label }));
  protected readonly busy = signal(false);

  protected readonly settings = rxResource({ stream: () => this.api.settings() });
  protected readonly current = valueOr(this.settings, undefined);
  protected readonly rules = rxResource({ stream: () => this.api.rules() });
  protected readonly ruleRows = valueOr(this.rules, []);
  protected readonly pool = rxResource({ stream: () => this.api.pool() });
  protected readonly poolRows = valueOr(this.pool, []);
  protected readonly templates = rxResource({ stream: () => this.api.templates() });
  private readonly templateRows = valueOr(this.templates, []);
  protected readonly selected = signal<DisputeTemplateView | null>(null);
  protected readonly history = rxResource({
    params: () => {
      const t = this.selected();
      return t ? { kind: t.kind, key: t.key } : undefined;
    },
    stream: ({ params }) => this.api.history(params.kind, params.key),
  });
  protected readonly historyRows = valueOr(this.history, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [...this.ruleRows().map((r) => r.creditorId), ...this.poolRows().map((m) => m.creditorId)]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    distributionMode: this.fb.control<DisputeSettingsRequest['distributionMode']>('MANUAL'),
    internalDays: [7, range('internalDays')],
    creditorRequestDays: [5, range('creditorRequestDays')],
    reminderDaysBefore: [1, range('reminderDaysBefore')],
    evidenceLinkDays: [7, range('evidenceLinkDays')],
  });
  protected readonly ruleCreditor = new FormControl<string[]>([], { nonNullable: true });
  protected readonly ruleForm = this.fb.group({
    type: this.fb.control<DisputeType | ''>(''),
    responseDays: [10, range('responseDays')],
    internalDays: [5, range('internalDays')],
  });
  protected readonly memberUser = new FormControl<string[]>([], { nonNullable: true });
  protected readonly memberCreditor = new FormControl<string[]>([], { nonNullable: true });
  protected readonly fromUser = new FormControl<string[]>([], { nonNullable: true });
  protected readonly toUser = new FormControl<string[]>([], { nonNullable: true });
  protected readonly redistributeReason = new FormControl('', { nonNullable: true, validators: [AppValidators.notBlank] });
  protected readonly templateForm = this.fb.group({
    subject: ['', [AppValidators.notBlank, Validators.maxLength(200)]],
    body: ['', [AppValidators.notBlank, Validators.maxLength(8000)]],
  });

  constructor() {
    effect(() => {
      const s = this.current();
      if (s) {
        this.form.reset({
          distributionMode: s.distributionMode,
          internalDays: s.internalDays,
          creditorRequestDays: s.creditorRequestDays,
          reminderDaysBefore: s.reminderDaysBefore,
          evidenceLinkDays: s.evidenceLinkDays,
        });
      }
    });
  }

  protected keyLabel(t: DisputeTemplateView): string {
    const labels: Record<string, string> = t.kind === 'CREDITOR_REQUEST' ? DISPUTE_TYPE_LABELS : DISPUTE_OUTCOME_LABELS;
    return labels[t.key] ?? t.key;
  }

  protected templatesOf(kind: DisputeTemplateView['kind']): DisputeTemplateView[] {
    return this.templateRows().filter((t) => t.kind === kind);
  }

  protected isSelected(t: DisputeTemplateView): boolean {
    const s = this.selected();
    return !!s && s.kind === t.kind && s.key === t.key;
  }

  protected select(t: DisputeTemplateView): void {
    this.selected.set(t);
    this.load(t);
  }

  protected load(t: Pick<DisputeTemplateView, 'subject' | 'body'>): void {
    this.templateForm.reset({ subject: t.subject, body: t.body });
    if (t !== this.selected()) {
      this.templateForm.markAsDirty();
    }
  }

  protected saveSettings(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.run(this.api.updateSettings(this.form.getRawValue()), 'Padrões das contestações salvos.', () => this.settings.reload());
  }

  protected addRule(): void {
    if (this.ruleForm.invalid) {
      this.ruleForm.markAllAsTouched();
      return;
    }
    const v = this.ruleForm.getRawValue();
    const creditorId = this.ruleCreditor.value[0] ?? null;
    if (!creditorId && !v.type) {
      this.notifications.error('Escolha o credor, o tipo ou ambos. O padrão geral fica em "Padrões da assessoria".');
      return;
    }
    this.run(this.api.createRule({ creditorId, type: v.type || null, responseDays: v.responseDays, internalDays: v.internalDays }), 'Regra de prazo incluída.', () => {
      this.ruleCreditor.setValue([]);
      this.rules.reload();
    });
  }

  protected removeRule(r: DeadlineRuleView): void {
    this.confirm
      .confirm({
        title: 'Remover regra de prazo',
        message: 'Novas contestações passam a usar a próxima regra aplicável. As já abertas mantêm o prazo.',
        confirmLabel: 'Remover',
        destructive: true,
        details: [
          { label: 'Credor', value: r.creditorId ? this.creditors.name(r.creditorId) : 'Todos' },
          { label: 'Tipo', value: r.type ? DISPUTE_TYPE_LABELS[r.type] : 'Todos' },
        ],
      })
      .subscribe(() => this.run(this.api.removeRule(r.id), 'Regra removida.', () => this.rules.reload()));
  }

  protected addMember(): void {
    const userId = this.memberUser.value[0];
    if (!userId) {
      return;
    }
    this.run(this.api.addPoolMember(this.memberCreditor.value[0] ?? null, userId), 'Analista incluído no grupo.', () => {
      this.memberUser.setValue([]);
      this.memberCreditor.setValue([]);
      this.pool.reload();
    });
  }

  protected removeMember(id: string): void {
    this.run(this.api.removePoolMember(id), 'Analista removido do grupo.', () => this.pool.reload());
  }

  protected redistribute(): void {
    const from = this.fromUser.value[0];
    if (!from) {
      return;
    }
    if (this.redistributeReason.invalid) {
      this.redistributeReason.markAsTouched();
      this.notifications.error('Informe o motivo da redistribuição.');
      return;
    }
    const to = this.toUser.value[0] ?? null;
    this.confirm
      .confirm({
        title: 'Redistribuir contestações',
        message: to ? 'Todas as contestações em aberto passam ao novo responsável.' : 'As contestações em aberto voltam à fila (ou seguem a distribuição automática).',
        confirmLabel: 'Redistribuir',
      })
      .subscribe(() => {
        this.busy.set(true);
        this.disputes
          .redistribute(from, to, this.redistributeReason.value.trim())
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe((r) => {
            this.notifications.success(`${r.moved} contestação(ões) redistribuída(s)${r.unassigned ? `, ${r.unassigned} sem responsável` : ''}.`);
            this.fromUser.setValue([]);
            this.toUser.setValue([]);
            this.redistributeReason.reset();
            this.pool.reload();
          });
      });
  }

  protected saveTemplate(t: DisputeTemplateView): void {
    if (this.templateForm.invalid) {
      this.templateForm.markAllAsTouched();
      return;
    }
    const v = this.templateForm.getRawValue();
    this.run(this.api.saveTemplate(t.kind, t.key, v.subject.trim(), v.body.trim()), 'Nova versão do modelo salva.', (saved) => this.afterTemplate(saved));
  }

  protected restore(t: DisputeTemplateView): void {
    this.confirm
      .confirm({ title: 'Voltar ao padrão do sistema', message: 'O texto padrão passa a valer como uma nova versão. As versões anteriores ficam no histórico.', confirmLabel: 'Voltar ao padrão' })
      .subscribe(() => this.run(this.api.restoreDefault(t.kind, t.key), 'Modelo padrão restaurado.', (saved) => this.afterTemplate(saved)));
  }

  private afterTemplate(saved: DisputeTemplateView): void {
    this.selected.set(saved);
    this.templateForm.reset({ subject: saved.subject, body: saved.body });
    this.templates.reload();
  }

  private run<T>(call: Observable<T>, message: string, after: (value: T) => void): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe((value) => {
      this.notifications.success(message);
      after(value);
    });
  }
}

