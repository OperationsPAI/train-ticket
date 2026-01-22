package edu.fudan.common.entity;

import edu.fudan.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @author fdse
 */
@Data
@Schema(description = "Trip entity representing a train trip")
public class Trip {
  @Schema(description = "Trip ID")
  private String id;

  @Schema(description = "Trip ID object")
  private TripId tripId;

  @Schema(description = "Train type name", example = "GaoTieOne")
  private String trainTypeName;

  @Schema(description = "Route ID")
  private String routeId;

  @Schema(description = "Start station name", example = "shanghai")
  private String startStationName;

  @Schema(description = "Intermediate stations", example = "suzhou,wuxi,changzhou")
  private String stationsName;

  @Schema(description = "Terminal station name", example = "nanjing")
  private String terminalStationName;

  @Schema(description = "Departure time", example = "08:00")
  private String startTime;

  @Schema(description = "Arrival time", example = "10:30")
  private String endTime;

  public Trip(
      TripId tripId,
      String trainTypeName,
      String startStationName,
      String stationsName,
      String terminalStationName,
      String startTime,
      String endTime) {
    this.tripId = tripId;
    this.trainTypeName = trainTypeName;
    this.startStationName = StringUtils.String2Lower(startStationName);
    this.stationsName = StringUtils.String2Lower(stationsName);
    this.terminalStationName = StringUtils.String2Lower(terminalStationName);
    this.startTime = startTime;
    this.endTime = endTime;
  }

  public Trip(TripId tripId, String trainTypeName, String routeId) {
    this.tripId = tripId;
    this.trainTypeName = trainTypeName;
    this.routeId = routeId;
    this.startStationName = "";
    this.terminalStationName = "";
    this.startTime = "";
    this.endTime = "";
  }

  public Trip() {
    // Default Constructor
    this.trainTypeName = "";
    this.startStationName = "";
    this.terminalStationName = "";
    this.startTime = "";
    this.endTime = "";
  }

  //    public Date getStartTime(){
  //        return StringUtils.String2Date(this.startTime);
  //    }
  //
  //    public Date getEndTime(){
  //        return StringUtils.String2Date(this.endTime);
  //    }
  //
  //    public void setStartTime(Date startTime){
  //        this.startTime = StringUtils.Date2String(startTime);
  //    }
  //
  //    public void setEndTime(Date endTime){
  //        this.endTime = StringUtils.Date2String(endTime);
  //    }

}
