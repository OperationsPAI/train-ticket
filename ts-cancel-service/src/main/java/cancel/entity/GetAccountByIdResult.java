package cancel.entity;

import edu.fudan.common.entity.Account;
import lombok.Data;

/**
 * @author fdse
 */
@Data
public class GetAccountByIdResult {

  private boolean status;

  private String message;

  private Account account;

  public GetAccountByIdResult() {
    // Default Constructor
  }
}
