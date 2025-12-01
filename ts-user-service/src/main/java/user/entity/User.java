package user.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.util.UUID;

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
