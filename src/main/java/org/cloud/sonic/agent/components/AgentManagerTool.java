
package org.cloud.sonic.agent.components;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentManagerTool {

    private static ConfigurableApplicationContext context;

    @Autowired
    public void setContext(ConfigurableApplicationContext c) {
        AgentManagerTool.context = c;
    }

    public static void stop() {
        try {
            context.close();
        } catch (Exception e) {
            log.error("Error occurred while closing application context: ", e);
        }
        log.info("Bye！");
        System.exit(0);
    }
}
