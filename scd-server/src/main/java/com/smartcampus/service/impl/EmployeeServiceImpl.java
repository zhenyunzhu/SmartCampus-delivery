package com.smartcampus.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.smartcampus.constant.MessageConstant;
import com.smartcampus.constant.PasswordConstant;
import com.smartcampus.constant.StatusConstant;
import com.smartcampus.context.BaseContext;
import com.smartcampus.dto.EmployeeDTO;
import com.smartcampus.dto.EmployeeLoginDTO;
import com.smartcampus.dto.EmployeePageQueryDTO;
import com.smartcampus.entity.Employee;
import com.smartcampus.exception.AccountLockedException;
import com.smartcampus.exception.AccountNotFoundException;
import com.smartcampus.exception.PasswordErrorException;
import com.smartcampus.mapper.EmployeeMapper;
import com.smartcampus.result.PageResult;
import com.smartcampus.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO 员工登录时传递的数据模型
     * @return 返回员工的实体对象
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //密码比对
        // 对前端传过来的明文密码进行md5加密处理
        password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!password.equals(employee.getPassword())) {
            //密码错误
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (StatusConstant.DISABLE.equals(employee.getStatus())) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //3、返回实体对象
        return employee;
    }


    /**
     * 新增员工
     *
     * @param employeeDTO
     * @return
     *
     */
    @Override
    public void save(EmployeeDTO employeeDTO) {
        System.out.println("当前线程的id：" + Thread.currentThread().getId());
        Employee employee = new Employee();
        //对象属性拷贝
        BeanUtils.copyProperties(employeeDTO, employee);
        //3、设置账号状态
        employee.setStatus(StatusConstant.ENABLE);
        //4、设置账号密码
        employee.setPassword(DigestUtils.md5DigestAsHex(PasswordConstant.DEFAULT_PASSWORD.getBytes()));
      /*  //5、设置当前记录的创建时间和修改时间
        employee.setCreateTime(LocalDateTime.now());
        employee.setUpdateTime(LocalDateTime.now());
        //设置当前记录创建人id和修改人id

        employee.setCreateUser(BaseContext.getCurrentId());
        employee.setUpdateUser(BaseContext.getCurrentId());
*/
        employeeMapper.insert(employee);
    }

    /**
    * 分页查询员工
    * @param employeePageQueryDTO
    * @return
    */
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        //select * from employee Limit 0,10
       //开始分页查询
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());
        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);
        long total = page.getTotal();
        List<Employee> employees = page.getResult();
        return new PageResult(total, employees);
    }
    /*
     * 员工停用/启用
     * @param status 0：停用 1：启用
     * @param id 员工id
     * @return
     * */
    public void startOrstop(Integer status, Long id) {
        //update employee set status=? where id=?
       /* Employee employee = new Employee();
        employee.setId(id);
        employee.setStatus(status);*/
        Employee employee = Employee.builder()
                .id(id)
                .status(status)
                        .build();

        employeeMapper.update(employee);
    }

    /*
     * 编辑员工
     * @param id 员工id
     * @param employee 员工信息
     * @return
     * */
    public Employee getById(Long id) {
        Employee employee=employeeMapper.getById(id);
        employee.setPassword("****");
        return employee;
    }
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);
      /*  employee.setUpdateTime(LocalDateTime.now());
        employee.setUpdateUser(BaseContext.getCurrentId());*/
        employeeMapper.update(employee);

    }

}