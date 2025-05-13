package spring.api.mentalmathpk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import spring.api.mentalmathpk.service.GameService;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final GameService gameService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameWebSocketHandler(GameService gameService) {
        this.gameService = gameService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("Connection attempt: URI=" + session.getUri());
        String playerName = UUID.randomUUID().toString();
        session.getAttributes().put("playerName", playerName);
        sessions.put(playerName, session);
        System.out.println("Player connected: " + playerName + ", Session ID: " + session.getId());
        gameService.handlePlayerJoin(playerName, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String playerName = getPlayerName(session);
        if (!isSessionValid(playerName) || !sessions.get(playerName).getId().equals(session.getId())) {
            System.out.println("Invalid session for player: " + playerName + ", Session ID: " + session.getId() + ", Expected Session ID: " + (sessions.get(playerName) != null ? sessions.get(playerName).getId() : "none"));
            return;
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
            System.out.println("Message received from " + playerName + ": " + message.getPayload());
            gameService.handlePlayerMessage(playerName, payload);
        } catch (Exception e) {
            System.out.println("Invalid message format from " + playerName + ": " + message.getPayload());
            sendMessage(playerName, "{\"type\":\"error\",\"message\":\"Invalid message format.\"}");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String playerName = getPlayerName(session);
        sessions.remove(playerName);
        gameService.handlePlayerDisconnect(playerName);
        System.out.println("Player disconnected: " + playerName + ", Session ID: " + session.getId() + ", Status: " + status);
    }

    private String getPlayerName(WebSocketSession session) {
        return session.getAttributes().computeIfAbsent("playerName", k -> UUID.randomUUID().toString()).toString();
    }

    private boolean isSessionValid(String playerName) {
        WebSocketSession session = sessions.get(playerName);
        return session != null && session.isOpen();
    }

    public void sendMessage(String playerName, String message) {
        WebSocketSession session = sessions.get(playerName);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
                System.out.println("Sent to " + playerName + ": " + message);
            } catch (IOException e) {
                System.out.println("Error sending message to " + playerName + ": " + e.getMessage());
                sessions.remove(playerName);
                gameService.handlePlayerDisconnect(playerName);
            }
        } else {
            System.out.println("Cannot send message to " + playerName + ": Session invalid or closed");
        }
    }
}