import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { FormDialogService, options } from '../../legal/components/form-dialog';
import { LegalMattersApi } from '../../legal/data/legal.api';
import { MatterView } from '../../legal/data/legal.models';
import { cnjValid } from '../../legal/data/legal.rules';
import { INSOLVENCY_KIND_LABELS, INSOLVENCY_KIND_PHRASES, InsolvencyApi, InsolvencyKind, ProceedingView } from '../data/insolvency';

/**
 * Recuperação judicial, extrajudicial ou falência da empresa devedora (cobrança B2B §7) na ficha do devedor e na ficha
 * do caso: aviso "Empresa em recuperação judicial desde …" com o processo, a suspensão da cobrança e o atalho para o
 * caso jurídico da empresa. Na ficha do devedor (`manage`), quem tem `DEBTOR_MANAGE` registra, convola em falência e
 * encerra. Só para empresa: com pessoa física nada é consultado nem exibido; sem permissão de consulta, nada aparece.
 *
 * ```html
 * <app-insolvency-notice [debtorId]="d.id" [personType]="d.personType" [manage]="true" />
 * ```
 */
@Component({
  selector: 'app-insolvency-notice',
  imports: [Icon, RouterLink, NgbDropdownModule, AppDatePipe],
  template: `
    @if (state(); as s) {
      @if (s.active; as p) {
        <div class="alert alert-warning d-flex gap-2 align-items-start in-alert" role="note" data-tour="insolvency-notice">
          <app-icon name="bank" />
          <div class="flex-grow-1">
            <strong>{{ phrase(p.kind) }} desde {{ p.since | appDate: 'date' }}.</strong>
            Cobrança suspensa em todos os credores, para a matriz e as filiais: sem régua, discador nem acordo novo; não faça contato de cobrança. Acordos em vigor continuam; a decisão sobre eles é do jurídico.
            <div class="small mt-1">
              Processo <span class="app-mono">{{ p.caseNumber }}</span>@if (p.court) { · {{ p.court }} }
              @if (p.convertedFrom) { · {{ kindLabels[p.convertedFrom] }} convolada em falência }
            </div>
            <div class="d-flex flex-wrap gap-2 align-items-center mt-2">
              @if (canSeeLegal()) {
                @if (matters().length === 1) {
                  <a class="btn btn-sm btn-outline-secondary" [routerLink]="['/juridico/casos', matters()[0].id]">
                    <app-icon name="bank" /> Abrir caso jurídico
                  </a>
                } @else if (matters().length > 1) {
                  <div ngbDropdown class="d-inline-block">
                    <button class="btn btn-sm btn-outline-secondary" type="button" ngbDropdownToggle>
                      <app-icon name="bank" /> Casos jurídicos ({{ matters().length }}) <app-icon name="chevron-down" />
                    </button>
                    <div ngbDropdownMenu class="shadow app-menu">
                      @for (m of matters(); track m.id) {
                        <a ngbDropdownItem [routerLink]="['/juridico/casos', m.id]">Caso jurídico encaminhado em {{ m.referredAt | appDate: 'date' }}</a>
                      }
                    </div>
                  </div>
                } @else {
                  <span class="small">Sem caso jurídico aberto para a empresa: avalie a habilitação do crédito no processo.</span>
                }
              }
              @if (canManage()) {
                @if (p.kind === 'JUDICIAL_RECOVERY') {
                  <button class="btn btn-sm btn-link" type="button" (click)="convert(p)" [disabled]="busy()">Convolar em falência</button>
                }
                <button class="btn btn-sm btn-link" type="button" (click)="end(p)" [disabled]="busy()">Encerrar processo</button>
              }
            </div>
          </div>
        </div>
      } @else if (canManage()) {
        <div class="d-flex flex-wrap gap-2 align-items-center in-idle" data-tour="insolvency-notice">
          <button class="btn btn-sm btn-link px-0" type="button" (click)="register()" [disabled]="busy()">
            <app-icon name="bank" /> Registrar recuperação judicial ou falência
          </button>
          @if (lastEnded(); as l) {
            <span class="app-muted small">Último processo ({{ kindLabels[l.kind] }}) encerrado em {{ l.endedOn | appDate: 'date' }}.</span>
          }
        </div>
      }
    } @else if (failed()) {
      <div class="alert alert-light d-flex gap-2 align-items-center in-alert" role="status">
        <app-icon name="exclamation-triangle" />
        <span class="flex-grow-1">Não foi possível verificar se a empresa está em recuperação judicial ou falência.</span>
        <button class="btn btn-sm btn-outline-secondary" type="button" (click)="retry()">Tentar novamente</button>
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .in-alert { margin-bottom: var(--app-space-3, 12px); }
    .in-idle { margin-bottom: var(--app-space-2, 8px); }
  `,
})
export class InsolvencyNotice {
  readonly debtorId = input.required<string>();
  readonly personType = input<string | null | undefined>(null);
  /** Ficha do devedor: mostra as ações de registro, convolação e encerramento (com `DEBTOR_MANAGE`). */
  readonly manage = input(false);

  private readonly api = inject(InsolvencyApi);
  private readonly mattersApi = inject(LegalMattersApi);
  private readonly auth = inject(AuthService);
  private readonly forms = inject(FormDialogService);
  private readonly notifications = inject(NotificationService);

  protected readonly kindLabels = INSOLVENCY_KIND_LABELS;
  protected readonly busy = signal(false);
  private readonly reload = signal(0);

