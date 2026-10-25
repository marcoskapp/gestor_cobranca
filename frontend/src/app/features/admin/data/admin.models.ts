/** Contratos de UserController, RoleController e TeamController (módulo identity). */

export type UserStatus = 'PENDING_ACTIVATION' | 'ACTIVE' | 'DISABLED';

/** `UserManagementUseCases.UserView` */
export interface UserView {
  id: string;
  name: string;
  email: string;
  status: UserStatus;
  locked: boolean;
  lockedUntil: string | null;
  mfaEnabled: boolean;
  lastLoginAt: string | null;
  passwordChangedAt: string | null;
  businessUnitId: string | null;
  roleIds: string[];
  roleCodes: string[];
  allCreditors: boolean;
  creditorIds: string[];
  createdAt: string;
}

export interface UserFilter {
  q?: string | null;
  status?: string | null;
  roleId?: string | null;
  businessUnitId?: string | null;
}

export interface CreateUserRequest {
  name: string;
  email: string;
  businessUnitId: string | null;
  roleIds: string[];
  allCreditors: boolean;
  creditorIds: string[];
}

export interface UpdateUserRequest {
  name: string;
  businessUnitId: string | null;
}

/** `RoleManagementUseCases.RoleView` */
export interface RoleView {
  id: string;
  code: string;
  name: string;
  description: string | null;
  systemRole: boolean;
  active: boolean;
  permissions: string[];
}

export interface RoleRequest {
  code: string | null;
  name: string;
  description: string | null;
  permissions: string[];
}

/** `RoleManagementUseCases.PermissionView` */
export interface PermissionView {
  code: string;
  description: string;
  /** O usuário atual pode conceder (só se concede o que o próprio perfil possui). */
  grantable: boolean;
}

/** `TeamUseCases.TeamView` */
export interface TeamView {
  id: string;
  name: string;
  businessUnitId: string | null;
  supervisorId: string | null;
  active: boolean;
  memberIds: string[];
}

export interface TeamRequest {
  name: string;
  businessUnitId: string | null;
  supervisorId: string | null;
  memberIds: string[];
}
