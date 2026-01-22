package assurance.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
public class PlainAssurance implements Serializable {

  private String id;

  private String orderId;

  private int typeIndex;

  private String typeName;

  private double typePrice;

  public PlainAssurance() {
    // Default Constructor
  }
}
