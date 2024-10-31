package org.cloud.sonic.agent.transport;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.android.AndroidSupplyTool;
import org.cloud.sonic.agent.bridge.ios.IOSDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.enums.AndroidKey;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.*;
import org.cloud.sonic.agent.components.AgentManagerTool;
import org.cloud.sonic.agent.components.PHCTool;
import org.cloud.sonic.agent.enums.MsgType;
import org.cloud.sonic.agent.tests.AndroidTests;
import org.cloud.sonic.agent.tests.IOSTests;
import org.cloud.sonic.agent.tests.SuiteListener;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.android.AndroidRunStepThread;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tests.handlers.AndroidStepHandler;
import org.cloud.sonic.agent.tests.handlers.IOSStepHandler;
import org.cloud.sonic.agent.tests.ios.IOSRunStepThread;
import org.cloud.sonic.agent.tests.ios.IOSTestTaskBootThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.driver.common.tool.SonicRespException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.testng.TestNG;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TransportClient extends WebSocketClient {
    private final String host;
    private final String version;
    private final Integer port;

    public TransportClient(URI serverUri, String host, String version, Integer port) {
        super(serverUri);
        this.host = host;
        this.version = version;
        this.port = port;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        log.info("Connected and auth...");
    }

    @Override
    public void onMessage(String s) {
        JSONObject jsonObject = JSON.parseObject(s);
        log.info("Agent <- Server message: {}", jsonObject);
        try {
            MsgType msgType = MsgType.valueOf(jsonObject.getString("msg"));

            Thread.startVirtualThread(() -> handleIncomingMessage(msgType, jsonObject));
        } catch (IllegalArgumentException e) {
            // Handle the case where msgTypeStr is not a valid MsgType
            log.error("Invalid message type: {}", jsonObject.getString("msg"));
        }
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        if (TransportWorker.getIsKeyAuth()) {
            log.info("Server disconnected. Retry in 10s...");
        }
        if (TransportWorker.getClient() == this) {
            TransportWorker.setClient(null);
        }
    }

    @Override
    public void onError(Exception e) {
        log.info(e.getMessage());
    }

    private void handleIncomingMessage(MsgType msgType, JSONObject jsonObject) {
        switch (msgType) {
            case pong -> handlePong(jsonObject);
            case occupy -> handleOccupy(jsonObject);
            case release -> handleRelease(jsonObject);
            case stopDebug -> handleStopDebug(jsonObject);
            case settings -> handleSettings(jsonObject);
            case auth -> handleAuth(jsonObject);
            case shutdown -> handleShutdown(jsonObject);
            case reboot -> handleReboot(jsonObject);
            case heartBeat -> handleHeartBeat(jsonObject);
            case hub -> handleHub(jsonObject);
            case runStep -> handleRunStep(jsonObject);
            case debugStep -> handleDebugStep(jsonObject);
            case suite -> handleSuite(jsonObject);
            case forceStopSuite -> handleForceStopSuite(jsonObject);
        }
    }

    private void handlePong(JSONObject jsonObject) {
    }

    private void handleOccupy(JSONObject jsonObject) {
        String udId = jsonObject.getString("udId");
        String token = jsonObject.getString("token");
        int platform = jsonObject.getInteger("platform");

        boolean lockSuccess = false;
        try {
            lockSuccess = DevicesLockMap.lockByUdId(udId, 30L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.info("Fail to get device lock, cause {}", e.getMessage());
        }
        if (!lockSuccess) {
            log.info("Fail to get device lock... please make sure device is not busy.");
            return;
        }

        switch (platform) {
            case PlatformType.ANDROID -> {
                log.info("android lock udId：{}", udId);
                AndroidDeviceLocalStatus.startDebug(udId);

                IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
                if (iDevice == null) {
                    log.info("Target device is not connecting, please check the connection.");
                    return;
                }

                int sasPort = jsonObject.getInteger("sasRemotePort");
                int uiaPort = jsonObject.getInteger("uia2RemotePort");

                if (sasPort != 0) {
                    AndroidSupplyTool.startShare(udId, sasPort);
                }

                if (uiaPort != 0) {
                    try {
                        AndroidDeviceBridgeTool.startUiaServer(iDevice, uiaPort);
                    } catch (InstallException e) {
                        log.error(e.getMessage());
                    }
                }

                OccupyMap.map.put(udId,
                        ScheduleTool.schedule(() -> {
                            log.info("time up!");
                            androidRelease(udId);
                        }, BytesTool.remoteTimeout));
            }
            case PlatformType.IOS -> {
                log.info("ios lock udId：{}", udId);
                IOSDeviceLocalStatus.startDebug(udId);

                if (!SibTool.getDeviceList().contains(udId)) {
                    log.info("Target device is not connecting, please check the connection.");
                    return;
                }

                int sibPort = jsonObject.getInteger("sibRemotePort");
                int wdaPort = jsonObject.getInteger("wdaServerRemotePort");
                int wdaMjpegPort = jsonObject.getInteger("wdaMjpegRemotePort");

                if (sibPort != 0) {
                    SibTool.startShare(udId, sibPort);
                }

                if (wdaPort != 0 || wdaMjpegPort != 0) {
                    try {
                        SibTool.startWda(udId, wdaPort, wdaMjpegPort);
                    } catch (IOException | InterruptedException e) {
                        log.error(e.getMessage());
                    }
                }

                OccupyMap.map.put(udId,
                        ScheduleTool.schedule(() -> {
                            log.info("time up!");
                            iosRelease(udId);
                        }, BytesTool.remoteTimeout));
            }
        }

        JSONObject jsonDebug = new JSONObject();
        jsonDebug.put("msg", "debugUser");
        jsonDebug.put("token", token);
        jsonDebug.put("udId", udId);
        TransportWorker.send(jsonDebug);
    }

    private void handleRelease(JSONObject jsonObject) {
        String udId = jsonObject.getString("udId");
        log.info("{} : release.", udId);
        ScheduledFuture<?> future = OccupyMap.map.get(udId);
        if (future != null) {
            future.cancel(true);
            OccupyMap.map.remove(udId);
            int platform = jsonObject.getInteger("platform");
            switch (platform) {
                case PlatformType.ANDROID -> androidRelease(udId);
                case PlatformType.IOS -> iosRelease(udId);
            }
        }
    }

    private void handleStopDebug(JSONObject jsonObject) {
        String udId = jsonObject.getString("udId");
        List<String> sessionList = Arrays.asList("AndroidWSServer", "AndroidTerminalWSServer", "AndroidScreenWSServer",
                "AudioWSServer", "IOSWSServer", "IOSTerminalWSServer", "IOSScreenWSServer");
        for (String ss : sessionList) {
//            Session session = WebSocketSessionMap.getSession(String.format("%s-%s", ss, udId));
            Session session = null;
            if (session == null) {
                continue;
            }
            if (session.isOpen()) {
                if (ss.equals("IOSWSServer")) {
                    IOSStepHandler iosStepHandler = HandlerMap.getIOSMap().get(udId);
                    if (iosStepHandler != null) {
                        try {
                            iosStepHandler.getIOSDriver().pressButton("home");
                        } catch (SonicRespException ignored) {
                        }
                    }
                }
                if (ss.equals("AndroidWSServer") || ss.equals("IOSWSServer")) {
                    JSONObject errMsg = new JSONObject();
                    errMsg.put("msg", "error");
                    BytesTool.sendText(session, errMsg.toJSONString());
                }
                try {
                    session.close();
                } catch (IOException e) {
                    log.info(e.fillInStackTrace().toString());
                }
                if (ss.equals("AndroidWSServer")) {
                    IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
                    if (iDevice != null) {
                        AndroidDeviceBridgeTool.pressKey(iDevice, AndroidKey.HOME);
                    }
                }
                log.info("{}-{} closed.", ss, udId);
            }
        }
    }

    private void handleSettings(JSONObject jsonObject) {
        if (jsonObject.getInteger("id") != null) {
            BytesTool.agentId = jsonObject.getInteger("id");
        }
        if (jsonObject.getInteger("highTemp") != null) {
            BytesTool.highTemp = jsonObject.getInteger("highTemp");
        }
        if (jsonObject.getInteger("highTempTime") != null) {
            BytesTool.highTempTime = jsonObject.getInteger("highTempTime");
        }
        if (jsonObject.getInteger("remoteTimeout") != null) {
            BytesTool.remoteTimeout = jsonObject.getInteger("remoteTimeout");
        }
    }

    private void handleAuth(JSONObject jsonObject) {
        if (!jsonObject.getString("result").equals("pass")) {
            TransportWorker.setIsKeyAuth(false);
            log.info("server auth failed!");
            return;
        }
        log.info("server auth successful!");
        BytesTool.agentId = jsonObject.getInteger("id");
        BytesTool.highTemp = jsonObject.getInteger("highTemp");
        BytesTool.highTempTime = jsonObject.getInteger("highTempTime");
        BytesTool.remoteTimeout = jsonObject.getInteger("remoteTimeout");
        BytesTool.agentHost = host;
        TransportWorker.setClient(this);
        JSONObject agentInfo = new JSONObject();
        agentInfo.put("msg", "agentInfo");
        agentInfo.put("agentId", BytesTool.agentId);
        agentInfo.put("port", port);
        agentInfo.put("version", "v" + version);
        agentInfo.put("systemType", System.getProperty("os.name"));
        agentInfo.put("host", host);
        agentInfo.put("hasHub", PHCTool.isSupport() ? 1 : 0);
        TransportWorker.getClient().send(agentInfo.toJSONString());
        IDevice[] iDevices = AndroidDeviceBridgeTool.getRealOnLineDevices();
        for (IDevice d : iDevices) {
            String status = AndroidDeviceManagerMap.getStatusMap().get(d.getSerialNumber());
            if (status != null) {
                AndroidDeviceLocalStatus.send(d.getSerialNumber(), status);
            } else {
                AndroidDeviceLocalStatus.send(d.getSerialNumber(), d.getState() == null ? null : d.getState().toString());
            }
        }
        List<String> udIds = SibTool.getDeviceList();
        for (String u : udIds) {
            String status = IOSDeviceManagerMap.getMap().get(u);
            if (status != null) {
                IOSDeviceLocalStatus.send(u, status);
            } else {
                IOSDeviceLocalStatus.send(u, DeviceStatus.ONLINE);
            }
        }
    }

    private void handleShutdown(JSONObject jsonObject) {
        AgentManagerTool.stop();
    }

    private void handleReboot(JSONObject jsonObject) {
        if (jsonObject.getInteger("platform") == PlatformType.ANDROID) {
            IDevice rebootDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(jsonObject.getString("udId"));
            if (rebootDevice != null) {
                AndroidDeviceBridgeTool.reboot(rebootDevice);
            }
        }
        if (jsonObject.getInteger("platform") == PlatformType.IOS) {
            if (SibTool.getDeviceList().contains(jsonObject.getString("udId"))) {
                SibTool.reboot(jsonObject.getString("udId"));
            }
        }
    }

    private void handleHeartBeat(JSONObject jsonObject) {
        JSONObject heartBeat = new JSONObject();
        heartBeat.put("msg", "heartBeat");
        heartBeat.put("status", "alive");
        TransportWorker.send(heartBeat);
    }

    private void handleHub(JSONObject jsonObject) {
        PHCTool.setPosition(jsonObject.getInteger("position"), jsonObject.getString("type"));
    }

    private void handleRunStep(JSONObject jsonObject) {
        if (jsonObject.getInteger("pf") == PlatformType.ANDROID) {
            runAndroidStep(jsonObject);
        }
        if (jsonObject.getInteger("pf") == PlatformType.IOS) {
            runIOSStep(jsonObject);
        }
    }

    private void handleDebugStep(JSONObject jsonObject) {
        if (jsonObject.getInteger("pf") == PlatformType.ANDROID) {
            debugAndroidStep(jsonObject);
        }
        if (jsonObject.getInteger("pf") == PlatformType.IOS) {
            debugIOSStep(jsonObject);
        }
    }

    private void handleSuite(JSONObject jsonObject) {
        List<JSONObject> cases = jsonObject.getJSONArray("cases").toJavaList(JSONObject.class);
        TestNG tng = new TestNG();
        List<XmlSuite> suiteList = new ArrayList<>();
        XmlSuite xmlSuite = new XmlSuite();
        //bug?
        for (JSONObject dataInfo : cases) {
            XmlTest xmlTest = new XmlTest(xmlSuite);
            Map<String, String> parameters = new HashMap<>();
            parameters.put("dataInfo", dataInfo.toJSONString());
            if (xmlSuite.getParameter("dataInfo") == null) {
                xmlSuite.setParameters(parameters);
            }
            xmlTest.setParameters(parameters);
            List<XmlClass> classes = new ArrayList<>();
            if (jsonObject.getInteger("pf") == PlatformType.ANDROID) {
                classes.add(new XmlClass(AndroidTests.class));
            }
            if (jsonObject.getInteger("pf") == PlatformType.IOS) {
                classes.add(new XmlClass(IOSTests.class));
            }
            xmlTest.setXmlClasses(classes);
        }
        suiteList.add(xmlSuite);
        tng.setXmlSuites(suiteList);
        tng.addListener(new SuiteListener());
        tng.run();
    }

    private void handleForceStopSuite(JSONObject jsonObject) {
        List<JSONObject> caseList = jsonObject.getJSONArray("cases").toJavaList(JSONObject.class);
        for (JSONObject aCase : caseList) {
            int resultId = (int) aCase.get("rid");
            int caseId = (int) aCase.get("cid");
            JSONArray devices = (JSONArray) aCase.get("device");
            List<JSONObject> deviceList = devices.toJavaList(JSONObject.class);
            for (JSONObject device : deviceList) {
                String udId = (String) device.get("udId");
                TaskManager.forceStopSuite(jsonObject.getInteger("pf"), resultId, caseId, udId);
            }
        }
    }

    /**
     * Android 步骤调试
     */
    private void runAndroidStep(JSONObject jsonObject) {

        AndroidPasswordMap.getMap().put(jsonObject.getString("udId"), jsonObject.getString("pwd"));
        AndroidStepHandler androidStepHandler = HandlerMap.getAndroidMap().get(jsonObject.getString("udId"));
        if (androidStepHandler == null) {
            return;
        }
        androidStepHandler.resetResultDetailStatus();
        androidStepHandler.setGlobalParams(jsonObject.getJSONObject("gp"));

        AndroidTestTaskBootThread dataBean = new AndroidTestTaskBootThread(jsonObject, androidStepHandler);
        AndroidRunStepThread task = new AndroidRunStepThread(dataBean) {
            @Override
            public void run() {
                super.run();
                androidStepHandler.sendStatus();
            }
        };
        TaskManager.startChildThread(task.getName(), task);
    }

    /**
     * IOS步骤调试
     */
    private void runIOSStep(JSONObject jsonObject) {
        IOSStepHandler iosStepHandler = HandlerMap.getIOSMap().get(jsonObject.getString("udId"));
        if (iosStepHandler == null) {
            return;
        }
        iosStepHandler.resetResultDetailStatus();
        iosStepHandler.setGlobalParams(jsonObject.getJSONObject("gp"));

        IOSTestTaskBootThread dataBean = new IOSTestTaskBootThread(jsonObject, iosStepHandler);

        IOSRunStepThread task = new IOSRunStepThread(dataBean) {
            @Override
            public void run() {
                super.run();
                iosStepHandler.sendStatus();
            }
        };
        TaskManager.startChildThread(task.getName(), task);
    }

    private void androidRelease(String udId) {
        AndroidDeviceLocalStatus.finish(udId);
        Thread s = AndroidThreadMap.getMap().get(String.format("%s-uia-thread", udId));
        if (s != null) {
            s.interrupt();
        }
        AndroidSupplyTool.stopShare(udId);
        DevicesLockMap.unlockAndRemoveByUdId(udId);
        log.info("android unlock udId：{}", udId);
    }

    private void iosRelease(String udId) {
        IOSDeviceLocalStatus.finish(udId);
        SibTool.stopShare(udId);
        if (IOSProcessMap.getMap().get(udId) != null) {
            List<Process> processList = IOSProcessMap.getMap().get(udId);
            for (Process p : processList) {
                if (p != null) {
                    p.children().forEach(ProcessHandle::destroy);
                    p.destroy();
                }
            }
            IOSProcessMap.getMap().remove(udId);
        }
        DevicesLockMap.unlockAndRemoveByUdId(udId);
        log.info("ios unlock udId：{}", udId);
    }

    private void debugAndroidStep(JSONObject jsonObject) {

    }

    private void debugIOSStep(JSONObject jsonObject) {

    }

}
