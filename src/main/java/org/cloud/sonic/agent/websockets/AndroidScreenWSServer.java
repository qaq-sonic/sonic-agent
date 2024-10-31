package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.AndroidAPKMap;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.tests.android.minicap.MiniCapUtil;
import org.cloud.sonic.agent.tests.android.scrcpy.ScrcpyServerUtil;
import org.cloud.sonic.agent.tests.handlers.AndroidMonitorHandler;
import org.cloud.sonic.agent.tools.BytesTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/android/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidScreenWSServer {

    private static final String DEVICE = "DEVICE";
    private static final String UDID = "UDID";

    private static final String VIDEO_SETTING = "VIDEO_SETTING";
    private static final String PIC_SETTING = "PIC_SETTING";
    private static final String VIDEO_THREAD = "VIDEO_THREAD";

    @Value("${sonic.agent.key}")
    private String key;

    private final AndroidMonitorHandler androidMonitorHandler = new AndroidMonitorHandler();

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey, @PathParam("udId") String udId, @PathParam("token") String token) throws Exception {
        if (secretKey.isEmpty() || (!secretKey.equals(key)) || token.isEmpty()) {
            log.info("Auth Failed!");
            return;
        }
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        if (iDevice == null) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }
        AndroidDeviceBridgeTool.screen(iDevice, "abort");

        session.getUserProperties().put(UDID, udId);
        session.getUserProperties().put(DEVICE, iDevice);

        int wait = 0;
        boolean isInstall = true;
        while (AndroidAPKMap.getMap().get(udId) == null || (!AndroidAPKMap.getMap().get(udId))) {
            Thread.sleep(500);
            wait++;
            if (wait >= 40) {
                isInstall = false;
                break;
            }
        }
        if (!isInstall) {
            log.info("Waiting for apk install timeout!");
            exit(session);
        }
    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("onError", error);
        JSONObject errMsg = new JSONObject();
        errMsg.put("msg", "error");
        BytesTool.sendText(session, errMsg.toJSONString());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        JSONObject msg = JSON.parseObject(message);
        String udId = session.getUserProperties().get(UDID).toString();
        log.info("{} send: {}", udId, msg);
        var msgType = msg.getString("type");
        switch (msgType) {
            case "switch" -> {
                session.getUserProperties().put(VIDEO_SETTING, msg.getString("detail"));
                var iDevice = (IDevice) session.getUserProperties().get(DEVICE);
                if (!androidMonitorHandler.isMonitorRunning(iDevice)) {
                    androidMonitorHandler.startMonitor(iDevice, res -> {
                        JSONObject rotationJson = new JSONObject();
                        rotationJson.put("msg", "rotation");
                        rotationJson.put("value", Integer.parseInt(res) * 90);
                        BytesTool.sendText(session, rotationJson.toJSONString());
                        startScreen(session);
                    });
                } else {
                    startScreen(session);
                }
            }
            case "pic" -> {
                session.getUserProperties().put(PIC_SETTING, msg.getString("detail"));
                startScreen(session);
            }
        }
    }

    private void startScreen(Session session) {
        var iDevice = (IDevice) session.getUserProperties().get(DEVICE);
        if (iDevice != null) {
            var oldVideoThread = (Thread) session.getUserProperties().get(VIDEO_THREAD);
            if (oldVideoThread != null) {
                oldVideoThread.interrupt();
                try {
                    oldVideoThread.join();
                } catch (InterruptedException e) {
                    log.error("Thread interrupted while waiting for old video thread to finish", e);
                    Thread.currentThread().interrupt(); // Restore interrupt status
                }
                // Optionally, verify that the thread has been removed
                if (session.getUserProperties().get(VIDEO_THREAD) != null) {
                    log.warn("Old video thread still present in session properties after interrupt and join");
                }
            }
            session.getUserProperties().putIfAbsent(VIDEO_SETTING, "scrcpy");
            switch ((String) session.getUserProperties().get(VIDEO_SETTING)) {
                case "scrcpy" -> {
                    ScrcpyServerUtil scrcpyServerUtil = new ScrcpyServerUtil();
                    Thread scrcpyThread = scrcpyServerUtil.start(iDevice.getSerialNumber(), AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber()), session);
                    session.getUserProperties().put(VIDEO_THREAD, scrcpyThread);
                }
                case "minicap" -> {
                    MiniCapUtil miniCapUtil = new MiniCapUtil();
                    AtomicReference<String[]> banner = new AtomicReference<>(new String[24]);
                    Thread miniCapThread = miniCapUtil.start(iDevice.getSerialNumber(), banner, null, session.getUserProperties().get(PIC_SETTING) == null ? "high" : (String) session.getUserProperties().get(PIC_SETTING), AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber()), session);
                    session.getUserProperties().put(VIDEO_THREAD, miniCapThread);
                }
            }
            JSONObject picFinish = new JSONObject();
            picFinish.put("msg", "picFinish");
            BytesTool.sendText(session, picFinish.toJSONString());
        }
    }

    private void exit(Session session) {
        var udId = session.getUserProperties().get(UDID).toString();
        var iDevice = (IDevice) session.getUserProperties().get(DEVICE);
        androidMonitorHandler.stopMonitor(iDevice);
        AndroidDeviceManagerMap.getRotationMap().remove(udId);
        var videoThread = (Thread) session.getUserProperties().get(VIDEO_THREAD);
        if (videoThread != null) {
            videoThread.interrupt();
        }
        try {
            session.close();
        } catch (IOException e) {
            log.error("IOException", e);
        }
        log.info("{} : quit.", session.getUserProperties().get("id").toString());
    }
}
