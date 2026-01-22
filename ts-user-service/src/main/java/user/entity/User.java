package user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * @author fdse
 */
@Data
@Builder
@AllArgsConstructor
@Entity
public class User {

  //    private UUID userId;
  @Id
  @Column(length = 36, name = "user_id")
  private String userId;

  @Column(name = "user_name")
  private String userName;

  private String password;

  private int gender;

  @Column(name = "document_type")
  private int documentType;

  @Column(name = "document_num")
  private String documentNum;

  private String email;

  public User() {
    this.userId = UUID.randomUUID().toString();
  }
}
