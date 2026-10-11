package com.smartcampus.service.impl;

import com.smartcampus.constant.StatusConstant;
import com.smartcampus.service.ShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 店铺营业状态业务实现
 * <p>
 * 营业状态属于高频读、低频写的数据，使用 Redis 的 String 类型存储，key 为 SHOP_STATUS。
 */
@Service
@Slf4j
public class ShopServiceImpl implements ShopService {

    /**
     * Redis 中营业状态对应的 key
     */
    public static final String KEY = "SHOP_STATUS";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 设置营业状态
     *
     * @param status
     */
    public void setStatus(Integer status) {
        log.info("设置营业状态为：{}", StatusConstant.ENABLE.equals(status) ? "营业中" : "打烊中");
        redisTemplate.opsForValue().set(KEY, status);
    }

    /**
     * 获取营业状态
     *
     * @return
     */
    public Integer getStatus() {
        Object status = redisTemplate.opsForValue().get(KEY);
        // 未设置时默认营业中
        return status == null ? StatusConstant.ENABLE : Integer.valueOf(status.toString());
    }
}
