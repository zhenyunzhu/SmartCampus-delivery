package com.smartcampus.service;

import com.smartcampus.dto.EmployeeDTO;
import com.smartcampus.dto.EmployeeLoginDTO;
import com.smartcampus.dto.EmployeePageQueryDTO;
import com.smartcampus.entity.Employee;
import com.smartcampus.result.PageResult;

public interface EmployeeService {

    /**
     * 员工登录
     * @param employeeLoginDTO
     * @return
     */
    Employee login(EmployeeLoginDTO employeeLoginDTO);


    /**
     * 新增员工
     * @param employeeDTO
     * @return
     */
    void save(EmployeeDTO employeeDTO);

    PageResult pageQuery(EmployeePageQueryDTO employeeDTO);

    void startOrstop(Integer status, Long id);

    /*
     * 编辑员工
     * @param id 员工id
     * @param employee 员工信息
     * @return
     * */
    Employee getById(Long id);

    void update(EmployeeDTO employeeDTO);
}
