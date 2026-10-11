package com.smartcampus.task;

import com.smartcampus.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * WebSocket 定时任务
 * <p>
 * 每 5 秒向所有客户端推送一次心跳消息，用于验证长连接可用性。
 * 生产环境一般仅保留业务推送（来单提醒 / 催单），此处保留心跳便于联调。
 */
@Component
@Slf4j
public class WebSocketTask {

    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 通过 WebSocket 每隔 5 秒向客户端发送一次消息
     */
    @Scheduled(cron = "0/5 * * * * ?")
    public void sendMessageToClient() {
        String time = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now());
        webSocketServer.sendToAllClient("这是来自服务端的消息：" + time);
    }
}
