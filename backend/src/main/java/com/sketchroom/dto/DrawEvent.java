package com.sketchroom.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DrawEvent {

    // Commands are stored and replayed in server-assigned sequence order.
    private String type;
    private String eventId;
    private String strokeId;
    // These two fields are assigned by the server, never trusted from clients.
    private String authorId;
    private Long sequence;

    // Current mouse position
    private Double x;
    private Double y;

    // Previous mouse position (draw FROM prevX,prevY TO x,y)
    private Double prevX;
    private Double prevY;

    // Drawing properties
    private String color;
    private Integer size;
    private Boolean isEraser;
}