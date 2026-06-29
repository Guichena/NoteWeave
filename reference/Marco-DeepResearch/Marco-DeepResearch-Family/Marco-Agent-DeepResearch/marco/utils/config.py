import os
import yaml
from typing import Optional
from dataclasses import dataclass, field
from pathlib import Path


def deep_merge(base: dict, override: dict) -> dict:
    result = base.copy()
    for key, value in override.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def load_yaml(path: str) -> dict:
    with open(path, 'r', encoding='utf-8') as f:
        return yaml.safe_load(f) or {}


@dataclass
class ModelConfig:
    name: str = ""
    api_key_env: str = "OPENAI_API_KEY"
    api_base_env: str = "OPENAI_BASE_URL"
    model_name_env: str = "MODEL_NAME"
    max_context_length: int = 65536
    tokenizer_path: str = ""
    
    @property
    def api_key(self) -> str:
        return os.getenv(self.api_key_env, "")
    
    @property
    def api_base(self) -> str:
        return os.getenv(self.api_base_env, "")
    
    @property
    def model_name(self) -> str:
        return os.getenv(self.model_name_env, "") or self.name


@dataclass
class GenerationConfig:
    max_tokens: int = 8192
    temperature: float = 0.7
    top_p: float = 0.95
    max_retries: int = 5
    stop_sequences: list = field(default_factory=lambda: ["</tool_call>"])


@dataclass
class AgentConfig:
    strategy: str = "single_agent"
    max_llm_calls: int = 128
    execution_mode: str = "efficiency"
    truncate_side: str = "none"
    max_tool_response_length: int = 65536
    max_retries: int = 3
    token_safety_buffer: int = 8192
    tts_enabled: bool = False
    tts_strategy: str = "discard_verify"
    tts_max_discard_count: int = 3
    tts_round_threshold: Optional[int] = None
    tts_give_up_llm_judge: bool = False
    tts_verify_enabled: bool = False
    tts_verify_max_answers: int = 8
    tts_verify_early_stop_count: int = 4
    search_empty_result_restart_enabled: bool = False
    search_empty_result_pattern: str = "Results:0 "


@dataclass
class ToolsConfig:
    enabled: list = field(default_factory=lambda: ["search", "visit"])
    search: dict = field(default_factory=lambda: {
        "timeout": 20,
        "max_retries": 3,
        "max_workers": 10,
        "max_results_per_query": 10,
    })
    visit: dict = field(default_factory=lambda: {
        "mode": "hybrid",
        "timeout": 15,
        "max_retries": 2,
        "max_workers": 3,
        "max_content_length": 409600,
        "summary_max_tokens": 4096,
        "summary_max_retries": 3,
    })


@dataclass
class RunnerConfig:
    max_workers: int = 5
    rollout_count: int = 8
    output_dir: str = "output"

@dataclass
class FieldMapping:
    question: str = "question"
    answer: str = "answer"
    task_id: str = "task_id"


@dataclass
class BenchmarkConfig:
    name: str = ""
    description: str = ""
    data_path: str = ""


