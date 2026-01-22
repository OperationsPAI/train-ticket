package foodsearch.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

@Data
@AllArgsConstructor
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
public class FoodOrder {

  @Id
  @GeneratedValue(generator = "jpa-uuid")
  @Column(length = 36)
  private String id;

  private String orderId;

  // 1:train food;2:food store
  private int foodType;

  private String stationName;

  private String storeName;

  private String foodName;

  private double price;

  public FoodOrder() {
    // Default Constructor
  }
}
