from dataclasses import dataclass
from typing import Dict, List, Any


class TruncateSide:
    NONE = "none"
    LEFT = "left"
    RIGHT = "right"
    MIDDLE = "middle"


class AgentState:
    RUNNING = "running"
    
    FINISHED = "finished"
    
    MAX_CALLS_REACHED = "max_calls_reached"
    TOKEN_LIMIT_REACHED = "token_limit_reached"
    
    LLM_ERROR = "llm_error"
    FORMAT_ERROR = "format_error"


class ExecutionMode:
    EFFICIENCY = "efficiency"
    ACCURACY = "accuracy"


class TTSStrategy:
    DISCARD_VERIFY = "discard_verify"


@dataclass
class AgentResult:
    task_id: str
    question: str
    answer: str
    prediction: str
    termination: str
    messages: List[Dict[str, Any]]
    rounds: int = 0
    execution_mode: str = ""
    rollout_id: int = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "question": self.question,
            "answer": self.answer,
            "prediction": self.prediction,
            "termination": self.termination,
            "messages": self.messages,
            "rollout_id": self.rollout_id,
            "stats": {
                "rounds": self.rounds,
                "execution_mode": self.execution_mode,
            },
        }
