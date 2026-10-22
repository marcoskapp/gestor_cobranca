package br.com.gestorcobranca.organization.application.port.in;

import java.util.List;
import java.util.UUID;

public interface BusinessUnitUseCases {

    BusinessUnitView create(String code, String name);

    BusinessUnitView update(UUID id, String name, boolean active);

    List<BusinessUnitView> list();

    BusinessUnitView get(UUID id);

    record BusinessUnitView(UUID id, String code, String name, boolean active) {
    }
}
