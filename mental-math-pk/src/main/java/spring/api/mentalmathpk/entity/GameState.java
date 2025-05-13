package spring.api.mentalmathpk.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.*;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GameState {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    String id;
    private String player1;
    private String player2;
    private int currentComparisonIndex;
    private final int maxComparisons = 10;
    @Transient
    private Map<String, Integer> correctCounts;
    @Transient
    private Map<String, Long> completionTimes;
    @Transient
    private Map<String, Boolean> completed;
    @Transient
    private List<Map<String, Object>> comparisons;
    private long gameStartTime;
    private String winner;

    public GameState(String player1, String player2) {
        this.player1 = player1;
        this.player2 = player2;
        this.currentComparisonIndex = 0;
        this.correctCounts = new HashMap<>();
        this.correctCounts.put(player1, 0);
        this.correctCounts.put(player2, 0);
        this.completionTimes = new HashMap<>();
        this.completed = new HashMap<>();
        this.completed.put(player1, false);
        this.completed.put(player2, false);
        this.comparisons = new ArrayList<>();
        this.gameStartTime = 0;
    }

    public String getPlayer1() {
        return player1;
    }

    public String getPlayer2() {
        return player2;
    }

    public int getCurrentComparisonIndex() {
        return currentComparisonIndex;
    }

    public void incrementComparisonIndex() {
        this.currentComparisonIndex++;
    }

    public int getMaxComparisons() {
        return maxComparisons;
    }

    public Map<String, Integer> getCorrectCounts() {
        return correctCounts;
    }

    public Map<String, Long> getCompletionTimes() {
        return completionTimes;
    }

    public Map<String, Boolean> getCompleted() {
        return completed;
    }

    public List<Map<String, Object>> getComparisons() {
        return comparisons;
    }

    public long getGameStartTime() {
        return gameStartTime;
    }

    public void setGameStartTime(long gameStartTime) {
        this.gameStartTime = gameStartTime;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }
}