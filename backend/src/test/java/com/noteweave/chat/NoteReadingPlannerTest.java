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

    @Test
    void crossChunkContinuationOnlyTouchesTheBoundaryWindow() {
        NoteReadingPlanner planner = new NoteReadingPlanner();

        var tight = planner.planPrescored(List.of(
                window(3, 2, "primary").withScore(10),
                window(3, 3, "later").withScore(1),
                window(4, 2, "same-number").withScore(1)), 2);
        assertThat(tight.get(0).chunkNo()).isEqualTo(3);
        assertThat(tight.get(0).windowNo()).isEqualTo(2);
        assertThat(tight).extracting(window -> window.chunkNo() + ":" + window.windowNo())
                .containsExactly("3:2", "3:3");

        var blocked = planner.planPrescored(List.of(
                window(3, 2, "primary").withScore(10),
                window(3, 3, "later").withScore(1),
                window(4, 2, "same-number").withScore(1)), 3);
        assertThat(continuations(blocked)).containsExactly("3:3");
        assertThat(blocked)
                .filteredOn(window -> window.chunkNo() == 4 && window.windowNo() == 2)
                .extracting(ReadingWindow::readRole)
                .doesNotContain("continuation-window");

        var previous = planner.planPrescored(List.of(
                window(3, 0, "primary").withScore(10),
                window(3, 1, "next-in-chunk").withScore(1),
                window(2, 0, "chunk-head").withScore(1),
                window(2, 7, "chunk-tail").withScore(1),
                window(4, 0, "following").withScore(1)), 4);
        assertThat(previous.get(0).chunkNo()).isEqualTo(3);
        assertThat(previous.get(0).windowNo()).isZero();
        assertThat(continuations(previous)).contains("2:7").doesNotContain("2:0", "4:0");

        var next = planner.planPrescored(List.of(
                window(3, 2, "primary").withScore(10),
                window(3, 1, "previous-in-chunk").withScore(1),
                window(4, 0, "following-head").withScore(1),
                window(4, 2, "following-later").withScore(1)), 4);
        assertThat(next.get(0).chunkNo()).isEqualTo(3);
        assertThat(next.get(0).windowNo()).isEqualTo(2);
        assertThat(continuations(next)).contains("4:0", "3:1").doesNotContain("4:2");
    }

    private List<String> continuations(List<ReadingWindow> plan) {
        return plan.stream()
                .filter(window -> "continuation-window".equals(window.readRole()))
                .map(window -> window.chunkNo() + ":" + window.windowNo())
                .toList();
    }

    private ReadingWindow window(int chunk, int no, String content) {
        return new ReadingWindow("c" + chunk + no, "s", "ss", chunk, "h", "t", "", "",
                no, content, "loc", 0, "candidate-window", no, "candidate-pool");
    }
}
