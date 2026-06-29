package com.sketchroom.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DrawEvent {

    // "draw" = a stroke segment, "clear" = wipe the canvas
    private String type;

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