  private readonly allowed = computed(
    () => this.personType() === 'COMPANY' && this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]),
  );
  protected readonly canManage = computed(
    () => this.manage() && this.auth.hasPermission(Permission.DEBTOR_MANAGE) && !!this.state()?.canManage,
  );
  protected readonly canSeeLegal = computed(() => this.auth.hasPermission(Permission.LEGAL_VIEW));

  private readonly resource = rxResource({
    params: () => (this.allowed() ? { id: this.debtorId(), n: this.reload() } : undefined),
    stream: ({ params }) => this.api.ofDebtor(params.id),
  });
  protected readonly state = computed(() => (this.resource.hasValue() ? this.resource.value() : null));
  /** A consulta falhou: a ficha avisa e oferece nova tentativa (nunca some em silêncio, a informação suspende a cobrança). */
  protected readonly failed = computed(() => this.allowed() && !!this.resource.error());

  protected retry(): void {
    this.reload.update((n) => n + 1);
  }
  protected readonly lastEnded = computed(() => this.state()?.history.find((p) => p.status === 'ENDED') ?? null);

  /** Casos jurídicos abertos da empresa (só com processo ativo e `LEGAL_VIEW`). */
  private readonly mattersResource = rxResource({
    params: () => (this.state()?.active && this.canSeeLegal() ? this.debtorId() : undefined),
    stream: ({ params }) =>
      this.mattersApi.search({ debtorId: params, open: true }, { page: 0, size: 20 }).pipe(
        map((r) => r.content),
        catchError(() => of([] as MatterView[])),
      ),
  });
  protected readonly matters = computed(() => (this.mattersResource.hasValue() ? (this.mattersResource.value() ?? []) : []));

  protected phrase(kind: InsolvencyKind): string {
    return INSOLVENCY_KIND_PHRASES[kind];
  }

  protected register(): void {
    this.forms
      .open(
        {
          title: 'Registrar recuperação judicial ou falência',
          message:
            'A cobrança da empresa fica suspensa em todos os credores (sem contato nem régua) enquanto o processo estiver em curso. ' +
            'Interações registradas e acordos em vigor não são alterados.',
          submitLabel: 'Registrar',
          fields: [
            { key: 'kind', label: 'Tipo', type: 'select', required: true, options: options(INSOLVENCY_KIND_LABELS), value: 'JUDICIAL_RECOVERY' },
            {
              key: 'caseNumber',
              label: 'Número do processo',
              type: 'text',
              required: true,
              maxLength: 30,
              hint: 'Padrão CNJ: NNNNNNN-DD.AAAA.J.TR.OOOO',
              validate: (v) => (cnjValid(String(v)) ? null : 'Número CNJ inválido (confira os dígitos verificadores).'),
            },
            { key: 'filedOn', label: 'Data do pedido', type: 'date', required: true, hint: 'Distribuição; o ano é o do número do processo.' },
            { key: 'court', label: 'Vara ou juízo (opcional)', type: 'text', maxLength: 150 },
            {
              key: 'note',
              label: 'Origem da informação',
              type: 'textarea',
              required: true,
              maxLength: 500,
              wide: true,
              hint: 'Ex.: edital no diário oficial, comunicado do administrador judicial. Não registre dados pessoais.',
            },
          ],
          submit: (v) =>
            this.api.register(this.debtorId(), {
              kind: v['kind'] as InsolvencyKind,
              caseNumber: String(v['caseNumber']),
              court: (v['court'] as string | null) || null,
              filedOn: String(v['filedOn']),
              note: String(v['note']),
            }),
        },
        'lg',
      )
      .subscribe(() => this.changed('Processo registrado. Os casos da empresa ficam suspensos em instantes.'));
  }

  protected convert(p: ProceedingView): void {
    this.forms
      .open({
        title: 'Convolar recuperação judicial em falência',
        message: 'O mesmo processo passa a ser de falência (Lei 11.101/2005, art. 73). A cobrança continua suspensa.',
        submitLabel: 'Convolar',
        details: [{ label: 'Processo', value: p.caseNumber }],
        fields: [
          { key: 'convertedOn', label: 'Decretação da falência', type: 'date', required: true, value: toLocalDate(new Date()) },
          { key: 'reason', label: 'Motivo', type: 'textarea', required: true, maxLength: 500, wide: true, hint: 'Ex.: plano rejeitado em assembleia.' },
        ],
        submit: (v) => this.api.convertToBankruptcy(p.id, String(v['convertedOn']), String(v['reason'])),
      })
      .subscribe(() => this.changed('Recuperação judicial convolada em falência.'));
  }

  protected end(p: ProceedingView): void {
    this.forms
      .open({
        title: 'Encerrar processo',
        message: 'Com o processo encerrado, os casos da empresa voltam a ser avaliados para contato e régua.',
        submitLabel: 'Encerrar',
        details: [
          { label: 'Tipo', value: INSOLVENCY_KIND_LABELS[p.kind] },
          { label: 'Processo', value: p.caseNumber },
        ],
        fields: [
          { key: 'endedOn', label: 'Encerrado em', type: 'date', required: true, value: toLocalDate(new Date()) },
          {
            key: 'reason',
            label: 'Motivo',
            type: 'textarea',
            required: true,
            maxLength: 500,
            wide: true,
            hint: 'Ex.: plano cumprido, processo extinto, registro por engano.',
          },
        ],
        submit: (v) => this.api.end(p.id, String(v['endedOn']), String(v['reason'])),
      })
      .subscribe(() => this.changed('Processo encerrado. A cobrança da empresa volta a ser avaliada.'));
  }

  private changed(message: string): void {
    this.notifications.success(message);
    this.reload.update((n) => n + 1);
  }
}
