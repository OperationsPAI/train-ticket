package travelplan.entity;

import java.util.List;
import lombok.Data;

/**
 * @author fdse
 */
@Data
public class TravelAdvanceResultUnit {

  private String tripId;

  private String trainTypeId;

  private String startStation;

  private String endStation;

  private List<String> stopStations;

  private String priceForSecondClassSeat;

  private int numberOfRestTicketSecondClass;

  private String priceForFirstClassSeat;

  private int numberOfRestTicketFirstClass;

  private String startTime;

  private String endTime;

  public TravelAdvanceResultUnit() {
    // Default Constructor
  }
}
