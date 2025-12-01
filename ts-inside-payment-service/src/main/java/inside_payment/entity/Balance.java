package inside_payment.entity;

import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * @author fdse
 */
@Data
public class Balance {
    @Valid
    @NotNull
    private String userId;

    @Valid
    @NotNull
    private String balance; //NOSONAR

    public Balance(){
        //Default Constructor
        this.userId = "";
        this.balance = "";
    }

}
