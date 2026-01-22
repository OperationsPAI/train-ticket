package consignprice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
@Entity
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
@Table(name = "consign_price")
public class ConsignPrice {
  @Id
  @GeneratedValue(generator = "jpa-uuid")
  @Column(length = 36)
  private String id;

  @Column(name = "idx", unique = true)
  private int index;

  @Column(name = "initial_weight")
  private double initialWeight;

  @Column(name = "initial_price")
  private double initialPrice;

  @Column(name = "within_price")
  private double withinPrice;

  @Column(name = "beyond_price")
  private double beyondPrice;

  public ConsignPrice() {
    // Default Constructor
  }
}
