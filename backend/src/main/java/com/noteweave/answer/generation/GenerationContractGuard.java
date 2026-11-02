package com.noteweave.answer.generation;

import com.noteweave.common.BusinessException;
import com.noteweave.knowledge.KnowledgeCitationReadGate;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 按冻结身份复查当前资格；旧历史无契约的兼容由调用方运行状态门禁限制。 */
@Service
public class GenerationContractGuard {
    public static final String CODE_REVOKED="ANSWER_EVIDENCE_REVOKED";
    private final JdbcTemplate jdbc;
    private final MaterialEligibilityPort eligibility;
    private final KnowledgeCitationReadGate knowledge;
    private final ResearchGeneratedSourceReadGate research;
    public GenerationContractGuard(JdbcTemplate jdbc,MaterialEligibilityPort eligibility) {
        this(jdbc,eligibility,new KnowledgeCitationReadGate(jdbc,eligibility),null);
    }
    @Autowired
    public GenerationContractGuard(JdbcTemplate jdbc,MaterialEligibilityPort eligibility,
                                   KnowledgeCitationReadGate knowledge,ResearchGeneratedSourceReadGate research) {
        this.jdbc=jdbc; this.eligibility=eligibility; this.knowledge=knowledge; this.research=research;
    }
    public void requireEligible(String workspaceId,String runId,GenerationContract contract) {
        if(contract==null) return;
        contract.requireValid();
        if(!GenerationContract.SCHEMA_VERSION.equals(contract.schemaVersion())) throw invalid("未知生成契约版本");
        List<String> allowed=contract.allowedEvidenceIds();
        if(contract.refusal()) {
            if(!allowed.isEmpty() || !contract.citationBindings().isEmpty()) throw invalid("拒答不能包含引用");
            return;
        }
        Set<String> identities=new HashSet<>();
        for(String id:allowed) if(blank(id) || !identities.add(id)) throw invalid("重复或缺失的证据身份");
        if(contract.citationBindings().size()!=allowed.size()) throw invalid("编号绑定不完整");
        Set<Integer> numbers=new HashSet<>();
        Set<String> bound=new HashSet<>();
        for(CitationBinding binding:contract.citationBindings()) {
            if(binding.number()<1 || binding.number()>allowed.size() || !numbers.add(binding.number())
                    || !bound.add(binding.evidenceId()) || !allowed.get(binding.number()-1).equals(binding.evidenceId()))
                throw invalid("编号绑定与允许集合不一致");
        }
        for(String id:allowed) {
            String expected=contract.evidenceTextHashes().get(id);
            if(expected==null || !expected.matches("[0-9a-fA-F]{64}")) throw invalid("证据缺少正文hash");
            List<Row> rows=jdbc.query("""
                    select evidence_id,evidence_kind,source_id,source_snapshot_id,passage_id,
                        knowledge_item_id,knowledge_version_id,content_hash
                    from answer_run_evidence_manifest where workspace_id=? and answer_run_id=? and evidence_id=?
                    """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                            rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),workspaceId,runId,id);
            if(rows.size()!=1) throw revoked(id+":MISSING_FROM_MANIFEST");
            Row row=rows.get(0);
            if(!expected.equals(row.hash())) throw revoked(id+":CONTENT_CHANGED");
            switch(row.kind()==null?"":row.kind()) {
                case "PASSAGE" -> passage(workspaceId,row);
                case "KNOWLEDGE_VERSION" -> {
                    if(blank(row.item()) || blank(row.version())) throw invalid("知识身份不完整");
                    try { knowledge.requireCurrentVersionEligible(workspaceId,row.item(),row.version()); }
                    catch(BusinessException ex) { throw revoked(id+":"+ex.code()); }
                }
                case "RESEARCH_EVIDENCE" -> research(workspaceId,runId,row);
                default -> throw invalid("未知证据种类");
            }
        }
    }
    private void passage(String workspaceId,Row row) {
        if(blank(row.source()) || blank(row.snapshot()) || blank(row.passage())) throw invalid("资料身份不完整");
        // 每条绑定快照独立检查，绝不按sourceId覆盖另一代snapshot的决定。
        OriginRef ref = OriginRef.internalSnapshot(row.source(), row.snapshot());
        var decision = eligibility.assess(workspaceId, List.of(ref), AccessPurpose.CURRENT_EVIDENCE).get(ref.originKey());
        if(decision == null || !decision.allowed(AccessPurpose.CURRENT_EVIDENCE) || !row.snapshot().equals(decision.snapshotId()))
            throw revoked(row.id()+":SOURCE_NOT_ELIGIBLE");
        Integer exists=jdbc.queryForObject("""
                select count(*) from source_chunk c join source_snapshot s on s.id=c.source_snapshot_id
                where c.workspace_id=? and c.id=? and c.source_id=? and c.source_snapshot_id=? and s.source_id=c.source_id
                """,Integer.class,workspaceId,row.passage(),row.source(),row.snapshot());
        if(exists==null || exists!=1) throw revoked(row.id()+":PASSAGE_NOT_OWNED");
        if(row.id().startsWith("note-window:")) {
            String suffix=row.id().substring("note-window:".length());
            int separator=suffix.lastIndexOf(':');
            if(separator<1 || !suffix.substring(0,separator).equals(row.passage())) throw invalid("Note窗口身份不完整");
            int window;
            try { window=Integer.parseInt(suffix.substring(separator+1)); }
            catch(NumberFormatException ex) { throw invalid("Note窗口编号非法"); }
            Integer windows=jdbc.queryForObject("select count(*) from source_window where source_chunk_id=? and window_no=?",Integer.class,row.passage(),window);
            if(window<0 || windows==null || windows!=1) throw revoked(row.id()+":WINDOW_NOT_OWNED");
        }
    }
    private void research(String workspaceId,String answerRunId,Row row) {
        String prefix="research-evidence:";
        if(!row.id().startsWith(prefix) || blank(row.passage())) throw invalid("研究证据身份不完整");
        String suffix=":"+row.passage();
        if(!row.id().endsWith(suffix)) throw invalid("研究证据键与身份不一致");
        String researchId=row.id().substring(prefix.length(),row.id().length()-suffix.length());
        if(blank(researchId)) throw invalid("研究运行身份缺失");
        List<String> snapshots=jdbc.query("""
                select coalesce(s.replay_availability,'FULL') as availability
                from research_run r left join run_input_snapshot s on s.id=r.context_snapshot_id
                where r.workspace_id=? and r.id=? and r.status='COMPLETED'
                  and exists(select 1 from source_evidence e where e.research_run_id=r.id and e.evidence_key=?)
                  and (r.query_message_id is null or exists(select 1 from conversation_message m
                       where m.id=r.query_message_id and m.workspace_id=r.workspace_id and m.context_status<>'DELETED'))
                  and (r.answer_message_id is null or exists(select 1 from conversation_message m
                       where m.id=r.answer_message_id and m.workspace_id=r.workspace_id and m.context_status<>'DELETED'))
                """,(rs,n)->rs.getString(1),workspaceId,researchId,row.passage());
        if(snapshots.size()!=1 || !"FULL".equals(snapshots.get(0))) throw revoked(row.id()+":RESEARCH_NOT_ELIGIBLE");
        if(research!=null && !research.visible(workspaceId,"research_agent",researchId)) throw revoked(row.id()+":RESEARCH_CONTEXT_REDACTED");
        if(!eligibility.assessGeneratedDependenciesDetailed(workspaceId,"research_agent",researchId,AccessPurpose.CURRENT_EVIDENCE)
                .allAllowed(AccessPurpose.CURRENT_EVIDENCE)) throw revoked(row.id()+":RESEARCH_DEPENDENCY_REVOKED");
    }
    private boolean blank(String value) { return value==null || value.isBlank(); }
    private BusinessException invalid(String reason) { return new BusinessException("ANSWER_GENERATION_CONTRACT_INVALID",reason,HttpStatus.CONFLICT); }
    private BusinessException revoked(String reason) { return new BusinessException(CODE_REVOKED,"冻结证据已失效："+reason,HttpStatus.CONFLICT); }
    private record Row(String id,String kind,String source,String snapshot,String passage,String item,String version,String hash) {}
}
