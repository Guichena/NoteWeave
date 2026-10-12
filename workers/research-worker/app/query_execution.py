"""按执行上下文记录实际发出的检索词，包括没有结果的查询。"""
from contextvars import ContextVar
from contextlib import contextmanager

_queries = ContextVar("research_executed_queries", default=None)


def record_query(query):
    recorder = _queries.get()
    if recorder is not None and query not in recorder:
        recorder.append(query)


@contextmanager
def capture_queries():
    recorder = []
    token = _queries.set(recorder)
    try:
        yield recorder
    finally:
        _queries.reset(token)
