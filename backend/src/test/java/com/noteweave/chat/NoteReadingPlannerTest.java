package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoteReadingPlannerTest {
    @Test
    void shouldKeepPrimaryAdjacentAndSecondaryWindowPlan() {
        NoteReadingPlanner planner = new NoteReadingPlanner();
        var result = planner.plan(List.of(
                window(0, 0, "other"), window(0, 1, "target"),
                window(0, 2, "context"), window(4, 0, "target")), "target", 3);
        assertThat(result).extracting(ReadingWindow::readRole)
                .containsExactly("primary-window", "continuation-window", "continuation-window");
        assertThat(result.get(0).windowNo()).isEqualTo(1);
    }

    private ReadingWindow window(int chunk, int no, String content) {
        return new ReadingWindow("c" + chunk + no, "s", "ss", chunk, "h", "t", "", "",
                no, content, "loc", 0, "candidate-window", no, "candidate-pool");
    }
}
