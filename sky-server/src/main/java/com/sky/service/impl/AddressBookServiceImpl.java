package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.AddressBookDTO;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.AddressBookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressBookServiceImpl implements AddressBookService {
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private UserMapper userMapper;

    @Override
    public List<AddressBook> list() {
        return addressBookMapper.listByUserId(currentUserId());
    }

    @Override
    @Transactional
    public void save(AddressBookDTO dto) {
        Long userId = currentUserId();
        AddressBook address = buildAddress(dto, userId);
        // 新增忽略客户端传入的id，默认状态只能由专用接口调整。
        address.setId(null);
        address.setIsDefault(0);
        lockUser(userId);
        addressBookMapper.insert(address);
    }

    @Override
    public AddressBook getById(Long id) {
        Long userId = currentUserId();
        requireId(id);
        return requireOwnedAddress(id, userId);
    }

    @Override
    @Transactional
    public void update(AddressBookDTO dto) {
        Long userId = currentUserId();
        requireId(dto == null ? null : dto.getId());
        AddressBook address = buildAddress(dto, userId);
        lockUser(userId);
        requireOwnedAddress(address.getId(), userId);
        addressBookMapper.update(address);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long userId = currentUserId();
        requireId(id);
        lockUser(userId);
        if (addressBookMapper.deleteByIdAndUserId(id, userId) != 1) {
            throw notFound();
        }
        // 删除默认地址后不补选，下一次默认地址查询自然返回null。
    }

    @Override
    @Transactional
    public void setDefault(Long id) {
        Long userId = currentUserId();
        requireId(id);
        lockUser(userId);
        // 必须先验证目标归属，失败时不能取消用户原来的默认地址。
        requireOwnedAddress(id, userId);
        addressBookMapper.clearDefaultByUserId(userId);
        if (addressBookMapper.setDefaultByIdAndUserId(id, userId) != 1) {
            throw notFound();
        }
    }

    @Override
    public AddressBook getDefault() {
        return addressBookMapper.getDefaultByUserId(currentUserId());
    }

    private AddressBook buildAddress(AddressBookDTO dto, Long userId) {
        if (dto == null) {
            throw new AddressBookBusinessException("地址信息不能为空");
        }
        AddressBook address = new AddressBook();
        address.setId(dto.getId());
        address.setUserId(userId);
        address.setConsignee(requiredText(dto.getConsignee(), "收货人", 50));
        String phone = requiredText(dto.getPhone(), "手机号", 11);
        if (!phone.matches("[0-9]{11}")) {
            throw new AddressBookBusinessException("手机号必须为11位数字");
        }
        address.setPhone(phone);
        String sex = optionalText(dto.getSex(), "性别", 2);
        if (sex != null && !"0".equals(sex) && !"1".equals(sex)) {
            throw new AddressBookBusinessException("性别只能为0（女）或1（男）");
        }
        address.setSex(sex);
        address.setProvinceCode(optionalText(dto.getProvinceCode(), "省级区划编号", 12));
        address.setProvinceName(optionalText(dto.getProvinceName(), "省级名称", 32));
        address.setCityCode(optionalText(dto.getCityCode(), "市级区划编号", 12));
        address.setCityName(optionalText(dto.getCityName(), "市级名称", 32));
        address.setDistrictCode(optionalText(dto.getDistrictCode(), "区级区划编号", 12));
        address.setDistrictName(optionalText(dto.getDistrictName(), "区级名称", 32));
        address.setDetail(requiredText(dto.getDetail(), "详细地址", 200));
        address.setLabel(optionalText(dto.getLabel(), "标签", 100));
        return address;
    }

    private String requiredText(String value, String field, int maxLength) {
        String text = optionalText(value, field, maxLength);
        if (text == null) {
            throw new AddressBookBusinessException(field + "不能为空");
        }
        return text;
    }

    private String optionalText(String value, String field, int maxLength) {
        String text = value == null ? null : value.trim();
        if (text != null && text.length() > maxLength) {
            throw new AddressBookBusinessException(field + "不能超过" + maxLength + "个字符");
        }
        return text == null || text.isEmpty() ? null : text;
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new AddressBookBusinessException("地址id必须为正整数");
        }
    }

    private AddressBook requireOwnedAddress(Long id, Long userId) {
        AddressBook address = addressBookMapper.getByIdAndUserId(id, userId);
        if (address == null) {
            throw notFound();
        }
        return address;
    }

    private AddressBookBusinessException notFound() {
        // 不区分不存在和属于他人，避免暴露其他用户的地址信息。
        return new AddressBookBusinessException("地址不存在或无权操作");
    }

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return userId;
    }

    private void lockUser(Long userId) {
        // 与购物车相同：复用已有用户行作为锁，所有地址写操作按用户串行执行。
        if (userMapper.lockById(userId) == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
    }
}
