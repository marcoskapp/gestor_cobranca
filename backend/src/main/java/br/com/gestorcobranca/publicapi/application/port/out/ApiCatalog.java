package br.com.gestorcobranca.publicapi.application.port.out;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ApiVersionView;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ChangelogRelease;

import java.util.List;

/** Registro de versões e changelog da API pública, lidos de arquivos versionados no repositório. */
public interface ApiCatalog {

    List<ApiVersionView> versions();

    List<ChangelogRelease> changelog();

    java.util.Optional<String> contract(String versionCode);
}
