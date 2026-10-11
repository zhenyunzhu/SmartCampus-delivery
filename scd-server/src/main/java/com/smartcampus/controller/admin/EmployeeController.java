package com.smartcampus.controller.admin;

import com.smartcampus.constant.JwtClaimsConstant;
import com.smartcampus.dto.EmployeeDTO;
import com.smartcampus.dto.EmployeeLoginDTO;
import com.smartcampus.dto.EmployeePageQueryDTO;
import com.smartcampus.entity.Employee;
import com.smartcampus.properties.JwtProperties;
import com.smartcampus.result.PageResult;
import com.smartcampus.result.Result;
import com.smartcampus.service.EmployeeService;
import com.smartcampus.utils.JwtUtil;
import com.smartcampus.vo.EmployeeLoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 员工管理
 */
@RestController
@RequestMapping("/admin/employee")
@Slf4j
@Api(tags = "员工")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 登录
     *
     * @param employeeLoginDTO
     * @return
     */
    @PostMapping("/login")
    @ApiOperation(value = "员工登录")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO employeeLoginDTO) {
        log.info("员工登录：{}", employeeLoginDTO);

        Employee employee = employeeService.login(employeeLoginDTO);

        //登录成功后，生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getAdminSecretKey(),
                jwtProperties.getAdminTtl(),
                claims);

        EmployeeLoginVO employeeLoginVO = EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .token(token)
                .build();

        return Result.success(employeeLoginVO);
    }

    /**
     * 退出
     *
     * @return
     */
    @PostMapping("/logout")
    @ApiOperation(value = "员工退出")
    public Result<String> logout() {
        return Result.success();
    }


    /**
     * 新增员工
     *
     * @param employeeDTO
     * @return
     *
     */
    @PostMapping
    @ApiOperation(value = "新增员工")
    public Result save(@RequestBody EmployeeDTO employeeDTO) {
        log.info("新增员工：{}", employeeDTO);
        System.out.println("当前线程的id：" + Thread.currentThread().getId());
        employeeService.save(employeeDTO);
        return Result.success();

    }


    /*
     * 分页查询
     * */
    @GetMapping("/page")
    @ApiOperation(value = "分页查询员工")
    public Result<PageResult> page(EmployeePageQueryDTO employeeDTO) {
        PageResult pageResult = employeeService.pageQuery(employeeDTO);
        return Result.success(pageResult);
    }


    /*
     * 员工停用/启用
     * @param status 0：停用 1：启用
     * @param id 员工id
     * @return
     * */
    @PostMapping("/status/{status}")
    @ApiOperation(value = "员工停用/启用")
    public Result startOrstop(@PathVariable Integer status, Long id) {
        employeeService.startOrstop(status, id);
        return Result.success();
    }

    /*
     * 编辑员工
     * @param id 员工id
     * @param employee 员工信息
     * @return
     * */
    @GetMapping("/{id}")
    @ApiOperation(value = "根据id查询员工")
    public Result<Employee> getById(@PathVariable Long id) {
        Employee employee=employeeService.getById(id);
        return Result.success(employee);
    }



    @PutMapping
    @ApiOperation(value = "编辑员工信息")
    public  Result update(@RequestBody EmployeeDTO employeeDTO) {
        employeeService.update(employeeDTO);
        return Result.success();
    }
}
