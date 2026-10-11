package com.smartcampus.controller.user;

import com.smartcampus.constant.StatusConstant;
import com.smartcampus.entity.Dish;
import com.smartcampus.result.Result;
import com.smartcampus.service.DishService;
import com.smartcampus.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜品浏览（用户端）
 * <p>
 * 查询结果优先走 Redis 缓存。
 */
@RestController("userDishController")
@RequestMapping("/user/dish")
@Api(tags = "C端-菜品浏览接口")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;

    /**
     * 根据分类id查询菜品（含口味）
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        List<DishVO> list = dishService.listWithFlavor(dish);
        return Result.success(list);
    }
}
