package com.sky.controller.admin;

import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.EmployeeService;
import com.sky.vo.EmployeeLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/employee")
@Slf4j
@Tag(name = "员工相关接口")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    @PostMapping("/login")
    @Operation(summary = "员工登录")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO employeeLoginDTO) {
        // 只记录用户名，不要把整个 DTO 打出来（里面含明文密码，会落进日志文件）
        log.info("员工登录：{}", employeeLoginDTO.getUsername());

        EmployeeLoginVO employeeLoginVO = employeeService.login(employeeLoginDTO);
        return Result.success(employeeLoginVO);
    }

    /**
     * 新增员工
     * 
     * @param employeeDTO
     * @return
     */
    @PostMapping
    @Operation(summary = "新增员工")
    public Result save(@RequestBody EmployeeDTO employeeDTO) {
        log.info("新增员工：{}", employeeDTO.getUsername());
        employeeService.save(employeeDTO);
        return Result.success();
    }

    /**
     * 员工分页查询
     * 
     * @param employeePageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @Operation(summary = "员工分页查询")
    public Result<PageResult> page(EmployeePageQueryDTO employeePageQueryDTO) {
        log.info("员工分页查询：参数为{}", employeePageQueryDTO);
        PageResult pageResult = employeeService.pageQuery(employeePageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 启用禁用员工账号
     * 
     * @param status 状态，1启用 0禁用
     * @param id     员工id
     * @return
     */
    @PostMapping("/status/{status}")
    @Operation(summary = "启用禁用员工账号")
    public Result startOrStop(@PathVariable Integer status, Long id) {
        log.info("启用禁用员工账号：status={}，员工id={}", status, id);
        employeeService.startOrStop(status, id);
        return Result.success();
    }

    /**
     * 根据id查询员工信息
     *
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据id查询员工信息")
    public Result<Employee> getById(@PathVariable Long id) {
        log.info("根据id查询员工信息：{}", id);
        Employee employee = employeeService.getById(id);
        return Result.success(employee);
    }

    /**
     * 编辑员工信息
     * @param employeeDTO
     * @return
     */
    @PutMapping
    @Operation(summary = "编辑员工信息")
    public Result update(@RequestBody EmployeeDTO employeeDTO){
        log.info("编辑员工信息：{}", employeeDTO.getName());
        employeeService.update(employeeDTO);
        return Result.success();
    }

    /**
     * 退出登录
     *
     * 说明：JWT 是无状态的，服务端没有"会话"需要销毁——真正的退出动作在前端
     * （删除本地保存的 token）。这个接口存在的意义，是让前端走完
     * "调用成功 → 清理本地 token → 跳转登录页"的标准流程。
     * 注意：退出后旧 token 在有效期内（2小时）依然可用，若要立即作废需引入令牌黑名单（Redis）。
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result logout() {
        log.info("员工退出登录");
        return Result.success();
    }
}
