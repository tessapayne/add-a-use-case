package team.projectpulse.evaluation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.system.WeekKeys;
import team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** UC-STU-deactivate-student T-create-overlap; real MySQL transactions, never rollback-only base tests. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(45)
class EvaluationCreationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StudentRepository students;
    @Autowired SectionRepository sections;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired EvaluationService service;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;
    TransactionTemplate transaction;
    final List<Integer> fixtureIds = new ArrayList<>();
    String week;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        week = WeekKeys.previousWeek(clock);
        transaction.executeWithoutResult(status -> {
            var section = sections.findById(2).orElseThrow();
            var team = section.getTeams().get(0);
            for (int i = 0; i < 3; i++) {
                String email = UUID.randomUUID() + "@concurrency.test";
                var student = new Student(email, "Concurrent", "Student", email, "unused", true, "student");
                student.setSection(section);
                student.setTeam(team);
                fixtureIds.add(students.saveAndFlush(student).getId());
            }
        });
    }

    @AfterEach
    void cleanUp() {
        if (transaction == null) return;
        transaction.executeWithoutResult(status -> {
            // Explicitly undo committed fixture records without touching seeded evaluations.
            for (Integer evaluatorId : fixtureIds) {
                evaluations.deleteAll(evaluations.findByWeekAndEvaluatorId(week, evaluatorId));
            }
            evaluations.flush();
            students.deleteAllById(fixtureIds);
            students.flush();
        });
    }

    @Test
    void overlappingCreateWaitsForCommitThenRefusesDuplicateDespiteOldSnapshot() throws Exception {
        exerciseOverlap(false);
    }

    @Test
    void overlappingCreateSucceedsAfterFirstWriterRollsBack() throws Exception {
        exerciseOverlap(true);
    }

    private void exerciseOverlap(boolean rollbackFirst) throws Exception {
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondHasOldSnapshot = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> transaction.executeWithoutResult(status -> {
                create(fixtureIds.get(1), "first");
                entityManager.flush();
                firstSaved.countDown();
                await(releaseFirst);
                if (rollbackFirst) status.setRollbackOnly();
            }));
            try {
                assertThat(firstSaved.await(15, TimeUnit.SECONDS)).isTrue();
                Future<Throwable> second = workers.submit(() -> catchThrowable(() ->
                        transaction.executeWithoutResult(status -> {
                            // Establish a repeatable-read snapshot before the first writer commits.
                            assertThat(evaluations.findByWeekAndEvaluatorId(week, fixtureIds.get(0))).isEmpty();
                            secondHasOldSnapshot.countDown();
                            create(fixtureIds.get(1), "second");
                        })));
                assertThat(secondHasOldSnapshot.await(15, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                first.get(15, TimeUnit.SECONDS);
                Throwable error = second.get(15, TimeUnit.SECONDS);
                if (rollbackFirst) assertThat(error).isNull();
                else assertThat(error).isInstanceOf(PeerEvaluationIllegalArgumentException.class)
                        .hasMessageContaining("already submitted");
            } finally {
                releaseFirst.countDown();
            }
        }
        transaction.executeWithoutResult(status -> {
            var saved = evaluations.findByWeekAndEvaluatorId(week, fixtureIds.get(0));
            assertThat(saved).singleElement().satisfies(evaluation -> {
                assertThat(evaluation.getEvaluatee().getId()).isEqualTo(fixtureIds.get(1));
                assertThat(evaluation.getPublicComment()).isEqualTo(rollbackFirst ? "second" : "first");
            });
            // Serialization must not confuse a different recipient with a duplicate.
            create(fixtureIds.get(2), "another recipient");
        });
        Integer count = transaction.execute(status -> evaluations.findByWeekAndEvaluatorId(week, fixtureIds.get(0)).size());
        assertThat(count).isEqualTo(2);
    }

    private void create(Integer recipientId, String comment) {
        var evaluator = students.findById(fixtureIds.get(0)).orElseThrow();
        var recipient = students.findById(recipientId).orElseThrow();
        // Service tests deliberately omit DTO/rubric conversion; HTTP coverage belongs to the endpoint leaf.
        service.addPeerEvaluation(new PeerEvaluation(week, evaluator, recipient, List.of(), comment, "private"));
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
