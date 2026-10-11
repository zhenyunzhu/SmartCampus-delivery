package com.smartcampus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Smart-Campus-Delivery 启动类
 * <p>
 * 智慧园区即时零售与履约配送平台后端服务入口。
 */
@SpringBootApplication
@EnableTransactionManagement // 开启注解方式的事务管理
@EnableCaching // 开启缓存注解功能，用于套餐缓存
@EnableScheduling // 开启任务调度，用于定时处理超时订单
@Slf4j
public class SmartCampusDeliveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartCampusDeliveryApplication.class, args);
        log.info("server started");
    }
}
