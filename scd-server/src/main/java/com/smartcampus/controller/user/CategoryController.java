package com.smartcampus.controller.user;

import com.smartcampus.entity.Category;
import com.smartcampus.result.Result;
import com.smartcampus.service.CategoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类浏览（用户端）
 */
@RestController("userCategoryController")
@RequestMapping("/user/category")
@Api(tags = "C端-分类接口")
@Slf4j
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 查询分类
     *
     * @param type 分类类型：1 菜品分类 2 套餐分类
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("查询分类")
    public Result<List<Category>> list(Integer type) {
        log.info("查询分类：type = {}", type);
        List<Category> list = categoryService.list(type);
        return Result.success(list);
    }
}
