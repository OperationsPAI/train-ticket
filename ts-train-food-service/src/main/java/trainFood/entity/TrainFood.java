package trainFood.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.fudan.common.entity.Food;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

@Data
@Entity
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrainFood {

  @Id
  @GeneratedValue(generator = "jpa-uuid")
  @Column(length = 36)
  private String id;

  @NotNull
  @Column(unique = true)
  private String tripId;

  @ElementCollection(targetClass = Food.class)
  @CollectionTable(name = "train_food_list", joinColumns = @JoinColumn(name = "trip_id"))
  private List<Food> foodList;

  public TrainFood() {
    // Default Constructor
    this.tripId = "";
  }
}
