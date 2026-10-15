package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.AddressBookDTO;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddressBookServiceImplTest {
    private AddressBookServiceImpl service;
    private AddressBookMapper mapper;
    private UserMapper users;

    @BeforeEach
    void setUp() {
        mapper = mock(AddressBookMapper.class);
        users = mock(UserMapper.class);
        service = new AddressBookServiceImpl();
        ReflectionTestUtils.setField(service, "addressBookMapper", mapper);
        ReflectionTestUtils.setField(service, "userMapper", users);
        BaseContext.setCurrentId(5L);
        when(users.lockById(5L)).thenReturn(5L);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    private AddressBookDTO form() {
        AddressBookDTO dto = new AddressBookDTO();
        dto.setConsignee(" 张三 ");
        dto.setPhone("13800138000");
        dto.setDetail(" 示例路1号 ");
        return dto;
    }

    @Test
    void createUsesCurrentIdentityAndDoesNotMakeFirstAddressDefault() {
        AddressBookDTO dto = form();
        dto.setId(99L);
        service.save(dto);
        verify(mapper).insert(argThat(a -> a.getId() == null && a.getUserId().equals(5L)
                && a.getIsDefault() == 0 && "张三".equals(a.getConsignee())
                && "示例路1号".equals(a.getDetail())));
        verify(mapper, never()).clearDefaultByUserId(anyLong());
    }

    @Test
    void updateAllowsOptionalFieldsToBeClearedWithoutChangingDefaultState() {
        AddressBookDTO dto = form();
        dto.setId(1L);
        dto.setLabel("   ");
        when(mapper.getByIdAndUserId(1L, 5L)).thenReturn(AddressBook.builder().id(1L).isDefault(1).build());
        service.update(dto);
        verify(mapper).update(argThat(a -> a.getId().equals(1L) && a.getUserId().equals(5L)
                && a.getLabel() == null && a.getIsDefault() == null));
    }

    @Test
    void foreignOrMissingAddressCannotBeReadUpdatedOrSetDefault() {
        assertThrows(AddressBookBusinessException.class, () -> service.getById(99L));
        AddressBookDTO dto = form();
        dto.setId(99L);
        assertThrows(AddressBookBusinessException.class, () -> service.update(dto));
        assertThrows(AddressBookBusinessException.class, () -> service.setDefault(99L));
        verify(mapper, never()).update(any());
        verify(mapper, never()).clearDefaultByUserId(anyLong());
    }

    @Test
    void settingDefaultLocksUserAndChecksOwnershipBeforeClearingOldDefault() {
        when(mapper.getByIdAndUserId(1L, 5L)).thenReturn(AddressBook.builder().id(1L).build());
        when(mapper.setDefaultByIdAndUserId(1L, 5L)).thenReturn(1);
        service.setDefault(1L);
        var order = inOrder(users, mapper);
        order.verify(users).lockById(5L);
        order.verify(mapper).getByIdAndUserId(1L, 5L);
        order.verify(mapper).clearDefaultByUserId(5L);
        order.verify(mapper).setDefaultByIdAndUserId(1L, 5L);
    }

    @Test
    void deletionIsScopedToUserAndNeverPromotesAnotherAddress() {
        when(mapper.deleteByIdAndUserId(1L, 5L)).thenReturn(1);
        service.delete(1L);
        verify(mapper).deleteByIdAndUserId(1L, 5L);
        verify(mapper, never()).setDefaultByIdAndUserId(anyLong(), anyLong());
        assertThrows(AddressBookBusinessException.class, () -> service.delete(99L));
    }

    @Test
    void emptyListAndMissingDefaultAreSuccessfulEmptyResults() {
        when(mapper.listByUserId(5L)).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
        assertNull(service.getDefault());
    }

    @Test
    void missingIdentityOrDeletedUserCannotWrite() {
        BaseContext.removeCurrentId();
        assertThrows(UserNotLoginException.class, () -> service.save(form()));
        assertThrows(UserNotLoginException.class, () -> service.list());
        BaseContext.setCurrentId(999L);
        when(users.lockById(999L)).thenReturn(null);
        assertThrows(UserNotLoginException.class, () -> service.save(form()));
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidFormsAndIdsNeverReachDatabase() {
        assertThrows(AddressBookBusinessException.class, () -> service.save(null));
        for (String phone : new String[]{null, "", "123", "1380013800a", "138001380000"}) {
            AddressBookDTO dto = form();
            dto.setPhone(phone);
            assertThrows(AddressBookBusinessException.class, () -> service.save(dto));
        }
        AddressBookDTO dto = form();
        dto.setConsignee(" ");
        assertThrows(AddressBookBusinessException.class, () -> service.save(dto));
        dto.setConsignee("张".repeat(51));
        assertThrows(AddressBookBusinessException.class, () -> service.save(dto));
        AddressBookDTO noDetail = form();
        noDetail.setDetail(" ");
        assertThrows(AddressBookBusinessException.class, () -> service.save(noDetail));
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertThrows(AddressBookBusinessException.class, () -> service.getById(id));
            assertThrows(AddressBookBusinessException.class, () -> service.delete(id));
            assertThrows(AddressBookBusinessException.class, () -> service.setDefault(id));
        }
        AddressBookDTO invalidSex = form();
        invalidSex.setSex("2");
        assertThrows(AddressBookBusinessException.class, () -> service.save(invalidSex));
        verifyNoInteractions(mapper, users);
    }
}
