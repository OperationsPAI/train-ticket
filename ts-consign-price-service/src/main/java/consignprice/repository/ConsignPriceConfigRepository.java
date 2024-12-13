package consignprice.repository;

import consignprice.entity.ConsignPrice;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * @author fdse
 */
@Repository
public interface ConsignPriceConfigRepository extends CrudRepository<ConsignPrice, String> {

    /**
     * find by index
     *
     * @param index index
     * @return ConsignPrice
     */
    // @Query("{ 'index': ?0 }")
    ConsignPrice findByIndex(int index);

    @Query("SELECT MAX(c.index) FROM ConsignPrice c")
    Integer findMaxIndex();
}
