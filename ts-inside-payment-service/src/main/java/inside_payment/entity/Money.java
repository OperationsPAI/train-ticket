package inside_payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
// import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

/**
 * @author fdse
 */
@Data
@Entity
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
@Table(name = "inside_money")
public class Money {

  @Valid
  @NotNull
  @Id
  @Column(length = 36)
  @GeneratedValue(generator = "jpa-uuid")
  private String id;

  @Valid
  @NotNull
  @Column(length = 36)
  private String userId;

  @Valid @NotNull private String money; // NOSONAR

  @Valid
  @NotNull
  @Enumerated(EnumType.STRING)
  private MoneyType type;

  public Money() {
    this.userId = "";
    this.money = "";
  }
}
