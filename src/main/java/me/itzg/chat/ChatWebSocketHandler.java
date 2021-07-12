package me.itzg.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChatWebSocketHandler implements WebSocketHandler {
    private final static Map<String, Sinks.Many<String>> rooms =
            new ConcurrentHashMap<>();

    public ChatWebSocketHandler() {
        log.info("Creating ChatWebSocketHandler");
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {

        return session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .skipUntil(msg -> msg.startsWith("SUB "))
                .switchOnFirst((signal, f) -> {
                    if (signal.hasValue()) {
                        final String subMsg = signal.get();
                        final String[] parts = subMsg.split(" ", 3);
                        if (parts.length >= 2) {
                            final String roomName = parts[1];
                            log.info("Got subscription for room={}", roomName);

                            final Sinks.Many<String> sink = rooms.computeIfAbsent(roomName, unused ->
                                    Sinks.many().multicast().directBestEffort()
                            );

                            final Mono<Void> input = f
                                    .filter(s -> s.startsWith("MSG "))
                                    .map(s -> s.substring(4))
                                    .doOnNext(msg -> sink.emitNext(msg, Sinks.EmitFailureHandler.FAIL_FAST))
                                    .then();

                            return Mono.zip(
                                    input,
                                    session.send(
                                            sink.asFlux()
                                                    .map(session::textMessage)
                                    )
                            );
                        }
                    }
                    return Mono.empty();
                })
                .then();
    }
}
