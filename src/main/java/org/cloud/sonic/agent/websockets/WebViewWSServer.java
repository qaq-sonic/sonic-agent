package org.cloud.sonic.agent.websockets;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.tools.BytesTool;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@ServerEndpoint(value = "/websockets/webView/{key}/{port}/{id}", configurator = WsEndpointConfigure.class)
public class WebViewWSServer {

    private static final String DEVICE = "DEVICE";
    private static final String UDID = "UDID";

    private static final String WEBSOCKET = "WEBSOCKET";
    
    @Value("${sonic.agent.key}")
    private String key;

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey, @PathParam("port") int port, @PathParam("id") String id) throws Exception {
        if (secretKey.isEmpty() || (!secretKey.equals(key))) {
            log.info("Auth Failed!");
            return;
        }
        URI uri = new URI("ws://localhost:" + port + "/devtools/page/" + id);
        WebSocketClient webSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                log.info("Connected!");
            }

            @Override
            public void onMessage(String s) {
                BytesTool.sendText(session, s);
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                log.info("Disconnected!");
            }

            @Override
            public void onError(Exception e) {

            }
        };
        webSocketClient.connect();
        session.getUserProperties().put(WEBSOCKET, webSocketClient);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        var webSocketClient = (WebSocketClient) session.getUserProperties().get(WEBSOCKET);
        if (webSocketClient != null) {
            try {
                webSocketClient.send(message);
            } catch (Exception e) {

            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        var webSocketClient = (WebSocketClient) session.getUserProperties().get(WEBSOCKET);
        webSocketClient.close();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
    }
}
