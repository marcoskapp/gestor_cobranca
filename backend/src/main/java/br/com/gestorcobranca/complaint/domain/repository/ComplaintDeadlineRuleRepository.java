package br.com.gestorcobranca.complaint.domain.repository;

import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintDeadlineRule;

import java.util.List;
import java.util.Optional;

public interface ComplaintDeadlineRuleRepository {

    ComplaintDeadlineRule save(ComplaintDeadlineRule rule);

    List<ComplaintDeadlineRule> findAll();

    Optional<ComplaintDeadlineRule> findByChannel(ComplaintChannel channel);
}
