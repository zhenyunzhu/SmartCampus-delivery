package com.smartcampus.task;

import com.smartcampus.entity.Orders;
import com.smartcampus.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单定时任务
 * <p>
 * 1、定时处理超时未支付订单（下单超过15分钟仍未支付 -> 自动取消）
 * 2、定时处理派送中订单（派送超过1小时仍未完成 -> 自动完成）
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理超时订单：每分钟触发一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void processTimeoutOrder() {
        log.info("定时处理超时订单：{}", LocalDateTime.now());

        // 计算 15 分钟之前的时间点
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                // CAS：仅当订单仍处于「待付款」时才取消，
                // 避免定时任务与用户支付并发时，把刚刚支付成功的订单错误取消
                Orders cancelOrder = Orders.builder()
                        .id(orders.getId())
                        .oldStatus(Orders.PENDING_PAYMENT)
                        .status(Orders.CANCELLED)
                        .cancelReason("订单超时，自动取消")
                        .cancelTime(LocalDateTime.now())
                        .build();
                if (orderMapper.update(cancelOrder) > 0) {
                    log.info("订单 {} 超时未支付，已自动取消", orders.getNumber());
                }
            }
        }
    }

    /**
     * 处理一直处于派送中的订单：每天凌晨 1 点触发一次
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDeliveryOrder() {
        log.info("定时处理派送中的订单：{}", LocalDateTime.now());

        // 计算 1 小时之前的时间点
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                // CAS：仅当订单仍处于「派送中」时才自动完成
                Orders completeOrder = Orders.builder()
                        .id(orders.getId())
                        .oldStatus(Orders.DELIVERY_IN_PROGRESS)
                        .status(Orders.COMPLETED)
                        .deliveryTime(LocalDateTime.now())
                        .build();
                if (orderMapper.update(completeOrder) > 0) {
                    log.info("订单 {} 派送超时，已自动完成", orders.getNumber());
                }
            }
        }
    }
}
