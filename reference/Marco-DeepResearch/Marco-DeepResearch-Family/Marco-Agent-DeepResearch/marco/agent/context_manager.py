import logging
import re
from collections import Counter
from typing import Dict, List, Callable, Tuple, Optional

from .constants import TTSStrategy
from .response_parser import ResponseParser
from ..prompts import GIVE_UP_JUDGE_PROMPT, VERIFY_SYSTEM_PROMPT, VERIFY_USER_PROMPT

logger = logging.getLogger(__name__)


ANSWER_GIVE_UP_PATTERNS = [
    r"(?:I |we )(?:was |were )?unable to (?:find|locate|determine|identify|provide|verify|confirm)",
    r"(?:I |we )(?:was |were )?unable to (?:definitively |accurately )?(?:find|locate|determine|identify|provide|verify|confirm)",
    r"(?:I |we )(?:could not|couldn't|cannot|can't) (?:find|locate|determine|identify|provide|verify|confirm)",
    r"(?:I |we )(?:could not|couldn't|cannot|can't) (?:definitively |accurately )?(?:answer|provide|give)",
    r"(?:I |we )(?:did not|didn't) (?:find|locate|succeed|manage to find)",
    r"(?:I |we )failed to (?:find|locate|determine|identify)",
    r"(?:I |we )(?:have not|haven't) (?:been able to |)(?:find|locate|determine|identify)",
    r"(?:I am |I'm )not (?:sure|certain|able to (?:find|determine|identify|provide))",
    r"(?:I )?(?:cannot|can't) (?:definitively |accurately )?(?:identify|answer|provide|determine) ",
    r"based on (?:my |the )?(?:extensive |thorough )?(?:search|research)",
    r"based on (?:my |the )?(?:extensive |thorough )?(?:search|research).{0,50}(?:I |we )?(?:was |were )?(?:unable|could not|cannot|couldn't|can't)",
    r"(?:despite|after) (?:extensive |thorough |exhaustive |multiple )?(?:search|research|attempt).{0,50}(?:unable|could not|cannot|couldn't|can't|no |not )",
    r"(?:unfortunately|regrettably).{0,30}(?:unable|cannot|could not|couldn't|can't|not |no )",
    r"no (?:definitive|specific|clear|relevant|useful) (?:answer|information|result|evidence) (?:was |were |has been )?(?:found|available|obtained|returned)",
    r"(?:the )?(?:exact|specific) (?:answer|information) (?:is not|could not be|was not) (?:found|available|determined)",
    r"(?:the )?search (?:did not|didn't) (?:return|yield|produce) (?:any )?(?:useful |relevant |definitive )?(?:results|information|answer)",
    r"\bunable to (?:find|locate|determine|identify|provide|verify|confirm|access)",
    r"\bcould not (?:be )?(?:found|located|determined|identified|verified|confirmed|accessed)",
    r"(?:not |un)available (?:in |from |on |through )",
    r"(?:are|is|were|was) not (?:accessible|available|obtainable)",
    r"(?:I )?apologize.{0,30}(?:unable|cannot|could not|not able to)",
    r"(?:无法|未能|没能|不能)(?:找到|确定|提供|获取|定位|查到)",
    r"(?:抱歉|遗憾|很遗憾).{0,20}(?:无法|未能|找不到|没有找到)",
    r"(?:未找到|找不到|搜索不到)(?:相关|具体|明确|有效)?(?:答案|信息|结果|内容)",
    r"搜索(?:未|没有|无法)(?:返回|找到|获取)(?:有效|相关|具体)?(?:结果|信息)",
    r"(?:信息|结果|答案)(?:不足|缺失|不可用|未找到)",
    r"(?:I |we )(?:haven't |have not )(?:found|located|determined)",
    r"</?think>",
    r"(?i)^based on ",
]

COMPILED_GIVE_UP = [re.compile(p, re.IGNORECASE) for p in ANSWER_GIVE_UP_PATTERNS]


