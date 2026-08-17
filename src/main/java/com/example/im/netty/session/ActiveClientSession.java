package com.example.im.netty.session;

public record ActiveClientSession(SessionKey key, ClientConnection connection) {
}
