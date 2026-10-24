package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.PresenceInterval;

public interface PresenceIntervalRepository {

    PresenceInterval save(PresenceInterval interval);
}
