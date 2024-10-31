package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceThreadPool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.AndroidAPKMap;
import org.cloud.sonic.agent.enums.AndroidTerminalMsgType;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ServerEndpoint(value = "/websockets/android/terminal/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidTerminalWSServer implements IAndroidWSServer {

    private static final String[] blackCommandList = {"reboot", "rm", "su "};

    @Value("${sonic.agent.key}")
    private String key;
    private static final Map<Session, Future<?>> terminalMap = new ConcurrentHashMap<>();
    private static final Map<Session, Future<?>> logcatMap = new ConcurrentHashMap<>();
    private static final Map<Session, Thread> socketMap = new ConcurrentHashMap<>();
    private static final Map<Session, OutputStream> outputStreamMap = new ConcurrentHashMap<>();
    private static final Map<Session, IDevice> deviceMap = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("key") String secretKey,
                       @PathParam("udId") String udId,
                       @PathParam("token") String token) throws Exception {
        if (secretKey.isEmpty() || (!secretKey.equals(key)) || token.isEmpty()) {
            log.info("Auth Failed!");
            return;
        }

        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);

        session.getUserProperties().put("udId", udId);
        deviceMap.put(session, iDevice);

        String username = iDevice.getProperty("ro.product.device");
        Thread.startVirtualThread(() -> {
            log.info("{} open terminal", udId);
            JSONObject ter = new JSONObject();
            ter.put("msg", "terminal");
            ter.put("user", username);
            BytesTool.sendText(session, ter.toJSONString());
        });
        Thread.startVirtualThread(() -> {
            log.info("{} open logcat", udId);
            JSONObject ter = new JSONObject();
            ter.put("msg", "logcat");
            BytesTool.sendText(session, ter.toJSONString());
        });
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

        startService(iDevice, session);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        JSONObject jsonObject = JSON.parseObject(message);
        log.info("{} send: {}", session.getUserProperties().get("udId").toString(), jsonObject);
        var msgType = AndroidTerminalMsgType.valueOf(jsonObject.getString("type"));
        handleIncomingMessage(msgType, session, jsonObject);
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
        BytesTool.sendText(session, errMsg.toJSONString());
    }

    private void handleIncomingMessage(AndroidTerminalMsgType msgType, Session session, JSONObject jsonObject) {
        switch (msgType) {
            case appList -> handleAppList(session, jsonObject);
            case wifiList -> handleWifiList(session, jsonObject);
            case stopCmd -> handleStopCmd(session, jsonObject);
            case command -> handleCommand(session, jsonObject);
            case stopLogcat -> handleStopLogcat(session, jsonObject);
            case logcat -> handleLogcat(session, jsonObject);
        }
    }

    private void handleAppList(Session session, JSONObject jsonObject) {
        var iDevice = deviceMap.get(session);
        var outPutStream = outputStreamMap.get(session);
        startService(iDevice, session);
        if (outPutStream != null) {
            try {
                outPutStream.write("action_get_all_app_info".getBytes(StandardCharsets.UTF_8));
                outPutStream.flush();
            } catch (IOException e) {
                log.error("appList", e);
            }
        }
    }

    private void handleWifiList(Session session, JSONObject jsonObject) {
        var iDevice = deviceMap.get(session);
        var outPutStream = outputStreamMap.get(session);
        startService(iDevice, session);
        if (outPutStream != null) {
            try {
                outPutStream.write("action_get_all_wifi_info".getBytes(StandardCharsets.UTF_8));
                outPutStream.flush();
            } catch (IOException e) {
                log.error("wifiList", e);
            }
        }
    }

    private void handleStopCmd(Session session, JSONObject jsonObject) {
        Future<?> ter = terminalMap.get(session);
        if (!ter.isDone() || !ter.isCancelled()) {
            try {
                ter.cancel(true);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

    private void handleCommand(Session session, JSONObject jsonObject) {
        String command = jsonObject.getString("detail");
        if (Arrays.stream(blackCommandList).anyMatch(command::contains)) {
            JSONObject done = new JSONObject();
            done.put("msg", "terDone");
            BytesTool.sendText(session, done.toJSONString());
            return;
        }
        var iDevice = deviceMap.get(session);
        var ter = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
            try {
                iDevice.executeShellCommand(command, new IShellOutputReceiver() {
                    @Override
                    public void addOutput(byte[] data, int offset, int length) {
                        String res = new String(data, offset, length);
                        JSONObject resp = new JSONObject();
                        resp.put("msg", "terResp");
                        resp.put("detail", res);
                        BytesTool.sendText(session, resp.toJSONString());
                    }

                    @Override
                    public void flush() {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                }, 0, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                return;
            }
            JSONObject done = new JSONObject();
            done.put("msg", "terDone");
            BytesTool.sendText(session, done.toJSONString());
        });
        terminalMap.put(session, ter);
    }

    private void handleStopLogcat(Session session, JSONObject jsonObject) {
        Future<?> logcat = logcatMap.get(session);
        if (logcat != null && !logcat.isDone() && !logcat.isCancelled()) {
            try {
                logcat.cancel(true);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

    private void handleLogcat(Session session, JSONObject jsonObject) {
        Future<?> logcat = logcatMap.get(session);
        if (logcat != null && !logcat.isDone() && !logcat.isCancelled()) {
            try {
                logcat.cancel(true);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        var iDevice = deviceMap.get(session);
        var command = "logcat *:" + jsonObject.getString("level") + (jsonObject.getString("filter").isEmpty() ? "" : " | grep " + jsonObject.getString("filter"));
        logcat = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
            try {
                iDevice.executeShellCommand(command, new IShellOutputReceiver() {
                    @Override
                    public void addOutput(byte[] bytes, int i, int i1) {
                        String res = new String(bytes, i, i1);
                        JSONObject resp = new JSONObject();
                        resp.put("msg", "logcatResp");
                        resp.put("detail", res);
                        BytesTool.sendText(session, resp.toJSONString());
                    }

                    @Override
                    public void flush() {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                }, 0, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                return;
            }
        });
        logcatMap.put(session, logcat);
    }

    private void exit(Session session) {
        Future<?> ter = terminalMap.get(session);
        if (ter != null && !ter.isDone() && !ter.isCancelled()) {
            try {
                ter.cancel(true);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        stopService(session);
        terminalMap.remove(session);

        Future<?> logcat = logcatMap.get(session);
        if (logcat != null && !logcat.isDone() && !logcat.isCancelled()) {
            try {
                logcat.cancel(true);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        logcatMap.remove(session);
        deviceMap.remove(session);
        try {
            session.close();
        } catch (IOException e) {
            log.error("IOException", e);
        }
        log.info("{} : quit.", session.getUserProperties().get("udId").toString());
    }

    public void startService(IDevice iDevice, Session session) {
        if (socketMap.get(session) != null && socketMap.get(session).isAlive()) {
            return;
        }
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            log.error("InterruptedException", e);
        }
        Thread manager = new Thread(() -> manageConnection(iDevice, session));
        manager.start();
        int w = 0;
        while (outputStreamMap.get(session) == null) {
            if (w > 10) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.error("InterruptedException", e);
            }
            w++;
        }
        socketMap.put(session, manager);
    }

    private void manageConnection(IDevice iDevice, Session session) {
        int managerPort = PortTool.getPort();
        AndroidDeviceBridgeTool.forward(iDevice, managerPort, 2334);

        try (Socket managerSocket = new Socket("localhost", managerPort);
             InputStream inputStream = managerSocket.getInputStream();
             OutputStream outputStream = managerSocket.getOutputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            outputStreamMap.put(session, outputStream);
            String s;
            while (managerSocket.isConnected() && !Thread.interrupted()) {
                try {
                    if ((s = br.readLine()) == null) break;
                } catch (IOException e) {
                    log.error("Error reading from socket", e);
                    break;
                }
                JSONObject managerDetail = new JSONObject();
                JSONObject data = JSON.parseObject(s);
                managerDetail.put("msg", data.getString("appName") != null ? "appListDetail" : "wifiListDetail");
                managerDetail.put("detail", data);
                BytesTool.sendText(session, managerDetail.toJSONString());
            }
        } catch (IOException e) {
            log.error("IO error", e);
        } finally {
            AndroidDeviceBridgeTool.removeForward(iDevice, managerPort, 2334);
            outputStreamMap.remove(session);
            log.info("manager done.");
        }
    }

    private void stopService(Session session) {
        var outputStream = outputStreamMap.get(session);
        if (outputStream != null) {
            try {
                outputStream.write("org.cloud.sonic.android.STOP".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                log.error("stopService", e);
            }
        }
        var manager = socketMap.get(session);
        if (manager != null) {
            manager.interrupt();
            int wait = 0;
            while (!manager.isInterrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.error("InterruptedException", e);
                }
                wait++;
                if (wait >= 3) {
                    break;
                }
            }
        }
        socketMap.remove(session);
    }
}
