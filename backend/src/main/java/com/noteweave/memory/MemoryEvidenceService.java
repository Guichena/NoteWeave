package com.noteweave.memory;

import com.noteweave.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 把先验和跨会话证据写到记忆上。调用方处于已有事务时加入该事务。 */
@Service
public class MemoryEvidenceService {
    private final JdbcTemplate jdbcTemplate;

    public MemoryEvidenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assignPrior(String itemId, MemoryEvidence.Band band) {
        MemoryEvidence.Posterior prior = MemoryEvidence.prior(band);
        jdbcTemplate.update("""
                update memory_item
                set evidence_alpha = ?, evidence_beta = ?, last_reinforced_at = current_timestamp
                where id = ?
                """, prior.alpha(), prior.beta(), itemId);
    }

    /** 创建会话占一个名额，权重为 0，不增加强化次数。 */
    public void recordOrigin(String itemId, String sessionKey) {
        if (itemId == null || itemId.isBlank() || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        if (sessionExists(itemId, sessionKey, "SUPPORT")) {
            return;
        }
        insertSession(itemId, sessionKey, "SUPPORT", 0);
    }

    public Result observe(String itemId, String sessionKey, String direction, MemoryEvidence.Band band) {
        String resolvedDirection = "CONTRADICT".equals(direction) ? "CONTRADICT" : "SUPPORT";
        String resolvedSession = sessionKey == null || sessionKey.isBlank() ? "anonymous" : sessionKey;
        if (sessionExists(itemId, resolvedSession, resolvedDirection)) {
            return new Result(false, 0);
        }
        Integer recorded = jdbcTemplate.queryForObject("""
                select count(distinct session_key) from memory_evidence_session where memory_item_id = ?
                """, Integer.class, itemId);
        double weight = MemoryEvidence.weight(band, recorded == null ? 0 : recorded);
        if ("SUPPORT".equals(resolvedDirection)) {
            jdbcTemplate.update("""
                    update memory_item
                    set evidence_alpha = evidence_alpha + ?,
                        reinforcement_count = reinforcement_count + 1,
                        last_reinforced_at = current_timestamp,
                        updated_at = current_timestamp
                    where id = ?
                    """, weight, itemId);
        } else {
            jdbcTemplate.update("""
                    update memory_item
                    set evidence_beta = evidence_beta + ?, updated_at = current_timestamp
                    where id = ?
                    """, weight, itemId);
        }
        insertSession(itemId, resolvedSession, resolvedDirection, weight);
        return new Result(true, weight);
    }

    private boolean sessionExists(String itemId, String sessionKey, String direction) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_evidence_session
                where memory_item_id = ? and session_key = ? and direction = ?
                """, Integer.class, itemId, sessionKey, direction);
        return count != null && count > 0;
    }

    private void insertSession(String itemId, String sessionKey, String direction, double weight) {
        jdbcTemplate.update("""
                insert into memory_evidence_session(id, memory_item_id, session_key, direction, weight)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), itemId, sessionKey, direction, weight);
    }

    public record Result(boolean applied, double weight) {
    }
}
