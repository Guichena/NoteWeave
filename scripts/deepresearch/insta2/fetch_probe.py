"""在 Worker 容器内用默认抓取链路逐个读取网址，打印每个网址的抓取方式、状态、失败原因与正文长度。

用法：docker exec -i <worker> python - < fetch_probe.py（网址写在 URLS 里）
"""
from app.fetch_adapters import _build_default_url_snapshot_transport

URLS = [
    "https://arxiv.org/abs/2107.08195",
    "https://arxiv.org/pdf/2107.08195",
    "https://dl.acm.org/doi/10.1145/2568225.2568271",
    "https://ieeexplore.ieee.org/document/6693076",
    "https://www.sciencedirect.com/science/article/pii/S0164121213002781",
    "https://www.cs.ubc.ca/~rtholmes/papers/icse_2014_inozemtseva.pdf",
    "https://www.researchgate.net/publication/221554536_The_Costs_and_Benefits_of_Pair_Programming",
    "https://link.springer.com/article/10.1007/s10664-016-9493-x",
    "https://www.semanticscholar.org/paper/Coverage-is-not-strongly-correlated-with-test-suite-Inozemtseva-Holmes",
]

transport = _build_default_url_snapshot_transport()
print("transport chain:", [type(item).__name__ for item in getattr(transport, "transports", [transport])])
for url in URLS:
    snapshot = transport.read(url, "test coverage defects empirical study", 4000)
    text = str(snapshot.get("text") or "")
    print(url)
    print("   method=%s status=%s code=%s chain=%s len=%d" % (
        snapshot.get("fetch_method"), snapshot.get("fetch_status"), snapshot.get("fetch_failure_code"),
        snapshot.get("transport_chain"), len(text)))
    if snapshot.get("transport_fallback_code"):
        print("   fallback:", str(snapshot.get("transport_fallback_code"))[:200])
    print("   head:", " ".join(text[:160].split()))
