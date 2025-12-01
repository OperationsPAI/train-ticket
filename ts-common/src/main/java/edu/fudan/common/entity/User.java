package edu.fudan.common.entity;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * @author fdse
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "User entity representing a system user")
public class User {
    @Schema(description = "Unique user ID", example = "d1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private UUID userId;

    @Schema(description = "Username for login", example = "john_doe")
    private String userName;

    @Schema(description = "User password (hashed)", example = "********", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    @Schema(description = "Gender: 0-Female, 1-Male, 2-Other", example = "1")
    private int gender;

    @Schema(description = "Document type: 0-ID Card, 1-Passport, 2-Other", example = "0")
    private int documentType;

    @Schema(description = "Document number", example = "ID1234567890")
    private String documentNum;

    @Schema(description = "Email address", example = "john@example.com")
    private String email;

}
