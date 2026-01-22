package fdse.microservice.service;

import edu.fudan.common.util.Response;
import fdse.microservice.entity.Station;
import java.util.List;
import org.springframework.http.HttpHeaders;

public interface StationService {
  // CRUD
  Response create(Station info, HttpHeaders headers);

  boolean exist(String stationName, HttpHeaders headers);

  Response update(Station info, HttpHeaders headers);

  Response delete(String stationsId, HttpHeaders headers);

  Response query(HttpHeaders headers);

  Response queryForId(String stationName, HttpHeaders headers);

  Response queryForIdBatch(List<String> nameList, HttpHeaders headers);

  Response queryById(String stationId, HttpHeaders headers);

  Response queryByIdBatch(List<String> stationIdList, HttpHeaders headers);
}
