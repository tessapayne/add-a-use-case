package team.projectpulse.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface PeerEvaluationRepository extends JpaRepository<PeerEvaluation, Integer> {

    List<PeerEvaluation> findByWeekAndEvaluateeId(String week, Integer studentId);

    Optional<PeerEvaluation> findByWeekAndEvaluatorIdAndEvaluateeId(String week, Integer evaluatorId, Integer evaluateeId);

    /** Current read after taking the evaluator lock: do not reuse a repeatable-read snapshot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PeerEvaluation e where e.week = :week and e.evaluator.id = :evaluatorId and e.evaluatee.id = :evaluateeId")
    Optional<PeerEvaluation> findExistingForCreation(@Param("week") String week,
            @Param("evaluatorId") Integer evaluatorId, @Param("evaluateeId") Integer evaluateeId);

    List<PeerEvaluation> findByWeekAndEvaluatorId(String week, Integer evaluatorId);

    /**
     * Every (evaluator id, evaluatee id) pair recorded for a week among the course section's students.
     */
    @Query("""
            select e.evaluator.id, e.evaluatee.id
            from PeerEvaluation e
            where e.week = :week
              and e.evaluator.section.sectionId = :sectionId
            """)
    List<Object[]> findEvaluatorAndEvaluateeIds(@Param("sectionId") Integer sectionId, @Param("week") String week);

}
