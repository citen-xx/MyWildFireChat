package com.example.im.auth.controller;

import com.example.im.auth.service.AuthService;
import com.example.im.auth.service.LoginCommand;
import com.example.im.auth.service.LoginResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginCommand command) {
        return authService.login(command);
    }
}
