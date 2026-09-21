package com.sketchroom;

import com.sketchroom.model.Room;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SketchroomBackendApplicationTests {
    @Test void newRoomsHaveA24HourLifetime() {
        Room room = Room.builder().roomKey("ABC234").build();
        room.prePersist();
        assertTrue(room.isActive());
        assertEquals(24, Duration.between(room.getCreatedAt(), room.getExpiresAt()).toHours());
    }
}
