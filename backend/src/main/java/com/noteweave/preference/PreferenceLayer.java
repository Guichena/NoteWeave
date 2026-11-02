package com.noteweave.preference;

/** F5-01：偏好层级，数字越小优先级越高。 */
public enum PreferenceLayer {

    THIS_TURN(0, "this_turn"),
    CONVERSATION_TOPIC(1, "conversation_topic"),
    LONG_TERM(2, "long_term");

    private final int precedence;
    private final String wire;

    PreferenceLayer(int precedence, String wire) {
        this.precedence = precedence;
        this.wire = wire;
    }

    public int precedence() {
        return precedence;
    }

    public String wire() {
        return wire;
    }
}
