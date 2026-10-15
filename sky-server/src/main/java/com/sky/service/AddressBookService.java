package com.sky.service;

import com.sky.dto.AddressBookDTO;
import com.sky.entity.AddressBook;

import java.util.List;

public interface AddressBookService {
    List<AddressBook> list();

    void save(AddressBookDTO dto);

    AddressBook getById(Long id);

    void update(AddressBookDTO dto);

    void delete(Long id);

    void setDefault(Long id);

    AddressBook getDefault();
}
