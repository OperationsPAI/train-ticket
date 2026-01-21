package edu.fudan.common.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @author fdse
 */
@Data
@Schema(description = "Train type entity defining train specifications")
public class TrainType {
    @Schema(description = "Train type ID", example = "train-type-001")
    private String id;

    @Schema(description = "Train type name (e.g., G, D, K)", example = "GaoTieOne")
    private String name;

    @Schema(description = "Number of economy class seats", example = "500")
    private int economyClass;

    @Schema(description = "Number of comfort/first class seats", example = "100")
    private int confortClass;

    @Schema(description = "Average speed in km/h", example = "300")
    private int averageSpeed;

    public TrainType(){
        //Default Constructor
    }

    public TrainType(String name, int economyClass, int confortClass) {
        this.name = name;
        this.economyClass = economyClass;
        this.confortClass = confortClass;
    }

    public TrainType(String name, int economyClass, int confortClass, int averageSpeed) {
        this.name = name;
        this.economyClass = economyClass;
        this.confortClass = confortClass;
        this.averageSpeed = averageSpeed;
    }
}
