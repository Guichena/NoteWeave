from abc import ABC, abstractmethod
from typing import Dict, Any


class BaseAgent(ABC):
    
    @abstractmethod
    def execute(self, question: str, **kwargs) -> Dict[str, Any]:
        pass
    
    def __call__(self, question: str, **kwargs) -> Dict[str, Any]:
        return self.execute(question, **kwargs)
