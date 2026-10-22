package com.noteweave.research;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.noteweave.common.BusinessException;
import java.nio.file.*;
import java.util.*;
public class StatementSupportReplay {
  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper(); var validator = new ResearchStatementSupportValidation();
    for (String name : List.of("statement-report.json", "statement-semantic.json")) {
      ObjectNode fixture = (ObjectNode) mapper.readTree(Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/" + name)));
      Map<String,Object> input = mapper.convertValue(fixture.get("input"),Map.class);
      Map<String,Object> result = mapper.convertValue(Map.of("narrative",fixture.get("narrative")),Map.class);
      validator.validate(mapper,input,result,2);
      if(name.equals("statement-report.json")) {
        List<Map<String,Object>> cells=mapper.convertValue(fixture.path("input").path("cells"),List.class);
        var claims=cells.stream().map(cell -> Map.<String,Object>of("cell_key",cell.get("cell_key"),"claim_text",cell.get("candidate_value"),"evidence_keys",cell.get("evidence_keys"),"guarded",false)).toList();
        new ResearchAgentSynthesisValidationService().validate(Set.of("row:features"),cells,claims,fixture.path("markdown").asText(),mapper.convertValue(fixture.path("narrative"),Map.class),fixture.path("input").path("question").asText(),false,true);
      }
      ObjectNode changed = fixture.deepCopy();
      ((ObjectNode)changed.path("narrative").path("sections").get(0).path("paragraphs").get(0)).put("text","Atlas silently invents a different behavior.");
      try { validator.validate(mapper,input,mapper.convertValue(Map.of("narrative",changed.get("narrative")),Map.class),2); throw new IllegalStateException("篡改文本被接受"); }
      catch(BusinessException expected) { }
      changed=fixture.deepCopy();
      ((ArrayNode)changed.path("narrative").path("statement_support").path("checks")).remove(0);
      try { validator.validate(mapper,input,mapper.convertValue(Map.of("narrative",changed.get("narrative")),Map.class),2); throw new IllegalStateException("遗漏核验记录被接受"); }
      catch(BusinessException expected) { }
    }
    System.out.println("{\"statement_manifest_validated\":true,\"tampered_text_rejected\":true,\"missing_checks_rejected\":true}");
  }
}
