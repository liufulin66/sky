package com.sky.mapper;

import com.sky.entity.AddressBook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AddressBookMapper {
    List<AddressBook> listByUserId(Long userId);

    AddressBook getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    void insert(AddressBook addressBook);

    int update(AddressBook addressBook);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    void clearDefaultByUserId(Long userId);

    int setDefaultByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    AddressBook getDefaultByUserId(Long userId);
}
