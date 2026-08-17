package com.example.im.netty.session;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    @Test
    void bindShouldReplaceSameUserDeviceSession() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        EmbeddedChannel newChannel = new EmbeddedChannel();

        sessionManager.bind(1001L, "iphone", oldChannel);
        sessionManager.bind(1001L, "iphone", newChannel);

        assertThat(sessionManager.findChannel(1001L, "iphone")).contains(newChannel);
        assertThat(oldChannel.isOpen()).isFalse();
        assertThat(sessionManager.onlineSessionCount()).isEqualTo(1);
    }

    @Test
    void removeOldChannelShouldNotRemoveReplacementSession() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        EmbeddedChannel newChannel = new EmbeddedChannel();

        sessionManager.bind(1001L, "iphone", oldChannel);
        sessionManager.bind(1001L, "iphone", newChannel);
        sessionManager.remove(oldChannel);

        assertThat(sessionManager.findChannel(1001L, "iphone")).contains(newChannel);
        assertThat(sessionManager.onlineSessionCount()).isEqualTo(1);
    }
}
