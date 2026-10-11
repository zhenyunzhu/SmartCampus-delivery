package com.smartcampus.mapper;

import com.smartcampus.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface UserMapper {

    /**
     * 根据 openid 查询用户
     *
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 插入用户数据
     *
     * @param user
     */
    void insert(User user);

    /**
     * 根据id查询用户
     *
     * @param id
     * @return
     */
    @Select("select * from user where id = #{id}")
    User getById(Long id);

    /**
     * 根据动态条件统计用户数量（用于用户统计报表）
     *
     * @param map
     * @return
     */
    Integer countByMap(Map<String, Object> map);
}
