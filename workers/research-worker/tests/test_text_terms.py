from app.citation_verifier import LEXICAL_SUPPORT_THRESHOLD, _lexical_support, _polarity_consistent
from app.text_terms import coverage, is_negated, terms


def test_chinese_text_is_split_into_bigrams_instead_of_one_sentence_token():
    assert {"缓存", "存一", "一致", "致性"} <= terms("缓存一致性")
    assert "redis" in terms("Redis 缓存")
    assert "ab" not in terms("ab")


def test_lexical_support_works_for_chinese_claims():
    claim = "删除缓存失败时可以通过消息队列重试"
    quote = "如果删除缓存失败，把删除操作写入消息队列，由消费者重试，直到成功。"
    unrelated = "Kafka 消费者组在成员变化时会触发分区再均衡。"

    assert _lexical_support(claim, quote) >= LEXICAL_SUPPORT_THRESHOLD
    assert _lexical_support(claim, unrelated) < LEXICAL_SUPPORT_THRESHOLD
    assert coverage("", quote) == 0.0


def test_words_that_merely_contain_negation_characters_are_not_negations():
    assert not is_negated("两种方案适用于不同的场景，未来还会继续演进。")
    assert is_negated("该方案不能保证强一致性。")
    assert is_negated("The cache is not refreshed.")
    assert _polarity_consistent("两种方案适用于不同场景", "这两种方案分别适用于不同的业务场景")
    assert not _polarity_consistent("方案保证强一致性", "方案无法保证强一致性")