class Config:
    
    def __init__(self, config_dict: Optional[dict] = None):
        config_dict = config_dict or {}
        
        model_cfg = config_dict.get("model", {})
        self.model = ModelConfig(
            name=model_cfg.get("name", ""),
            api_key_env=model_cfg.get("api_key_env", "OPENAI_API_KEY"),
            api_base_env=model_cfg.get("api_base_env", "OPENAI_BASE_URL"),
            model_name_env=model_cfg.get("model_name_env", "MODEL_NAME"),
            max_context_length=model_cfg.get("max_context_length", 65536),
            tokenizer_path=model_cfg.get("tokenizer_path", ""),
        )
        
        gen_cfg = config_dict.get("generation", {})
        self.generation = GenerationConfig(
            max_tokens=gen_cfg.get("max_tokens", 8192),
            temperature=gen_cfg.get("temperature", 0.7),
            top_p=gen_cfg.get("top_p", 0.95),
            max_retries=gen_cfg.get("max_retries", 5),
            stop_sequences=gen_cfg.get("stop_sequences", ["</tool_call>"]),
        )
        
        agent_cfg = config_dict.get("agent", {})
        self.agent = AgentConfig(
            strategy=agent_cfg.get("strategy", "single_agent"),
            max_llm_calls=agent_cfg.get("max_llm_calls", 128),
            execution_mode=agent_cfg.get("execution_mode", "efficiency"),
            truncate_side=agent_cfg.get("truncate_side", "none"),
            max_tool_response_length=agent_cfg.get("max_tool_response_length", 65536),
            max_retries=agent_cfg.get("max_retries", 3),
            token_safety_buffer=agent_cfg.get("token_safety_buffer", 8192),
            tts_enabled=agent_cfg.get("tts_enabled", False),
            tts_strategy=agent_cfg.get("tts_strategy", "discard_verify"),
            tts_max_discard_count=agent_cfg.get("tts_max_discard_count", 3),
            tts_round_threshold=agent_cfg.get("tts_round_threshold"),
            tts_give_up_llm_judge=agent_cfg.get("tts_give_up_llm_judge", False),
            tts_verify_enabled=agent_cfg.get("tts_verify_enabled", False),
            tts_verify_max_answers=agent_cfg.get("tts_verify_max_answers", 8),
            tts_verify_early_stop_count=agent_cfg.get("tts_verify_early_stop_count", 4),
            search_empty_result_restart_enabled=agent_cfg.get("search_empty_result_restart_enabled", False),
            search_empty_result_pattern=agent_cfg.get("search_empty_result_pattern", "Results:0 "),
        )
        
        tools_cfg = config_dict.get("tools", {})
        _default_tools = ToolsConfig()
        self.tools = ToolsConfig(
            enabled=tools_cfg.get("enabled", list(_default_tools.enabled)),
            search=tools_cfg.get("search", dict(_default_tools.search)),
            visit=tools_cfg.get("visit", dict(_default_tools.visit)),
        )
        
        runner_cfg = config_dict.get("runner", {})
        self.runner = RunnerConfig(
            max_workers=runner_cfg.get("max_workers", 5),
            rollout_count=runner_cfg.get("rollout_count", 8),
            output_dir=runner_cfg.get("output_dir", "output"),
        )
        
        benchmark_cfg = config_dict.get("benchmark", {})
        self.benchmark = BenchmarkConfig(
            name=benchmark_cfg.get("name", ""),
            description=benchmark_cfg.get("description", ""),
            data_path=benchmark_cfg.get("data_path", ""),
        )
        
        field_cfg = config_dict.get("field_mapping", {})
        self.field_mapping = FieldMapping(
            question=field_cfg.get("question", "question"),
            answer=field_cfg.get("answer", "answer"),
            task_id=field_cfg.get("task_id", "task_id"),
        )
        
        self._raw_config = config_dict
    
    @property
    def raw(self) -> dict:
        return self._raw_config
    
    def to_dict(self) -> dict:
        from dataclasses import asdict
        
        return {
            "model": {
                "name": self.model.name,
                "api_key_env": self.model.api_key_env,
                "api_base_env": self.model.api_base_env,
                "model_name_env": self.model.model_name_env,
                "max_context_length": self.model.max_context_length,
                "tokenizer_path": self.model.tokenizer_path,
                "_resolved_model_name": self.model.model_name,
            },
            "generation": asdict(self.generation),
            "agent": asdict(self.agent),
            "tools": asdict(self.tools),
            "runner": asdict(self.runner),
            "benchmark": asdict(self.benchmark),
            "field_mapping": asdict(self.field_mapping),
        }
    
    def save_to_file(self, filepath: str, format: str = "yaml") -> None:
        import json
        
        config_dict = self.to_dict()
        
        with open(filepath, 'w', encoding='utf-8') as f:
            if format == "yaml":
                yaml.dump(config_dict, f, default_flow_style=False, allow_unicode=True, sort_keys=False)
            else:
                json.dump(config_dict, f, ensure_ascii=False, indent=2)
    
    def get_llm_config(self) -> dict:
        return {
            "model": self.model.name,
            "generate_cfg": {
                "max_tokens": self.generation.max_tokens,
                "temperature": self.generation.temperature,
                "top_p": self.generation.top_p,
                "max_retries": self.generation.max_retries,
            },
        }


def load_config(
    benchmark_name: Optional[str] = None,
    profile_name: Optional[str] = None,
    config_dir: str = "configs",
    default_config: str = "default.yaml",
    benchmark_dir: str = "benchmarks",
    profile_dir: str = "profiles"
) -> Config:
    project_root = Path(__file__).resolve().parent.parent.parent
    config_path = project_root.joinpath(config_dir)
    
    default_path = config_path.joinpath(default_config)
    if default_path.exists():
        config_dict = load_yaml(str(default_path))
    else:
        config_dict = {}
    
    if profile_name:
        profile_path = config_path.joinpath(profile_dir, f"{profile_name}.yaml")
        if profile_path.exists():
            profile_dict = load_yaml(str(profile_path))
            config_dict = deep_merge(config_dict, profile_dict)
        else:
            raise FileNotFoundError(f"Profile config not found: {profile_path}")
    
    if benchmark_name:
        benchmark_path = config_path.joinpath(benchmark_dir, f"{benchmark_name}.yaml")
        if benchmark_path.exists():
            benchmark_dict = load_yaml(str(benchmark_path))
            config_dict = deep_merge(config_dict, benchmark_dict)
        else:
            raise FileNotFoundError(f"Benchmark config not found: {benchmark_path}")
    
    return Config(config_dict)

