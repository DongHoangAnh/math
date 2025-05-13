package spring.api.mentalmathpk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import spring.api.mentalmathpk.entity.GameState;

public interface GameStateRepository extends JpaRepository<GameState,String> {
    
}
