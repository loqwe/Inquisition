package moe.dazecake.inquisition;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountDTOPresenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omittedNoticeIsNotTreatedAsAnUpdate() throws Exception {
        AccountDTO dto = mapper.readValue("{\"config\":{\"script\":{}},\"active\":{}}", AccountDTO.class);

        assertTrue(dto.isConfigPresent());
        assertTrue(dto.isActivePresent());
        assertFalse(dto.isNoticePresent());
        assertFalse(dto.isNamePresent());
    }
}
