package order.controller;

import static org.springframework.http.ResponseEntity.ok;

import edu.fudan.common.entity.Seat;
import edu.fudan.common.util.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import order.entity.Order;
import order.entity.OrderInfo;
import order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/api/v1/orderservice")
@Tag(name = "Order", description = "Order management APIs for train ticket orders")
public class OrderController {

  @Autowired private OrderService orderService;

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

  @GetMapping(path = "/welcome")
  @Operation(
      summary = "Welcome endpoint",
      description = "Returns a welcome message for the Order Service")
  public String home() {
    return "Welcome to [ Order Service ] !";
  }

  /***************************For Normal Use***************************/

  @Operation(
      summary = "Get sold tickets",
      description = "Get list of sold tickets by date and trip")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved sold tickets")
      })
  @PostMapping(value = "/order/tickets")
  public HttpEntity getTicketListByDateAndTripId(
      @Parameter(description = "Seat request information") @RequestBody Seat seatRequest,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info(
        "[getSoldTickets][Get Sold Ticket][Travel Date: {}]",
        seatRequest.getTravelDate().toString());
    return ok(orderService.getSoldTickets(seatRequest, headers));
  }

  @Operation(summary = "Create new order", description = "Create a new train ticket order")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Order created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid order data")
      })
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/order")
  public HttpEntity createNewOrder(
      @Parameter(description = "Order details") @RequestBody Order createOrder,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info(
        "[createNewOrder][Create Order][from {} to {} at {}]",
        createOrder.getFrom(),
        createOrder.getTo(),
        createOrder.getTravelDate());
    return ok(orderService.create(createOrder, headers));
  }

  @Operation(
      summary = "Admin create order",
      description = "Create a new order with admin privileges")
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/order/admin")
  public HttpEntity addcreateNewOrder(
      @Parameter(description = "Order details") @RequestBody Order order,
      @RequestHeader HttpHeaders headers) {
    return ok(orderService.addNewOrder(order, headers));
  }

  @Operation(summary = "Query orders", description = "Query orders for a specific user")
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/order/query")
  public HttpEntity queryOrders(
      @Parameter(description = "Query criteria") @RequestBody OrderInfo qi,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[queryOrders][Query Orders][for LoginId :{}]", qi.getLoginId());
    return ok(orderService.queryOrders(qi, qi.getLoginId(), headers));
  }

  @Operation(
      summary = "Refresh orders query",
      description = "Query orders for refresh with updated status")
  @CrossOrigin(origins = "*")
  @PostMapping(path = "/order/refresh")
  public HttpEntity queryOrdersForRefresh(
      @Parameter(description = "Query criteria") @RequestBody OrderInfo qi,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info(
        "[queryOrdersForRefresh][Query Orders][for LoginId:{}]", qi.getLoginId());
    return ok(orderService.queryOrdersForRefresh(qi, qi.getLoginId(), headers));
  }

  @Operation(
      summary = "Calculate sold tickets",
      description = "Get sold tickets count by date and train number")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/{travelDate}/{trainNumber}")
  public HttpEntity calculateSoldTicket(
      @Parameter(description = "Travel date (yyyy-MM-dd)") @PathVariable String travelDate,
      @Parameter(description = "Train number") @PathVariable String trainNumber,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info(
        "[queryAlreadySoldOrders][Calculate Sold Tickets][Date: {} TrainNumber: {}]",
        travelDate,
        trainNumber);
    return ok(
        orderService.queryAlreadySoldOrders(
            StringUtils.String2Date(travelDate), trainNumber, headers));
  }

  @Operation(summary = "Get order price", description = "Get the price of a specific order")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/price/{orderId}")
  public HttpEntity getOrderPrice(
      @Parameter(description = "Order ID") @PathVariable String orderId,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[getOrderPrice][Get Order Price][OrderId: {}]", orderId);
    return ok(orderService.getOrderPrice(orderId, headers));
  }

  @Operation(summary = "Pay order", description = "Mark an order as paid")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/orderPay/{orderId}")
  public HttpEntity payOrder(
      @Parameter(description = "Order ID") @PathVariable String orderId,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[payOrder][Pay Order][OrderId: {}]", orderId);
    return ok(orderService.payOrder(orderId, headers));
  }

  @Operation(summary = "Get order by ID", description = "Retrieve a specific order by its ID")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/{orderId}")
  public HttpEntity getOrderById(
      @Parameter(description = "Order ID") @PathVariable String orderId,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[getOrderById][Get Order By Id][OrderId: {}]", orderId);
    return ok(orderService.getOrderById(orderId, headers));
  }

  @Operation(summary = "Modify order status", description = "Update the status of an order")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/status/{orderId}/{status}")
  public HttpEntity modifyOrder(
      @Parameter(description = "Order ID") @PathVariable String orderId,
      @Parameter(description = "New status code") @PathVariable int status,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[modifyOrder][Modify Order Status][OrderId: {}]", orderId);
    return ok(orderService.modifyOrder(orderId, status, headers));
  }

  @Operation(
      summary = "Security check",
      description = "Check security information about orders for an account")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order/security/{checkDate}/{accountId}")
  public HttpEntity securityInfoCheck(
      @Parameter(description = "Check date (yyyy-MM-dd)") @PathVariable String checkDate,
      @Parameter(description = "Account ID") @PathVariable String accountId,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info(
        "[checkSecurityAboutOrder][Security Info Get][AccountId:{}]", accountId);
    return ok(
        orderService.checkSecurityAboutOrder(
            StringUtils.String2Date(checkDate), accountId, headers));
  }

  @Operation(summary = "Update order", description = "Save/update order information")
  @CrossOrigin(origins = "*")
  @PutMapping(path = "/order")
  public HttpEntity saveOrderInfo(
      @Parameter(description = "Updated order information") @RequestBody Order orderInfo,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[saveChanges][Save Order Info][OrderId:{}]", orderInfo.getId());
    return ok(orderService.saveChanges(orderInfo, headers));
  }

  @Operation(summary = "Admin update order", description = "Update order with admin privileges")
  @CrossOrigin(origins = "*")
  @PutMapping(path = "/order/admin")
  public HttpEntity updateOrder(
      @Parameter(description = "Updated order") @RequestBody Order order,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[updateOrder][Update Order][OrderId: {}]", order.getId());
    return ok(orderService.updateOrder(order, headers));
  }

  @Operation(summary = "Delete order", description = "Delete an order by ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Order deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Order not found")
      })
  @CrossOrigin(origins = "*")
  @DeleteMapping(path = "/order/{orderId}")
  public HttpEntity deleteOrder(
      @Parameter(description = "Order ID to delete") @PathVariable String orderId,
      @RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[deleteOrder][Delete Order][OrderId: {}]", orderId);
    return ok(orderService.deleteOrder(orderId, headers));
  }

  /***************For super admin(Single Service Test*******************/

  @Operation(summary = "Get all orders", description = "Retrieve all orders in the system (admin)")
  @CrossOrigin(origins = "*")
  @GetMapping(path = "/order")
  public HttpEntity findAllOrder(@RequestHeader HttpHeaders headers) {
    OrderController.LOGGER.info("[getAllOrders][Find All Order]");
    return ok(orderService.getAllOrders(headers));
  }
}
