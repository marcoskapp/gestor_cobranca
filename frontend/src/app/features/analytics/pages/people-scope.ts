import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';

/** Agrupamentos por pessoa só para quem tem escopo de desempenho (próprio, equipe ou todos). */
export function peopleGroupBy(groups: readonly string[], auth: AuthService): string[] {
  const people = auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_OWN, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL]);
  return groups.filter((g) => people || (g !== 'OPERATOR' && g !== 'TEAM'));
}
