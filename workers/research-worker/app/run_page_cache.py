"""同一工作台、研究和对象内短时复用抓取正文；查询相关选段始终重新执行。"""
from collections import OrderedDict
from copy import deepcopy
from threading import Lock
from time import monotonic


class RunPageCache:
    def __init__(self, *, ttl=600.0, max_entries=64, max_bytes=16 * 1024 * 1024, clock=monotonic):
        self.ttl = ttl
        self.max_entries = max_entries
        self.max_bytes = max_bytes
        self.clock = clock
        self._entries = OrderedDict()
        self._bytes = 0
        self._lock = Lock()

    def get(self, key):
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            expires, snapshot, size = entry
            if self.clock() >= expires:
                self._entries.pop(key)
                self._bytes -= size
                return None
            self._entries.move_to_end(key)
            return deepcopy(snapshot)

    def put(self, key, snapshot):
        # 只有带未按查询裁剪正文的成功读取才可复用；失败与搜索摘要不能变成证据。
        text = snapshot.get('_unfocused_text')
        if (snapshot.get('fetch_status') != 'FETCHED' or snapshot.get('content_origin') != 'FETCHED_SNAPSHOT'
                or not isinstance(text, str) or not text.strip()):
            return
        size = len(text.encode('utf-8'))
        if size > min(self.max_bytes, 2 * 1024 * 1024) or self.max_entries < 1:
            return
        with self._lock:
            previous = self._entries.pop(key, None)
            if previous:
                self._bytes -= previous[2]
            self._entries[key] = (self.clock() + self.ttl, deepcopy(snapshot), size)
            self._bytes += size
            while len(self._entries) > self.max_entries or self._bytes > self.max_bytes:
                _, (_, _, removed_size) = self._entries.popitem(last=False)
                self._bytes -= removed_size


RUN_PAGES = RunPageCache()
