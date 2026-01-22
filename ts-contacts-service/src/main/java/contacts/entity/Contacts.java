package contacts.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(
    indexes = {
      @Index(
          name = "account_document_idx",
          columnList = "account_id, document_number, document_type",
          unique = true)
    })
@Schema(description = "Contact entity representing a passenger's contact information")
public class Contacts {

  @Id
  //    private UUID id;
  @GeneratedValue(generator = "jpa-uuid")
  @Column(length = 36)
  @Schema(
      description = "Unique identifier for the contact",
      example = "550e8400-e29b-41d4-a716-446655440000")
  private String id;

  @Column(name = "account_id")
  @Schema(description = "Associated account ID", example = "user-123")
  private String accountId;

  @Schema(description = "Contact person's name", example = "John Doe")
  private String name;

  @Column(name = "document_type")
  @Schema(
      description = "Type of identification document (0: ID Card, 1: Passport, 2: Other)",
      example = "0")
  private int documentType;

  @Column(name = "document_number")
  @Schema(description = "Document number", example = "110101199001011234")
  private String documentNumber;

  @Column(name = "phone_number")
  @Schema(description = "Phone number", example = "+86-13800138000")
  private String phoneNumber;

  public Contacts() {
    // Default Constructor
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    Contacts other = (Contacts) obj;
    return name.equals(other.getName())
        && accountId.equals(other.getAccountId())
        && documentNumber.equals(other.getDocumentNumber())
        && phoneNumber.equals(other.getPhoneNumber())
        && documentType == other.getDocumentType();
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + (id == null ? 0 : id.hashCode());
    return result;
  }
}
