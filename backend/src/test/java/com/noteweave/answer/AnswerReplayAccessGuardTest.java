package com.noteweave.answer;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class AnswerReplayAccessGuardTest {
    @Test void deletionAndComplianceFailClosedButOrdinaryCancellationRemainsReadable() {
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:replayguard;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table answer_run(id varchar primary key,workspace_id varchar,conversation_id varchar,query_message_id varchar,answer_message_id varchar,status varchar,error_code varchar)");
        jdbc.execute("create table conversation_message(id varchar primary key,workspace_id varchar,conversation_id varchar,context_status varchar)");
        jdbc.update("insert into conversation_message values('q','ws','c','AVAILABLE'),('a','ws','c','AVAILABLE')");
        jdbc.update("insert into answer_run values('r','ws','c','q','a','CANCELLED',null)");
        AnswerReplayAccessGuard guard=new AnswerReplayAccessGuard(jdbc);
        assertThat(guard.isReadable("r")).isTrue();
        assertThatThrownBy(()->guard.requireReadable("other","r")).isInstanceOf(com.noteweave.common.BusinessException.class);
        for(String code:java.util.List.of("ANSWER_MESSAGE_DELETED","ANSWER_EVIDENCE_REVOKED")) {
            jdbc.update("update answer_run set status='COMPLETED',error_code=?",code);
            assertThat(guard.isReadable("r")).isFalse();
        }
        jdbc.update("update answer_run set error_code=null");
        jdbc.update("update conversation_message set context_status='DELETED' where id='q'");
        assertThat(guard.isReadable("r")).isFalse();
        assertThat(guard.isReadable("missing")).isFalse();
    }
}
