package spring.api.mentalmathpk.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import spring.api.mentalmathpk.controller.GameWebSocketHandler;
import spring.api.mentalmathpk.entity.GameState;
import spring.api.mentalmathpk.repository.GameStateRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class GameService {

    private final Map<String, String> matches = new ConcurrentHashMap<>();
    private final Map<String, GameState> gameStates = new ConcurrentHashMap<>();
    private final Queue<String> waitingPlayers = new LinkedList<>();
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final GameWebSocketHandler webSocketHandler;
    private final Map<String, ReentrantLock> matchLocks = new ConcurrentHashMap<>();
    private final GameStateRepository gameStateRepository;

    public GameService(@Lazy GameWebSocketHandler webSocketHandler, GameStateRepository gameStateRepository) {
        this.webSocketHandler = webSocketHandler;
        this.gameStateRepository = gameStateRepository;
    }

    public void handlePlayerJoin(String playerName, WebSocketSession session) {
        waitingPlayers.offer(playerName);
        if (waitingPlayers.size() >= 2) {
            String player1 = waitingPlayers.poll();
            String player2 = waitingPlayers.poll();

            matches.put(player1, player2);
            matches.put(player2, player1);

            GameState gameState = new GameState(player1, player2);
            String matchKey = player1.compareTo(player2) < 0 ? player1 + "-" + player2 : player2 + "-" + player1;
            gameStates.put(matchKey, gameState);
            matchLocks.put(matchKey, new ReentrantLock());

            // Generate 10 comparisons
            List<Map<String, Object>> comparisons = new ArrayList<>();
            for (int i = 0; i < gameState.getMaxComparisons(); i++) {
                int num1 = random.nextInt(10);
                int num2 = random.nextInt(10);
                String operator = num1 > num2 ? ">" : num1 < num2 ? "<" : "=";
                Map<String, Object> comparison = new HashMap<>();
                comparison.put("num1", num1);
                comparison.put("num2", num2);
                comparison.put("operator", operator);
                comparisons.add(comparison);
            }
            gameState.getComparisons().addAll(comparisons);

            System.out.println("Match created: " + player1 + " vs " + player2);
            sendNextComparison(matchKey);
        } else {
            webSocketHandler.sendMessage(playerName, "{\"type\":\"waiting\",\"message\":\"Waiting for another player...\"}");
        }
    }

    public void handlePlayerMessage(String playerName, Map<String, Object> payload) {
        String type = (String) payload.get("type");
        if ("answer".equals(type)) {
            String answer = (String) payload.get("answer");
            if (!Arrays.asList("<", ">", "=").contains(answer)) {
                System.out.println("Invalid answer from " + playerName + ": " + answer);
                sendMessage(playerName, "{\"type\":\"error\",\"message\":\"Invalid answer. Draw <, >, or =.\"}");
                return;
            }

            String opponent = matches.get(playerName);
            if (opponent == null) {
                System.out.println("No opponent found for " + playerName);
                sendMessage(playerName, "{\"type\":\"error\",\"message\":\"No opponent found.\"}");
                return;
            }

            String matchKey = playerName.compareTo(opponent) < 0 ? playerName + "-" + opponent : opponent + "-" + playerName;
            ReentrantLock lock = matchLocks.get(matchKey);
            if (lock == null) {
                System.out.println("No lock found for " + matchKey);
                return;
            }

            lock.lock();
            try {
                GameState gameState = gameStates.get(matchKey);
                if (gameState == null || gameState.getCompleted().get(playerName)) {
                    System.out.println("Game not found or player completed for " + matchKey);
                    sendMessage(playerName, "{\"type\":\"error\",\"message\":\"Game already completed or not found.\"}");
                    return;
                }

                long timeElapsed = System.currentTimeMillis() - gameState.getGameStartTime();
                if (timeElapsed > 60000) {
                    System.out.println("Game time up for " + playerName);
                    endGame(playerName, opponent, matchKey);
                    return;
                }

                int index = gameState.getCurrentComparisonIndex();
                if (index >= gameState.getMaxComparisons()) {
                    gameState.getCompleted().put(playerName, true);
                    gameState.getCompletionTimes().put(playerName, timeElapsed);
                    sendMessage(playerName, "{\"type\":\"result\",\"message\":\"Waiting for opponent to finish.\",\"correct\":false}");
                    if (gameState.getCompleted().get(opponent)) {
                        endGame(playerName, opponent, matchKey);
                    }
                    return;
                }

                Map<String, Object> comparison = gameState.getComparisons().get(index);
                int num1 = (int) comparison.get("num1");
                int num2 = (int) comparison.get("num2");
                String correctOperator = (String) comparison.get("operator");

                boolean isCorrect = answer.equals(correctOperator);
                if (isCorrect) {
                    gameState.getCorrectCounts().put(playerName, gameState.getCorrectCounts().get(playerName) + 1);
                    sendMessage(playerName, "{\"type\":\"result\",\"message\":\"Correct!\",\"correct\":true}");
                } else {
                    sendMessage(playerName, "{\"type\":\"result\",\"message\":\"Incorrect! Correct answer was " + correctOperator + ".\",\"correct\":false}");
                }

                System.out.println(playerName + " answered comparison " + num1 + " vs " + num2 + ": " + answer + " (" + (isCorrect ? "Correct" : "Incorrect") + ")");

                gameState.incrementComparisonIndex();
                if (gameState.getCurrentComparisonIndex() >= gameState.getMaxComparisons()) {
                    gameState.getCompleted().put(playerName, true);
                    gameState.getCompletionTimes().put(playerName, timeElapsed);
                    sendMessage(playerName, "{\"type\":\"result\",\"message\":\"Waiting for opponent to finish.\",\"correct\":false}");
                    if (gameState.getCompleted().get(opponent)) {
                        endGame(playerName, opponent, matchKey);
                    }
                } else {
                    sendNextComparisonToPlayer(playerName, matchKey);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public void handlePlayerDisconnect(String playerName) {
        waitingPlayers.remove(playerName);
        String opponent = matches.remove(playerName);
        if (opponent != null) {
            matches.remove(opponent);
            String matchKey = playerName.compareTo(opponent) < 0 ? playerName + "-" + opponent : opponent + "-" + playerName;
            gameStates.remove(matchKey);
            matchLocks.remove(matchKey);
            sendMessage(opponent, "{\"type\":\"disconnected\",\"message\":\"Opponent disconnected.\"}");
        }
    }

    private void sendMessage(String playerName, String message) {
        webSocketHandler.sendMessage(playerName, message);
    }

    private void sendNextComparison(String matchKey) {
        ReentrantLock lock = matchLocks.get(matchKey);
        if (lock == null) {
            System.out.println("No lock found for " + matchKey);
            return;
        }

        lock.lock();
        try {
            GameState gameState = gameStates.get(matchKey);
            if (gameState == null) {
                System.out.println("Cannot send comparison for " + matchKey + ": Game state invalid");
                return;
            }

            if (gameState.getGameStartTime() == 0) {
                gameState.setGameStartTime(System.currentTimeMillis());
                scheduler.schedule(() -> {
                    ReentrantLock timerLock = matchLocks.get(matchKey);
                    if (timerLock == null) return;
                    timerLock.lock();
                    try {
                        if (gameStates.containsKey(matchKey)) {
                            endGame(gameState.getPlayer1(), gameState.getPlayer2(), matchKey);
                        }
                    } finally {
                        timerLock.unlock();
                    }
                }, 60, TimeUnit.SECONDS);
            }

            sendNextComparisonToPlayer(gameState.getPlayer1(), matchKey);
            sendNextComparisonToPlayer(gameState.getPlayer2(), matchKey);
        } finally {
            lock.unlock();
        }
    }

    private void sendNextComparisonToPlayer(String playerName, String matchKey) {
        ReentrantLock lock = matchLocks.get(matchKey);
        if (lock == null) return;
        lock.lock();
        try {
            GameState gameState = gameStates.get(matchKey);
            if (gameState == null || gameState.getCompleted().get(playerName)) return;

            int index = gameState.getCurrentComparisonIndex();
            if (index >= gameState.getMaxComparisons()) return;

            Map<String, Object> comparison = gameState.getComparisons().get(index);
            int num1 = (int) comparison.get("num1");
            int num2 = (int) comparison.get("num2");
            String comparisonText = num1 + " vs " + num2;
            long timeLeft = 60000 - (System.currentTimeMillis() - gameState.getGameStartTime());
            timeLeft = Math.max(timeLeft / 1000, 0);

            String message = "{\"type\":\"comparison\",\"comparison\":\"" + comparisonText + "\",\"time\":" + timeLeft + ",\"total\":" + gameState.getMaxComparisons() + "}";
            sendMessage(playerName, message);
            System.out.println("Comparison sent to " + playerName + " for " + matchKey + ": " + comparisonText);
        } finally {
            lock.unlock();
        }
    }

    private void endGame(String player1, String player2, String matchKey) {
        ReentrantLock lock = matchLocks.get(matchKey);
        if (lock == null) return;
        lock.lock();
        try {
            GameState gameState = gameStates.get(matchKey);
            if (gameState == null) return;

            int correct1 = gameState.getCorrectCounts().get(player1);
            int correct2 = gameState.getCorrectCounts().get(player2);
            long time1 = gameState.getCompletionTimes().getOrDefault(player1, 60000L);
            long time2 = gameState.getCompletionTimes().getOrDefault(player2, 60000L);

            String resultMessage;
            String winner = null;
            if (correct1 > correct2) {
                resultMessage = "Game over! " + player1 + ": " + correct1 + " correct, " + player2 + ": " + correct2 + " correct. " + player1 + " wins!";
                winner = player1;
            } else if (correct2 > correct1) {
                resultMessage = "Game over! " + player1 + ": " + correct1 + " correct, " + player2 + ": " + correct2 + " correct. " + player2 + " wins!";
                winner = player2;
            } else {
                if (time1 < time2) {
                    resultMessage = "Game over! Both have " + correct1 + " correct. " + player1 + " finished faster (" + (time1 / 1000) + "s vs " + (time2 / 1000) + "s). " + player1 + " wins!";
                    winner = player1;
                } else if (time2 < time1) {
                    resultMessage = "Game over! Both have " + correct1 + " correct. " + player2 + " finished faster (" + (time2 / 1000) + "s vs " + (time1 / 1000) + "s). " + player2 + " wins!";
                    winner = player2;
                } else {
                    resultMessage = "Game over! Both have " + correct1 + " correct and same time (" + (time1 / 1000) + "s). It's a tie!";
                }
            }

            // Lưu người thắng và lưu vào cơ sở dữ liệu
            gameState.setWinner(winner);
            gameStateRepository.save(gameState); // Lưu GameState vào cơ sở dữ liệu

            // Lấy danh sách người thắng từ cơ sở dữ liệu
            List<GameState> allGames = gameStateRepository.findAll();
            List<String> winners = allGames.stream()
                    .filter(game -> game.getWinner() != null)
                    .map(GameState::getWinner)
                    .distinct() // Loại bỏ các người thắng trùng lặp
                    .limit(10) // Giới hạn 10 người thắng gần nhất
                    .toList();

            // Gửi kết quả và danh sách người thắng
            String winnersList = String.join(",", winners);
            String message = "{\"type\":\"gameover\",\"message\":\"" + resultMessage + "\",\"winners\":[\"" + winnersList + "\"]}";
            sendMessage(player1, message);
            sendMessage(player2, message);

            matches.remove(player1);
            matches.remove(player2);
            gameStates.remove(matchKey);
            matchLocks.remove(matchKey);
            System.out.println("Game ended for " + player1 + " and " + player2 + ": " + resultMessage);
        } finally {
            lock.unlock();
        }
    }
}