package org.cloud.sonic.agent;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.common.maps.GlobalProcessMap;
import org.cloud.sonic.agent.common.maps.IOSProcessMap;
import org.cloud.sonic.agent.components.SGMTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.agent.components.SpringTool;
import org.cloud.sonic.agent.transport.TransportConnectionThread;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.io.File;
import java.util.List;


@Slf4j
@Import(SpringTool.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        File testFile = new File("test-output");
        if (!testFile.exists()) {
            testFile.mkdirs();
        }
        ScheduleTool.scheduleAtFixedRate(
                new TransportConnectionThread(),
                TransportConnectionThread.DELAY,
                TransportConnectionThread.DELAY,
                TransportConnectionThread.TIME_UNIT
        );
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
