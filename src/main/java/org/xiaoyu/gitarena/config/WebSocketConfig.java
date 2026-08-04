package org.xiaoyu.gitarena.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket (STOMP) 配置（M3 阶段B）。协作房间的实时同步走这里：
 * 客户端连 {@code /ws}，订阅 {@code /topic/rooms/{roomId}} 接收房间状态变更（有人加入 / 有人 push /
 * PR 变更），后端在 CollabService 变更后向该主题广播（§3 数据流的 WebSocket 分支）。
 *
 * <p>广播的只是"房间状态已变，来拉最新"的信号 + 最新房间快照；每个成员的图仍由各自沙盒经
 * GraphService 实时读出（不引入第二份状态，§3 黄金法则）。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins("http://localhost:5173")
                .withSockJS();
    }
}
