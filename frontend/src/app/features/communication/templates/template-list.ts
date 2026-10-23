import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { debounceTime, map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { TemplatesApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, PURPOSE_LABELS } from '../data/communication.labels';
import { TemplateFilter, TemplateView } from '../data/communication.models';
import { ApprovalDialog, TemplateDialog, TemplateDialogData, exampleValues } from './template-dialogs';

export function toTemplateFilter(v: { creditor: string[]; code: string; channel: string; status: string }): TemplateFilter {
  return { creditorId: v.creditor[0] ?? null, code: v.code.trim().toUpperCase() || null, channel: v.channel || null, status: v.status || null };
}

/**
 * Templates de mensagem versionados: rascunho editável, aprovação do WhatsApp, publicação (retira a
 * versão publicada anterior do mesmo código) e retirada. Prévia com valores de exemplo.
 */
@Component({
  selector: 'app-template-list',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatSelectModule,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
  ],
  template: `
    <app-page-header title="Templates de mensagem" subtitle="Textos aprovados para cada canal. E-mail e SMS só saem por template; no WhatsApp, fora da janela de 24 h.">
      <button mat-flat-button type="button" (click)="edit(null)">
        <mat-icon aria-hidden="true">add</mat-icon>
        Novo template
      </button>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar templates">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Código</mat-label>
        <input matInput formControlName="code" maxlength="60" />
      </mat-form-field>
      <mat-form-field class="app-compact">
        <mat-label>Canal</mat-label>
        <mat-select formControlName="channel">
          <mat-option value="">Todos</mat-option>
          @for (c of channelOptions; track c.value) {
            <mat-option [value]="c.value">{{ c.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>

    @if (preview(); as p) {
      <section class="app-card tl-preview" aria-labelledby="tl-preview-title" role="region">
        <div class="tl-head">
          <h2 class="app-card__title" id="tl-preview-title">Prévia: {{ p.name }}</h2>
          <button mat-icon-button type="button" (click)="preview.set(null)" aria-label="Fechar prévia"><mat-icon aria-hidden="true">close</mat-icon></button>
        </div>
        @if (p.subject) {
          <p><strong>{{ p.subject }}</strong></p>
        }
        <p class="tl-body">{{ p.body }}</p>
        <p class="app-muted">Valores de exemplo; no envio, o operador preenche cada variável.</p>
      </section>
    }

    <app-data-table
      caption="Templates de mensagem"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="article"
      emptyMessage="Nenhum template encontrado."
    >
      <ng-template appCell="code" [appCellOf]="query.rows()" let-t>
        <span class="app-mono">{{ t.code }}</span> · v{{ t.templateVersion }}
        <div class="app-muted">{{ t.name }}</div>
      </ng-template>
      <ng-template appCell="channel" [appCellOf]="query.rows()" let-t>
        <span class="tl-chan"><mat-icon aria-hidden="true">{{ icons[t.channel] }}</mat-icon>{{ channels[t.channel] }}</span>
        <div class="app-muted">{{ purposes[t.purpose] }}</div>
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-t>{{ t.creditorId ? creditors.name(t.creditorId) : 'Assessoria' }}</ng-template>
      <ng-template appCell="variables" [appCellOf]="query.rows()" let-t>{{ t.variables.length }}</ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-t>
        <app-status-chip domain="template" [status]="t.status" />
        @if (t.channel === 'WHATSAPP' && t.status === 'DRAFT') {
          <div><app-status-chip domain="templateApproval" [status]="t.approvalStatus" /></div>
        }
        @if (t.publishedAt) {
          <div class="app-muted">publicado em {{ t.publishedAt | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-t>
        <button mat-icon-button type="button" [matMenuTriggerFor]="menu" [attr.aria-label]="'Ações do template ' + t.code">
          <mat-icon aria-hidden="true">more_vert</mat-icon>
        </button>
        <mat-menu #menu="matMenu">
          <button mat-menu-item type="button" (click)="showPreview(t)"><mat-icon aria-hidden="true">visibility</mat-icon>Prévia</button>
          @if (t.status === 'DRAFT') {
            <button mat-menu-item type="button" (click)="edit(t)"><mat-icon aria-hidden="true">edit</mat-icon>Editar rascunho</button>
            @if (t.channel === 'WHATSAPP') {
              <button mat-menu-item type="button" (click)="approval(t)"><mat-icon aria-hidden="true">fact_check</mat-icon>Registrar aprovação do WhatsApp</button>
            }
            <button mat-menu-item type="button" (click)="publish(t)" [disabled]="t.channel === 'WHATSAPP' && t.approvalStatus !== 'APPROVED'">
              <mat-icon aria-hidden="true">publish</mat-icon>Publicar
            </button>
          } @else {
            <button mat-menu-item type="button" (click)="newVersion(t)"><mat-icon aria-hidden="true">content_copy</mat-icon>Criar nova versão</button>
          }
          @if (t.status === 'PUBLISHED') {
            <button mat-menu-item type="button" (click)="retire(t)"><mat-icon aria-hidden="true">archive</mat-icon>Retirar</button>
          }
          <button mat-menu-item type="button" (click)="versions(t)"><mat-icon aria-hidden="true">history</mat-icon>Ver versões</button>
        </mat-menu>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .tl-chan {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .tl-preview {
      margin-bottom: var(--app-gap);
    }
    .tl-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .tl-body {
      white-space: pre-wrap;
    }
  `,
})
export class TemplateList {
  private readonly api = inject(TemplatesApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly channels = CHANNEL_LABELS;
  protected readonly icons = CHANNEL_ICONS;
  protected readonly purposes = PURPOSE_LABELS;
  protected readonly channelOptions = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly statuses = statusOptions('template');
  protected readonly preview = signal<{ name: string; subject: string | null; body: string } | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), code: [''], channel: [''], status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map(() => toTemplateFilter(this.filters.getRawValue())),
    ),
    { initialValue: toTemplateFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'code', header: 'Código e versão' },
    { key: 'channel', header: 'Canal' },
    { key: 'creditor', header: 'Credor' },
    { key: 'variables', header: 'Variáveis', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected edit(template: TemplateView | null): void {
    this.openEditor({ template }, template ? 'Rascunho salvo.' : 'Template criado como rascunho.');
  }

  protected newVersion(template: TemplateView): void {
    this.openEditor({ template, asNewVersion: true }, 'Nova versão criada como rascunho.');
  }

  private openEditor(data: TemplateDialogData, message: string): void {
    this.dialog
      .open<TemplateDialog, TemplateDialogData, TemplateView>(TemplateDialog, { data, width: '720px' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(message);
          this.query.reload();
        }
      });
  }

  protected approval(template: TemplateView): void {
    this.dialog
      .open<ApprovalDialog, TemplateDialogData, TemplateView>(ApprovalDialog, { data: { template }, width: '520px' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Decisão do WhatsApp registrada.');
          this.query.reload();
        }
      });
  }

  protected publish(t: TemplateView): void {
    this.confirm
      .confirm({
        title: 'Publicar template',
        message: 'A versão publicada passa a ser usada nos envios; a versão publicada anterior deste código é retirada.',
        confirmLabel: 'Publicar',
        details: [
          { label: 'Template', value: `${t.code} · v${t.templateVersion}` },
          { label: 'Canal', value: CHANNEL_LABELS[t.channel] },
          { label: 'Credor', value: t.creditorId ? this.creditors.name(t.creditorId) : 'Assessoria' },
        ],
      })
      .subscribe(() =>
        this.api.publish(t.id).subscribe(() => {
          this.notifications.success('Template publicado.');
          this.query.reload();
        }),
      );
  }

  protected retire(t: TemplateView): void {
    this.confirm
      .confirm({
        title: 'Retirar template',
        message: 'Sem versão publicada do credor, os envios usam a da assessoria; sem nenhuma, o template deixa de estar disponível.',
        confirmLabel: 'Retirar',
        destructive: true,
        details: [{ label: 'Template', value: `${t.code} · v${t.templateVersion}` }],
      })
      .subscribe(() =>
        this.api.retire(t.id).subscribe(() => {
          this.notifications.success('Template retirado.');
          this.query.reload();
        }),
      );
  }

  protected versions(t: TemplateView): void {
    this.filters.patchValue({ code: t.code, status: '', channel: '' });
  }

  protected showPreview(t: TemplateView): void {
    this.api.preview(t.id, exampleValues(t)).subscribe((p) => this.preview.set({ name: `${t.code} · v${t.templateVersion}`, subject: p.subject, body: p.body }));
  }
}
