package notification.entity;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * @author fdse
 */
@Data
public class Mail {

  private String mailFrom;

  private String mailTo;

  private String mailCc;

  private String mailBcc;

  private String mailSubject;

  private String mailContent;

  private String contentType;

  private List<Object> attachments;

  private Map<String, Object> model;

  public Mail() {
    contentType = "text/plain";
  }
}
