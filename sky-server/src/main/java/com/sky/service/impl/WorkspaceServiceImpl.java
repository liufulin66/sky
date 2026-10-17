package com.sky.service.impl;

import com.sky.dto.WorkspaceBusinessDataDTO;
import com.sky.mapper.WorkspaceMapper;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {
    private final WorkspaceMapper workspaceMapper;
    private final Clock clock;

    @Autowired
    public WorkspaceServiceImpl(WorkspaceMapper workspaceMapper) {
        this(workspaceMapper, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    // 测试可提供固定时间，验证午夜切换；生产环境使用上面的北京时间时钟。
    WorkspaceServiceImpl(WorkspaceMapper workspaceMapper, Clock clock) {
        this.workspaceMapper = workspaceMapper;
        this.clock = clock;
    }

    @Override
    public BusinessDataVO businessData() {
        LocalDate today = LocalDate.now(clock);
        WorkspaceBusinessDataDTO data = workspaceMapper.businessData(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        BigDecimal turnover = data.getTurnover();
        int validCount = data.getValidOrderCount();
        BigDecimal unitPrice = validCount == 0 ? BigDecimal.ZERO
                : turnover.divide(BigDecimal.valueOf(validCount), 2, RoundingMode.HALF_UP);
        BigDecimal rate = data.getAllOrders() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(validCount).divide(BigDecimal.valueOf(data.getAllOrders()), 4, RoundingMode.HALF_UP);
        // 保持现有VO的Double字段及JSON契约；所有金额求和与除法此前均使用BigDecimal。
        return BusinessDataVO.builder().turnover(turnover.doubleValue()).validOrderCount(validCount)
                .orderCompletionRate(rate.doubleValue()).unitPrice(unitPrice.doubleValue())
                .newUsers(data.getNewUsers()).build();
    }

    @Override
    public OrderOverViewVO overviewOrders() {
        LocalDate today = LocalDate.now(clock);
        return workspaceMapper.overviewOrders(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    @Override
    public DishOverViewVO overviewDishes() {
        return workspaceMapper.overviewDishes();
    }

    @Override
    public SetmealOverViewVO overviewSetmeals() {
        return workspaceMapper.overviewSetmeals();
    }
}
