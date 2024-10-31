package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static org.cloud.sonic.agent.tools.BytesTool.sendText;

@Slf4j
@Component
@ServerEndpoint(value = "/websockets/ios/terminal/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class IOSTerminalWSServer {

    private static final String DEVICE = "DEVICE";
    private static final String UDID = "UDID";

    @Value("${sonic.agent.key}")
    private String key;

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("key") String secretKey,
                       @PathParam("udId") String udId,
                       @PathParam("token") String token) {
        if (secretKey.isEmpty() || (!secretKey.equals(key)) || token.isEmpty()) {
            log.info("Auth Failed!");
            return;
        }

        if (!SibTool.getDeviceList().contains(udId)) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }

        session.getUserProperties().put(UDID, udId);

        JSONObject ter = new JSONObject();
        ter.put("msg", "terminal");
        sendText(session, ter.toJSONString());
    }

    @OnMessage
    public void onMessage(String message, Session session) throws InterruptedException {
        JSONObject msg = JSON.parseObject(message);
        var udId = (String) session.getUserProperties().get(UDID);
        log.info("{} send: {}", udId, msg);
        switch (msg.getString("type")) {
            case "processList" -> SibTool.getProcessList(udId, session);
            case "appList" -> SibTool.getAppList(udId, session);
            case "syslog" -> SibTool.getSysLog(udId, msg.getString("filter"), session);
            case "stopSyslog" -> SibTool.stopSysLog(udId);
        }
    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
        JSONObject errMsg = new JSONObject();
        errMsg.put("msg", "error");
        sendText(session, errMsg.toJSONString());
    }

    private void exit(Session session) {
        var udId = (String) session.getUserProperties().get(UDID);
        if (udId != null) {
            SibTool.stopSysLog(udId);
        }
        try {
            session.close();
        } catch (IOException e) {
            log.error("IOException", e);
        }
        log.info("{} : quit.", udId);

    }
}
