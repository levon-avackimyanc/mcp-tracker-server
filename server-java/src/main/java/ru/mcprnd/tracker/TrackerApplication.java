package ru.mcprnd.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP-сервер трекера задач.
 *
 * <p>Одна и та же доменная логика публикуется в двух вариантах контракта,
 * которые выбираются профилем Spring:
 * <ul>
 *   <li>{@code v1} — описания тулов «как javadoc», ошибки без подсказок;</li>
 *   <li>{@code v2} — описания, написанные для языковой модели.</li>
 * </ul>
 *
 * <p>Запуск: {@code java -jar tracker-mcp.jar --spring.profiles.active=v1}
 */
@SpringBootApplication
public class TrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }
}
