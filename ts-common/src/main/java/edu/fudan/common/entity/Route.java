package edu.fudan.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * @author fdse
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Route entity representing a train route")
public class Route {
    @Schema(description = "Route ID", example = "route-001")
    private String id;

    @Schema(description = "List of station names on the route", example = "[\"shanghai\", \"suzhou\", \"nanjing\"]")
    private List<String> stations;

    @Schema(description = "Distances between stations in kilometers", example = "[0, 100, 200]")
    private List<Integer> distances;

    @Schema(description = "Start station name", example = "shanghai")
    private String startStation;

    @Schema(description = "End station name", example = "nanjing")
    private String endStation;

    public Route(){
        this.id = UUID.randomUUID().toString();
    }

    public Route(String id, List<String> stations, List<Integer> distances, String startStationName, String terminalStationName) {
        this.id = id;
        this.stations = stations;
        this.distances = distances;
        this.startStation = startStationName;
        this.endStation = terminalStationName;
    }

    public Route(List<String> stations, List<Integer> distances, String startStationName, String terminalStationName) {
        this.id = UUID.randomUUID().toString();
        this.stations = stations;
        this.distances = distances;
        this.startStation = startStationName;
        this.endStation = terminalStationName;
    }
}