class ContextManager:

    DEFAULT_ENABLED = False
    DEFAULT_MAX_DISCARD_COUNT = 3

    def __init__(
            self,
            max_input_tokens: int,
            token_counter: Callable[[List[Dict]], int],
            enabled: bool = None,
            truncate_side: str = None,
            max_tool_response_length: int = None,
            max_discard_count: int = None,
            round_threshold: Optional[int] = None,
            use_llm_judge: bool = False,
            llm_call_fn: Optional[Callable[[List[Dict]], str]] = None,
            verify_enabled: bool = False,
            verify_max_answers: int = 8,
            verify_early_stop_count: int = 4,
        ):
        self.max_input_tokens = max_input_tokens
        self.token_counter = token_counter
        self.enabled = enabled if enabled is not None else self.DEFAULT_ENABLED
        self.strategy = TTSStrategy.DISCARD_VERIFY
        self.use_llm_judge = use_llm_judge and llm_call_fn is not None
        self.llm_call_fn = llm_call_fn
        self.truncate_side = truncate_side or "none"
        self.max_tool_response_length = max_tool_response_length or 2000
        self.max_discard_count = max_discard_count if max_discard_count is not None else self.DEFAULT_MAX_DISCARD_COUNT
        self.round_threshold = round_threshold
        self._discard_count = 0
        self.verify_enabled = verify_enabled and llm_call_fn is not None
        self.verify_max_answers = verify_max_answers
        self.verify_early_stop_count = verify_early_stop_count
        self._answer_queue: List[str] = []

    def reset_discard_count(self) -> None:
        self._discard_count = 0
        self._answer_queue = []

    def count_tokens(self, messages: List[Dict]) -> int:
        return self.token_counter(messages)

    def is_over_limit(self, messages: List[Dict]) -> bool:
        return self.count_tokens(messages) > self.max_input_tokens

    def truncate_tool_response(
            self,
            response: str,
            max_length: int = None,
            truncate_side: str = None
        ) -> str:
        max_length = max_length or self.max_tool_response_length
        truncate_side = truncate_side or self.truncate_side

        if truncate_side == "none" or len(response) <= max_length:
            return response

        truncated_chars = len(response) - max_length
        truncate_marker = f"...[truncated {truncated_chars} chars]..."

        if truncate_side == "left":
            return response[:max_length] + truncate_marker
        elif truncate_side == "right":
            return truncate_marker + response[-max_length:]
        else:
            head_len = int(max_length * 0.6)
            tail_len = int(max_length * 0.3)
            return (
                response[:head_len] +
                f"\n\n...[truncated {len(response) - head_len - tail_len} chars]...\n\n" +
                response[-tail_len:]
            )


    def force_discard(self, messages: List[Dict]) -> List[Dict]:
        return self._discard_all(messages, reason="verify_collect (forced)")

    def try_discard(self, messages: List[Dict], user_question: str = "") -> List[Dict]:
        if len(messages) <= 2:
            return messages

        result = self._discard_verify(messages)

        if len(result) == len(messages):
            logger.info("✅ TTS discard_verify: no discard (answer not give-up), %d messages unchanged", len(messages))
        else:
            logger.info("🔄 TTS discard_verify: %d -> %d messages", len(messages), len(result))
        return result

    def check_and_discard(
            self,
            messages: List[Dict],
            user_question: str,
            execution_mode: str,
            already_discarded: bool
        ) -> Tuple[List[Dict], bool, bool]:
        if not self.enabled:
            return messages, already_discarded, False

        token_count = self.count_tokens(messages)
        logger.debug("TTS check: tokens=%d, discard_limit=%d, mode=%s", token_count, self.max_input_tokens, execution_mode)

        round_count = self._count_rounds(messages)
        round_triggered = (
            self.round_threshold is not None
            and round_count >= self.round_threshold
        )

        if round_triggered:
            if self._discard_count >= self.max_discard_count:
                if token_count > self.max_input_tokens:
                    logger.info("🚫 TTS: round_threshold reached (round=%d >= %d), max discard exhausted, stopping",
                                 round_count, self.round_threshold)
                    return messages, True, True
                return messages, already_discarded, False
            messages = self._discard_all(
                messages, reason=f"round_threshold (round={round_count} >= {self.round_threshold})")
            return messages, True, False

        last_assistant_content = ""
        for i in range(len(messages) - 1, -1, -1):
            if messages[i].get("role") == "assistant":
                c = messages[i].get("content", "")
                last_assistant_content = c if isinstance(c, str) else str(c)
                break
        has_answer = bool(last_assistant_content and self._parser.has_answer(last_assistant_content))
        should_try = (token_count > self.max_input_tokens) or has_answer
        if not should_try:
            return messages, already_discarded, False
        if self._discard_count >= self.max_discard_count:
            if token_count > self.max_input_tokens:
                logger.info("🚫 TTS: max discard count exhausted and still over limit, stopping")
                return messages, True, True
            return messages, already_discarded, False
        messages_after = self.try_discard(messages, user_question)
        actually_discarded = len(messages_after) != len(messages)
        return messages_after, actually_discarded, False


    def _discard_all(
            self,
            messages: List[Dict],
            reason: str = "",
        ) -> List[Dict]:
        if len(messages) <= 2:
            return messages
        if self._discard_count >= self.max_discard_count:
            return messages
        self._discard_count += 1
        kept = [messages[0], messages[1]]
        logger.info("🔄 TTS discard (%d/%d): %s, %d -> %d messages",
                     self._discard_count, self.max_discard_count, reason, len(messages), len(kept))
        return kept

    def _discard_verify(self, messages: List[Dict]) -> List[Dict]:
        if len(messages) <= 2:
            return messages
        last_assistant_content = ""
        for i in range(len(messages) - 1, -1, -1):
            if messages[i].get("role") == "assistant":
                c = messages[i].get("content", "")
                last_assistant_content = c if isinstance(c, str) else str(c)
                break
        token_count = self.count_tokens(messages)

        answer_text = self._parser.extract_answer(last_assistant_content) if last_assistant_content else None
        if answer_text and self._is_give_up_answer(answer_text):
            return self._discard_all(messages, reason="give-up answer detected")

        if token_count <= self.max_input_tokens:
            return messages
        return self._discard_all(
            messages, reason=f"token_exceeded (tokens={token_count} > {self.max_input_tokens})")

    @staticmethod
    def _count_rounds(messages: List[Dict]) -> int:
        if len(messages) <= 2:
            return 0
        return sum(1 for m in messages[2:] if m.get("role") == "assistant")

    def _is_give_up_answer(self, content: str) -> bool:
        if not content or not isinstance(content, str):
            return False
        text = content.strip()

        if self.use_llm_judge:
            return self._llm_judge_give_up(text)

        for idx, pattern in enumerate(COMPILED_GIVE_UP):
            m = pattern.search(text)
            if m:
                logger.debug("[give_up_check] rule matched: pattern_idx=%d, snippet=%r", idx, m.group(0)[:60])
                return True
        return False

    def _llm_judge_give_up(self, content: str) -> bool:
        prompt = GIVE_UP_JUDGE_PROMPT.format(content=content)
        result = self.llm_call_fn([{"role": "user", "content": prompt}])
        is_give_up = result.strip().lower().startswith("yes")
        logger.debug("[give_up_check] llm_judge result=%s", is_give_up)
        return is_give_up

    _parser = ResponseParser()


    def add_answer(self, prediction: str) -> None:
        self._answer_queue.append(prediction)
        logger.info("📥 Answer queued (%d/%d): %s",
                    len(self._answer_queue), self.verify_max_answers,
                    prediction[:100])

    @staticmethod
    def _normalize_answer(text: str) -> str:
        return text.strip().lower()

    def _vote_counter(self) -> Tuple[Counter, Dict[str, str]]:
        counter: Counter = Counter()
        norm_to_raw: Dict[str, str] = {}
        for ans in self._answer_queue:
            key = self._normalize_answer(ans)
            counter[key] += 1
            if key not in norm_to_raw:
                norm_to_raw[key] = ans
        return counter, norm_to_raw

    def check_early_stop(self) -> Optional[str]:
        if not self._answer_queue or self.verify_early_stop_count <= 0:
            return None
        counter, norm_to_raw = self._vote_counter()
        top_key, top_count = counter.most_common(1)[0]
        if top_count >= self.verify_early_stop_count:
            result = norm_to_raw[top_key]
            logger.info("⚡ Early stop: answer %r appeared %d times (threshold=%d)",
                        result[:100], top_count, self.verify_early_stop_count)
            return result
        return None

    def should_verify(self) -> bool:
        return self.verify_enabled and len(self._answer_queue) >= self.verify_max_answers

    def verify_answers(self, question: str) -> Optional[str]:
        if not self._answer_queue:
            return None

        counter, norm_to_raw = self._vote_counter()
        total = len(self._answer_queue)
        max_count = counter.most_common(1)[0][1]
        top_keys = [k for k, c in counter.items() if c == max_count]

        if len(top_keys) == 1:
            winner_key = top_keys[0]
            raw_answers = [a for a in self._answer_queue if self._normalize_answer(a) == winner_key]
            if all(a == raw_answers[0] for a in raw_answers):
                logger.info("✅ Verify (rule): %s (count=%d/%d)", raw_answers[0][:100], max_count, total)
                return raw_answers[0]
            logger.info("🔄 Verify (rule→llm): norm=%r identical but %d raw variants, fallback to LLM",
                        winner_key[:60], len(set(raw_answers)))
            return self._llm_verify(question)

        logger.info("🔄 Verify (tie→llm): %d candidates with %d votes each, fallback to LLM",
                    len(top_keys), max_count)
        return self._llm_verify(question)

    def _llm_verify(self, question: str) -> str:
        predictions_text = "\n".join(
            f"## Prediction {i}:\n{pred}" for i, pred in enumerate(self._answer_queue, 1)
        )
        user_prompt = VERIFY_USER_PROMPT.format(question=question, predictions=predictions_text)
        messages = [
            {"role": "system", "content": VERIFY_SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ]
        response = self.llm_call_fn(messages)
        answer = self._parser.extract_answer(response)
        if answer:
            logger.info("✅ Verify (llm): %s (from %d answers)", answer[:200], len(self._answer_queue))
        else:
            logger.warning("⚠️ Verify LLM failed to extract <answer>, using raw response")
            answer = re.sub(r"<think>.*?</think>", "", response, flags=re.DOTALL).strip()
        return answer
