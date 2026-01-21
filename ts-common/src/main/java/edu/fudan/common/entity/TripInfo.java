package edu.fudan.common.entity;

import edu.fudan.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
@Schema(description = "Trip query information")
public class TripInfo {
    @Valid
    @NotNull
    @Schema(description = "Departure place/station", example = "shanghai", required = true)
    private String startPlace;

    @Valid
    @NotNull
    @Schema(description = "Destination place/station", example = "beijing", required = true)
    private String endPlace;

    @Valid
    @NotNull
    @Schema(description = "Departure date", example = "2024-02-01", required = true)
    private String departureTime;

    public TripInfo(){
        //Default Constructor
        this.startPlace = "";
        this.endPlace = "";
        this.departureTime = "";
    }

    public String getStartPlace() {
        return StringUtils.String2Lower(this.startPlace);
    }

    public String getEndPlace() {
        return StringUtils.String2Lower(this.endPlace);
    }

//    public Date getDepartureTime(){
//        return StringUtils.String2Date(this.departureTime);
//    }
}
