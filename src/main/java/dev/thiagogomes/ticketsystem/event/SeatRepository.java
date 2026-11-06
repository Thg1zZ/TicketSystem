package dev.thiagogomes.ticketsystem.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("""
            select new dev.thiagogomes.ticketsystem.event.SeatView(
                s.id, s.sector.id, s.rowLabel, s.seatNumber, s.status, s.heldUntil)
            from Seat s
            where s.sector.event.id = :eventId
            order by s.sector.id, s.rowLabel, s.seatNumber
            """)
    List<SeatView> findSeatMap(Long eventId);
}
