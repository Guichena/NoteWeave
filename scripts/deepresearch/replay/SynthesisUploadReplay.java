package com.noteweave.research;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.noteweave.common.BusinessException;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class SynthesisUploadReplay {
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var input = mapper.readTree(Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/synthesis-upload.json")));
    JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:upload;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    db.execute("create table research_agent_task(id varchar(36) primary key, role varchar(48), execution_context_json longtext)");
    String context = "{\"query_policy\":{\"synthesis_input\":{\"report_upload_version\":1}}}";
    String taskId = "00000000-0000-0000-0000-000000000001";
    db.update("insert into research_agent_task values (?,'SYNTHESIS',?)", taskId, context);
    for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT") + "/backend/src/main/resources/db/migration/V185__research_synthesis_upload_parts.sql")).split(";")) if (!sql.isBlank()) db.execute(sql);
    // 上传入口复用的租约校验在本回放中预设通过，只验证持久化、不可变性和重组边界。
    var tasks = new ResearchAgentTaskService(db, mapper, null, null, null) {
      @Override public ClaimedTask heartbeat(LeaseCommand command) {
        return new ClaimedTask(command.taskId(),command.leaseEpoch(),command.fencingToken(),java.time.Instant.now(),"[]","{}","{}","sha256:" + "c".repeat(64));
      }
    };
    var service = new ResearchSynthesisUploadService(db, mapper, tasks);
    ResearchSynthesisUploadService.PartRequest first = null;
    for (var raw : input.path("parts")) {
      var request = mapper.convertValue(raw, ResearchSynthesisUploadService.PartRequest.class);
      if (first == null) first = request;
      service.upload(taskId, request);
    }
    if (!Boolean.TRUE.equals(service.upload(taskId, first).get("idempotent_replay"))) throw new IllegalStateException("重放没有保持幂等");
    var task = new ResearchAgentCompletionCommitter.TaskRow(taskId,"run","CLAIMED","SYNTHESIS","row","branch",0,0,"[]","{}",context,
        "research-agent-task-snapshot.v3","sha256:" + "c".repeat(64),"offline-worker",1,10L,"logical",null,1,1,true);
    Map<String,Object> reference = mapper.convertValue(input.path("reference"), new TypeReference<>(){});
    var result = service.resolve(task, reference);
    List<Map<String,Object>> cells = mapper.convertValue(input.path("input").path("cells"), new TypeReference<>(){});
    List<Map<String,Object>> claims = mapper.convertValue(result.get("claims"), new TypeReference<>(){});
    Map<String,Object> narrative = mapper.convertValue(result.get("narrative"), new TypeReference<>(){});
    new ResearchAgentSynthesisValidationService().validate(Set.of("row:features"), cells, claims, String.valueOf(result.get("markdown")), narrative,
        input.path("input").path("question").asText(),false,true);
    byte[] firstBytes = db.queryForObject("select content_bytes from research_synthesis_upload_part where part_index=0", byte[].class);
    byte[] changed = firstBytes.clone(); changed[0] ^= 1;
    db.update("update research_synthesis_upload_part set content_bytes=? where part_index=0", changed);
    boolean tamperRejected = false;
    try { service.resolve(task, reference); } catch (BusinessException error) { tamperRejected = true; }
    if (!tamperRejected) throw new IllegalStateException("片段变更未被拒绝");
    db.update("update research_synthesis_upload_part set content_bytes=? where part_index=0", firstBytes);
    db.update("delete from research_synthesis_upload_part where part_index=0");
    boolean missingRejected = false;
    try { service.resolve(task, reference); } catch (BusinessException error) { missingRejected = true; }
    if (!missingRejected) throw new IllegalStateException("缺段未被拒绝");
    System.out.println("{\"large_report_java_validation\":\"PASSED\",\"immutable_replay\":true,\"tampered_part_rejected\":true,\"missing_part_rejected\":true}");
  }
}
