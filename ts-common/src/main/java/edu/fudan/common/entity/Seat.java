package edu.fudan.common.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
@Schema(description = "Seat request information")
public class Seat {
    @Valid
    @NotNull
    @Schema(description = "Travel date", example = "2024-02-01", required = true)
    private String travelDate;

    @Valid
    @NotNull
    @Schema(description = "Train number", example = "G1234", required = true)
    private String trainNumber;

    @Valid
    @NotNull
    @Schema(description = "Starting station", example = "shanghai", required = true)
    private String startStation;

    @Valid
    @NotNull
    @Schema(description = "Destination station", example = "beijing", required = true)
    private String destStation;

    @Valid
    @NotNull
    @Schema(description = "Seat type: 2-First Class, 3-Second Class", example = "2", required = true)
    private int seatType;

    @Schema(description = "Total number of seats")
    private int totalNum;

    @Schema(description = "List of station names")
    private List<String> stations;

    public Seat(){
        //Default Constructor
        this.travelDate = "";
        this.trainNumber = "";
        this.startStation = "";
        this.destStation = "";
        this.seatType = 0;
        this.totalNum = 0;
        this.stations = null;
    }


}
