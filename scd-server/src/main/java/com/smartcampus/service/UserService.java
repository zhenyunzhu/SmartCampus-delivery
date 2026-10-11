package com.smartcampus.service;

import com.smartcampus.dto.UserLoginDTO;
import com.smartcampus.entity.User;

public interface UserService {

    /**
     * 微信登录
     *
     * @param userLoginDTO
     * @return
     */
    User wxLogin(UserLoginDTO userLoginDTO);
}
