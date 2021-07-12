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
                // ignore messages until a subscription shows up
                .skipUntil(msg -> msg.startsWith("SUB "))
                // ...and process that subscription and content after it
                .switchOnFirst((signal, f) -> {
                    if (signal.hasValue()) {
                        final String subMsg = signal.get();
                        final String[] parts = subMsg.split(" ", 3);
                        if (parts.length >= 2) {
                            final String roomName = parts[1];
                            log.info("Got subscription for room={}", roomName);

                            // get or create the room's multicasting sink
                            final Sinks.Many<String> sink = rooms.computeIfAbsent(roomName, unused ->
                                    Sinks.many().multicast().directBestEffort()
                            );

                            final Mono<Void> input = f
                                    // messages only
                                    .filter(s -> s.startsWith("MSG "))
                                    .map(s -> s.substring(4))
                                    // emit to the room's sink
                                    .doOnNext(msg -> sink.emitNext(msg, Sinks.EmitFailureHandler.FAIL_FAST))
                                    .then();

                            // tie together completion of receiving flux and the sending from room sink
                            return Mono.zip(
                                    input,
                                    session.send(
                                            sink.asFlux()
                                                    .map(session::textMessage)
                                    )
                            );
                        }
                    }
                    log.warn("Websocket started out without a value");
                    // ignore the incoming content and turn into a completion mono
                    return f.then();
                })
                // switch back to a Mono<Void> to let websocket wait for completion
                .then();
    }
}
