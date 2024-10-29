package org.cloud.sonic.agent.transport;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.components.SpringTool;
import org.cloud.sonic.agent.tools.NetworkInfo;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TransportConnectionThread implements Runnable {
    /**
     * second
     */
    public static final long DELAY = 10;
    public static final String THREAD_NAME = "transport-connection-thread";
    public static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;

    String wss = String.valueOf(SpringTool.getPropertiesValue("sonic.server.wss"));
    String host = NetworkInfo.getHostIP();
    String version = String.valueOf(SpringTool.getPropertiesValue("spring.version"));
    Integer port = Integer.valueOf(SpringTool.getPropertiesValue("server.port"));

    @Override
    public void run() {
        Thread.currentThread().setName(THREAD_NAME);
        if (TransportWorker.getClient() == null) {
            if (!TransportWorker.getIsKeyAuth()) {
                return;
            }
            TransportClient transportClient = new TransportClient(URI.create(wss), host, version, port);
            transportClient.connect();
        } else {
            JSONObject ping = new JSONObject();
            ping.put("msg", "ping");
            TransportWorker.send(ping);
        }
    }
}
