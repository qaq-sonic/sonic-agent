package org.cloud.sonic.agent;

import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.common.maps.GlobalProcessMap;
import org.cloud.sonic.agent.common.maps.IOSProcessMap;
import org.cloud.sonic.agent.components.SGMTool;
import org.cloud.sonic.agent.components.SpringTool;
import org.cloud.sonic.agent.tools.NetworkInfo;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.agent.transport.TransportClient;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Slf4j
@Import(SpringTool.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@SpringBootApplication
public class AgentApplication {
    @Value("${sonic.server.wss}")
    private String wss;
    @Value("${spring.version}")
    String version;
    @Value("${server.port}")
    Integer port;
    private static final String host = NetworkInfo.getHostIP();

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        File testFile = new File("test-output");
        if (!testFile.exists()) {
            testFile.mkdirs();
        }
        ScheduleTool.scheduleAtFixedRate(() -> {
            if (TransportWorker.getClient() == null) {
                TransportClient transportClient = new TransportClient(URI.create(wss), host, version, port);
                transportClient.connect();
            } else {
                JSONObject ping = new JSONObject();
                ping.put("msg", "ping");
                TransportWorker.send(ping);
            }
        }, 0, 10, TimeUnit.SECONDS);
        Thread.startVirtualThread(() -> {
            File file = new File("plugins/sonic-go-mitmproxy-ca-cert.pem");
            if (!file.exists()) {
                log.info("Generating ca file...");
                SGMTool.startProxy("init", SGMTool.getCommand());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    log.error("InterruptedException", e);
                }
                // 仅生成证书
                SGMTool.stopProxy("init");
                file = new File("plugins/sonic-go-mitmproxy-ca-cert.pem");
                if (!file.exists()) {
                    log.info("init sonic-go-mitmproxy-ca failed!");
                } else {
                    log.info("init sonic-go-mitmproxy-ca Successful!");
                }
            }
        });
    }

    @PreDestroy
    public void destroy() {
        ScheduleTool.shutdownScheduler();
        for (String key : GlobalProcessMap.getMap().keySet()) {
            Process ps = GlobalProcessMap.getMap().get(key);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
        for (String key : IOSProcessMap.getMap().keySet()) {
            List<Process> ps = IOSProcessMap.getMap().get(key);
            for (Process p : ps) {
                p.children().forEach(ProcessHandle::destroy);
                p.destroy();
            }
        }
        log.info("Release done!");
    }

}
