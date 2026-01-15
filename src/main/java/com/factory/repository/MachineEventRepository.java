package com.factory.repository;

import com.factory.model.MachineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for MachineEvent entity.
 *
 * Provides:
 * - Basic CRUD operations (from JpaRepository)
 * - Custom queries for statistics
 * - Efficient bulk operations
 */
@Repository
public interface MachineEventRepository extends JpaRepository<MachineEvent, String> {

    /**
     * Count events for a machine within a time window.
     * Start is inclusive, end is exclusive.
     */
    @Query("SELECT COUNT(e) FROM MachineEvent e WHERE e.machineId = :machineId " +
           "AND e.eventTime >= :start AND e.eventTime < :end")
    long countEventsByMachineAndTimeRange(
        @Param("machineId") String machineId,
        @Param("start") Instant start,
        @Param("end") Instant end
    );

    /**
     * Sum defects for a machine within a time window.
     * Excludes events where defectCount = -1 (unknown).
     */
    @Query("SELECT COALESCE(SUM(e.defectCount), 0) FROM MachineEvent e " +
           "WHERE e.machineId = :machineId " +
           "AND e.eventTime >= :start AND e.eventTime < :end " +
           "AND e.defectCount >= 0")
    long sumDefectsByMachineAndTimeRange(
        @Param("machineId") String machineId,
        @Param("start") Instant start,
        @Param("end") Instant end
    );

    /**
     * Get aggregated defect statistics by line for a factory.
     * Used for top-defect-lines endpoint.
     */
    @Query("SELECT e.lineId, SUM(e.defectCount), COUNT(e) " +
           "FROM MachineEvent e " +
           "WHERE e.factoryId = :factoryId " +
           "AND e.eventTime >= :from AND e.eventTime < :to " +
           "AND e.defectCount >= 0 " +
           "AND e.lineId IS NOT NULL " +
           "GROUP BY e.lineId " +
           "ORDER BY SUM(e.defectCount) DESC")
    List<Object[]> findTopDefectLinesByFactory(
        @Param("factoryId") String factoryId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
}

