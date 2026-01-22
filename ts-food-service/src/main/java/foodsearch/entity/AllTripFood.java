package foodsearch.entity;

import edu.fudan.common.entity.Food;
import edu.fudan.common.entity.StationFoodStore;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class AllTripFood {

  private List<Food> trainFoodList;

  private Map<String, List<StationFoodStore>> foodStoreListMap;

  public AllTripFood() {
    // Default Constructor
  }
}
