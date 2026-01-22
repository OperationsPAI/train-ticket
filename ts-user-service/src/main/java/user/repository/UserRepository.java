package user.repository;

import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import user.entity.User;

/**
 * @author fdse
 */
@Repository
public interface UserRepository extends CrudRepository<User, String> {

  User findByUserName(String userName);

  User findByUserId(String userId);

  void deleteByUserId(String userId);

  @Override
  List<User> findAll();
}
