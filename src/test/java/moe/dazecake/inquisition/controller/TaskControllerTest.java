package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.model.dto.account.AccountIDDTO;
import moe.dazecake.inquisition.model.vo.task.TaskBoardVO;
import moe.dazecake.inquisition.service.impl.TaskBoardService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskControllerTest {

    @Test
    void exposesOneConsistentTaskBoardSnapshot() {
        var controller = new TaskController();
        controller.taskBoardService = mock(TaskBoardService.class);
        var board = new TaskBoardVO();
        when(controller.taskBoardService.getBoard(any())).thenReturn(board);

        var result = controller.showTaskBoard();

        assertEquals(200, result.getCode());
        assertSame(board, result.getData());
    }

    @Test
    void retriesAndCancelsOnlyExistingTwentySixUrgentTasks() {
        var controller = new TaskController();
        controller.taskBoardService = mock(TaskBoardService.class);
        var request = new AccountIDDTO();
        request.setId(11L);
        when(controller.taskBoardService.retryUrgentTask(any(), any())).thenReturn(true);
        when(controller.taskBoardService.cancelUrgentTask(any(), any())).thenReturn(false);

        assertEquals(200, controller.retryUrgentTask(request).getCode());
        assertEquals(404, controller.cancelUrgentTask(request).getCode());
    }
}
