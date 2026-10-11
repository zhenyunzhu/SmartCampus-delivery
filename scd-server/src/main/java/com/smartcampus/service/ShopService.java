package com.smartcampus.service;

public interface ShopService {

    /**
     * 设置营业状态
     *
     * @param status
     */
    void setStatus(Integer status);

    /**
     * 获取营业状态
     *
     * @return
     */
    Integer getStatus();
}
