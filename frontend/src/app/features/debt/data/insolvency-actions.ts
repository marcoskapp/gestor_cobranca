import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { toLocalDate } from '../../../shared/models/dates';
import { FormDialogService, options } from '../../legal/components/form-dialog';
import { cnjValid } from '../../legal/data/legal.rules';
import { INSOLVENCY_KIND_LABELS, InsolvencyApi, InsolvencyKind, ProceedingView } from './insolvency';

/**
 * Registro, convolação e encerramento do processo de recuperação judicial, extrajudicial ou falência (cobrança B2B §7),
 * com os formulários e avisos. Usado pelo aviso da ficha (`app-insolvency-notice`) e pelo selo de atenção especial
 * (`app-vulnerability-badge`); `revision` muda a cada alteração, para os dois recarregarem juntos.
 */
@Injectable({ providedIn: 'root' })
export class InsolvencyActions {
  private readonly api = inject(InsolvencyApi);
  private readonly forms = inject(FormDialogService);
  private readonly notifications = inject(NotificationService);

  /** Muda depois de cada registro, convolação ou encerramento concluído. */
  readonly revision = signal(0);

  /** Formulário do processo; `kind` vem pré-escolhido quando a pessoa já escolheu a situação (atenção especial). */
  register(debtorId: string, kind: InsolvencyKind = 'JUDICIAL_RECOVERY'): Observable<ProceedingView> {
    return this.forms
      .open(
        {
          title: 'Registrar recuperação judicial ou falência',
          message:
            'A cobrança da empresa fica suspensa em todos os credores (sem contato nem régua) enquanto o processo estiver em curso. ' +
            'Interações registradas e acordos em vigor não são alterados.',
          submitLabel: 'Registrar',
          fields: [
            { key: 'kind', label: 'Tipo', type: 'select', required: true, options: options(INSOLVENCY_KIND_LABELS), value: kind },
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
            this.api.register(debtorId, {
              kind: v['kind'] as InsolvencyKind,
              caseNumber: String(v['caseNumber']),
              court: (v['court'] as string | null) || null,
              filedOn: String(v['filedOn']),
              note: String(v['note']),
            }),
        },
        'lg',
      )
      .pipe(tap(() => this.changed('Processo registrado. Os casos da empresa ficam suspensos em instantes.')));
  }

  convert(p: ProceedingView): Observable<ProceedingView> {
    return this.forms
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
      .pipe(tap(() => this.changed('Recuperação judicial convolada em falência.')));
  }

  end(p: ProceedingView): Observable<ProceedingView> {
    return this.forms
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
      .pipe(tap(() => this.changed('Processo encerrado. A cobrança da empresa volta a ser avaliada.')));
  }

  private changed(message: string): void {
    this.notifications.success(message);
    this.revision.update((n) => n + 1);
  }
}
