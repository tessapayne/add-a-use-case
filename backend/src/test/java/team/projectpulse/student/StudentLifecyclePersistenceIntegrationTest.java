package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.exception.ObjectNotFoundException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** UC-STU-deactivate-student trunk: T-profile, T-overlap, T-order, T-repeat, T-disappear, T-teamless. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(45)
class StudentLifecyclePersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StudentRepository students;
    @Autowired SectionRepository sections;
    @Autowired StudentService service;
    @Autowired StudentSecurityService permissions;
    @Autowired InstructorRepository instructors;
    @Autowired EntityManager entityManager;
    TransactionTemplate transaction;
    Integer studentId;
    Integer sectionId;
    Integer ownerId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            // Owner has no separate instructor assignment in this new course section.
            var section = new Section();
            section.setSectionName("Lifecycle test");
            section.setCourse(sections.findById(2).orElseThrow().getCourse());
            ownerId = section.getCourse().getCourseAdmin().getId();
            sectionId = sections.saveAndFlush(section).getSectionId();
            String email = UUID.randomUUID() + "@lifecycle.test";
            var student = new Student(email, "Retained", "Student", email, "unchanged-secret", true, "student");
            student.setSection(section);
            studentId = students.saveAndFlush(student).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (transaction == null || studentId == null) return;
        transaction.executeWithoutResult(status -> {
            students.deleteById(studentId);
            students.flush();
            sections.deleteById(sectionId);
            sections.flush();
        });
    }

    @Test
    void inheritedInstructorOwnerCanDeactivateTeamlessStudentAndRepeat() {
        asOwner();
        transaction.executeWithoutResult(status -> {
            assertThat(permissions.canManageLifecycle(studentId)).isTrue();
            assertThat(permissions.canViewStudent(studentId)).isTrue();
            for (int i = 0; i < 2; i++) {
                Student result = service.deactivateStudent(studentId);
                assertThat(result.isEnabled()).isFalse();
                assertThat(result.getFirstName()).isEqualTo("Retained");
                assertThat(result.getPassword()).isEqualTo("unchanged-secret");
                assertThat(result.getSection().getSectionId()).isEqualTo(sectionId);
                assertThat(result.getTeam()).isNull();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ordinaryProfileSaveIgnoresBodyStatusAndPrivilegeFields(boolean active) {
        transaction.executeWithoutResult(status -> students.findById(studentId).orElseThrow().setEnabled(active));
        asStudent();
        transaction.executeWithoutResult(status -> {
            Student draft = profile("Saved");
            draft.setEnabled(!active);
            draft.setRoles("admin");
            draft.setPassword("replace-secret");
            Student result = service.updateStudent(studentId, draft);
            assertThat(result.isEnabled()).isEqualTo(active);
            assertThat(result.getFirstName()).isEqualTo("Saved");
            assertThat(result.getRoles()).isEqualTo("student");
            assertThat(result.getPassword()).isEqualTo("unchanged-secret");
            assertThat(result.getSection().getSectionId()).isEqualTo(sectionId);
            assertThat(result.getTeam()).isNull();
        });
    }

    @Test
    void restorationIsAtomicAndUnauthorizedRestorationCannotSaveProfile() {
        asOwner();
        service.deactivateStudent(studentId);
        asStudent();
        assertThatThrownBy(() -> service.updateStudent(studentId, profile("Unauthorized"), true))
                .isInstanceOf(ObjectNotFoundException.class);
        asOwner();
        transaction.executeWithoutResult(status -> {
            Student unchanged = students.findById(studentId).orElseThrow();
            assertThat(unchanged.getFirstName()).isEqualTo("Retained");
            assertThat(unchanged.isEnabled()).isFalse();
            Student restored = service.updateStudent(studentId, profile("Restored"), true);
            assertThat(restored.isEnabled()).isTrue();
            assertThat(restored.getFirstName()).isEqualTo("Restored");
        });
    }

    @Test
    void rollbackLeavesStatusAndProfileUnchanged() {
        asOwner();
        transaction.executeWithoutResult(status -> {
            service.deactivateStudent(studentId);
            service.updateStudent(studentId, profile("Rolled back"), true);
            entityManager.flush();
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> {
            Student saved = students.findById(studentId).orElseThrow();
            assertThat(saved.isEnabled()).isTrue();
            assertThat(saved.getFirstName()).isEqualTo("Retained");
        });
    }

    @Test
    void scopePredicateRefusesMutationAfterAssignmentLost() {
        transaction.executeWithoutResult(status -> {
            var instructor = new Instructor("temporary-instructor", "Temporary", "Instructor",
                    "temporary-instructor@lifecycle.test", "unused", true, "instructor");
            instructors.saveAndFlush(instructor);
            var section = sections.findById(sectionId).orElseThrow();
            section.addInstructor(instructor);
            entityManager.flush();
            authenticate(instructor.getId(), "ROLE_instructor");
            assertThat(permissions.canManageLifecycle(studentId)).isTrue();

            // The route admitted this caller; her assignment disappears before service/mutation.
            section.removeInstructor(instructor);
            entityManager.flush();
            assertThat(students.writeStatus(studentId, instructor.getId(), true, true, false, false)).isZero();
            assertThatThrownBy(() -> service.deactivateStudent(studentId)).isInstanceOf(ObjectNotFoundException.class);
            assertThat(students.findById(studentId).orElseThrow().isEnabled()).isTrue();
            status.setRollbackOnly(); // Temporary instructor/assignment never escape this test.
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lifecycleWritersSerializeAndLastSuccessfulCommitWins(boolean deactivateFirst) throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> {
                asOwner();
                try {
                    transaction.executeWithoutResult(status -> {
                        lifecycle(deactivateFirst);
                        firstWritten.countDown();
                        await(releaseFirst);
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(firstWritten.await(15, TimeUnit.SECONDS)).isTrue();
                Future<?> second = workers.submit(() -> {
                    asOwner();
                    try {
                        transaction.executeWithoutResult(status -> {
                            // Load stale managed state before the first writer commits.
                            students.findById(studentId).orElseThrow();
                            secondStarted.countDown();
                            lifecycle(!deactivateFirst);
                        });
                    } finally { SecurityContextHolder.clearContext(); }
                });
                assertThat(secondStarted.await(15, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally { releaseFirst.countDown(); }
        }
        transaction.executeWithoutResult(status -> {
            Student saved = students.findById(studentId).orElseThrow();
            assertThat(saved.isEnabled()).isEqualTo(deactivateFirst);
            assertThat(saved.getFirstName()).isEqualTo("Restored");
            assertThat(saved.getPassword()).isEqualTo("unchanged-secret");
        });
    }

    @Test
    void staleManagedProfileCannotUndoCommittedDeactivation() throws Exception {
        CountDownLatch profileLoaded = new CountDownLatch(1);
        CountDownLatch deactivated = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            Future<?> profile = worker.submit(() -> {
                asStudent();
                try {
                    transaction.executeWithoutResult(status -> {
                        Student stale = students.findById(studentId).orElseThrow();
                        assertThat(stale.isEnabled()).isTrue();
                        profileLoaded.countDown();
                        await(deactivated);
                        Student result = service.updateStudent(studentId, profile("Saved after deactivate"));
                        assertThat(result.isEnabled()).isFalse();
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(profileLoaded.await(15, TimeUnit.SECONDS)).isTrue();
                asOwner();
                service.deactivateStudent(studentId);
            } finally { deactivated.countDown(); }
            profile.get(15, TimeUnit.SECONDS);
        }
        transaction.executeWithoutResult(status -> {
            Student saved = students.findById(studentId).orElseThrow();
            assertThat(saved.isEnabled()).isFalse();
            assertThat(saved.getFirstName()).isEqualTo("Saved after deactivate");
        });
    }

    private void lifecycle(boolean deactivate) {
        if (deactivate) service.deactivateStudent(studentId);
        else service.updateStudent(studentId, profile("Restored"), true);
    }

    private Student profile(String firstName) {
        Student draft = new Student();
        draft.setUsername("profile@lifecycle.test");
        draft.setFirstName(firstName);
        draft.setLastName("Student");
        draft.setEmail("profile@lifecycle.test");
        return draft;
    }

    private void asOwner() { authenticate(ownerId, "ROLE_admin"); }
    private void asStudent() { authenticate(studentId, "ROLE_student"); }

    private void authenticate(Integer id, String authority) {
        Jwt jwt = Jwt.withTokenValue("service-test").header("alg", "RS256").subject("service-test")
                .claim("userId", id).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(authority))));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Writer was not released");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
