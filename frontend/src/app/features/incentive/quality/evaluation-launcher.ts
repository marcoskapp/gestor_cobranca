import { Injectable, inject } from '@angular/core';
import { Observable, from, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { zonedNow } from '../../collection/data/collection.rules';
import { QualityApi } from '../data/incentive.api';
import { EvaluationView, QualityForm, ReferenceType } from '../data/incentive.models';
import { score } from '../data/incentive.rules';

/**
 * Atendimento a avaliar, vindo da tela em que ele aparece (chamada, conversa, histórico do caso): o formulário de
 * monitoria abre com o negociador, a referência e a data preenchidos — sem digitar identificador (J7).
 */
export interface EvaluationTarget {
  /** Quem atendeu (o negociador avaliado). */
  userId: string;
  /** Nome de quem atendeu, só para exibir. */
  userName: string;
  referenceType: ReferenceType;
  referenceId: string;
  /** Data do atendimento (`yyyy-MM-dd`, fuso de operação). */
  evaluatedOn: string;
  /** Descrição curta do atendimento ("Chamada de 07/11/2026 14:32"). */
  label: string;
}

/** Dados do diálogo de monitoria: os formulários ativos e, quando vem do atendimento, o que já está preenchido. */
export interface EvaluationDialogData {
  forms: QualityForm[];
  prefill?: EvaluationTarget;
}

/** Data local (fuso de operação) de um instante ISO — a data do atendimento na monitoria. */
export function attendanceDate(instant: string): string {
  return zonedNow(new Date(instant)).date;
}

/**
 * "Avaliar este atendimento": regra de exibição e abertura do diálogo de monitoria já preenchido. Exige
 * `QUALITY_EVALUATE`; ninguém avalia o próprio atendimento (o servidor também recusa, `quality_evaluation.self_evaluation`,
 * e confere que o negociador está no escopo de quem avalia). Atendimento sem pessoa (assistente, sistema) não é avaliado
 * aqui. O diálogo é carregado sob demanda (fica no pacote da monitoria).
 */
@Injectable({ providedIn: 'root' })
export class EvaluationLauncher {
  private readonly auth = inject(AuthService);
  private readonly api = inject(QualityApi);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);

  /** Mostra "Avaliar" para este atendimento: permissão de avaliar e atendente que não é a própria pessoa. */
  canEvaluate(attendantId: string | null | undefined): boolean {
    return !!attendantId && this.auth.hasPermission(Permission.QUALITY_EVALUATE) && attendantId !== this.auth.user()?.id;
  }

  /** Abre a monitoria preenchida; sem formulário ativo, avisa onde criar. Emite a avaliação registrada (ou nada). */
  open(target: EvaluationTarget): Observable<EvaluationView | undefined> {
    if (!this.canEvaluate(target.userId)) {
      return of(undefined);
    }
    return this.api.forms().pipe(
      map((forms) => forms.filter((f) => f.active)),
      switchMap((forms) => {
        if (!forms.length) {
          this.notifications.info('Nenhum formulário de monitoria ativo: crie um em Monitoria de qualidade › Formulários.');
          return of(undefined);
        }
        return from(import('./evaluation-list')).pipe(
          switchMap(({ EvaluationDialog }) =>
            this.dialog.open<InstanceType<typeof EvaluationDialog>, EvaluationDialogData, EvaluationView>(EvaluationDialog, { data: { forms, prefill: target }, size: 'lg' }).afterClosed(),
          ),
          map((saved) => {
            if (saved) {
              this.notifications.success(`Monitoria registrada: nota ${score(saved.score)}. Ela aparece em Monitoria de qualidade, com o link para este atendimento.`);
            }
            return saved;
          }),
        );
      }),
    );
  }
}
