package com.smartcampus.mapper;

import com.github.pagehelper.Page;
import com.smartcampus.annocation.AutoFill;
import com.smartcampus.dto.EmployeePageQueryDTO;
import com.smartcampus.entity.Employee;
import com.smartcampus.enumeration.OperationType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeMapper {

    /**
     * 根据用户名查询员工
     * @param username
     * @return
     */
    @Select("select * from employee where username = #{username}")
    Employee getByUsername(String username);

    /**
     *插入员工数据
     * @param employee
     */
    @Insert("insert into employee (username, password, name, phone, sex, id_number, status, create_time, update_time, create_user, update_user) " +
            "values (#{username}, #{password}, #{name}, #{phone}, #{sex}, #{idNumber}, #{status}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser})")
    @AutoFill(value = OperationType.INSERT)
    void insert(Employee employee);

    /**
     * 分页查询员工
     *
     * @param employeePageQueryDTO
     * @return
     */
    Page<Employee> pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    /**
     * 根据主键动态修改员工信息（含状态启停、编辑）
     * 通过 @AutoFill(UPDATE) 自动填充 updateTime / updateUser
     *
     * @param employee
     */
    @AutoFill(value = OperationType.UPDATE)
    void update(Employee employee);

    /**
     * 根据id查询员工
     *
     * @param id
     * @return
     */
    @Select("select * from employee where id = #{id}")
    Employee getById(Long id);
}
