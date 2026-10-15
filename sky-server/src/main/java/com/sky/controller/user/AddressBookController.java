package com.sky.controller.user;

import com.sky.dto.AddressBookDTO;
import com.sky.entity.AddressBook;
import com.sky.result.Result;
import com.sky.service.AddressBookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userAddressBookController")
@RequestMapping("/user/addressBook")
@Tag(name = "用户端地址簿接口")
public class AddressBookController {
    @Autowired
    private AddressBookService addressBookService;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleInvalidBody(HttpMessageNotReadableException ex) {
        return Result.error("地址簿请求体缺失或格式错误");
    }

    @GetMapping("/list")
    @Operation(summary = "查询当前用户的地址列表")
    public Result<List<AddressBook>> list() {
        return Result.success(addressBookService.list());
    }

    @PostMapping
    @Operation(summary = "新增地址，默认不设为默认地址")
    public Result<Void> save(@RequestBody AddressBookDTO dto) {
        addressBookService.save(dto);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据id查询当前用户的地址")
    public Result<AddressBook> getById(@PathVariable Long id) {
        return Result.success(addressBookService.getById(id));
    }

    @PutMapping
    @Operation(summary = "修改地址信息，保留默认状态")
    public Result<Void> update(@RequestBody AddressBookDTO dto) {
        addressBookService.update(dto);
        return Result.success();
    }

    @DeleteMapping
    @Operation(summary = "删除地址，删除默认地址后不自动补选")
    public Result<Void> delete(@RequestParam(required = false) Long id) {
        addressBookService.delete(id);
        return Result.success();
    }

    @PutMapping("/default")
    @Operation(summary = "设置默认地址，请求体只需id")
    public Result<Void> setDefault(@RequestBody AddressBookDTO dto) {
        addressBookService.setDefault(dto.getId());
        return Result.success();
    }

    @GetMapping("/default")
    @Operation(summary = "查询默认地址，无默认地址时data为null")
    public Result<AddressBook> getDefault() {
        return Result.success(addressBookService.getDefault());
    }
}
