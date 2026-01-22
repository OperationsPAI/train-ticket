package contacts.controller;

import static org.springframework.http.ResponseEntity.ok;

import contacts.entity.Contacts;
import contacts.service.ContactsService;
import edu.fudan.common.util.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author fdse
 */
@RestController
@RequestMapping("api/v1/contactservice")
@Tag(name = "Contacts", description = "Contacts management APIs")
public class ContactsController {

  @Autowired private ContactsService contactsService;

  private static final Logger LOGGER = LoggerFactory.getLogger(ContactsController.class);

  @Operation(summary = "Welcome endpoint", description = "Returns a welcome message")
  @GetMapping(path = "/contacts/welcome")
  public String home() {
    return "Welcome to [ Contacts Service ] !";
  }

  @Operation(summary = "Get all contacts", description = "Retrieves all contacts from the system")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved all contacts")
      })
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/contacts")
  public HttpEntity getAllContacts(@RequestHeader HttpHeaders headers) {
    ContactsController.LOGGER.info("[getAllContacts][Get All Contacts]");
    return ok(contactsService.getAllContacts(headers));
  }

  @Operation(summary = "Create new contact", description = "Creates a new contact entry")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Contact created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input data")
      })
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/contacts")
  public ResponseEntity<Response> createNewContacts(
      @Parameter(description = "Contact information") @RequestBody Contacts aci,
      @RequestHeader HttpHeaders headers) {
    ContactsController.LOGGER.info("[createNewContacts][VerifyLogin Success]");
    return new ResponseEntity<>(contactsService.create(aci, headers), HttpStatus.CREATED);
  }

  @Operation(
      summary = "Create contact (Admin)",
      description = "Creates a new contact with admin privileges")
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/contacts/admin")
  public HttpEntity<?> createNewContactsAdmin(
      @Parameter(description = "Contact information") @RequestBody Contacts aci,
      @RequestHeader HttpHeaders headers) {
    aci.setId(UUID.randomUUID().toString());
    ContactsController.LOGGER.info("[createNewContactsAdmin][Create Contacts In Admin]");
    return new ResponseEntity<>(contactsService.createContacts(aci, headers), HttpStatus.CREATED);
  }

  @Operation(summary = "Delete contact", description = "Deletes a contact by ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Contact deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Contact not found")
      })
  @CrossOrigin(origins = "*")
  @DeleteMapping(path = "/contacts/{contactsId}")
  public HttpEntity deleteContacts(
      @Parameter(description = "Contact ID to delete") @PathVariable String contactsId,
      @RequestHeader HttpHeaders headers) {
    return ok(contactsService.delete(contactsId, headers));
  }

  @Operation(summary = "Modify contact", description = "Updates an existing contact")
  @CrossOrigin(origins = "*")
  @PutMapping(path = "/contacts")
  public HttpEntity modifyContacts(
      @Parameter(description = "Updated contact information") @RequestBody Contacts info,
      @RequestHeader HttpHeaders headers) {
    ContactsController.LOGGER.info(
        "[Contacts modifyContacts][Modify Contacts] ContactsId: {}", info.getId());
    return ok(contactsService.modify(info, headers));
  }

  @Operation(
      summary = "Find contacts by account ID",
      description = "Retrieves all contacts for a specific account")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/contacts/account/{accountId}")
  public HttpEntity findContactsByAccountId(
      @Parameter(description = "Account ID") @PathVariable String accountId,
      @RequestHeader HttpHeaders headers) {
    ContactsController.LOGGER.info(
        "[findContactsByAccountId][Find Contacts By Account Id][accountId: {}]", accountId);
    ContactsController.LOGGER.info("[ContactsService][VerifyLogin Success]");
    return ok(contactsService.findContactsByAccountId(accountId, headers));
  }

  @Operation(summary = "Get contact by ID", description = "Retrieves a specific contact by its ID")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/contacts/{id}")
  public HttpEntity getContactsByContactsId(
      @Parameter(description = "Contact ID") @PathVariable String id,
      @RequestHeader HttpHeaders headers) {
    ContactsController.LOGGER.info("[ContactsService][Contacts Id Print][id: {}]", id);
    ContactsController.LOGGER.info("[ContactsService][VerifyLogin Success]");
    return ok(contactsService.findContactsById(id, headers));
  }
}
