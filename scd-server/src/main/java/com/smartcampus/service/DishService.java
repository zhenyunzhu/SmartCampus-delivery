package com.smartcampus.service;

import com.smartcampus.dto.DishDTO;
import com.smartcampus.dto.DishPageQueryDTO;
import com.smartcampus.entity.Dish;
import com.smartcampus.result.PageResult;
import com.smartcampus.vo.DishVO;

import java.util.List;

/**
 * 新增菜品和口味数
 * @param dishDTO
 */
public interface DishService {

    public void saveWithFlavor(DishDTO dishDTO);


    /**
     * 分页查询菜品
     * @param dishPageQueryDTO
     * @return
     */

    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

    void deleteBatch(List<Long> ids);


    /**
     * 根据id查询菜品详情和口味信息
     * @param id
     * @return
     */
    DishVO getByIdwithFlavor(Long id);

    /**
     * 根据id修改菜品和口味信息
     * @param dishDTO
     */
    void updateWithFlavor(DishDTO dishDTO);

    /**
     * 菜品起售、停售
     * @param status
     * @param id
     */
    void startOrStop(Integer status, Long id);

    /**
     * 条件查询菜品和口味（用户端，优先走 Redis 缓存）
     * @param dish
     * @return
     */
    List<DishVO> listWithFlavor(Dish dish);

    /**
     * 条件查询菜品（管理端，用于套餐管理中挑选菜品）
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);
}


