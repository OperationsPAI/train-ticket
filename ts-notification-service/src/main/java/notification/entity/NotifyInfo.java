package notification.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.persistence.Column;
import jakarta.persistence.Id;


import jakarta.persistence.Entity;
import org.hibernate.annotations.GenericGenerator;
import java.util.UUID;

import lombok.Data;

import jakarta.persistence.Entity;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
@Entity
@GenericGenerator(name = "jpa-uuid", strategy = "org.hibernate.id.UUIDGenerator")
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotifyInfo {

    public NotifyInfo(){
        //Default Constructor
    }

    @Id
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Column(length = 36)
    private String id;

    private Boolean sendStatus;

    private String email;
    private String orderNumber;
    private String username;
    private String startPlace;
    private String endPlace;
    private String startTime;
    private String date;
    private String seatClass;
    private int seatNumber;
    private String price;

}
