#!/usr/bin/env python
"""
Marco Benchmark Runner - 推理入口脚本

支持模型配置和数据集配置分离：
- 模型配置：configs/models/*.yaml
- 数据集配置：configs/benchmarks/*.yaml
"""
import argparse
import logging
from pathlib import Path

from marco.utils import load_config
from marco.runner import BenchmarkRunner

logger = logging.getLogger(__name__)


# 项目根目录
PROJECT_ROOT = Path(__file__).resolve().parent
BENCHMARKS_DIR = PROJECT_ROOT.joinpath("configs", "benchmarks")
PROFILES_DIR = PROJECT_ROOT.joinpath("configs", "profiles")


def list_configs():
    """列出所有可用的配置"""
    logger.info("=" * 50)
    logger.info("Available Configurations")
    logger.info("=" * 50)
    
    # 列出 Profile 配置
    logger.info("[Profiles] configs/profiles/")
    logger.info("  (模型、策略、Agent 等运行配置)")
    if PROFILES_DIR.exists():
        for config_file in sorted(PROFILES_DIR.glob("*.yaml")):
            logger.info("  - %s", config_file.stem)
    else:
        logger.info("  No profile configs found")
    
    # 列出数据集配置
    logger.info("[Benchmarks] configs/benchmarks/")
    logger.info("  (数据集路径、字段映射、评估配置)")
    if BENCHMARKS_DIR.exists():
        for config_file in sorted(BENCHMARKS_DIR.glob("*.yaml")):
            logger.info("  - %s", config_file.stem)
    else:
        logger.info("  No benchmark configs found")
    
    logger.info("=" * 50)
    logger.info("Usage: python run_benchmark.py run -b <benchmark> [-p <profile>]")
    logger.info("Example: python run_benchmark.py run -b gaia -p gpt4o")
    logger.info("         python run_benchmark.py run -b gaia -p quick_test")
    logger.info("=" * 50)


def run_inference(args):
    """运行推理"""
    logger.info("=" * 50)
    logger.info("Running Inference")
    logger.info("=" * 50)
    
    # 加载配置：default.yaml -> profile -> benchmark
    config = load_config(
        benchmark_name=args.benchmark,
        profile_name=args.profile
    )
    
    # 显示配置信息
    logger.info("Profile: %s", args.profile or 'default')
    logger.info("Benchmark: %s", args.benchmark)
    
    # 覆盖命令行参数
    if args.model:
        config.model.name = args.model
    if args.output_dir:
        config.runner.output_dir = args.output_dir
    if args.max_workers:
        config.runner.max_workers = args.max_workers
    if args.rollout_count is not None:
        config.runner.rollout_count = args.rollout_count
    if args.temperature is not None:
        config.generation.temperature = args.temperature
    if args.top_p is not None:
        config.generation.top_p = args.top_p
    
    logger.info("=" * 50)
    
    runner = BenchmarkRunner(config)
    output_dir = runner.run(data_path=args.data_path)
    
    return output_dir


class ColorFormatter(logging.Formatter):
    """终端彩色日志 Formatter"""
    COLORS = {
        logging.DEBUG:    "\033[36m",   # cyan
        logging.INFO:     "\033[32m",   # green
        logging.WARNING:  "\033[33m",   # yellow
        logging.ERROR:    "\033[31m",   # red
        logging.CRITICAL: "\033[1;31m", # bold red
    }
    RESET = "\033[0m"

    def format(self, record):
        color = self.COLORS.get(record.levelno, self.RESET)
        record.levelname = f"{color}{record.levelname}{self.RESET}"
        record.name = f"\033[2m{record.name}{self.RESET}"  # dim
        return super().format(record)


def main():
    handler = logging.StreamHandler()
    handler.setFormatter(ColorFormatter(
        fmt="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    ))
    logging.basicConfig(level=logging.INFO, handlers=[handler])
    # 第三方库日志级别：仅显示 WARNING 及以上，避免 httpx/openai 的 INFO 刷屏
    for lib in ("httpx", "httpcore", "openai"):
        logging.getLogger(lib).setLevel(logging.WARNING)
    
    parser = argparse.ArgumentParser(
        description="Marco Benchmark Runner",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Config Loading Order:
  1. configs/default.yaml           - 所有配置的默认值
  2. configs/profiles/<profile>.yaml - 运行配置（模型、策略、Agent）
  3. configs/benchmarks/<benchmark>.yaml - 数据集配置

Examples:
  # 列出所有可用配置
  python run_benchmark.py list
  
  # 使用默认配置运行 gaia
  python run_benchmark.py run -b gaia
  
  # 使用 gpt4o profile 运行 gaia
  python run_benchmark.py run -b gaia -p gpt4o
  
  # 快速测试（少轮次、单线程）
  python run_benchmark.py run -b gaia -p quick_test
  
  # 调试模式（3轮、单线程）
  python run_benchmark.py run -b gaia -p debug
  
  # 精度模式（上下文压缩）
  python run_benchmark.py run -b gaia -p accuracy_mode
        """
    )
    subparsers = parser.add_subparsers(dest="command", help="Available commands")
    
    # list 命令
    subparsers.add_parser("list", help="List available profiles and benchmarks")
    
    # run 命令
    run_parser = subparsers.add_parser("run", help="Run inference on benchmark")
    run_parser.add_argument("--benchmark", "-b", type=str, required=True,
                           help="Benchmark/dataset name (e.g., gaia, browsecomp_en)")
    run_parser.add_argument("--profile", "-p", type=str, default=None,
                           help="Profile name (e.g., gpt4o, quick_test, debug)")
    run_parser.add_argument("--model", "-m", type=str, default="",
                           help="Override model name")
    run_parser.add_argument("--output-dir", "-o", type=str, default=None,
                           help="Output directory for results")
    run_parser.add_argument("--data-path", type=str, default=None,
                           help="Override dataset path")
    run_parser.add_argument("--max-workers", "-w", type=int, default=None,
                           help="Max concurrent workers")
    run_parser.add_argument("--rollout-count", "-n", type=int, default=None,
                           help="Number of rollouts per question")
    run_parser.add_argument("--temperature", type=float, default=None,
                           help="LLM generation temperature")
    run_parser.add_argument("--top-p", type=float, default=None,
                           help="LLM generation top_p")
    
    args = parser.parse_args()
    
    if args.command == "list":
        list_configs()
    elif args.command == "run":
        run_inference(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
