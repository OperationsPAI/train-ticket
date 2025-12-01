package edu.fudan.common.util;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * @author fdse
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Schema(description = "Standard API response wrapper")
public class Response<T> {

    /**
     * 1 true, 0 false
     */
    @Schema(description = "Response status: 1 for success, 0 for failure", example = "1")
    Integer status;

    @Schema(description = "Response message", example = "Operation successful")
    String msg;
    
    @Schema(description = "Response data payload")
    T data;
}
