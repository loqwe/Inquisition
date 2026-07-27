package moe.dazecake.inquisition.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryBackoffTest {
    @Test
    void growsToOneHourAndThenCaps() {
        assertEquals(2, RetryBackoff.delayMinutes(1));
        assertEquals(5, RetryBackoff.delayMinutes(2));
        assertEquals(15, RetryBackoff.delayMinutes(3));
        assertEquals(30, RetryBackoff.delayMinutes(4));
        assertEquals(60, RetryBackoff.delayMinutes(5));
        assertEquals(60, RetryBackoff.delayMinutes(20));
    }
}
