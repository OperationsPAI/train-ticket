package edu.fudan.common.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Locale;

@Data
@Schema(description = "Railway station entity")
public class Station {
    @Schema(description = "Station ID", example = "station-001")
    private String id;

    @Schema(description = "Station name", example = "shanghai")
    private String name;

    @Schema(description = "Default stay time in minutes", example = "5")
    private int stayTime;

    public Station(){
        this.name = "";
    }

    public void setName(String name) {
        this.name = name.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    public Station(String name) {
        this.name = name.replace(" ", "").toLowerCase(Locale.ROOT);
    }


    public Station(String name, int stayTime) {
        this.name = name.replace(" ", "").toLowerCase(Locale.ROOT);;
        this.stayTime = stayTime;
    }
}

