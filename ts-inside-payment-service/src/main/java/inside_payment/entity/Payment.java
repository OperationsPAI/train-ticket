package inside_payment.entity;

import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
//import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * @author fdse
 */
@Data
@Entity
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
@Table(name="inside_payment")
public class Payment {
    @Id
    @NotNull
    @Column(length = 36)
    @GeneratedValue(generator = "jpa-uuid")
    private String id;

    @NotNull
    @Valid
    @Column(length = 36)
    private String orderId;

    @NotNull
    @Valid
    @Column(length = 36)
    private String userId;

    @NotNull
    @Valid
    private String price;

    @NotNull
    @Valid
    @Enumerated(EnumType.STRING)
    private PaymentType type;

    public Payment(){
        this.orderId = "";
        this.userId = "";
        this.price = "";
    }

}
