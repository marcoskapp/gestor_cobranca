import { Injectable, computed, inject } from '@angular/core';
import { rxResource, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, from, map, of, switchMap } from 'rxjs';
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
 * `QUALITY_EVALUATE` e que quem atendeu esteja no escopo de quem avalia — todos, com visão total, ou os membros das
 * equipes que supervisiona —, informado pelo servidor (`GET /quality/evaluable-users`, a mesma regra que ele aplica ao
 * registrar); até a resposta chegar, o botão não aparece. Ninguém avalia o próprio atendimento. Atendimento sem pessoa
 * (assistente, sistema) não é avaliado aqui. O diálogo é carregado sob demanda (fica no pacote da monitoria).
 */
@Injectable({ providedIn: 'root' })
export class EvaluationLauncher {
  private readonly auth = inject(AuthService);
  private readonly api = inject(QualityApi);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);

  /** Escopo de quem avalia, carregado uma vez por pessoa logada (falha = ninguém, o botão não aparece). */
  private readonly scope = rxResource({
    params: () => (this.auth.hasPermission(Permission.QUALITY_EVALUATE) ? (this.auth.user()?.id ?? undefined) : undefined),
    stream: () => this.api.evaluableUsers().pipe(catchError(() => of({ all: false, userIds: [] }))),
  });
  private readonly evaluable = computed(() => {
    const s = this.scope.hasValue() ? this.scope.value() : null;
    return s ? { all: s.all, ids: new Set(s.userIds) } : null;
  });

  /** Escopo de quem avalia (`null` até a resposta do servidor), para o seletor de negociador. */
  readonly scope$ = toObservable(this.evaluable);

  /** Valor atual do escopo (`null` até a resposta). */
  scopeSnapshot(): { all: boolean; ids: ReadonlySet<string> } | null {
    return this.evaluable();
  }

  /** Mostra "Avaliar" para este atendimento: permissão de avaliar, atendente no escopo e que não é a própria pessoa. */
  canEvaluate(attendantId: string | null | undefined): boolean {
    if (!attendantId || !this.auth.hasPermission(Permission.QUALITY_EVALUATE) || attendantId === this.auth.user()?.id) {
      return false;
    }
    const e = this.evaluable();
    return !!e && (e.all || e.ids.has(attendantId));
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
