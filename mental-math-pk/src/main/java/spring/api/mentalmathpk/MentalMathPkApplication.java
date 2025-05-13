package spring.api.mentalmathpk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import spring.api.mentalmathpk.controller.GameWebSocketHandler;
import spring.api.mentalmathpk.service.GameService;

@SpringBootApplication
@EnableWebSocket
public class MentalMathPkApplication implements WebSocketConfigurer {

    private final GameService gameService;

    public MentalMathPkApplication(GameService gameService) {
        this.gameService = gameService;
    }

    @Bean
    public GameWebSocketHandler gameWebSocketHandler() {
        return new GameWebSocketHandler(gameService);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler(), "/ws/game")
                .setAllowedOrigins("*");
    }

    public static void main(String[] args) {
        SpringApplication.run(MentalMathPkApplication.class, args);
    }

    @Configuration
    public class WebConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/favicon.ico").addResourceLocations("classpath:/static/");
        }
    }
}