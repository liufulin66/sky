package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.aspect.AutoFillAspect;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.dto.PasswordEditDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordEditFailedException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.result.PageResult;
import com.sky.service.EmployeeService;
import com.sky.utils.JwtUtil;
import com.sky.vo.EmployeeLoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    private final AutoFillAspect autoFillAspect;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private JwtProperties jwtProperties;

    EmployeeServiceImpl(AutoFillAspect autoFillAspect) {
        this.autoFillAspect = autoFillAspect;
    }

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    @Override
    public EmployeeLoginVO login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        // 1. 根据用户名查询员工
        Employee employee = employeeMapper.getByUsername(username);

        // 2. 校验三种失败分支：账号不存在 / 密码错误 / 账号被锁定
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        // 密码比对：对前端传来的明文密码做一次 MD5 摘要，再与数据库中的摘要比对
        String md5Password = DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8));
        if (!md5Password.equals(employee.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        // 注意 Integer 用 equals 比较，不能用 ==（== 比的是引用）
        if (StatusConstant.DISABLE.equals(employee.getStatus())) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 3. 登录成功，生成 JWT 令牌（载荷中放入员工 id，供后续拦截器识别身份）
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());

        String token = JwtUtil.createJWT(
                jwtProperties.getAdminSecretKey(),
                jwtProperties.getAdminTtl(),
                claims);

        // 4. 封装 EmployeeLoginVO 返回（不含密码等敏感字段）
        return EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .token(token)
                .build();
    }

    /**
     * 新增员工
     *
     * @param employeeDTO
     */
    @Override
    public void save(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();

        // 1. 前端可控字段：显式逐个赋值
        // （等价于教程的 BeanUtils.copyProperties，但编译期可检查、不会把 DTO 里多余的字段悄悄带进来——
        // 比如 DTO 里的 id：新增时主键由数据库自增生成，不应该赋值）
        employee.setUsername(employeeDTO.getUsername());
        employee.setName(employeeDTO.getName());
        employee.setPhone(employeeDTO.getPhone());
        employee.setSex(employeeDTO.getSex());
        employee.setIdNumber(employeeDTO.getIdNumber());

        // 2. 后端补齐字段：初始密码（默认密码 123456 的 MD5 摘要）、初始状态为启用
        employee.setPassword(DigestUtils.md5DigestAsHex(
                PasswordConstant.DEFAULT_PASSWORD.getBytes(StandardCharsets.UTF_8)));
        employee.setStatus(StatusConstant.ENABLE);

        //审计字段游@AutoFill切面填充

        // 4. 入库（username 唯一索引冲突会抛数据库异常，由全局异常处理器统一转成"用户名已存在"）
        employeeMapper.insert(employee);
    }

    /**
     * 员工分页查询
     *
     * @param employeePageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        // 1. PageHelper 设置分页参数（当前页、每页条数）
        // 参数被存入 ThreadLocal，只对"紧接着的下一条 MyBatis 查询"生效，用完即清
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());

        // 2. 执行查询：SQL 在 EmployeeMapper.xml 里（没有写 LIMIT）——
        // PageHelper 的 MyBatis 拦截器会自动改写 SQL 追加 LIMIT，并额外执行一条 count 查询统计总数
        // 返回对象实际是 Page 类型（List 的子类），额外携带 total
        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);

        // 3. 转换为对外的 PageResult，隔离第三方框架类型
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 启用禁用员工账号
     *
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        // 只携带"主键 + 要改的字段 + 审计字段"，其余保持 null，
        // 与 Mapper 的动态 SQL <if test="xxx != null"> 配合：只更新非空字段
        Employee employee = Employee.builder()
                .id(id)
                .status(status)
                .build();

        employeeMapper.update(employee);
    }

    /**
     * 根据id查询员工
     *
     * @param id
     * @return
     */
    @Override
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);

        // 回显页面不需要密码，且不应把密码摘要暴露给前端 → 置空
        // ⚠ 必须先判空：查不存在的 id 时 employee 为 null，不判空直接 set 会 NPE（500）
        if (employee != null) {
            employee.setPassword(null);
        }

        return employee;
    }

    /**
     * 编辑员工信息
     *
     * @param employeeDTO
     */
    @Override
    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();

        // 1. 显式搬运"编辑表单允许修改的字段"：id 用于定位记录，其余是表单内容
        employee.setId(employeeDTO.getId());
        employee.setUsername(employeeDTO.getUsername());
        employee.setName(employeeDTO.getName());
        employee.setPhone(employeeDTO.getPhone());
        employee.setSex(employeeDTO.getSex());
        employee.setIdNumber(employeeDTO.getIdNumber());

        // 2. 刻意不设置 password / status / createTime / createUser——它们保持 null，
        // 配合 XML 里动态 SQL 的 <if test="xxx != null">：null 字段不会出现在 update 语句里，库里原值保留。
        // ⚠ 经典翻车点：如果把 update 改成"全量字段"语句，password 会被 null 刷掉——编辑一次密码就没了
        // （EmployeeDTO 里本来也没有密码字段：编辑表单不提供改密功能）

        

        // 4. 复用通用动态更新 SQL（与启用禁用共用同一个 Mapper 方法/同一条 XML）
        employeeMapper.update(employee);
    }

    /**
     * 修改密码
     *
     * @param passwordEditDTO
     */
    @Override
    public void editPassword(PasswordEditDTO passwordEditDTO) {
        // 1. 当前登录人 id 从 token（BaseContext）取，而不是用前端传的 empId（原因见下文）
        Long empId = BaseContext.getCurrentId();

        // 2. 校验原密码：查库拿"完整实体"（含密码摘要），和输入的旧密码摘要比对
        Employee dbEmployee = employeeMapper.getById(empId);
        if (dbEmployee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        String oldMd5 = DigestUtils.md5DigestAsHex(passwordEditDTO.getOldPassword().getBytes(StandardCharsets.UTF_8));
        if (!oldMd5.equals(dbEmployee.getPassword())) {
            // 这个异常在 sky-common 里闲置很久了，今天终于上岗
            throw new PasswordEditFailedException(MessageConstant.PASSWORD_EDIT_FAILED);
        }

        // 3. 新密码做 MD5 摘要，连同审计字段组装实体，复用动态更新 SQL
        Employee employee = new Employee();
        employee.setId(empId);
        employee.setPassword(
                DigestUtils.md5DigestAsHex(passwordEditDTO.getNewPassword().getBytes(StandardCharsets.UTF_8)));
        
        employeeMapper.update(employee);
    }

}
