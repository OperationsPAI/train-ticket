package edu.fudan.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.fudan.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * @author fdse
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
@Schema(description = "Order entity representing a train ticket order")
public class Order {

    @Schema(description = "Order ID", example = "d1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String id;

    @Schema(description = "Date when the ticket was bought", example = "2024-01-15")
    private String boughtDate;

    @Schema(description = "Date of travel", example = "2024-02-01")
    private String travelDate;

    @Schema(description = "Time of travel", example = "08:30:00")
    private String travelTime;

    @Schema(description = "Account ID of the buyer", example = "user-12345")
    private String accountId;

    @Schema(description = "Name of the contact/passenger", example = "John Doe")
    private String contactsName;

    @Schema(description = "Document type: 0-ID Card, 1-Passport, 2-Other", example = "0")
    private int documentType;

    @Schema(description = "Contact's document number", example = "ID1234567890")
    private String contactsDocumentNumber;

    @Schema(description = "Train number", example = "G1234")
    private String trainNumber;

    @Schema(description = "Coach/carriage number", example = "5")
    private int coachNumber;

    @Schema(description = "Seat class: 2-First Class, 3-Second Class", example = "2")
    private int seatClass;

    @Schema(description = "Seat number", example = "15")
    private int seatNumber;

    @Schema(description = "Departure station", example = "shanghai")
    private String from;

    @Schema(description = "Arrival station", example = "beijing")
    private String to;

    @Schema(description = "Order status: 0-Not Paid, 1-Paid, 2-Collected, 3-Cancelled, 4-Refunded, 5-Used", example = "1")
    private int status;

    @Schema(description = "Ticket price", example = "553.00")
    private String price;

    @Schema(description = "Price difference for rebooking", example = "0.00")
    private String differenceMoney;

    public Order(){
        boughtDate = StringUtils.Date2String(new Date(System.currentTimeMillis()));
        travelDate = StringUtils.Date2String(new Date(123456789));
        trainNumber = "G1235";
        coachNumber = 5;
        seatClass = SeatClass.FIRSTCLASS.getCode();
        seatNumber = 2;
        from = "shanghai";
        to = "taiyuan";
        status = OrderStatus.PAID.getCode();
        price = "0.0";
        differenceMoney ="0.0";
    }

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
        Order other = (Order) obj;
        return getBoughtDate().equals(other.getBoughtDate())
                && getBoughtDate().equals(other.getTravelDate())
                && getTravelTime().equals(other.getTravelTime())
                && accountId .equals( other.getAccountId() )
                && contactsName.equals(other.getContactsName())
                && contactsDocumentNumber.equals(other.getContactsDocumentNumber())
                && documentType == other.getDocumentType()
                && trainNumber.equals(other.getTrainNumber())
                && coachNumber == other.getCoachNumber()
                && seatClass == other.getSeatClass()
                && seatNumber == other.getSeatNumber()
                && from.equals(other.getFrom())
                && to.equals(other.getTo())
                && status == other.getStatus()
                && price.equals(other.price);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (id == null ? 0 : id.hashCode());
        return result;
    }

}
