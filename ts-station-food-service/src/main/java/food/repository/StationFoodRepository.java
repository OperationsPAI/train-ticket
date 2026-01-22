package food.repository;

import food.entity.StationFoodStore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StationFoodRepository extends CrudRepository<StationFoodStore, String> {

  @Override
  Optional<StationFoodStore> findById(String id);

  List<StationFoodStore> findByStationName(String stationName);

  List<StationFoodStore> findByStationNameIn(List<String> stationNames);

  @Override
  List<StationFoodStore> findAll();

  @Override
  void deleteById(String id);
}
