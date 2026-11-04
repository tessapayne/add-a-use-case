package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataAccessResourceFailureException;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.student.StudentService;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.user.UserRepository;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T-auth, T-deny, T-token, T-jwt, T-restore malformed intent: genuinely issued or server-signed JWTs. */
class StudentLifecycleSecurityIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired StudentRepository students;
    @Autowired SectionRepository sections;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean StudentService service;

    String login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/users/login").with(httpBasic(username,"123456")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        return "Bearer " + new JSONObject(result.getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }

    String signed(Object id, boolean include, boolean expired) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject("j.smith@abc.edu").issuedAt(now.minusSeconds(3600))
                .expiresAt(expired ? now.minusSeconds(300) : now.plusSeconds(3600))
                .claim("authorities","ROLE_student");
        if (include) claims.claims(map -> map.put("userId", id));
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    @Test
    void signedMalformedIdentitiesFailBeforeLookupOrHandler() throws Exception {
        List<Object> invalid = Arrays.asList(null, "4", 4.5, true, List.of(4), 0, -1, 2147483648L);
        for (Object id : invalid) {
            clearInvocations(users, service);
            refused(signed(id, true, false),401);
            verify(users, never()).findById(anyInt());
            verify(service, never()).findStudentById(anyInt());
        }
        clearInvocations(users);
        refused(signed(null,false,false),401);
        verify(users, never()).findById(anyInt());
    }

    @Test
    void expiryAndBadSignatureKeepDefaultValidation() throws Exception {
        String valid = signed(4,true,false);
        clearInvocations(users, service);
        refused(signed(4,true,true),401);
        String[] segments = valid.substring(7).split("\\.");
        segments[2] = (segments[2].startsWith("A") ? "B" : "A") + segments[2].substring(1);
        refused("Bearer " + String.join(".",segments),401);
        verify(users,never()).findById(anyInt());
        verify(service,never()).findStudentById(anyInt());
    }

    @Test
    void unavailableAccountStoreReturnsGeneric503WithoutProtectedLogic() throws Exception {
        String token = login("j.smith@abc.edu");
        clearInvocations(service);
        doThrow(new DataAccessResourceFailureException("SECRET database URL")).when(users).findById(4);
        mvc.perform(get("/api/v1/students/4").header("Authorization",token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("Account verification is temporarily unavailable. Please try again."));
        verify(service,never()).findStudentById(anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings={"", "TRUE", "False", "1", "yes"})
    void malformedIntentIs400AfterAuthentication(String value) throws Exception {
        String token = login("b.wei@abc.edu");
        mvc.perform(put("/api/v1/students/4").param("reactivate",value).header("Authorization",token)
                        .contentType(MediaType.APPLICATION_JSON).content(profile()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(400));
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
    }

    @Test
    void duplicatesAreInvalidAndAnonymousAuthenticationPrecedesParsing() throws Exception {
        mvc.perform(put("/api/v1/students/4").param("reactivate","true","true")
                        .header("Authorization",login("b.wei@abc.edu")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.flag").value(false));
        mvc.perform(put("/api/v1/students/4").param("reactivate","bad"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.flag").value(false));
    }

    @Test
    void selfCannotRestoreAndUnresolvedLifecycleIdsFailClosed() throws Exception {
        mvc.perform(put("/api/v1/students/4").param("reactivate","true").header("Authorization",login("j.smith@abc.edu"))
                        .contentType(MediaType.APPLICATION_JSON).content(profile()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403)).andExpect(jsonPath("$.flag").value(false));
        for (String id : List.of("missing","2147483647")) {
            mvc.perform(post("/api/v1/students/"+id+"/deactivate").header("Authorization",login("b.wei@abc.edu")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403)).andExpect(jsonPath("$.flag").value(false));
        }
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void issuedTokenObeysCommittedStateForBothAccountTypesAndWorksAgainAfterRestore(boolean instructor) throws Exception {
        var tx = new TransactionTemplate(transactions);
        String email = UUID.randomUUID()+"@token.test";
        Integer id = tx.execute(status -> {
            if (instructor) return users.saveAndFlush(new Instructor(email,"Token","Instructor",email,passwords.encode("123456"),true,"instructor")).getId();
            var student = new Student(email,"Token","Student",email,passwords.encode("123456"),true,"student");
            student.setSection(sections.findById(2).orElseThrow());
            return users.saveAndFlush(student).getId();
        });
        try {
            String token = login(email);
            mvc.perform(get("/api/v1/"+(instructor ? "instructors/" : "students/")+id).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true)).andExpect(jsonPath("$.code").value(200));
            tx.executeWithoutResult(status -> users.findById(id).orElseThrow().setEnabled(false));
            for (String path : List.of("/students/4","/teams/1/documents/1","/evaluations/evaluators/4/week/2023-W32")) {
                mvc.perform(get("/api/v1"+path).header("Authorization",token)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(401));
            }
            mvc.perform(post("/api/v1/activities").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(401));
            mvc.perform(post("/api/v1/users/login").with(httpBasic(email,"123456")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.flag").value(false));
            tx.executeWithoutResult(status -> users.findById(id).orElseThrow().setEnabled(true));
            mvc.perform(get("/api/v1/"+(instructor ? "instructors/" : "students/")+id).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true)).andExpect(jsonPath("$.code").value(200));
            tx.executeWithoutResult(status -> users.deleteById(id));
            refused(token,401);
        } finally {
            tx.executeWithoutResult(status -> users.findById(id).ifPresent(users::delete));
        }
    }

    void refused(String token,int code) throws Exception {
        mvc.perform(get("/api/v1/students/4").header("Authorization",token))
                .andExpect(status().is(code)).andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(code));
    }

    static String profile() {return "{\"username\":\"j.smith@abc.edu\",\"firstName\":\"Changed\",\"lastName\":\"Smith\",\"email\":\"j.smith@abc.edu\",\"enabled\":true}";}
}
