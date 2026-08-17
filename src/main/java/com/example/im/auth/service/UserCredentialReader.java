package com.example.im.auth.service;

import java.util.Optional;

public interface UserCredentialReader {

    Optional<UserCredential> findByUsername(String username);
}
