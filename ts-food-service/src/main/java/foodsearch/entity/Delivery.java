package foodsearch.entity;

import java.util.UUID;
import lombok.Data;

@Data
public class Delivery {
  public Delivery() {
    // Default Constructor
  }

  private UUID orderId;
  private String foodName;
  private String storeName;
  private String stationName;
}
