package edu.fudan.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * @author fdse
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Contact/Passenger information entity")
public class Contacts {

    @Schema(description = "Contact ID")
    private UUID id;

    @Schema(description = "Associated account ID")
    private UUID accountId;

    @Schema(description = "Contact name", example = "John Doe")
    private String name;

    @Schema(description = "Document type: 0-ID Card, 1-Passport, 2-Other", example = "0")
    private int documentType;

    @Schema(description = "Document number", example = "ID1234567890")
    private String documentNumber;

    @Schema(description = "Phone number", example = "+86-13800138000")
    private String phoneNumber;

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
                && accountId .equals( other.getAccountId() )
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
