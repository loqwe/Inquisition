package moe.dazecake.inquisition.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SklandSignerTest {

    @Test
    void matchesTheSklandSigningAlgorithm() {
        var result = SklandSigner.sign(
                "test-token",
                "https://zonai.skland.com/api/v1/game/player/info?uid=123456789",
                1720000000L);

        assertEquals("86e580b24b5b04909601b152fdf52d53", result.signature());
        assertEquals("1720000000", result.headers().get("timestamp"));
        assertEquals("", result.headers().get("platform"));
        assertEquals("", result.headers().get("dId"));
        assertEquals("", result.headers().get("vName"));
    }
}
