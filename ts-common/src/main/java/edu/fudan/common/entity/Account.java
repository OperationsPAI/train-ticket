package edu.fudan.common.entity;

import java.util.UUID;
import lombok.Data;

/**
 * @author fdse
 */
@Data
public class Account {

  private UUID id;

  private String accountId;

  private String loginId;

  private String password;

  private int gender;

  private String name;

  private int documentType;

  private String documentNum;

  private String email;

  public Account() {
    gender = Gender.OTHER.getCode();
    password = "defaultPassword"; // NOSONAR
    name = "None";
    documentType = DocumentType.NONE.getCode();
    documentNum = "0123456789";
    email = "0123456789";
  }
}
