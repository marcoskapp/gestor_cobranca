package br.com.gestorcobranca.shared.privacy;

import br.com.gestorcobranca.shared.security.Permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tela que abre a evidência de um compartilhamento (conversa, chamadas filtradas, caso jurídico, cobranças Pix) e a
 * permissão que essa tela exige — a interface só mostra o link para quem a abre (menus e links sem permissão ficam
 * ocultos). Só ids e códigos: nunca dado pessoal na rota ou nos parâmetros.
 *
 * @param route          rota do frontend (começa com {@code /}, sem query string)
 * @param queryParams    filtros da tela de destino (ids e códigos)
 * @param anyPermission  basta uma (as permissões da rota)
 * @param allPermissions exigidas todas, além de uma de {@code anyPermission}
 * @param label          texto curto do link (ex.: "Abrir a conversa mais recente")
 */
public record DataSharingLink(String route, Map<String, String> queryParams, Set<Permission> anyPermission,
                              Set<Permission> allPermissions, String label) {

    private static final Pattern PARAM = Pattern.compile("[a-z][a-z-]{0,30}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_,:-]{1,80}");

    public DataSharingLink {
        Objects.requireNonNull(route, "route");
        if (!route.startsWith("/") || route.contains("?") || route.contains("#")) {
            throw new IllegalArgumentException("Rota inválida: " + route);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (queryParams != null) {
            queryParams.forEach((k, v) -> {
                if (!PARAM.matcher(k).matches() || v == null || !VALUE.matcher(v).matches()) {
                    throw new IllegalArgumentException("Filtro inválido: " + k);
                }
                copy.put(k, v);
            });
        }
        queryParams = Collections.unmodifiableMap(copy);
        anyPermission = Set.copyOf(Objects.requireNonNull(anyPermission, "anyPermission"));
        if (anyPermission.isEmpty()) {
            throw new IllegalArgumentException("Informe a permissão da tela de destino");
        }
        allPermissions = allPermissions == null ? Set.of() : Set.copyOf(allPermissions);
        Objects.requireNonNull(label, "label");
    }

    public static DataSharingLink to(String route, String label, Permission... anyPermission) {
        return new DataSharingLink(route, Map.of(), Set.of(anyPermission), Set.of(), label);
    }

    public DataSharingLink withParam(String key, String value) {
        Map<String, String> params = new LinkedHashMap<>(queryParams);
        params.put(key, value);
        return new DataSharingLink(route, params, anyPermission, allPermissions, label);
    }

    public DataSharingLink alsoRequiring(Permission... permissions) {
        return new DataSharingLink(route, queryParams, anyPermission, Set.of(permissions), label);
    }
}
