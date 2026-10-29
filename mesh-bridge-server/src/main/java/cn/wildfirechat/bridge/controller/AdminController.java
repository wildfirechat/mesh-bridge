package cn.wildfirechat.bridge.controller;

import cn.wildfirechat.bridge.model.PojoDomainId;
import cn.wildfirechat.bridge.pojo.DomainRequest;
import cn.wildfirechat.bridge.pojo.LoginRequest;
import cn.wildfirechat.bridge.pojo.UpdatePasswordRequest;
import cn.wildfirechat.bridge.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/admin")
public class AdminController {
    @Autowired
    AdminService adminService;

    @GetMapping("/hello")
    public Object hello() {
        return "Hello from admin controller";
    }

    @PostMapping(value = "login", produces = "application/json;charset=UTF-8")
    public Object login(@RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        return adminService.login(response, request.getAccount(), request.getPassword(), httpRequest.getRemoteAddr());
    }

    @PostMapping(value = "logout", produces = "application/json;charset=UTF-8")
    public Object logout() {
        return adminService.logout();
    }

    @PostMapping(value = "update_pwd", produces = "application/json;charset=UTF-8")
    public Object updatePwd(@RequestBody UpdatePasswordRequest request) {
        return adminService.updatePassword(request.oldPassword, request.newPassword);
    }

    @PostMapping(value = "account", produces = "application/json;charset=UTF-8")
    public Object getAccount() {
        return adminService.getAccount();
    }

    @Transactional
    @PostMapping(value = "/create_domain", produces = "application/json;charset=UTF-8")
    public Object createDomain(@RequestBody DomainRequest domain) throws Exception {
        return adminService.createDomain(domain);
    }

    @Transactional
    @PostMapping(value = "/update_domain", produces = "application/json;charset=UTF-8")
    public Object updateDomain(@RequestBody DomainRequest domain) throws Exception {
        return adminService.updateDomain(domain);
    }

    @Transactional
    @PostMapping(value = "/delete_domain", produces = "application/json;charset=UTF-8")
    public Object deleteDomain(@RequestBody PojoDomainId domainId) throws Exception {
        return adminService.deleteDomain(domainId.domainId);
    }

    @PostMapping(value = "/get_domain", produces = "application/json;charset=UTF-8")
    public Object getDomain(@RequestBody PojoDomainId domainId) {
        return adminService.getDomain(domainId.domainId);
    }

    @PostMapping(value = "/list_domain", produces = "application/json;charset=UTF-8")
    public Object listDomain() {
        return adminService.listDomain();
    }

    @PostMapping(value = "/ping_domain", produces = "application/json;charset=UTF-8")
    public Object pingDomain(@RequestBody PojoDomainId domainId) throws Exception {
        return adminService.pingDomain(domainId.domainId);
    }
}
