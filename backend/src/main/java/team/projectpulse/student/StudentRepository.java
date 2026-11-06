package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Scope is repeated in writes: an earlier authorized read is not authority for a later mutation.
    String SCOPE = """
            s.id = :studentId and
            ((:allowSelf = true and s.id = :callerId) or
             (:instructor = true and exists
              (select cs.sectionId from Section cs where cs.sectionId = s.section.sectionId and
               ((:allowOwner = true and cs.course.courseAdmin.id = :callerId) or
                exists (select i.id from cs.instructors i where i.id = :callerId)))))
            """;

    @Query("select s from Student s where " + SCOPE)
    Optional<Student> findAccessible(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId,
            @Param("instructor") boolean instructor, @Param("allowOwner") boolean allowOwner,
            @Param("allowSelf") boolean allowSelf);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where " + SCOPE)
    Optional<Student> lockAccessible(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId,
            @Param("instructor") boolean instructor, @Param("allowOwner") boolean allowOwner,
            @Param("allowSelf") boolean allowSelf);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Student s set s.enabled = :enabled where " + SCOPE)
    int writeStatus(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId,
            @Param("instructor") boolean instructor, @Param("allowOwner") boolean allowOwner,
            @Param("allowSelf") boolean allowSelf, @Param("enabled") boolean enabled);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Student s set s.username = :username, s.firstName = :firstName,
            s.lastName = :lastName, s.email = :email where
            """ + SCOPE)
    int writeProfile(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId,
            @Param("instructor") boolean instructor, @Param("allowOwner") boolean allowOwner,
            @Param("allowSelf") boolean allowSelf, @Param("username") String username,
            @Param("firstName") String firstName, @Param("lastName") String lastName, @Param("email") String email);

    /** Existing row is also the serialization point for evaluation creation; no lock table is needed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :studentId")
    Optional<Student> lockById(@Param("studentId") Integer studentId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
