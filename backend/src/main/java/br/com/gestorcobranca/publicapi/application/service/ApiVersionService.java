package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.ApiCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Registro de versões e changelog (arquivos versionados no repositório, carregados na subida). */
@Service
class ApiVersionService implements ApiVersionUseCases {

    private final ApiCatalog catalog;

    ApiVersionService(ApiCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<ApiVersionView> versions() {
        return catalog.versions();
    }

    @Override
    public Optional<ApiVersionView> version(String code) {
        return catalog.versions().stream().filter(v -> v.code().equals(code)).findFirst();
    }

    @Override
    public List<ChangelogRelease> changelog() {
        return catalog.changelog();
    }
}
