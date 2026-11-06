package dev.thiagogomes.ticketsystem.event;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = "sectors")
    Optional<Event> findWithSectorsById(Long id);
}
