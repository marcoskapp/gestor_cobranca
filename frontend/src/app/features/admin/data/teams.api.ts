import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { TeamRequest, TeamView } from './admin.models';

@Injectable({ providedIn: 'root' })
export class TeamsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/teams`;

  list(): Observable<TeamView[]> {
    return this.http.get<TeamView[]>(this.base);
  }

  create(request: TeamRequest): Observable<TeamView> {
    return this.http.post<TeamView>(this.base, request);
  }

  update(id: string, request: TeamRequest): Observable<TeamView> {
    return this.http.put<TeamView>(`${this.base}/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<TeamView> {
    return this.http.post<TeamView>(`${this.base}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }
}
