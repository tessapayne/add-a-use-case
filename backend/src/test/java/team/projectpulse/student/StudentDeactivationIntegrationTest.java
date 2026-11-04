package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.notification.SubmissionStatusService;
import team.projectpulse.notification.SubmittableItem;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP acceptance for UC-STU-deactivate-student and its evaluation compatibility boundaries. */
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean StudentRepository students;
    @Autowired SectionRepository sections;
    @Autowired InstructorRepository instructors;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired SubmissionStatusService submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired JsonMapper json;
    @MockitoSpyBean Clock clock;

    String login(String name) throws Exception {
        var result=mvc.perform(post("/api/v1/users/login").with(httpBasic(name,"123456")))
                .andExpect(status().isOk()).andReturn();
        return "Bearer "+new JSONObject(result.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
    void deactivate(int id,String token,int code) throws Exception {
        mvc.perform(post("/api/v1/students/"+id+"/deactivate").header("Authorization",token))
                .andExpect(status().is(code)).andExpect(jsonPath("$.flag").value(code==200))
                .andExpect(jsonPath("$.code").value(code));
    }
    Map<String,Object> profile(int id) {
        return new HashMap<>(Map.of("id",id,"username","j.smith@abc.edu","firstName","Changed","lastName","Smith",
                "email","j.smith@abc.edu","enabled",true));
    }
    void save(int id,String token,boolean restore,Map<String,Object> draft,int code) throws Exception {
        var request=put("/api/v1/students/"+id).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draft));
        if(restore) request.param("reactivate","true");
        mvc.perform(request).andExpect(status().is(code)).andExpect(jsonPath("$.flag").value(code==200))
                .andExpect(jsonPath("$.code").value(code));
    }
    void persisted(int id,boolean enabled,String name) {
        em.clear(); var saved=students.findById(id).orElseThrow();
        assertThat(saved.isEnabled()).isEqualTo(enabled); assertThat(saved.getFirstName()).isEqualTo(name);
    }

    @Test
    void courseOwnerWithoutSectionAssignmentCanReadDeactivateRepeatAndRestore() throws Exception {
        String admin=login("b.wei@abc.edu");
        sections.findById(2).orElseThrow().removeInstructor(instructors.findById(1).orElseThrow()); em.flush();
        mvc.perform(get("/api/v1/students/4").header("Authorization",admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true)).andExpect(jsonPath("$.code").value(200));
        deactivate(4,admin,200); persisted(4,false,"John");
        deactivate(4,admin,200); persisted(4,false,"John");
        save(4,admin,true,profile(4),200); persisted(4,true,"Changed");
        save(4,admin,true,profile(4),200); persisted(4,true,"Changed");
    }

    @Test
    void assignedInstructorPermissionDoesNotDependOnSelectedDefaults() throws Exception {
        String instructor=login("b.gates@abc.edu");
        instructors.findById(2).orElseThrow().setDefaultSection(null); em.flush();
        deactivate(4,instructor,200); persisted(4,false,"John");
    }

    @Test
    void refusalsNeverMutateAndDoNotRevealTargetExistence() throws Exception {
        for(String username:List.of("j.smith@abc.edu","e.hudson@abc.edu","t.cook@abc.edu")) {
            deactivate(4,login(username),403); persisted(4,true,"John");
        }
        var bill=instructors.findById(2).orElseThrow();
        sections.findById(2).orElseThrow().removeInstructor(bill); em.flush();
        deactivate(4,login("b.gates@abc.edu"),403); persisted(4,true,"John");
        mvc.perform(post("/api/v1/students/4/deactivate")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(401));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void ordinaryProfileSaveIgnoresBodyStatus(boolean active) throws Exception {
        String admin=login("b.wei@abc.edu");
        students.findById(4).orElseThrow().setEnabled(active);em.flush();
        var draft=profile(4);draft.put("enabled",!active);
        save(4,admin,false,draft,200);persisted(4,active,"Changed");
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void validationAndStatusWriteFailureRollBackWholeRestoration() throws Exception {
        String admin=login("b.wei@abc.edu");
        String email=UUID.randomUUID()+"@rollback.test";
        Student fixture=new Student(email,"Retained","Student",email,"unused",true,"student");
        fixture.setSection(sections.findById(2).orElseThrow());
        int id=students.saveAndFlush(fixture).getId();
        try {
            deactivate(id,admin,200);
            var draft=profile(id);draft.put("firstName","");
            save(id,admin,true,draft,400);persisted(id,false,"Retained");
            doThrow(new DataAccessResourceFailureException("injected write failure")).when(students)
                    .writeStatus(id,1,true,true,false,true);
            save(id,admin,true,profile(id),500);persisted(id,false,"Retained");
        } finally { students.deleteById(id); }
    }

    @Test
    void postGuardDisappearanceUses404AndPersistenceFailureDoesNotSucceed() throws Exception {
        String admin=login("b.wei@abc.edu");
        doReturn(Optional.empty()).when(students).lockAccessible(4,1,true,true,false);
        deactivate(4,admin,404);persisted(4,true,"John");
    }

    @Test
    void failedDeactivationRollsBack() throws Exception {
        String admin=login("b.wei@abc.edu");
        doThrow(new DataAccessResourceFailureException("injected write failure")).when(students)
                .writeStatus(4,1,true,true,false,false);
        deactivate(4,admin,500);persisted(4,true,"John");
    }

    @Test
    void deactivationPreservesEnrollmentMembershipHistoricalRecordsAndAuthoredContent() throws Exception {
        String admin=login("b.wei@abc.edu");
        // Representative student-authored RAM record; compare identity, author and content, not counts.
        jdbc.update("update requirement_artifact set created_by_id=4 where id=(select candidate.id from (select min(id) id from requirement_artifact) candidate)");
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("activity","peer_evaluation","rating","requirement_artifact","requirement_document","document_section")) {
            var rows=jdbc.queryForList("select * from "+table);
            assertThat(rows).as(table+" fixture").isNotEmpty();before.put(table,rows);
        }
        var account=new HashMap<>(jdbc.queryForMap("select * from peer_evaluation_user where id=4"));
        deactivate(4,admin,200);
        for(var entry:before.entrySet()) assertThat(jdbc.queryForList("select * from "+entry.getKey())).isEqualTo(entry.getValue());
        var after=new HashMap<>(jdbc.queryForMap("select * from peer_evaluation_user where id=4"));
        account.remove("enabled");after.remove("enabled");assertThat(after).isEqualTo(account);
        mvc.perform(post("/api/v1/teams/1/documents/search").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/evaluations/students/4/week/2023-W31/details").header("Authorization",admin)).andExpect(status().isOk());
    }

    @Test
    void teamlessStudentCanBeDeactivatedAndInactiveStudentOwesNothing() throws Exception {
        String admin=login("b.wei@abc.edu");
        var student=students.findById(4).orElseThrow();student.setTeam(null);em.flush();
        deactivate(4,admin,200);persisted(4,false,"John");
        assertThat(submissions.findOwing(2,SubmittableItem.PEER_EVALUATION,"2023-W32"))
                .extracting(Student::getId).doesNotContain(4,5).contains(7);
    }

    @Test
    void staleCreationRefusedButSavedInactiveRecipientEvaluationRemainsEditable() throws Exception {
        String admin=login("b.wei@abc.edu"),john=login("j.smith@abc.edu");
        var fixed=Clock.fixed(Instant.parse("2023-08-08T12:00:00Z"),ZoneOffset.UTC);
        doReturn(fixed.instant()).when(clock).instant();doReturn(fixed.getZone()).when(clock).getZone();
        var existing=evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W31",4,5).orElseThrow();
        int evaluationId=existing.getPeerEvaluationId();
        var draft=new HashMap<String,Object>();draft.put("week","2023-W31");draft.put("evaluatorId",4);draft.put("evaluateeId",5);
        draft.put("ratings",sections.findById(2).orElseThrow().getRubric().getCriteria().stream()
                .map(c->Map.of("criterionId",c.getCriterionId(),"actualScore",1)).toList());
        draft.put("publicComment","Retained edit");draft.put("privateComment","Private");
        deactivate(5,admin,200);
        mvc.perform(post("/api/v1/evaluations").header("Authorization",john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
        draft.put("evaluatorId",99);draft.put("evaluateeId",4);draft.put("week","2023-W32");
        mvc.perform(put("/api/v1/evaluations/"+evaluationId).header("Authorization",john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true)).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.evaluatorId").value(4)).andExpect(jsonPath("$.data.evaluateeId").value(5))
                .andExpect(jsonPath("$.data.week").value("2023-W31"));
        doReturn(fixed.instant().plusSeconds(7*86400)).when(clock).instant();
        mvc.perform(put("/api/v1/evaluations/"+evaluationId).header("Authorization",john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(400));
    }
}
