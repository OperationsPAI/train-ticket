package foodsearch.repository;

import foodsearch.entity.FoodOrder;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FoodOrderRepository extends CrudRepository<FoodOrder, String> {

    Optional<FoodOrder> findById(String id);

    FoodOrder findByOrderId(String orderId);

    @Override
    List<FoodOrder> findAll();

    @Override
    void deleteById(String id);

    void deleteFoodOrderByOrderId(String id);

}
