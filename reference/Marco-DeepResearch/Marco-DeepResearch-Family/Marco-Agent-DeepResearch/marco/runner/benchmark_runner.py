import logging
import os
import json
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Any, Optional
from pathlib import Path
from tqdm import tqdm

from ..agent import MarcoAgent
from ..utils import Config

logger = logging.getLogger(__name__)


class BenchmarkRunner:
    
    def __init__(self, config: Config):
        self.config = config
        self.agent = MarcoAgent(config)
    
    def load_dataset(self, data_path: Optional[str] = None) -> List[Dict]:
        data_path = data_path or self.config.benchmark.data_path
        
        if not os.path.exists(data_path):
            raise FileNotFoundError(f"Dataset not found: {data_path}")
        
        items = []
        if data_path.endswith(".json"):
            with open(data_path, "r", encoding="utf-8") as f:
                items = json.load(f)
        elif data_path.endswith(".jsonl"):
            with open(data_path, "r", encoding="utf-8") as f:
                items = [json.loads(line) for line in f if line.strip()]
        else:
            raise ValueError(f"Unsupported file format: {data_path}")
        
        return items
    
    def _extract_question(self, item: Dict) -> str:
        field_name = self.config.field_mapping.question
        question = item.get(field_name, "").strip()
        if not question:
            try:
                user_msg = item["messages"][1]["content"]
                question = user_msg.split("User:")[1].strip() if "User:" in user_msg else user_msg
            except Exception:
                pass
        return question
    
    def _extract_answer(self, item: Dict) -> str:
        field_name = self.config.field_mapping.answer
        return item.get(field_name, "")
    
    def _extract_task_id(self, item: Dict) -> str:
        field_name = self.config.field_mapping.task_id
        return item.get(field_name, "")
    
    def _load_processed(self, output_file: str) -> set:
        processed = set()
        if os.path.exists(output_file):
            with open(output_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        data = json.loads(line)
                        if "question" in data and "error" not in data:
                            processed.add(data["question"].strip())
                    except json.JSONDecodeError:
                        continue
        return processed
    
    def _run_single_task(self, task: Dict) -> Dict:
        item = task["item"]
        rollout_id = task["rollout_id"]
        
        question = self._extract_question(item)
        answer = self._extract_answer(item)
        task_id = self._extract_task_id(item)
        
        agent = MarcoAgent(self.config)
        result = agent.execute(
            question=question,
            answer=answer,
            task_id=task_id
        )
        result.rollout_id = rollout_id
        return result
    
    def run(
            self,
            output_dir: Optional[str] = None,
            data_path: Optional[str] = None,
            rollout_count: Optional[int] = None,
            max_workers: Optional[int] = None,
        ) -> str:
        output_dir = output_dir or self.config.runner.output_dir
        rollout_count = rollout_count or self.config.runner.rollout_count
        max_workers = max_workers or self.config.runner.max_workers
        
        benchmark_name = self.config.benchmark.name
        model_name = self.config.model.model_name or "default_model"
        
        if model_name.startswith('/'):
            model_name = os.path.basename(model_name.rstrip('/'))
        
        output_path = Path(output_dir).joinpath(model_name, benchmark_name)
        output_path.mkdir(parents=True, exist_ok=True)
        
        config_file = output_path.joinpath("config.yaml")
        self.config.save_to_file(str(config_file), format="yaml")
        logger.info("Config saved to: %s", config_file)
        
        logger.info("Benchmark: %s", benchmark_name)
        logger.info("Model: %s", model_name)
        logger.info("Output: %s", output_path)
        logger.info("Rollout count: %d", rollout_count)
        logger.info("Max workers: %d", max_workers)
        
        items = self.load_dataset(data_path)
        logger.info("Total items: %d", len(items))
        
        question_field = self.config.field_mapping.question
        for item in items:
            if question_field not in item or not item.get(question_field, "").strip():
                item[question_field] = self._extract_question(item)
        
        for rollout_idx in range(1, rollout_count + 1):
            output_file = str(output_path.joinpath(f"iter{rollout_idx}.jsonl"))
            
            logger.info("=" * 60)
            logger.info("📍 Rollout %d/%d", rollout_idx, rollout_count)
            logger.info("Output file: %s", output_file)
            
            processed = self._load_processed(output_file)
            logger.info("Already processed: %d", len(processed))
            
            question_field = self.config.field_mapping.question
            tasks = []
            for item in items:
                question = item.get(question_field, "").strip()
                if question and question not in processed:
                    tasks.append({"item": item.copy(), "rollout_id": rollout_idx})
            
            logger.info("Tasks to run: %d", len(tasks))
            
            if not tasks:
                logger.info("Rollout %d completed (all processed)", rollout_idx)
                continue
            
            write_lock = threading.Lock()
            
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                future_to_task = {
                    executor.submit(self._run_single_task, task): task
                    for task in tasks
                }
                
                for future in tqdm(as_completed(future_to_task), total=len(tasks), desc=f"Rollout {rollout_idx}"):
                    task = future_to_task[future]
                    try:
                        result = future.result()
                        result_dict = result.to_dict() if hasattr(result, 'to_dict') else result
                        with write_lock:
                            with open(output_file, "a", encoding="utf-8") as f:
                                f.write(json.dumps(result_dict, ensure_ascii=False) + "\n")
                    except Exception as exc:
                        logger.error("❌ Task error: %s", exc)
                        error_result = {
                            "task_id": task["item"].get(self.config.field_mapping.task_id, ""),
                            "question": task["item"].get("question", ""),
                            "answer": task["item"].get("answer", ""),
                            "rollout_id": task["rollout_id"],
                            "error": str(exc),
                            "messages": [],
                            "prediction": "[Failed]",
                        }
                        with write_lock:
                            with open(output_file, "a", encoding="utf-8") as f:
                                f.write(json.dumps(error_result, ensure_ascii=False) + "\n")
            
            logger.info("Rollout %d completed", rollout_idx)
        
        logger.info("✅ All %d rollouts completed!", rollout_count)
        return str(output_path)
