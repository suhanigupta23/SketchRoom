package com.sketchroom.repository;

import com.sketchroom.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByRoomKeyAndActiveTrue(String roomKey);

    boolean existsByRoomKey(String roomKey);

    @Modifying
    @Transactional
    @Query("UPDATE Room r SET r.active = false WHERE r.expiresAt < :now AND r.active = true")
    int deactivateExpiredRooms(LocalDateTime now);
}