package org.cloud.sonic.agent.websockets;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.tests.ios.mjpeg.MjpegInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;

import static org.cloud.sonic.agent.tools.BytesTool.sendByte;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/ios/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class IOSScreenWSServer {

    private static final String DEVICE = "DEVICE";
    private static final String UDID = "UDID";

    @Value("${sonic.agent.key}")
    private String key;
    @Value("${server.port}")
    private int port;

    @OnOpen
    public void onOpen(Session session,
                       @PathParam("key") String secretKey,
                       @PathParam("udId") String udId,
                       @PathParam("token") String token) throws InterruptedException {
        if (secretKey.isEmpty() || (!secretKey.equals(key)) || token.isEmpty()) {
            log.info("Auth Failed!");
            return;
        }

        if (!SibTool.getDeviceList().contains(udId)) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }

        session.getUserProperties().put(UDID, udId);

        int screenPort = 0;
        int wait = 0;
        while (wait < 120) {
            Integer p = IOSWSServer.screenMap.get(udId);
            if (p != null) {
                screenPort = p;
                break;
            }
            Thread.sleep(500);
            wait++;
        }
        if (screenPort == 0) {
            return;
        }
        int finalScreenPort = screenPort;
        new Thread(() -> {
            URL url;
            try {
                url = new URL("http://localhost:" + finalScreenPort);
            } catch (MalformedURLException e) {
                return;
            }
            MjpegInputStream mjpegInputStream = null;
            int waitMjpeg = 0;
            while (mjpegInputStream == null) {
                try {
                    mjpegInputStream = new MjpegInputStream(url.openStream());
                } catch (IOException e) {
                    log.info(e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.info(e.getMessage());
                    return;
                }
                waitMjpeg++;
                if (waitMjpeg >= 20) {
                    log.info("mjpeg server connect fail");
                    return;
                }
            }
            ByteBuffer bufferedImage;
            int i = 0;
            while (true) {
                try {
                    if ((bufferedImage = mjpegInputStream.readFrameForByteBuffer()) == null) break;
                } catch (IOException e) {
                    log.info(e.getMessage());
                    break;
                }
                i++;
                if (i % 3 != 0) {
                    sendByte(session, bufferedImage);
                } else {
                    i = 0;
                }
            }
            try {
                mjpegInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info("screen done.");
        }).start();
    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
    }

    private void exit(Session session) {
        try {
            session.close();
        } catch (IOException e) {
            log.error("IOException", e);
        }
        log.info("{} : quit.", session.getUserProperties().get("id").toString());
    }
}
