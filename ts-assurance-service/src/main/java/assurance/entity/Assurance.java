package assurance.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/**
 * @author fdse
 */
@Data
@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
public class Assurance {

  @Id
  @Column(name = "assurance_id")
  private String id; // 主键

  /** which order the assurance is related to */
  @NotNull private String orderId; // 这个保险关联的订单

  /** the type of assurance */
  @Enumerated(EnumType.STRING)
  @Column(name = "assurance_type")
  private AssuranceType type;

  public Assurance() {
    this.orderId = UUID.randomUUID().toString();
  }

  public Assurance(String id, String orderId, AssuranceType type) {
    this.id = id;
    this.orderId = orderId;
    this.type = type;
  }
}
